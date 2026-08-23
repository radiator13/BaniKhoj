//! BaniKhoj native core: XZ extraction, in-memory Gurmukhi index + substring search,
//! multilingual reader queries, and a persistent binary index cache for fast relaunches.
//! Platform-independent; exercised by examples/cli.rs on host.

use memchr::memmem;
use rusqlite::{Connection, OpenFlags};
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::Path;
use std::time::UNIX_EPOCH;

pub const CACHE_MAGIC: &[u8; 4] = b"BKID";
const CACHE_VERSION: u32 = 5;

/// Translation sources per language, in fallback order.
pub const EN_CHAIN: &[&str] = &["DSSK", "DSKO", "SBMS"];
pub const PA_CHAIN: &[&str] = &["PSST", "NKFT", "RSJD"];

const ALL_SOURCES: &[&str] = &["DSSK", "DSKO", "SBMS", "PSST", "NKFT", "RSJD"];

#[derive(Default, Clone)]
pub struct Translations {
    pub en: String,
    pub pa: String,
}

impl Translations {
    fn pick(map: &HashMap<String, String>, chain: &[&str]) -> String {
        for src in chain {
            if let Some(t) = map.get(*src) {
                if !t.is_empty() {
                    return t.clone();
                }
            }
        }
        String::new()
    }
}

pub struct Row {
    pub id: String,
    pub gu: String,
    pub tr: Translations,
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
    pub fn banis(&self) -> Vec<(String, String, bool)> {
        let mut out = Vec::new();

        let mut covered: HashMap<String, i64> = HashMap::new();
        let sql = "
            SELECT bl.bani_id, COUNT(*),
                   COALESCE(SUM(EXISTS(
                        SELECT 1 FROM asset_lines x
                        WHERE x.line_id = bl.line_id AND x.type = 'translation'
                          AND x.asset_id IN ('DSSK','DSKO','SBMS'))), 0)
            FROM bani_lines bl
            GROUP BY bl.bani_id";
        if let Ok(mut stmt) = self.conn.prepare(sql) {
            if let Ok(mut rows) = stmt.query([]) {
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

    /// Lines of the shabad (line group) that `line_id` belongs to.
    pub fn shabad(&self, line_id: &str) -> Vec<(String, Translations, String)> {
        read_lines(
            &self.conn,
            "
            SELECT COALESCE(p.data, ''), COALESCE(sec.name, ''), en.asset_id, COALESCE(en.data, '')
            FROM lines l
            JOIN asset_lines p ON p.line_id = l.id AND p.type = 'primary'
                  AND NOT EXISTS (
                        SELECT 1 FROM asset_lines q
                        WHERE q.line_id = p.line_id AND q.type = 'primary'
                          AND (q.priority < p.priority
                               OR (q.priority = p.priority AND q.rowid < p.rowid)))
            JOIN line_groups lg ON lg.id = l.line_group_id
            LEFT JOIN sections sec ON sec.id = lg.section_id
            LEFT JOIN asset_lines en ON en.line_id = l.id AND en.type = 'translation'
                  AND en.asset_id IN ('DSSK','DSKO','SBMS','PSST','NKFT','RSJD')
            WHERE l.line_group_id = (SELECT line_group_id FROM lines WHERE id = ?1)
            ORDER BY l.line_group_order
            ",
            [line_id],
        )
    }

    /// Ordered lines of a bani (Nitnem etc.) with section names.
    pub fn bani(&self, bani_id: &str) -> Vec<(String, Translations, String)> {
        read_lines(
            &self.conn,
            "
            SELECT COALESCE(p.data, ''), COALESCE(sec.name, ''), en.asset_id, COALESCE(en.data, '')
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
            LEFT JOIN asset_lines en ON en.line_id = l.id AND en.type = 'translation'
                  AND en.asset_id IN ('DSSK','DSKO','SBMS','PSST','NKFT','RSJD')
            WHERE bl.bani_id = ?1
            ORDER BY bl.section_order, bl.line_order
            ",
            [bani_id],
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

/// Runs a line-query whose translation join may fan out to multiple rows per line;
/// merges translations and preserves the SQL ordering.
fn read_lines<P: rusqlite::Params>(
    conn: &Connection,
    sql: &str,
    params: P,
) -> Vec<(String, Translations, String)> {
    #[derive(Default)]
    struct Acc {
        gu: String,
        sec: String,
        t: HashMap<String, String>,
    }

    let mut out: Vec<Acc> = Vec::new();
    let mut index: HashMap<(String, String), usize> = HashMap::new();

    let mut stmt = match conn.prepare(sql) {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let mut rows = match stmt.query(params) {
        Ok(r) => r,
        Err(_) => return Vec::new(),
    };
    while let Ok(Some(r)) = rows.next() {
        let col = |i: usize| -> String {
            r.get::<_, Option<String>>(i).ok().flatten().unwrap_or_default()
        };
        let gu = col(0);
        let sec = col(1);
        let key = (gu.clone(), sec.clone());
        let idx = *index.entry(key).or_insert_with(|| {
            out.push(Acc { gu, sec, ..Default::default() });
            out.len() - 1
        });
        let aid: Option<String> = r.get(2).unwrap_or(None);
        if let Some(aid) = aid {
            if ALL_SOURCES.contains(&aid.as_str()) {
                let data = col(3);
                if !data.is_empty() {
                    out[idx].t.insert(aid, data);
                }
            }
        }
    }

    out.into_iter()
        .map(|a| {
            let tr = Translations {
                en: Translations::pick(&a.t, EN_CHAIN),
                pa: Translations::pick(&a.t, PA_CHAIN),
            };
            (a.gu, tr, a.sec)
        })
        .collect()
}

/// One best primary-text row per line, ordered canonically. Prefers lowest `priority`,
/// then earliest rowid. English translation resolved through EN_CHAIN when present.
fn build_index(conn: &Connection) -> Result<Vec<Row>, String> {
    let sources_in = ALL_SOURCES
        .iter()
        .map(|s| format!("'{s}'"))
        .collect::<Vec<_>>()
        .join(",");

    let sql = format!(
        "
        SELECT a.rowid, a.line_id, a.data, en.asset_id, COALESCE(en.data, '')
        FROM asset_lines a
        LEFT JOIN asset_lines en ON en.line_id = a.line_id AND en.type = 'translation'
              AND en.asset_id IN ({sources_in})
        WHERE a.type = 'primary'
        ORDER BY a.priority, a.rowid"
    );

    let mut best: HashMap<String, (String, HashMap<String, String>)> = HashMap::new();
    let mut order: Vec<(i64, String)> = Vec::new();

    let mut stmt = conn.prepare(&sql).map_err(|e| e.to_string())?;
    let mut it = stmt.query([]).map_err(|e| e.to_string())?;
    while let Some(r) = it.next().map_err(|e| e.to_string())? {
        let rid: i64 = r.get(0).map_err(|e| e.to_string())?;
        let id: String = r.get(1).map_err(|e| e.to_string())?;
        let gu: String = r.get(2).map_err(|e| e.to_string())?;
        let entry = best.entry(id.clone()).or_insert_with(|| {
            order.push((rid, id.clone()));
            (gu, HashMap::new())
        });
        let aid: Option<String> = r.get(3).unwrap_or(None);
        if let Some(aid) = aid {
            let data: String = r.get(4).unwrap_or_default();
            if !data.is_empty() && ALL_SOURCES.contains(&aid.as_str()) {
                entry.1.insert(aid, data);
            }
        }
    }

    // Restore canonical rowid ordering for ranking results.
    order.sort_by_key(|(rid, _)| *rid);
    let mut rows = Vec::with_capacity(order.len());
    for (_, id) in order {
        if let Some((gu, t)) = best.remove(&id) {
            rows.push(Row {
                id,
                gu,
                tr: Translations {
                    en: Translations::pick(&t, EN_CHAIN),
                    pa: Translations::pick(&t, PA_CHAIN),
                },
            });
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
        let pa = if c.u32()? == 1 { c.string()? } else { String::new() };
        rows.push(Row { id, gu, tr: Translations { en, pa } });
    }
    Some(rows)
}

fn write_cache(path: &Path, rows: &[Row], fp: &(u64, u64)) {
    let mut v = Vec::with_capacity(2 << 20);
    v.extend_from_slice(CACHE_MAGIC);
    put_u32(&mut v, CACHE_VERSION);
    v.extend_from_slice(&fp.0.to_le_bytes());
    v.extend_from_slice(&fp.1.to_le_bytes());
    put_u32(&mut v, rows.len() as u32);
    for r in rows {
        for s in [&r.id, &r.gu] {
            put_u32(&mut v, s.len() as u32);
            v.extend_from_slice(s.as_bytes());
        }
        for t in [&r.tr.en, &r.tr.pa] {
            if t.is_empty() {
                put_u32(&mut v, 0);
            } else {
                put_u32(&mut v, 1);
                put_u32(&mut v, t.len() as u32);
                v.extend_from_slice(t.as_bytes());
            }
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

/// [{"id":..,"gu":..,"en":..}, ...] for the search list (English via EN_CHAIN).
pub fn search_json(rows: &[&Row]) -> String {
    let mut o = String::from("[");
    for (i, r) in rows.iter().enumerate() {
        if i > 0 {
            o.push(',');
        }
        o.push_str("{\"id\":\"");
        o.push_str(&esc(&r.id));
        o.push_str("\",\"gu\":\"");
        o.push_str(&esc(&r.gu));
        o.push_str("\",\"en\":\"");
        o.push_str(&esc(&r.tr.en));
        o.push_str("\"}");
    }
    o.push(']');
    o
}

/// [[gurmukhi, {"en":..,"pa":..,"es":..}, section], ...] — empty languages omitted.
pub fn lines_json(v: &[(String, Translations, String)]) -> String {
    let mut o = String::from("[");
    for (i, (gu, tr, sec)) in v.iter().enumerate() {
        if i > 0 {
            o.push(',');
        }
        o.push_str("[\"");
        o.push_str(&esc(gu));
        o.push_str("\",{");
        let mut wrote = false;
        for (k, val) in [("en", &tr.en), ("pa", &tr.pa)] {
            if val.is_empty() {
                continue;
            }
            if wrote {
                o.push(',');
            }
            o.push_str(&format!("\"{k}\":\"{}\"", esc(val)));
            wrote = true;
        }
        o.push_str("},\"");
        o.push_str(&esc(sec));
        o.push_str("\"]");
    }
    o.push(']');
    o
}
