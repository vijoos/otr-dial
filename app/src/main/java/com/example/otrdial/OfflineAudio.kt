package com.example.otrdial

import android.app.DownloadManager
import android.content.Context
import android.media.MediaExtractor
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
    fun entry(id: String): Entry? = synchronized(lock) {
        val job = prefs.getLong("job:$id", -1); if (job == -1L) return@synchronized null
        var result = manager.query(DownloadManager.Query().setFilterById(job))?.use { c ->
            if(c.moveToFirst()) Entry(id, job, c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) else null
        } ?: Entry(id, job, DownloadManager.STATUS_FAILED, 0, 0, -1)
        val path = prefs.getString("staging:$id", null)
        if(result.state == DownloadManager.STATUS_SUCCESSFUL && path != null) {
            val stage = File(path)
            val ok = runCatching { promote(id, stage, result.total) }.getOrDefault(false)
            prefs.edit().remove("staging:$id").putBoolean("invalid:$id", !ok).commit()
            if(!ok) stage.delete()
        }
        if(prefs.getBoolean("invalid:$id", false)) result = result.copy(state = DownloadManager.STATUS_FAILED)
        result
    }
    fun local(id: String): Uri? = synchronized(lock) {
        val status = entry(id)
        // The stable file is independent of a replacement job. Legacy 2.7 files retain their success check.
        if (file(id).isFile && file(id).length() > 0 && (prefs.getBoolean("ready:$id", false) || status?.state == DownloadManager.STATUS_SUCCESSFUL)) Uri.fromFile(file(id)) else null
    }
    internal fun promote(id: String, stage: File, expectedBytes: Long): Boolean {
        if(!stage.isFile || stage.length() == 0L || (expectedBytes > 0 && stage.length() != expectedBytes)) return false
        val extractor = MediaExtractor()
        val valid = try { extractor.setDataSource(stage.absolutePath); (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true } } catch(_: Exception) { false } finally { extractor.release() }
        if(!valid) return false
        // Same-filesystem atomic replacement: a crash or validation failure cannot truncate the original.
        android.system.Os.rename(stage.absolutePath, file(id).absolutePath)
        prefs.edit().putBoolean("ready:$id", true).commit()
        return true
    }
    fun start(e: Episode) = synchronized(lock) {
        require(EpisodeCatalogue.validUrl(e.url))
        if(local(e.id) != null) prefs.edit().putBoolean("ready:${e.id}", true).commit()
        val previousStage = prefs.getString("staging:${e.id}", null)
        val stage = File(file(e.id).parentFile, file(e.id).name + ".${java.util.UUID.randomUUID()}.part")
        val request = DownloadManager.Request(Uri.parse(e.url)).setTitle(e.title).setDescription(e.series)
            .setDestinationUri(Uri.fromFile(stage)).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if(wifiOnly()) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        val job = manager.enqueue(request)
        // Cancel only old staging jobs. Removing a legacy job can delete the retained playable file.
        if(previousStage != null) { val old = prefs.getLong("job:${e.id}", -1); if(old != -1L) manager.remove(old); File(previousStage).delete() }
        prefs.edit().putLong("job:${e.id}", job).putString("staging:${e.id}", stage.absolutePath).remove("invalid:${e.id}").commit()
        Unit
    }
    fun remove(id: String) = synchronized(lock) {
        val job = prefs.getLong("job:$id", -1); if(job != -1L) manager.remove(job)
        prefs.getString("staging:$id", null)?.let { File(it).delete() }; file(id).delete()
        prefs.edit().remove("job:$id").remove("staging:$id").remove("ready:$id").remove("invalid:$id").commit(); Unit
    }
    fun usedBytes() = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.listFiles()?.sumOf { it.length() } ?: 0L
    fun status(e: Entry): String {
        val retained = if(e.state != DownloadManager.STATUS_SUCCESSFUL && local(e.id) != null) " • existing copy available offline" else ""
        return when(e.state) {
            DownloadManager.STATUS_SUCCESSFUL -> if(local(e.id) != null) "Available offline" else "File missing • download again"
            DownloadManager.STATUS_RUNNING -> if(e.total > 0) "Downloading ${e.bytes * 100 / e.total}%" else "Downloading ${e.bytes / 1024} KB"
            DownloadManager.STATUS_PENDING -> "Queued for download"
            DownloadManager.STATUS_PAUSED -> "Waiting for a connection • retry when online"
            else -> "Could not download valid audio • retry available"
        } + retained
    }
    companion object { private val lock = Any() }
}
