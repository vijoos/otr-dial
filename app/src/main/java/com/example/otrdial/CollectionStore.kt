package com.example.otrdial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Personal organisation is separate from the playback queue and contains no downloaded paths. */
class CollectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("collections24", Context.MODE_PRIVATE)
    private fun array(key: String) = JSONArray(prefs.getString(key, "[]"))
    private fun write(key: String, a: JSONArray) { prefs.edit().putString(key, a.toString()).apply() }
    fun sources(): List<EpisodeSource> = EpisodeCatalogue.sources + objects(array("sources")).map { source(it) }
    fun addSource(s: EpisodeSource) {
        require(s.id.startsWith("custom-")); val all = objects(array("sources")).filter { it.getString("id") != s.id }
        write("sources", JSONArray(all + sourceJson(s)))
    }
    fun removeSource(id: String) { write("sources", JSONArray(objects(array("sources")).filter { it.getString("id") != id })) }
    fun playlists() = objects(array("playlists"))
    fun createPlaylist(name: String): String {
        require(name.isNotBlank()); val id = UUID.randomUUID().toString()
        write("playlists", JSONArray(playlists() + JSONObject().put("id", id).put("name", name.trim().take(120)).put("episodes", JSONArray())))
        return id
    }
    fun playlistIds(id: String) = playlists().find { it.getString("id") == id }?.getJSONArray("episodes")?.let { strings(it) }.orEmpty()
    fun editPlaylist(id: String, name: String? = null, episodes: List<String>? = null) {
        write("playlists", JSONArray(playlists().map { p -> if (p.getString("id") == id) {
            name?.let { require(it.isNotBlank()); p.put("name", it.trim().take(120)) }
            episodes?.let { p.put("episodes", JSONArray(it.distinct())) }
        }; p }))
    }
    fun removePlaylist(id: String) { write("playlists", JSONArray(playlists().filter { it.getString("id") != id })) }
    fun bookmarks() = objects(array("bookmarks"))
    fun bookmark(episode: String, position: Long, note: String) {
        require(position >= 0 && episode.startsWith("episode:"))
        write("bookmarks", JSONArray(bookmarks() + JSONObject().put("id", UUID.randomUUID().toString())
            .put("episode", episode).put("position", position).put("note", note.take(2000))))
    }
    fun removeBookmark(id: String) { write("bookmarks", JSONArray(bookmarks().filter { it.getString("id") != id })) }
    fun followedProgrammes() = prefs.getStringSet("programmes", emptySet()).orEmpty().toSet()
    fun followProgramme(name: String) { val s = followedProgrammes().toMutableSet(); if (!s.add(name)) s.remove(name); prefs.edit().putStringSet("programmes", s).apply() }
    fun recentSearches() = strings(array("searches"))
    fun rememberSearch(q: String) { if (q.isNotBlank()) write("searches", JSONArray((listOf(q.take(200)) + recentSearches()).distinct().take(8))) }
    fun clearSearches() { write("searches", JSONArray()) }
    fun status(id: String) = prefs.getString("status_$id", "Not refreshed on this device").orEmpty()
    fun status(id: String, message: String) { prefs.edit().putString("status_$id", message.take(1000)).apply() }
    fun export() = JSONObject().put("sources", array("sources")).put("playlists", array("playlists"))
        .put("bookmarks", array("bookmarks")).put("programmes", JSONArray(followedProgrammes().toList()))
    fun validate(j: JSONObject) {
        for (key in listOf("sources", "playlists", "bookmarks", "programmes")) require(j.getJSONArray(key).length() <= 10000)
        objects(j.getJSONArray("sources")).forEach { val s = source(it); require(s.id.startsWith("custom-") && s.kind in listOf("rss", "archive")); require(EpisodeCatalogue.validUrl(s.page)); require(if (s.kind == "rss") EpisodeCatalogue.validUrl(s.url) else s.url.matches(Regex("[A-Za-z0-9_.-]+"))) }
        objects(j.getJSONArray("playlists")).forEach { require(it.getString("id").isNotBlank() && it.getString("name").isNotBlank()); require(it.getJSONArray("episodes").length() <= 10000); strings(it.getJSONArray("episodes")).forEach { id -> require(id.startsWith("episode:")) } }
        objects(j.getJSONArray("bookmarks")).forEach { require(it.getString("id").isNotBlank()); require(it.getString("episode").startsWith("episode:") && it.getLong("position") >= 0); require(it.getString("note").length <= 2000) }
        strings(j.getJSONArray("programmes"))
    }
    fun restore(j: JSONObject) {
        validate(j)
        for (key in listOf("sources", "playlists", "bookmarks")) {
            val existing = objects(array(key)).associateBy { it.getString("id") }.toMutableMap()
            objects(j.getJSONArray(key)).forEach { incoming ->
                val id = incoming.getString("id")
                if (key == "playlists" && existing[id] != null) incoming.put("episodes", JSONArray((strings(existing[id]!!.getJSONArray("episodes")) + strings(incoming.getJSONArray("episodes"))).distinct()))
                existing[id] = incoming
            }
            write(key, JSONArray(existing.values.toList()))
        }
        prefs.edit().putStringSet("programmes", followedProgrammes() + strings(j.getJSONArray("programmes"))).apply()
    }
    companion object {
        fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
        fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
        fun sourceJson(s: EpisodeSource) = JSONObject().put("id", s.id).put("title", s.title).put("kind", s.kind).put("url", s.url).put("page", s.page).put("genre", s.genre)
        fun source(j: JSONObject) = EpisodeSource(j.getString("id"), j.getString("title"), j.getString("kind"), j.getString("url"), j.getString("page"), j.getString("genre"))
    }
}

object ProgrammeIndex {
    // Only explicit title matches; a mixed podcast episode remains one recording.
    private val names = listOf("Gunsmoke", "X Minus One", "Our Miss Brooks", "Suspense", "The Whistler", "Dragnet", "Jack Benny", "Fibber McGee and Molly", "Broadway Is My Beat", "Yours Truly Johnny Dollar", "The Shadow", "Escape", "The Six Shooter", "Richard Diamond", "Dimension X", "The Saint", "Philip Marlowe", "Cavalcade Of America", "Voyage Of The Scarlet Queen")
    private fun normal(s: String) = s.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    fun names(e: Episode): List<String> {
        val hay = " ${normal(e.title)} "
        val matched = names.filter { val n = normal(it); (if (' ' in n) hay.contains(" $n ") else normal(e.title).split(" and ").any { part -> part == n || part.startsWith("$n ") }) || normal(e.series) == n }
        return matched.ifEmpty { listOf(e.series) }
    }
    fun duplicateKey(e: Episode): String = normal(e.title) + "|" + normal(e.series)
    fun date(e: Episode): Long = runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).parse(e.date)?.time ?: 0L }.getOrDefault(0L)
    fun daily(episodes: List<Episode>, followed: Set<String>, day: Long = System.currentTimeMillis() / 86400000): List<Episode> =
        episodes.filter { e -> followed.isEmpty() || names(e).any { it in followed } }.ifEmpty { episodes }
            .sortedBy { (it.id + day).hashCode() }.distinctBy { it.source }.take(3)
}
