package com.example.otrdial

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/** IDs are unchanged from 2.7. Personal state stays in its original preferences. */
class CatalogueDatabase private constructor(private val app: Context) : SQLiteOpenHelper(app, "catalogue28.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE episodes(id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, series TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX episodes_source ON episodes(source)")
        db.execSQL("CREATE INDEX episodes_title ON episodes(title COLLATE NOCASE)")
        db.execSQL("CREATE VIRTUAL TABLE episode_search USING fts4(id, title, series, description)")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    @Synchronized fun initialise() {
        val db = writableDatabase
        val ready = db.rawQuery("SELECT value FROM metadata WHERE key='seeded'", null).use { it.moveToFirst() }
        if (ready) return
        val prefs = app.getSharedPreferences("episode_library", Context.MODE_PRIVATE)
        val legacy = prefs.getString("catalogue", null)
        // Parse everything before writing. A failed migration leaves the legacy file untouched.
        val episodes = if (legacy != null) decode(JSONArray(legacy)) else EpisodeCatalogue.sources.flatMap { source ->
            runCatching { app.assets.open("episodes/${source.id}.json").bufferedReader().use { decode(JSONArray(it.readText())) } }.getOrDefault(emptyList())
        }
        transaction { put(it, episodes); it.execSQL("INSERT INTO metadata VALUES('seeded','1')") }
        prefs.edit().remove("catalogue").commit() // Remove only after the database transaction succeeds.
    }
    fun all(): List<Episode> = select("SELECT payload FROM episodes ORDER BY rowid", emptyArray())
    fun find(id: String?): Episode? = if (id == null) null else select("SELECT payload FROM episodes WHERE id=?", arrayOf(id)).firstOrNull()
    fun count(source: String? = null): Int = readableDatabase.rawQuery(if(source == null) "SELECT COUNT(*) FROM episodes" else "SELECT COUNT(*) FROM episodes WHERE source=?", source?.let { arrayOf(it) }).use { it.moveToFirst(); it.getInt(0) }
    fun page(source: String? = null, query: String = "", offset: Int = 0, limit: Int = 25): List<Episode> {
        val conditions = mutableListOf<String>(); val args = mutableListOf<String>()
        if (source != null) { conditions += "source=?"; args += source }
        val terms = query.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (terms.isNotEmpty()) { conditions += "id IN (SELECT id FROM episode_search WHERE episode_search MATCH ?)"; args += terms.joinToString(" AND ") { "\"$it\"*" } }
        args += limit.toString(); args += offset.toString()
        return select("SELECT payload FROM episodes" + if(conditions.isEmpty()) " ORDER BY rowid LIMIT ? OFFSET ?" else " WHERE ${conditions.joinToString(" AND ")} ORDER BY rowid LIMIT ? OFFSET ?", args.toTypedArray())
    }
    private fun select(sql: String, args: Array<String>) = readableDatabase.rawQuery(sql, args).use { c -> buildList { while(c.moveToNext()) add(Episode.from(org.json.JSONObject(c.getString(0)))) } }
    @Synchronized fun merge(values: List<Episode>) = transaction { put(it, values) }
    @Synchronized fun refresh(source: String, values: List<Episode>, protected: Set<String>) = transaction { db ->
        val old = db.rawQuery("SELECT id FROM episodes WHERE source=?", arrayOf(source)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        val retain = protected + values.map { it.id }
        old.filter { it !in retain }.forEach { id -> db.execSQL("DELETE FROM episode_search WHERE rowid IN (SELECT rowid FROM episodes WHERE id=?)", arrayOf(id)); db.delete("episodes", "id=?", arrayOf(id)) }
        put(db, values)
    }
    private fun put(db: SQLiteDatabase, values: List<Episode>) {
        val row = db.compileStatement("INSERT OR REPLACE INTO episodes(id,source,title,series,payload) VALUES(?,?,?,?,?)")
        val search = db.compileStatement("INSERT INTO episode_search(rowid,id,title,series,description) VALUES(?,?,?,?,?)")
        try { values.forEach { e ->
            require(e.id.startsWith("episode:") && EpisodeCatalogue.validUrl(e.url))
            db.execSQL("DELETE FROM episode_search WHERE rowid IN (SELECT rowid FROM episodes WHERE id=?)", arrayOf(e.id))
            listOf(e.id,e.source,e.title,e.series,e.json().toString()).forEachIndexed { i,v -> row.bindString(i+1,v) }; val rowId = row.executeInsert(); row.clearBindings()
            search.bindLong(1, rowId)
            listOf(e.id,e.title,e.series,e.description).forEachIndexed { i,v -> search.bindString(i+2,v) }; search.executeInsert(); search.clearBindings()
        } } finally { row.close(); search.close() }
    }
    private fun transaction(action: (SQLiteDatabase)->Unit) { val db = writableDatabase; db.beginTransaction(); try { action(db); db.setTransactionSuccessful() } finally { db.endTransaction() } }
    companion object {
        @Volatile private var instance: CatalogueDatabase? = null
        fun get(context: Context): CatalogueDatabase = instance ?: synchronized(this) { instance ?: CatalogueDatabase(context.applicationContext).also { it.setWriteAheadLoggingEnabled(true); it.initialise(); instance = it } }
        internal fun resetForTest(context: Context) { synchronized(this) { instance?.close(); instance = null; context.deleteDatabase("catalogue28.db") } }
        private fun decode(a: JSONArray) = (0 until a.length()).map { Episode.from(a.getJSONObject(it)) }.onEach { require(it.id.startsWith("episode:") && EpisodeCatalogue.validUrl(it.url)) }
    }
}
