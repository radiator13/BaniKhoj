//! JNI bridge: app.banikhoj.GurbaniDb native methods.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint};
use jni::JNIEnv;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::{pairs_json, search_json, xz_extract, Core, SearchJson};

static STATE: Mutex<Option<Core>> = Mutex::new(None);

fn with_core<T>(f: impl FnOnce(&Core) -> T, default: T) -> T {
    match STATE.lock().ok().as_deref() {
        Some(Some(c)) => f(c),
        _ => default,
    }
}

fn jstr<'l>(env: &mut JNIEnv<'l>, s: String) -> JString<'l> {
    env.new_string(s).expect("new_string").into()
}

#[no_mangle]
pub extern "system" fn Java_app_banikhoj_GurbaniDb_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    xz_path: JString,
) -> jboolean {
    let db: PathBuf = match env.get_string(&db_path) {
        Ok(s) => s.to_string_lossy().into_owned().into(),
        Err(_) => return 0,
    };
    let xz: PathBuf = match env.get_string(&xz_path) {
        Ok(s) => s.to_string_lossy().into_owned().into(),
        Err(_) => return 0,
    };

    if !db.exists() {
        if !xz.exists() {
            return 0;
        }
        if xz_extract(&xz, &db).is_err() {
            return 0;
        }
        let _ = std::fs::remove_file(&xz);
    }

    match Core::open(&db) {
        Ok(core) => {
            if let Ok(mut g) = STATE.lock() {
                *g = Some(core);
            }
            1
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_banikhoj_GurbaniDb_nativeSearch<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass,
    q: JString,
    limit: jint,
) -> JString<'l> {
    let query = env.get_string(&q).map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let out = with_core(
        |c| search_json(SearchJson(&c.search(&query, limit.max(0) as usize))),
        "[]".to_string(),
    );
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_app_banikhoj_GurbaniDb_nativeBanis<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass,
) -> JString<'l> {
    // [[id, name-json], ...] — Kotlin parses the name JSON.
    let pairs = with_core(|c| c.banis(), Vec::new());
    let mut o = String::from("[");
    for (i, (id, name)) in pairs.iter().enumerate() {
        if i > 0 {
            o.push(',');
        }
        o.push_str("[\"");
        o.push_str(&crate::esc(id));
        o.push_str("\",");
        o.push_str(name); // name is itself JSON straight from the DB
        o.push(']');
    }
    o.push(']');
    jstr(&mut env, o)
}

#[no_mangle]
pub extern "system" fn Java_app_banikhoj_GurbaniDb_nativeShabad<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass,
    line_id: JString,
) -> JString<'l> {
    let id = env.get_string(&line_id).map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let out = with_core(|c| pairs_json(&c.shabad(&id)), "[]".to_string());
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_app_banikhoj_GurbaniDb_nativeBani<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass,
    bani_id: JString,
) -> JString<'l> {
    let id = env.get_string(&bani_id).map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let out = with_core(|c| pairs_json(&c.bani(&id)), "[]".to_string());
    jstr(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_app_banikhoj_GurbaniDb_nativeClose(_env: JNIEnv, _class: JClass) {
    if let Ok(mut g) = STATE.lock() {
        *g = None;
    }
}
