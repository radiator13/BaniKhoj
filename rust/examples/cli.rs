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
    println!("  with English: {} | without: {}", en.len(), no_en.len());

    for q in [&query, "ੴ"] {
        let t = Instant::now();
        let hits = core.search(q, 100);
        println!("search {q:?}: {} hits in {:?}", hits.len(), t.elapsed());
        if let Some(h) = hits.first() {
            let shabad = core.shabad(&h.id);
            let (gu, tr, _) = &shabad[0];
            println!(
                "  first: {gu}\n    en: {} | pa: {} | es: {}\n    shabad({} lines)",
                &tr.en[..tr.en.len().min(50)],
                &tr.pa[..tr.pa.len().min(30)],
                &tr.es[..tr.es.len().min(30)],
                shabad.len()
            );
        }
    }

    // A Dasam Granth bani should have pa but no en; a GGS bani all three.
    for (bid, expect) in [("CDDV", "Dasam"), ("JAPJ", "GGS")] {
        let lines = core.bani(bid);
        let with_en = lines.iter().filter(|(_, t, _)| !t.en.is_empty()).count();
        let with_pa = lines.iter().filter(|(_, t, _)| !t.pa.is_empty()).count();
        let with_es = lines.iter().filter(|(_, t, _)| !t.es.is_empty()).count();
        println!(
            "bani {bid} ({expect}): {} lines | en={with_en} pa={with_pa} es={with_es}",
            lines.len()
        );
        if let Some((gu, tr, sec)) = lines.first() {
            println!("  line 1 [{sec}]: {gu}");
            println!("    en={} pa={}", !tr.en.is_empty(), !tr.pa.is_empty());
        }
    }
}

fn fs_size(p: &Path) -> u64 {
    std::fs::metadata(p).map(|m| m.len()).unwrap_or(0)
}
