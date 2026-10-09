package com.example.otrdial

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FeedArtwork {
    private val executor = Executors.newFixedThreadPool(2)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    fun load(context: Context, view: ImageView, url: String) {
        view.tag = url
        if(!EpisodeCatalogue.validUrl(url)) return
        executor.execute {
            val bitmap = runCatching {
                val dir = File(context.cacheDir, "feed-art").apply { mkdirs() }
                val key = EpisodeCatalogue.stableId("art", url).substringAfterLast(':'); val file = File(dir, key)
                val bytes = if(file.isFile) file.readBytes() else http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    check(r.isSuccessful); r.body!!.byteStream().use { it.readLimited(2 * 1024 * 1024) }.also { data ->
                        if(dir.listFiles().orEmpty().sumOf { it.length() } > 24 * 1024 * 1024) dir.listFiles().orEmpty().sortedBy { it.lastModified() }.take(10).forEach { it.delete() }
                        file.writeBytes(data)
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 700).coerceAtLeast(1) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull()
            if(bitmap != null) view.post { if(view.tag == url && view.isAttachedToWindow) view.setImageBitmap(bitmap) }
        }
    }
}
