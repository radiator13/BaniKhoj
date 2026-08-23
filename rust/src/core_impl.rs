//! BaniKhoj native core: XZ extraction, in-memory Gurmukhi index + substring search,
//! and reader queries. Platform-independent; exercised by examples/cli.rs on host.

use memchr::memmem;
use rusqlite::{Connection, OpenFlags};
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::Path;

pub const EN_ASSET: &str = "DSSK";

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
    /// Open the SQLite DB read-only and build the in-memory search index.
    pub fn open(db_path: &Path) -> Result<Self, String> {
        let conn = Connection::open_with_flags(
            db_path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(|e| e.to_string())?;
        let rows = build_index(&conn)?;
        Ok(Core { conn, rows })
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

    /// All banis as [id, name-json] pairs in table order.
    pub fn banis(&self) -> Vec<(String, String)> {
        let mut out = Vec::new();
        let mut stmt = match self.conn.prepare("SELECT id, name FROM banis") {
            Ok(s) => s,
            Err(_) => return out,
        };
        if let Ok(mut rows) = stmt.query([]) {
            while let Ok(Some(r)) = rows.next() {
                if let (Ok(id), Ok(name)) = (r.get::<_, String>(0), r.get::<_, String>(1)) {
                    out.push((id, name));
                }
            }
        }
        out
    }

    /// Lines of the shabad (line group) that `line_id` belongs to.
    pub fn shabad(&self, line_id: &str) -> Vec<(String, String)> {
        read_pairs(
            &self.conn,
            r#"
            SELECT COALESCE(p.data, ''), COALESCE(en.data, '')
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

    /// Ordered lines of a bani (Nitnem etc.).
    pub fn bani(&self, bani_id: &str) -> Vec<(String, String)> {
        read_pairs(
            &self.conn,
            r#"
            SELECT COALESCE(p.data, ''), COALESCE(en.data, '')
            FROM bani_lines bl
            JOIN lines l ON l.id = bl.line_id
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

fn read_pairs<P: rusqlite::Params>(
    conn: &Connection,
    sql: &str,
    params: P,
) -> Vec<(String, String)> {
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
        let gu: String = r.get(0).unwrap_or_default();
        let en: String = r.get(1).unwrap_or_default();
        out.push((gu, en));
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

pub fn pairs_json(v: &[(String, String)]) -> String {
    let mut o = String::from("[");
    for (i, (gu, en)) in v.iter().enumerate() {
        if i > 0 {
            o.push(',');
        }
        o.push_str("[\"");
        o.push_str(&esc(gu));
        o.push_str("\",\"");
        o.push_str(&esc(en));
        o.push_str("\"]");
    }
    o.push(']');
    o
}

pub struct SearchJson<'a>(pub &'a [&'a Row]);
pub fn search_json(rows: SearchJson) -> String {
    let mut o = String::from("[");
    for (i, r) in rows.0.iter().enumerate() {
        if i > 0 {
            o.push(',');
        }
        o.push_str("{\"id\":\"");
        o.push_str(&esc(&r.id));
        o.push_str("\",\"gu\":\"");
        o.push_str(&esc(&r.gu));
        o.push_str("\",\"en\":\"");
        o.push_str(&esc(&r.en));
        o.push_str("\"}");
    }
    o.push(']');
    o
}
