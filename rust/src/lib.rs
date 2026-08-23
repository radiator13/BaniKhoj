//! BaniKhoj native core: XZ extraction, in-memory Gurmukhi index + substring search,
//! reader queries, and a persistent binary index cache for fast relaunches.

mod core_impl;
pub use core_impl::*;

mod jni_bridge;
