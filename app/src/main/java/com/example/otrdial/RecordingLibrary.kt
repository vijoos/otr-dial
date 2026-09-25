package com.example.otrdial

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Only displays this application's own recordings; no broad storage permission. */
object RecordingLibrary {
    fun show(activity: Activity) {
        val entries = mutableListOf<Pair<android.net.Uri, String>>()
        try {
            activity.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0",
                arrayOf("Music/OTR Dial/", activity.packageName),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    entries.add(uri to "${cursor.getString(1)}\n${android.text.format.Formatter.formatFileSize(activity, cursor.getLong(2))}")
                }
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not read recordings: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            return
        }
        if (entries.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("Your recordings")
                .setMessage("No completed recordings yet. Use REC while listening to save a recording here.")
                .setPositiveButton("Close", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Your recordings")
            .setItems(entries.map { it.second }.toTypedArray()) { _, index ->
                val (uri, label) = entries[index]
                AlertDialog.Builder(activity).setTitle(label.substringBefore('\n'))
                    .setItems(arrayOf("Play", "Share", "Rename", "Delete")) { _, action ->
                        when (action) {
                            0 -> launch(activity, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            1 -> launch(activity, Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share recording"))
                            2 -> {
                                val edit = EditText(activity).apply { setText(label.substringBefore('\n').substringBeforeLast('.')) }
                                AlertDialog.Builder(activity).setTitle("Rename recording").setView(edit)
                                    .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                                        val name = edit.text.toString().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                                        if (name.isNotEmpty()) runCatching {
                                            activity.contentResolver.update(uri, ContentValues().apply {
                                                put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.${label.substringBefore('\n').substringAfterLast('.', "mp3")}")
                                            }, null, null)
                                        }.onFailure { Toast.makeText(activity, "Rename failed", Toast.LENGTH_SHORT).show() }
                                        show(activity)
                                    }.show()
                            }
                            3 -> AlertDialog.Builder(activity).setTitle("Delete recording?")
                                .setMessage("This permanently deletes ${label.substringBefore('\n')}.")
                                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                                    runCatching { activity.contentResolver.delete(uri, null, null) }
                                        .onFailure { Toast.makeText(activity, "Delete failed", Toast.LENGTH_SHORT).show() }
                                    show(activity)
                                }.show()
                        }
                    }.show()
            }.setNegativeButton("Close", null).show()
    }

    private fun launch(activity: Activity, intent: Intent) {
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, "No compatible app is installed", Toast.LENGTH_LONG).show()
        }
    }
}
