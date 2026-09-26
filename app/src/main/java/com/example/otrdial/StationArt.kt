package com.example.otrdial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/** Public-domain genre illustrations, bundled for offline use. */
object StationArt {
    private fun key(station: Station): String = when {
        station.genre.contains("Sci-Fi", true) || station.genre.contains("Horror", true) -> "scifi"
        station.genre.contains("Western", true) -> "western"
        station.genre.contains("Adventure", true) -> "adventure"
        station.genre.contains("Mystery", true) || station.genre.contains("Suspense", true) -> "mystery"
        station.genre.contains("Crime", true) -> "crime"
        station.genre.contains("Comedy", true) -> "comedy"
        station.genre.contains("Seasonal", true) -> "seasonal"
        else -> "radio"
    }
    private fun credit(context: Context, station: Station): org.json.JSONObject {
        val entries = org.json.JSONArray(context.assets.open("artwork/credits.json").bufferedReader().use { it.readText() })
        return (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("key") == key(station) }
    }
    private fun path(context: Context, station: Station) = "artwork/" + credit(context, station).getString("file")
    fun credits(context: Context, station: Station): String = runCatching {
        val item = credit(context, station)
        "Genre illustration (not an official station logo or current episode cover).\n\n${item.getString("title")}\n${item.getString("artist")}\n${item.getString("license")}\n\n${item.getString("source")}"
    }.getOrDefault("OTR Dial fallback illustration")
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private fun bitmap(context: Context, station: Station): Bitmap? {
        cache.get(key(station))?.let { return it }
        return runCatching {
            context.assets.open(path(context, station)).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { cache.put(key(station), it) }
    }
    fun drawable(context: Context, station: Station): Drawable =
        bitmap(context, station)?.let { BitmapDrawable(context.resources, it) } ?: RadioArtwork(station.genre)
    fun bytes(context: Context, station: Station): ByteArray =
        runCatching { context.assets.open(path(context, station)).use { it.readBytes() } }
            .getOrElse { RadioArtwork(station.genre).pngBytes() }
}
