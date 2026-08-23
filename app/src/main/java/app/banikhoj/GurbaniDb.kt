package app.banikhoj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Line(val gurmukhi: String, val english: String)
data class SearchResult(val lineId: String, val gurmukhi: String, val english: String)
data class Bani(val id: String, val nameGuru: String, val nameLatin: String)

/**
 * Thin JNI wrapper over the native Rust core (`rust/` crate, libgurbanidb.so).
 * The native side owns the SQLite handle plus an in-memory Gurmukhi index used
 * for substring search; results cross the boundary as small JSON strings.
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
     * native layer decompresses and opens it. Safe to call repeatedly.
     */
    fun open(context: Context): Boolean {
        synchronized(lock) {
            if (ready) return true
            val db = context.getDatabasePath("gurbani.db")
            val xz = File(db.parentFile, "gurbani.db.xz")
            val existed = db.exists()
            if (!existed) {
                xz.parentFile?.mkdirs()
                context.assets.open("databases/master.sqlite.xz").use { input ->
                    xz.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            ready = nativeInit(db.path, xz.path)
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
        nativeShabad(lineId).toPairs().map { Line(it.first, it.second) }

    fun banis(): List<Bani> {
        val arr = JSONArray(nativeBanis())
        return List(arr.length()) { i ->
            val pair = arr.getJSONArray(i)
            val json = pair.getString(1)
            val o = runCatching { JSONObject(json) }.getOrNull()
            Bani(pair.getString(0), o?.optString("Guru").orEmpty(), o?.optString("Latn").orEmpty())
        }
    }

    fun baniLines(baniId: String): List<Line> =
        nativeBani(baniId).toPairs().map { Line(it.first, it.second) }

    fun close() {
        synchronized(lock) {
            nativeClose()
            ready = false
        }
    }

    private fun String.toPairs(): List<Pair<String, String>> {
        val arr = JSONArray(this)
        return List(arr.length()) { i ->
            val p = arr.getJSONArray(i)
            p.optString(0) to p.optString(1)
        }
    }

    private external fun nativeInit(dbPath: String, xzPath: String): Boolean
    private external fun nativeSearch(query: String, limit: Int): String
    private external fun nativeBanis(): String
    private external fun nativeShabad(lineId: String): String
    private external fun nativeBani(baniId: String): String
    private external fun nativeClose()
}
