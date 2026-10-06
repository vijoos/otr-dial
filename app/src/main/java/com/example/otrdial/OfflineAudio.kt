package com.example.otrdial

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.MessageDigest

class OfflineAudio(private val context: Context) {
    private val prefs = context.getSharedPreferences("offline24", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    data class Entry(val id: String, val job: Long, val state: Int, val bytes: Long, val total: Long, val reason: Int)
    fun wifiOnly() = prefs.getBoolean("wifi", true)
    fun wifiOnly(value: Boolean) { prefs.edit().putBoolean("wifi", value).apply() }
    fun file(id: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "$key.audio")
    }
    fun entries() = prefs.all.keys.filter { it.startsWith("job:") }.mapNotNull { entry(it.removePrefix("job:")) }
    fun entry(id: String): Entry? {
        val job = prefs.getLong("job:$id", -1); if (job == -1L) return null
        manager.query(DownloadManager.Query().setFilterById(job))?.use { c ->
            if (c.moveToFirst()) return Entry(id, job, c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
        }
        return Entry(id, job, DownloadManager.STATUS_FAILED, 0, 0, -1)
    }
    fun local(id: String): Uri? = if (entry(id)?.state == DownloadManager.STATUS_SUCCESSFUL && file(id).isFile && file(id).length() > 0) Uri.fromFile(file(id)) else null
    fun start(e: Episode) {
        require(EpisodeCatalogue.validUrl(e.url)); remove(e.id)
        val request = DownloadManager.Request(Uri.parse(e.url)).setTitle(e.title).setDescription(e.series)
            .setDestinationUri(Uri.fromFile(file(e.id))).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (wifiOnly()) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        val id = manager.enqueue(request); prefs.edit().putLong("job:${e.id}", id).apply()
    }
    fun remove(id: String) { val job = prefs.getLong("job:$id", -1); if (job != -1L) manager.remove(job); file(id).delete(); prefs.edit().remove("job:$id").apply() }
    fun usedBytes() = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.listFiles()?.sumOf { it.length() } ?: 0L
    fun status(e: Entry) = when(e.state) {
        DownloadManager.STATUS_SUCCESSFUL -> if (local(e.id) != null) "Available offline" else "File missing • download again"
        DownloadManager.STATUS_RUNNING -> if (e.total > 0) "Downloading ${e.bytes * 100 / e.total}%" else "Downloading ${e.bytes / 1024} KB"
        DownloadManager.STATUS_PENDING -> "Queued"
        DownloadManager.STATUS_PAUSED -> "Waiting for network / retry (${e.reason})"
        else -> "Download failed (${e.reason}) • retry available"
    }
}
