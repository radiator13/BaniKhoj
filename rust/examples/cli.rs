//! Host-side smoke test / benchmark against a real master.sqlite.
//!
//!   cargo run --release --example cli -- <path-to-master.sqlite> [query]
//!
//! Run twice: first run builds + caches the index, second run loads the cache.

use gurbanidb::Core;
use std::path::{Path, PathBuf};
use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let db = PathBuf::from(args.next().expect("usage: cli <db> [query]"));
    let query = args.next().unwrap_or_else(|| "ਮਨ".to_string());
    let cache = PathBuf::from("cli-index.cache");
    let _ = std::fs::remove_file(&cache);

    let t0 = Instant::now();
    let core = Core::open_cached(&db, &cache).expect("open db (cold)");
    println!("cold open+index: {:?} ({} lines)", t0.elapsed(), core.rows_len());

    let t0 = Instant::now();
    drop(core);
    let core = Core::open_cached(&db, &cache).expect("open db (warm)");
    println!("warm cached open: {:?}", t0.elapsed());
    println!("cache file: {} bytes", fs_size(&cache));

    println!("banis: {} total", core.banis().len());
    let (en, no_en): (Vec<_>, Vec<_>) = core.banis().into_iter().partition(|(_, _, e)| *e);
    println!("  with English: {}", en.len());
    println!("  without English: {}", no_en.len());
    for (id, name, _) in no_en.iter().take(5) {
        println!("    {id} {name}");
    }

    for q in [&query, "ਹੁਕਮ", "ੴ"] {
        let t = Instant::now();
        let hits = core.search(q, 100);
        println!("search {q:?}: {} hits in {:?}", hits.len(), t.elapsed());
        if let Some(h) = hits.first() {
            let shabad = core.shabad(&h.id);
            let (gu, en, _) = &shabad[0];
            println!("  first: {gu} | {en} | shabad({} lines)", shabad.len());
        }
    }

    if let Some((bid, _, _)) = core.banis().first() {
        let t = Instant::now();
        let lines = core.bani(bid);
        println!("bani {}: {} lines in {:?}", bid, lines.len(), t.elapsed());
        let sections: Vec<&str> =
            lines.iter().map(|(_, _, s)| s.as_str()).filter(|s| !s.is_empty()).collect();
        println!("  sections: {:?}", &sections[..sections.len().min(3)]);
    }
}

fn fs_size(p: &Path) -> u64 {
    std::fs::metadata(p).map(|m| m.len()).unwrap_or(0)
}
