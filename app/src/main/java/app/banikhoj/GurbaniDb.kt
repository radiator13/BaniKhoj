package app.banikhoj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Line(
    val gurmukhi: String,
    val english: String = "",
    val punjabi: String = "",
    val section: String = ""
) {
    fun translation(lang: ReaderLang): String = when (lang) {
        ReaderLang.EN -> english
        ReaderLang.PA -> punjabi
    }
}

/** Reader translation languages, in cycle order. */
enum class ReaderLang(val code: String, val label: String) {
    EN("en", "EN"),
    PA("pa", "ਪੰ");
}
data class SearchResult(val lineId: String, val gurmukhi: String, val english: String)
data class Bani(val id: String, val nameGuru: String, val nameLatin: String, val hasEnglish: Boolean)

/**
 * Thin JNI wrapper over the native Rust core (`rust/` crate, libgurbanidb.so).
 * The native side owns the SQLite handle plus an in-memory Gurmukhi index used
 * for substring search, persisted to a binary cache for fast relaunches;
 * results cross the boundary as small JSON strings.
 */
object GurbaniDb {

    init {
        System.loadLibrary("gurbanidb")
    }

    private val lock = Any()
    private var ready = false

    /**
     * Ensures the database is available and the native index is loaded.
     * Copies the XZ-compressed asset to private storage on first run; the
     * native layer decompresses and opens it, then caches the search index
     * beside it so later launches skip the rebuild. Safe to call repeatedly.
     */
    fun open(context: Context): Boolean {
        synchronized(lock) {
            if (ready) return true
            val db = context.getDatabasePath("gurbani.db")
            val xz = File(db.parentFile, "gurbani.db.xz")
            val idx = File(db.parentFile, "gurbani.idx")
            val existed = db.exists()
            if (!existed) {
                xz.parentFile?.mkdirs()
                context.assets.open("databases/master.sqlite.xz").use { input ->
                    xz.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            ready = nativeInit(db.path, xz.path, idx.path)
            if (!ready && !existed) {
                // Avoid leaving a truncated DB behind after a failed first launch.
                db.delete()
            }
            return ready
        }
    }

    fun search(q: String, limit: Int = 100): List<SearchResult> {
        if (q.isBlank()) return emptyList()
        val arr = JSONArray(nativeSearch(q.trim(), limit))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SearchResult(o.getString("id"), o.getString("gu"), o.getString("en"))
        }
    }

    fun shabadOf(lineId: String): List<Line> =
        nativeShabad(lineId).toLines()

    fun banis(): List<Bani> {
        val arr = JSONArray(nativeBanis())
        return List(arr.length()) { i ->
            val pair = arr.getJSONArray(i)
            val json = pair.getString(1)
            val o = runCatching { JSONObject(json) }.getOrNull()
            Bani(
                id = pair.getString(0),
                nameGuru = o?.optString("Guru").orEmpty(),
                nameLatin = o?.optString("Latn").orEmpty(),
                hasEnglish = pair.optInt(2) == 1,
            )
        }
    }

    fun baniLines(baniId: String): List<Line> =
        nativeBani(baniId).toLines()

    fun close() {
        synchronized(lock) {
            nativeClose()
            ready = false
        }
    }

    private fun String.toLines(): List<Line> {
        val arr = JSONArray(this)
        return List(arr.length()) { i ->
            val p = arr.getJSONArray(i)
            val tr = p.optJSONObject(1)
            Line(
                gurmukhi = p.optString(0),
                english = tr?.optString("en").orEmpty(),
                punjabi = tr?.optString("pa").orEmpty(),
                section = prettySection(p.optString(2))
            )
        }
    }

    /** sections.name arrives JSON-encoded ({\"Guru\":..,\"Latn\":..}); unwrap for display. */
    private fun prettySection(raw: String): String {
        if (!raw.startsWith("{")) return raw
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        return o.optString("Guru").ifBlank { o.optString("Latn").ifBlank { raw } }
    }

    private external fun nativeInit(dbPath: String, xzPath: String, idxPath: String): Boolean
    private external fun nativeSearch(query: String, limit: Int): String
    private external fun nativeBanis(): String
    private external fun nativeShabad(lineId: String): String
    private external fun nativeBani(baniId: String): String
    private external fun nativeClose()
}
