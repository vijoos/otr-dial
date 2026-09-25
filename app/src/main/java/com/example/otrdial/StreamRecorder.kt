package com.example.otrdial

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object StreamRecorder {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null
    @Volatile private var stopping = false
    @Volatile var isRecording = false
        private set

    @Synchronized
    fun start(context: Context, station: Station, callback: (Boolean, String) -> Unit) {
        if (isRecording) {
            callback(false, "A recording is already running or stopping")
            return
        }
        val app = context.applicationContext
        isRecording = true
        stopping = false
        Thread {
            var uri: android.net.Uri? = null
            var bytes = 0L
            var failure: String? = null
            try {
                val request = Request.Builder().url(station.streamUrl)
                    .header("User-Agent", "OTR Dial/1.2").header("Icy-MetaData", "0").build()
                val requestCall = client.newCall(request)
                call = requestCall
                if (stopping) requestCall.cancel()
                requestCall.execute().use { response ->
                    check(response.isSuccessful) { "Stream returned HTTP ${response.code}" }
                    val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                    val extension = when (mime) {
                        "audio/mpeg", "audio/mp3" -> "mp3"
                        "audio/aac", "audio/aacp" -> "aac"
                        "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
                        else -> error("Recording is not supported for this stream format")
                    }
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                    val safeName = station.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
                    uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeName $stamp.$extension")
                            put(MediaStore.Audio.Media.MIME_TYPE, if (extension == "ogg") "audio/ogg" else mime)
                            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/OTR Dial")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }) ?: error("Could not create recording")
                    app.contentResolver.openOutputStream(uri!!, "w")!!.use { out ->
                        response.body!!.byteStream().use { input ->
                            val buffer = ByteArray(32768)
                            while (!stopping) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                                bytes += count
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopping) failure = e.localizedMessage ?: "Connection lost"
            } finally {
                var saved = false
                uri?.let { target ->
                    saved = runCatching {
                        if (bytes > 0) {
                            app.contentResolver.update(target, ContentValues().apply {
                                put(MediaStore.Audio.Media.IS_PENDING, 0)
                            }, null, null)
                            true
                        } else {
                            app.contentResolver.delete(target, null, null)
                            false
                        }
                    }.getOrDefault(false)
                }
                synchronized(this) {
                    call = null
                    isRecording = false
                    stopping = false
                }
                callback(saved, if (saved) {
                    if (failure == null) "Saved to Music/OTR Dial" else "Partial recording saved: $failure"
                } else failure ?: "No audio was recorded")
            }
        }.start()
    }

    fun stop() {
        stopping = true
        call?.cancel()
    }
}
