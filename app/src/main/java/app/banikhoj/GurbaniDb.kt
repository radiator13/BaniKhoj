package app.banikhoj

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.apache.commons.compress.compressors.xz.XZInputStream
import org.json.JSONObject
import java.io.File

data class Line(val gurmukhi: String, val english: String)
data class SearchResult(val lineId: String, val gurmukhi: String, val english: String)
data class Bani(val id: String, val nameGuru: String, val nameLatin: String)

object GurbaniDb {

    const val TEXT_ASSET = "JSDS"
    const val EN_ASSET = "DSSK"

    private var db: SQLiteDatabase? = null
    private val lock = Any()

    fun get(context: Context): SQLiteDatabase {
        synchronized(lock) {
            db?.let { return it }
            val out = context.getDatabasePath("gurbani.db")
            if (!out.exists()) {
                out.parentFile?.mkdirs()
                context.assets.open("databases/master.sqlite.xz").use { input ->
                    XZInputStream(input.buffered()).use { xz ->
                        out.outputStream().buffered().use { output -> xz.copyTo(output) }
                    }
                }
            }
            return SQLiteDatabase.openDatabase(
                out.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).also { db = it }
        }
    }

    fun search(q: String, limit: Int = 100): List<SearchResult> {
        if (q.isBlank()) return emptyList()
        val d = db ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        d.rawQuery(
            """
            SELECT al.line_id AS id, al.data AS gurmukhi,
                   COALESCE(en.data, '') AS english
            FROM asset_lines al
            LEFT JOIN asset_lines en
                   ON en.line_id = al.line_id AND en.asset_id = ? AND en.type = 'translation'
            WHERE al.asset_id = ? AND al.type = 'primary' AND al.data LIKE '%' || ? || '%'
            ORDER BY al.rowid
            LIMIT ?
            """.trimIndent(),
            arrayOf(EN_ASSET, TEXT_ASSET, q, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += SearchResult(c.getString(0), c.getString(1), c.getString(2))
            }
        }
        return out
    }

    fun shabadOf(lineId: String): List<Line> {
        val d = db ?: return emptyList()
        val out = mutableListOf<Line>()
        d.rawQuery(
            """
            SELECT al.data AS gurmukhi, COALESCE(en.data, '') AS english
            FROM lines l
            JOIN asset_lines al ON al.line_id = l.id AND al.asset_id = ? AND al.type = 'primary'
            LEFT JOIN asset_lines en ON en.line_id = l.id AND en.asset_id = ? AND en.type = 'translation'
            WHERE l.line_group_id = (SELECT line_group_id FROM lines WHERE id = ?)
            ORDER BY l.line_group_order
            """.trimIndent(),
            arrayOf(TEXT_ASSET, EN_ASSET, lineId)
        ).use { c ->
            while (c.moveToNext()) out += Line(c.getString(0), c.getString(1))
        }
        return out
    }

    fun banis(): List<Bani> {
        val d = db ?: return emptyList()
        val out = mutableListOf<Bani>()
        d.rawQuery("SELECT id, name FROM banis", null).use { c ->
            while (c.moveToNext()) {
                val json = c.getString(1)
                runCatching {
                    val o = JSONObject(json)
                    Bani(c.getString(0), o.optString("Guru", ""), o.optString("Latn", ""))
                }.onSuccess { out += it }
            }
        }
        return out
    }

    fun baniLines(baniId: String): List<Line> {
        val d = db ?: return emptyList()
        val out = mutableListOf<Line>()
        d.rawQuery(
            """
            SELECT al.data AS gurmukhi, COALESCE(en.data, '') AS english
            FROM bani_lines bl
            JOIN lines l ON l.id = bl.line_id
            JOIN asset_lines al ON al.line_id = l.id AND al.asset_id = ? AND al.type = 'primary'
            LEFT JOIN asset_lines en ON en.line_id = l.id AND en.asset_id = ? AND en.type = 'translation'
            WHERE bl.bani_id = ?
            ORDER BY bl.section_order, bl.line_order
            """.trimIndent(),
            arrayOf(TEXT_ASSET, EN_ASSET, baniId)
        ).use { c ->
            while (c.moveToNext()) out += Line(c.getString(0), c.getString(1))
        }
        return out
    }

    fun close() {
        synchronized(lock) { db?.close(); db = null }
    }
}
