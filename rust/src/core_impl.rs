//! BaniKhoj native core: XZ extraction, in-memory Gurmukhi index + substring search,
//! reader queries, and a persistent binary index cache for fast relaunches.
//! Platform-independent; exercised by examples/cli.rs on host.

use memchr::memmem;
use rusqlite::{Connection, OpenFlags};
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::Path;
use std::time::UNIX_EPOCH;

pub const EN_ASSET: &str = "DSSK";

const CACHE_MAGIC: &[u8; 4] = b"BKID";
const CACHE_VERSION: u32 = 3;

pub struct Row {
    pub id: String,
    pub gu: String,
    pub en: String,
}

pub struct Core {
    conn: Connection,
    rows: Vec<Row>,
}

/// Decompress an .xz file to `out` (overwrites).
pub fn xz_extract(xz: &Path, out: &Path) -> std::io::Result<()> {
    let f = File::open(xz)?;
    let mut dec = xz2::read::XzDecoder::new(BufReader::with_capacity(1 << 20, f));
    let o = File::create(out)?;
    let mut w = BufWriter::with_capacity(1 << 20, o);
    std::io::copy(&mut dec, &mut w)?;
    w.flush()
}

impl Core {
    /// Open read-only and build the in-memory search index.
    pub fn open(db_path: &Path) -> Result<Self, String> {
        let conn = open_conn(db_path)?;
        let rows = build_index(&conn)?;
        Ok(Core { conn, rows })
    }

    /// Like [`Core::open`], but persists the index to `cache_path` and reloads it
    /// on subsequent launches when the DB file is unchanged (size + mtime_ns).
    pub fn open_cached(db_path: &Path, cache_path: &Path) -> Result<Self, String> {
        let conn = open_conn(db_path)?;
        let fp = fingerprint(db_path)?;
        if let Some(rows) = load_cache(cache_path, &fp) {
            return Ok(Core { conn, rows });
        }
        let rows = build_index(&conn)?;
        write_cache(cache_path, &rows, &fp);
        Ok(Core { conn, rows })
    }

    /// Number of indexed lines.
    pub fn rows_len(&self) -> usize {
        self.rows.len()
    }

    /// Substring search over primary Gurmukhi text, canonical (rowid) order, capped at `limit`.
    pub fn search(&self, q: &str, limit: usize) -> Vec<&Row> {
        if q.is_empty() {
            return Vec::new();
        }
        let finder = memmem::Finder::new(q.as_bytes());
        self.rows
            .iter()
            .filter(|r| finder.find(r.gu.as_bytes()).is_some())
            .take(limit)
            .collect()
    }

    /// All banis in table order as (id, name-json, has_english_majority).
    /// A bani counts as translatable when most of its lines carry an EN_ASSET translation.
    pub fn banis(&self) -> Vec<(String, String, bool)> {
        let mut out = Vec::new();

        // Coverage of DSSK translations per bani (one grouped scan).
        let mut covered: HashMap<String, i64> = HashMap::new();
        let sql = "
            SELECT bl.bani_id, COUNT(*), COALESCE(SUM(en.line_id IS NOT NULL), 0)
            FROM bani_lines bl
            LEFT JOIN asset_lines en ON en.line_id = bl.line_id AND en.asset_id = ?1
                  AND en.type = 'translation'
            GROUP BY bl.bani_id";
        if let Ok(mut stmt) = self.conn.prepare(sql) {
            if let Ok(mut rows) = stmt.query([EN_ASSET]) {
                while let Ok(Some(r)) = rows.next() {
                    if let (Ok(id), Ok(total), Ok(tr)) =
                        (r.get::<_, String>(0), r.get::<_, i64>(1), r.get::<_, i64>(2))
                    {
                        if tr * 2 > total {
                            covered.insert(id, tr);
                        }
                    }
                }
            }
        }

        if let Ok(mut stmt) = self.conn.prepare("SELECT id, name FROM banis") {
            if let Ok(mut rows) = stmt.query([]) {
                while let Ok(Some(r)) = rows.next() {
                    if let (Ok(id), Ok(name)) = (r.get::<_, String>(0), r.get::<_, String>(1)) {
                        let has_en = covered.contains_key(&id);
                        out.push((id, name, has_en));
                    }
                }
            }
        }
        out
    }

    /// Lines of the shabad (line group) that `line_id` belongs to: (gurmukhi, english, section).
    pub fn shabad(&self, line_id: &str) -> Vec<(String, String, String)> {
        read_triples(
            &self.conn,
            r#"
            SELECT COALESCE(p.data, ''), COALESCE(en.data, ''), ''
            FROM lines l
            JOIN asset_lines p ON p.line_id = l.id AND p.type = 'primary'
                  AND NOT EXISTS (
                        SELECT 1 FROM asset_lines q
                        WHERE q.line_id = p.line_id AND q.type = 'primary'
                          AND (q.priority < p.priority
                               OR (q.priority = p.priority AND q.rowid < p.rowid)))
            LEFT JOIN asset_lines en ON en.line_id = l.id AND en.asset_id = ?2
                  AND en.type = 'translation'
            WHERE l.line_group_id = (SELECT line_group_id FROM lines WHERE id = ?1)
            ORDER BY l.line_group_order
            "#,
            (line_id, EN_ASSET),
        )
    }

    /// Ordered lines of a bani (Nitnem etc.): (gurmukhi, english, section name).
    pub fn bani(&self, bani_id: &str) -> Vec<(String, String, String)> {
        read_triples(
            &self.conn,
            r#"
            SELECT COALESCE(p.data, ''), COALESCE(en.data, ''), COALESCE(sec.name, '')
            FROM bani_lines bl
            JOIN lines l ON l.id = bl.line_id
            JOIN line_groups lg ON lg.id = l.line_group_id
            LEFT JOIN sections sec ON sec.id = lg.section_id
            JOIN asset_lines p ON p.line_id = l.id AND p.type = 'primary'
                  AND NOT EXISTS (
                        SELECT 1 FROM asset_lines q
                        WHERE q.line_id = p.line_id AND q.type = 'primary'
                          AND (q.priority < p.priority
                               OR (q.priority = p.priority AND q.rowid < p.rowid)))
            LEFT JOIN asset_lines en ON en.line_id = l.id AND en.asset_id = ?3
                  AND en.type = 'translation'
            WHERE bl.bani_id = ?1
            ORDER BY bl.section_order, bl.line_order
            "#,
            (bani_id, "", EN_ASSET),
        )
    }
}

fn open_conn(db_path: &Path) -> Result<Connection, String> {
    Connection::open_with_flags(
        db_path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|e| e.to_string())
}

fn read_triples<P: rusqlite::Params>(
    conn: &Connection,
    sql: &str,
    params: P,
) -> Vec<(String, String, String)> {
    let mut out = Vec::new();
    let mut stmt = match conn.prepare(sql) {
        Ok(s) => s,
        Err(_) => return out,
    };
    let mut rows = match stmt.query(params) {
        Ok(r) => r,
        Err(_) => return out,
    };
    while let Ok(Some(r)) = rows.next() {
        let col = |i: usize| -> String {
            r.get::<_, Option<String>>(i).ok().flatten().unwrap_or_default()
        };
        out.push((col(0), col(1), col(2)));
    }
    out
}

/// One best primary-text row per line, ordered canonically. Prefers lowest `priority`,
/// then earliest rowid. English translation joined from EN_ASSET when present.
fn build_index(conn: &Connection) -> Result<Vec<Row>, String> {
    let mut best: HashMap<String, (String, Option<String>)> = HashMap::new();
    let mut order: Vec<(i64, String)> = Vec::new();

    let sql = "
        SELECT a.rowid, a.line_id, a.data,
               (SELECT data FROM asset_lines en
                 WHERE en.line_id = a.line_id AND en.asset_id = ?1
                   AND en.type = 'translation' LIMIT 1)
        FROM asset_lines a
        WHERE a.type = 'primary'
        ORDER BY a.priority, a.rowid";
    let mut stmt = conn.prepare(sql).map_err(|e| e.to_string())?;
    let mut it = stmt.query([EN_ASSET]).map_err(|e| e.to_string())?;
    while let Some(r) = it.next().map_err(|e| e.to_string())? {
        let rid: i64 = r.get(0).map_err(|e| e.to_string())?;
        let id: String = r.get(1).map_err(|e| e.to_string())?;
        let gu: String = r.get(2).map_err(|e| e.to_string())?;
        let en: Option<String> = r.get(3).unwrap_or(None);
        if best.insert(id.clone(), (gu, en)).is_none() {
            order.push((rid, id));
        }
    }

    // Restore canonical rowid ordering for ranking results.
    order.sort_by_key(|(rid, _)| *rid);
    let mut rows = Vec::with_capacity(order.len());
    for (_, id) in order {
        if let Some((gu, en)) = best.remove(&id) {
            rows.push(Row { id, gu, en: en.unwrap_or_default() });
        }
    }
    Ok(rows)
}

// ---------- Binary index cache ----------

fn fingerprint(db_path: &Path) -> Result<(u64, u64), String> {
    let md = std::fs::metadata(db_path).map_err(|e| e.to_string())?;
    let mtime = md
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);
    Ok((md.len(), mtime))
}

fn put_u32(v: &mut Vec<u8>, x: u32) {
    v.extend_from_slice(&x.to_le_bytes());
}

struct Cursor<'a> {
    b: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(n)?;
        if end > self.b.len() {
            return None;
        }
        let s = &self.b[self.pos..end];
        self.pos = end;
        Some(s)
    }
    fn u32(&mut self) -> Option<u32> {
        Some(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> Option<u64> {
        Some(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn string(&mut self) -> Option<String> {
        let len = self.u32()? as usize;
        Some(String::from_utf8_lossy(self.take(len)?).into_owned())
    }
}

fn load_cache(path: &Path, fp: &(u64, u64)) -> Option<Vec<Row>> {
    let buf = std::fs::read(path).ok()?;
    let mut c = Cursor { b: &buf, pos: 0 };
    if c.take(4)? != CACHE_MAGIC || c.u32()? != CACHE_VERSION {
        return None;
    }
    if c.u64()? != fp.0 || c.u64()? != fp.1 {
        return None;
    }
    let count = c.u32()? as usize;
    let mut rows = Vec::with_capacity(count.min(200_000));
    for _ in 0..count {
        let id = c.string()?;
        let gu = c.string()?;
        let en = if c.u32()? == 1 { c.string()? } else { String::new() };
        rows.push(Row { id, gu, en });
    }
    Some(rows)
}

fn write_cache(path: &Path, rows: &[Row], fp: &(u64, u64)) {
    let mut v = Vec::with_capacity(1 << 20);
    v.extend_from_slice(CACHE_MAGIC);
    put_u32(&mut v, CACHE_VERSION);
    v.extend_from_slice(&fp.0.to_le_bytes());
    v.extend_from_slice(&fp.1.to_le_bytes());
    put_u32(&mut v, rows.len() as u32);
    for r in rows {
        put_u32(&mut v, r.id.len() as u32);
        v.extend_from_slice(r.id.as_bytes());
        put_u32(&mut v, r.gu.len() as u32);
        v.extend_from_slice(r.gu.as_bytes());
        if r.en.is_empty() {
            put_u32(&mut v, 0);
        } else {
            put_u32(&mut v, 1);
            put_u32(&mut v, r.en.len() as u32);
            v.extend_from_slice(r.en.as_bytes());
        }
    }
    // Atomic-ish replace so a crash never leaves a torn cache.
    let tmp = path.with_extension("idx.tmp");
    if File::create(&tmp)
        .and_then(|mut f| f.write_all(&v))
        .and_then(|_| std::fs::rename(&tmp, path))
        .is_err()
    {
        let _ = std::fs::remove_file(&tmp);
    }
}

// ---------- JSON helpers (tiny, no serde) ----------

pub fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o
}

/// [[gurmukhi, english, section], ...]
pub fn triples_json(v: &[(String, String, String)]) -> String {
    let mut o = String::from("[");
    for (i, (gu, en, sec)) in v.iter().enumerate() {
        if i > 0 {
            o.push(',');
        }
        o.push_str("[\"");
        o.push_str(&esc(gu));
        o.push_str("\",\"");
        o.push_str(&esc(en));
        o.push_str("\",\"");
        o.push_str(&esc(sec));
        o.push_str("\"]");
    }
    o.push(']');
    o
}
