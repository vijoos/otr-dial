package com.example.otrdial

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Xml
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size())); if (count < 0) break
        output.write(buffer, 0, count); require(output.size() <= limit) { "File is too large" }
    }
    return output.toByteArray()
}

data class EpisodeSource(val id: String, val title: String, val kind: String, val url: String, val page: String, val genre: String)
data class Episode(val id: String, val source: String, val title: String, val series: String,
    val url: String, val page: String, val description: String = "", val date: String = "", val duration: Long = 0) {
    fun json() = JSONObject().put("id", id).put("source", source).put("title", title).put("series", series)
        .put("url", url).put("page", page).put("description", description).put("date", date).put("duration", duration)
    fun media(context: Context): MediaItem {
        val art = artStation()
        return MediaItem.Builder().setMediaId(id).setUri(url).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title).setArtist(series).setAlbumTitle(series)
            .setArtworkData(StationArt.bytes(context, art), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build()
    }
    fun artStation() = Station(source, series, series, EpisodeCatalogue.sources.find { it.id == source }?.genre ?: "Drama", url, page, "", false, "")
    companion object {
        fun from(j: JSONObject) = Episode(j.getString("id"), j.getString("source"), j.getString("title"),
            j.getString("series"), j.getString("url"), j.optString("page"), j.optString("description"), j.optString("date"), j.optLong("duration"))
    }
}

object EpisodeCatalogue {
    val sources = listOf(
        EpisodeSource("relic", "The Relic Radio Show", "rss", "https://feeds.feedburner.com/relicradio", "https://www.relicradio.com/otr/", "Drama"),
        EpisodeSource("scifi", "Relic Radio Science Fiction", "rss", "https://feeds.feedburner.com/relicradiosciencefiction", "https://www.relicradio.com/otr/", "Science fiction"),
        EpisodeSource("caseclosed", "Case Closed", "rss", "https://feeds.feedburner.com/caseclosed", "https://www.relicradio.com/otr/", "Detective"),
        EpisodeSource("laughs", "A Legacy of Laughs", "rss", "https://feeds.feedburner.com/alegacyoflaughs", "https://www.relicradio.com/otr/", "Comedy"),
        EpisodeSource("gunsmoke", "Gunsmoke", "archive", "OTRR_Gunsmoke_Singles", "https://archive.org/details/OTRR_Gunsmoke_Singles", "Western"),
        EpisodeSource("xminusone", "X Minus One", "archive", "OTRR_X_Minus_One_Singles", "https://archive.org/details/OTRR_X_Minus_One_Singles", "Science fiction"),
        EpisodeSource("missbrooks", "Our Miss Brooks", "archive", "OTRR_Our_Miss_Brooks_Singles", "https://archive.org/details/OTRR_Our_Miss_Brooks_Singles", "Comedy")
    )
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    fun validUrl(url: String): Boolean = runCatching { val u = Uri.parse(url); u.scheme in listOf("http", "https") && !u.host.isNullOrBlank() }.getOrDefault(false)
    private fun text(raw: String) = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim().take(12000)
    fun stableId(source: String, key: String): String = "episode:$source:" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun refresh(source: EpisodeSource): List<Episode> {
        val url = if (source.kind == "rss") source.url else "https://archive.org/metadata/${source.url}"
        http.newCall(Request.Builder().url(url).header("User-Agent", "OTRDial/2.0 (Android podcast reader)").build()).execute().use { response ->
            check(response.isSuccessful) { "Source returned HTTP ${response.code}" }
            val body = response.body ?: error("Empty response")
            val bytes = body.byteStream().use { it.readLimited(8 * 1024 * 1024) }
            check(bytes.size <= 8 * 1024 * 1024) { "Source response is too large" }
            val result = if (source.kind == "rss") parseRss(source, bytes.toString(Charsets.UTF_8)) else parseArchive(source, JSONObject(bytes.toString(Charsets.UTF_8)))
            check(result.isNotEmpty()) { "No public audio found; keeping the saved catalogue" }
            return result
        }
    }
    fun parseRss(source: EpisodeSource, xml: String): List<Episode> {
        val p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); p.setInput(xml.reader())
        val result = mutableListOf<Episode>(); var fields: MutableMap<String, String>? = null
        var tag = ""; var fieldDepth = -1
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "item") fields = mutableMapOf()
                    else if (fields != null) {
                        if (p.name == "enclosure") {
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            val url = p.getAttributeValue(null, "url").orEmpty()
                            if (type.startsWith("audio/") && validUrl(url)) fields["url"] = url
                        } else if (p.name in listOf("title", "guid", "link", "description", "pubDate", "duration")) {
                            tag = p.name; fieldDepth = p.depth
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (fields != null && tag.isNotEmpty()) fields[tag] = fields.getOrDefault(tag, "") + p.text
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        val f = fields.orEmpty(); val url = f["url"].orEmpty()
                        if (validUrl(url)) result += Episode(stableId(source.id, f["guid"]?.trim()?.ifBlank { url } ?: url), source.id,
                            text(f["title"].orEmpty()).ifBlank { "Untitled episode" }, source.title, url,
                            f["link"]?.trim()?.takeIf { validUrl(it) } ?: source.page, text(f["description"].orEmpty()),
                            f["pubDate"].orEmpty().trim(), duration(f["duration"].orEmpty()))
                        fields = null; tag = ""
                    } else if (p.depth == fieldDepth) tag = ""
                }
            }
            p.next()
        }
        return result.distinctBy { it.id }.take(125)
    }
    private fun duration(value: String): Long = runCatching {
        (value.trim().split(":").fold(0.0) { sum, part -> sum * 60 + part.toDouble() } * 1000).toLong().coerceAtLeast(0)
    }.getOrDefault(0)
    fun parseArchive(source: EpisodeSource, data: JSONObject): List<Episode> {
        val metadata = data.optJSONObject("metadata") ?: error("Archive item unavailable")
        check(!metadata.optBoolean("is_dark") && !metadata.optBoolean("access-restricted-item")) { "This collection is restricted" }
        val files = data.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".mp3", true) && !it.optBoolean("private") }
            .sortedBy { it.optString("name") }.map { f ->
                val name = f.getString("name")
                Episode(stableId(source.id, name), source.id, text(f.optString("title")).ifBlank { name.removeSuffix(".mp3") },
                    source.title, "https://archive.org/download/${source.url}/${Uri.encode(name)}", source.page,
                    "${f.optString("album")}\n${text(f.optString("comment"))}\nProvided by the Old Time Radio Researchers collection on Internet Archive.\nFile: $name".trim(),
                    f.optString("album"), duration(f.optString("length")))
            }.distinctBy { it.id }
    }
}

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("episode_library", Context.MODE_PRIVATE)
    private val app = context.applicationContext
    fun episodes(): List<Episode> {
        val raw = prefs.getString("catalogue", null)
        return if (raw != null) decodeEpisodes(JSONArray(raw)) else EpisodeCatalogue.sources.flatMap { source ->
            decodeEpisodes(JSONArray(app.assets.open("episodes/${source.id}.json").bufferedReader().use { it.readText() }))
        }
    }
    fun find(id: String?) = episodes().find { it.id == id }
    fun update(source: EpisodeSource, fresh: List<Episode>) {
        // Keep saved and previously played episodes even when an RSS feed drops older entries.
        val retained = episodes().filter { it.source != source.id || it.id in saved() || progress(it.id) > 0 || it.id in queue() }
        putEpisodes((fresh + retained).distinctBy { it.id })
        prefs.edit().putLong("updated_${source.id}", System.currentTimeMillis()).apply()
    }
    fun updated(id: String) = prefs.getLong("updated_$id", 0)
    private fun putEpisodes(values: List<Episode>) { prefs.edit().putString("catalogue", JSONArray(values.map { it.json() }).toString()).apply() }
    private fun set(key: String) = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
    fun saved() = set("saved")
    fun follows() = set("follows")
    fun toggle(key: String, id: String) { val s = set(key); if (!s.add(id)) s.remove(id); prefs.edit().putStringSet(key, s).apply() }
    fun progress(id: String) = prefs.getLong("position_$id", 0)
    fun played(id: String) = prefs.getLong("played_$id", 0)
    fun completed(id: String) = prefs.getBoolean("completed_$id", false)
    fun saveProgress(id: String?, position: Long, duration: Long, ended: Boolean = false) {
        if (id?.startsWith("episode:") != true) return
        val complete = ended || (duration > 0 && position >= duration - 3000)
        prefs.edit().putLong("position_$id", if (complete) 0 else position.coerceAtLeast(0))
            .putLong("played_$id", System.currentTimeMillis()).putBoolean("completed_$id", complete).putString("last", id).apply()
    }
    fun last() = prefs.getString("last", null)
    fun queue(): List<String> { val a = JSONArray(prefs.getString("queue", "[]")); return (0 until a.length()).map { a.getString(it) } }
    fun setQueue(ids: List<String>) { prefs.edit().putString("queue", JSONArray(ids.distinct()).toString()).apply() }
    fun export(): String {
        val state = JSONObject()
        prefs.all.forEach { (k, v) -> if (k != "catalogue") state.put(k, if (v is Set<*>) JSONArray(v.toList()) else v) }
        return JSONObject().put("format", "otr-dial-library").put("version", 1).put("episodes", JSONArray(episodes().map { it.json() })).put("state", state).toString(2)
    }
    fun restore(raw: String) {
        require(raw.length <= 12 * 1024 * 1024) { "Backup is too large" }
        val root = JSONObject(raw)
        require(root.getString("format") == "otr-dial-library" && root.getInt("version") == 1) { "Unsupported backup" }
        val incoming = decodeEpisodes(root.getJSONArray("episodes")); require(incoming.size <= 10000)
        val state = root.getJSONObject("state")
        // Validate all fields before touching preferences; malformed backups cannot partially overwrite data.
        val validated = mutableMapOf<String, Any>()
        state.keys().forEach { k ->
            val v = state.get(k)
            when {
                k in listOf("saved", "follows") -> { require(v is JSONArray); validated[k] = (0 until v.length()).map { v.getString(it) }.toSet() }
                k == "queue" -> { require(v is String); val a = JSONArray(v); (0 until a.length()).forEach { a.getString(it) }; validated[k] = v }
                k == "last" -> { require(v is String); validated[k] = v }
                k.startsWith("position_") || k.startsWith("played_") || k.startsWith("updated_") -> { require(v is Number && v.toLong() >= 0); validated[k] = v.toLong() }
                k.startsWith("completed_") -> { require(v is Boolean); validated[k] = v }
            }
        }
        val merged = (incoming + episodes()).distinctBy { it.id }
        val edit = prefs.edit().putString("catalogue", JSONArray(merged.map { it.json() }).toString())
        validated.forEach { (k, v) -> when (v) {
            is Set<*> -> edit.putStringSet(k, set(k) + v.filterIsInstance<String>())
            is String -> edit.putString(k, if (k == "queue") JSONArray((queue() + JSONArray(v).let { a -> (0 until a.length()).map { a.getString(it) } }).distinct()).toString() else v)
            is Long -> edit.putLong(k, v)
            is Boolean -> edit.putBoolean(k, v)
        } }
        edit.apply()
    }
    private fun decodeEpisodes(array: JSONArray): List<Episode> = (0 until array.length()).map { Episode.from(array.getJSONObject(it)) }.onEach {
        require(it.id.startsWith("episode:") && EpisodeCatalogue.validUrl(it.url)) { "Invalid episode in catalogue" }
    }
}
