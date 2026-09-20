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
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var call: Call? = null
    @Volatile var isRecording: Boolean = false
        private set

    fun start(context: Context, station: Station, callback: (Boolean, String) -> Unit) {
        if (isRecording) {
            callback(false, "A recording is already running")
            return
        }
        isRecording = true

        Thread {
            var mediaUri: android.net.Uri? = null
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                val safeName = station.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeName $stamp.mp3")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/OTR Dial")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                mediaUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create recording file")

                val request = Request.Builder()
                    .url(station.streamUrl)
                    .header("User-Agent", "OTR Dial/0.1")
                    .build()
                call = client.newCall(request)
                val response = call!!.execute()
                if (!response.isSuccessful) error("Stream returned HTTP ${response.code}")

                context.contentResolver.openOutputStream(mediaUri, "w")!!.use { out ->
                    response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (isRecording) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.flush()
                    }
                }
                response.close()

                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                context.contentResolver.update(mediaUri, done, null, null)
                callback(true, "Saved to Music/OTR Dial")
            } catch (e: Exception) {
                if (!isRecording && mediaUri != null) {
                    // stop() cancels the HTTP call. Keep what has already been written
                    // and publish the partial recording instead of deleting it.
                    runCatching {
                        val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                        context.contentResolver.update(mediaUri, done, null, null)
                    }
                    callback(true, "Recording stopped and saved to Music/OTR Dial")
                } else {
                    mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                    callback(false, e.message ?: "Recording failed")
                }
            } finally {
                isRecording = false
                call = null
            }
        }.start()
    }

    fun stop() {
        isRecording = false
        call?.cancel()
    }
}
