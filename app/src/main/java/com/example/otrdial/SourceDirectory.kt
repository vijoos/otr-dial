package com.example.otrdial

import android.content.Context
import org.json.JSONArray

/** Source pages are deliberately distinct from playable media URLs. */
data class DirectorySource(val id: String, val title: String, val category: String,
    val page: String, val genre: String, val notes: String, val warning: String,
    val stationId: String, val sourceId: String, val feed: String, val archive: String) {
    fun episodeSource(): EpisodeSource? = when {
        feed.isNotBlank() -> EpisodeSource("custom-$id", title, "rss", feed, page, genre)
        archive.isNotBlank() -> EpisodeSource("custom-$id", title, "archive", archive, page, genre)
        else -> null
    }
}

object SourceDirectory {
    private fun read(context: Context, file: String): List<DirectorySource> = runCatching {
        val a = JSONArray(context.assets.open(file).bufferedReader().use { it.readText() })
        (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            DirectorySource(j.getString("id"), j.getString("title"), j.getString("category"),
                j.getString("page"), j.optString("genre"), j.optString("notes"), j.optString("warning"),
                j.optString("stationId"), j.optString("sourceId"), j.optString("feed"), j.optString("archive"))
        }
    }.getOrDefault(emptyList())

    fun load(context: Context): List<DirectorySource> {
        // Keep the original directory intact and layer the release's checked additions on top.
        // This also lets an older APK open if the optional additions asset is absent.
        return (read(context, "source-directory.json") + read(context, "source-directory-2.6.json"))
            .distinctBy { it.id }
    }
}
