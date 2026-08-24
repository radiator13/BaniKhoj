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

/** Gurmukhi search modes; [code] crosses the JNI boundary — keep in sync with Rust. */
enum class SearchMode(val code: Int) {
    PARTIAL(0),
    FIRST_START(1),
    FIRST_ANY(2),
    FULL_WORD(3),
    EXACT(4),
}

/** Where a line lives: its section (ang) plus how many shabads precede it there. */
data class LineLocation(val sourceId: String, val sectionId: String, val anchor: Int)
data class Bani(val id: String, val nameGuru: String, val nameLatin: String, val hasEnglish: Boolean)
data class Source(val id: String, val nameGuru: String, val nameLatin: String)
data class Section(val id: String, val title: String)

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

    fun search(q: String, limit: Int = 100, mode: SearchMode = SearchMode.PARTIAL): List<SearchResult> {
        if (q.isBlank()) return emptyList()
        val arr = JSONArray(nativeSearch(q.trim(), limit, mode.code))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SearchResult(o.getString("id"), o.getString("gu"), o.getString("en"))
        }
    }

    /** Section (ang) + source + shabad anchor for a line; null when unknown. */
    fun locateLine(lineId: String): LineLocation? {
        val raw = runCatching { nativeLocateLine(lineId) }.getOrNull().orEmpty()
        if (raw.isEmpty()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val sec = o.optString("sec")
        if (sec.isEmpty()) return null
        return LineLocation(o.optString("src"), sec, o.optInt("anchor"))
    }

    fun sourceOfSection(sectionId: String): String =
        runCatching { nativeSourceOfSection(sectionId) }.getOrDefault("")

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

    /** Scriptures (SGGS, Dasam Granth, …) as [id, name-json] pairs. */
    fun sources(): List<Source> {
        val arr = JSONArray(nativeSources())
        return List(arr.length()) { i ->
            val pair = arr.getJSONArray(i)
            val o = runCatching { JSONObject(pair.getString(1)) }.getOrNull()
            Source(
                id = pair.getString(0),
                nameGuru = o?.optString("Guru").orEmpty(),
                nameLatin = o?.optString("Latn").orEmpty(),
            )
        }
    }

    /** Sections of a scripture, in canonical order; titles prefer Gurmukhi when present. */
    fun sectionsOf(sourceId: String): List<Section> {
        val arr = JSONArray(nativeSections(sourceId))
        return List(arr.length()) { i ->
            val pair = arr.getJSONArray(i)
            val o = runCatching { JSONObject(pair.getString(1)) }.getOrNull()
            val guru = o?.optString("Guru").orEmpty().trim()
            val latn = o?.optString("Latn").orEmpty().trim()
            Section(pair.getString(0), guru.ifBlank { latn.ifBlank { "?" } })
        }
    }

    /** Every line of a section as one continuous stream for the unified reader. */
    fun sectionLines(sectionId: String): List<Line> =
        nativeSectionLines(sectionId).toLines()

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
    private external fun nativeSearch(query: String, limit: Int, mode: Int): String
    private external fun nativeLocateLine(lineId: String): String
    private external fun nativeSourceOfSection(sectionId: String): String
    private external fun nativeBanis(): String
    private external fun nativeBani(baniId: String): String
    private external fun nativeSources(): String
    private external fun nativeSections(sourceId: String): String
    private external fun nativeSectionLines(sectionId: String): String
    private external fun nativeClose()
}
