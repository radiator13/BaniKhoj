//! Host-side smoke test / benchmark against a real master.sqlite.
//!
//!   cargo run --release --example cli -- <path-to-master.sqlite> [query]

use gurbanidb::Core;
use std::path::PathBuf;
use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let db = PathBuf::from(args.next().expect("usage: cli <db> [query]"));
    let query = args.next().unwrap_or_else(|| "ਮਨ".to_string());

    let t0 = Instant::now();
    let core = Core::open(&db).expect("open db");
    println!("index built: {:?} ({} lines)", t0.elapsed(), core.search("ਿ", usize::MAX).len());

    let banis = core.banis();
    println!("banis: {}", banis.len());
    for (id, name) in banis.iter().take(5) {
        println!("  {id} {name}");
    }

    for q in [&query, "ਹੁਕਮ", "ੴ"] {
        let t = Instant::now();
        let hits = core.search(q, 100);
        println!("search {q:?}: {} hits in {:?}", hits.len(), t.elapsed());
        if let Some(h) = hits.first() {
            let t = Instant::now();
            let shabad = core.shabad(&h.id);
            let bani_ms = {
                let _ = core.shabad(&h.id);
                t.elapsed()
            };
            println!("  first: {} | {} | shabad({} lines) re-query {bani_ms:?}", h.gu, h.en, shabad.len());
        }
    }

    if let Some((bid, _)) = core.banis().first() {
        let t = Instant::now();
        let lines = core.bani(bid);
        println!("bani {}: {} lines in {:?}", bid, lines.len(), t.elapsed());
        if let Some((gu, en)) = lines.first() {
            println!("  line 1: {gu}\n          {en}");
        }
    }
}
