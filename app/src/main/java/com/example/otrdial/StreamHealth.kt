package com.example.otrdial

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

object StreamHealth {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    fun check(activity: Activity, station: Station) {
        val dialog = AlertDialog.Builder(activity).setTitle(station.name)
            .setMessage("Checking whether this stream returns audio…")
            .setNegativeButton("Close", null).create()
        val call = client.newCall(Request.Builder().url(station.streamUrl).build())
        dialog.setOnDismissListener { call.cancel() }
        dialog.show()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finish("Could not verify on this connection. The station may still work on another network.")
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    val type = it.header("Content-Type").orEmpty()
                    val result = if (it.isSuccessful && type.startsWith("audio/") && (it.body?.byteStream()?.read() ?: -1) >= 0) {
                        "Audio response received ($type). This checks connectivity, not programme content."
                    } else "Not verified: HTTP ${it.code}, ${type.ifBlank { "unknown format" }}. Try normal playback."
                    finish(result)
                } } catch (e: IOException) { onFailure(call, e) }
            }
            private fun finish(message: String) {
                if (call.isCanceled()) return
                val stamp = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
                activity.getSharedPreferences("otr_dial", 0).edit()
                    .putString("health_${station.id}", "$stamp\n$message").apply()
                activity.runOnUiThread {
                    if (!activity.isFinishing && dialog.isShowing) dialog.setMessage("$stamp\n\n$message")
                }
            }
        })
    }
}
