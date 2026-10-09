package com.example.otrdial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FeedArtwork {
    private val executor = Executors.newFixedThreadPool(2)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val memory=object: android.util.LruCache<String,Bitmap>(8*1024*1024) { override fun sizeOf(key:String,value:Bitmap)=value.byteCount }
    fun load(context: Context, view: ImageView, url: String) {
        view.tag=url
        if(!EpisodeCatalogue.validUrl(url)) return
        memory.get(url)?.let { view.setImageBitmap(it); return }
        val app=context.applicationContext
        val call=http.newCall(Request.Builder().url(url).build())
        val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
        var job: java.util.concurrent.Future<*>?=null
        val listener=object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancelled.set(true); call.cancel(); job?.cancel(true); view.removeOnAttachStateChangeListener(this) }
        }
        view.addOnAttachStateChangeListener(listener)
        job=executor.submit {
            if(cancelled.get()) return@submit
            val dir=File(app.cacheDir,"feed-art").apply { mkdirs() }
            val file=File(dir,EpisodeCatalogue.stableId("art",url).substringAfterLast(':'))
            val bitmap=runCatching {
                val bytes=if(file.isFile) file.readBytes() else call.execute().use { r -> check(r.isSuccessful); r.body!!.byteStream().use { it.readLimited(2*1024*1024) } }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                check(bounds.outWidth>0 && bounds.outHeight>0)
                val options=BitmapFactory.Options().apply { inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/700).coerceAtLeast(1) }
                val result=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Invalid artwork")
                if(!file.isFile && !cancelled.get()) { if(dir.listFiles().orEmpty().sumOf { it.length() }>24*1024*1024) dir.listFiles().orEmpty().sortedBy { it.lastModified() }.take(10).forEach { it.delete() }; file.writeBytes(bytes) }
                result
            }.onFailure { if(!cancelled.get()) file.delete() }.getOrNull()
            if(bitmap!=null && !cancelled.get()) { memory.put(url,bitmap); view.post { if(view.tag==url && !cancelled.get()) { view.setImageBitmap(bitmap); view.contentDescription="Publisher-supplied artwork" }; view.removeOnAttachStateChangeListener(listener) } }
        }
    }
}
