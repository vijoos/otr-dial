package com.example.otrdial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class FeedUpdates(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val library = LibraryStore(applicationContext); val collections = CollectionStore(applicationContext)
        var count = 0
        collections.sources().filter { it.kind == "rss" && it.id in library.follows() }.forEach { source ->
            if (isStopped) return Result.success()
            runCatching {
                val old = library.episodes().map { it.id }.toSet()
                val fresh = EpisodeCatalogue.refresh(source)
                count += fresh.count { it.id !in old }
                library.update(source, fresh); collections.status(source.id, "Refresh succeeded")
            }.onFailure { collections.status(source.id, "Last refresh failed: ${it.message}. Saved entries retained.") }
        }
        if (count > 0) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("feed_updates", "Podcast updates", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = PendingIntent.getActivity(applicationContext, 24, Intent(applicationContext, LibraryActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching { manager.notify(24, NotificationCompat.Builder(applicationContext, "feed_updates").setSmallIcon(R.drawable.ic_brand)
                .setContentTitle("New podcast entries").setContentText("$count entries added from your followed feeds").setContentIntent(intent).setAutoCancel(true).build()) }
        }
        return Result.success()
    }
    companion object {
        fun enabled(c: Context) = c.getSharedPreferences("collections24", Context.MODE_PRIVATE).getBoolean("digest", false)
        fun setEnabled(c: Context, enabled: Boolean) {
            c.getSharedPreferences("collections24", Context.MODE_PRIVATE).edit().putBoolean("digest", enabled).apply()
            val manager = WorkManager.getInstance(c)
            if (!enabled) manager.cancelUniqueWork("otr-daily-feeds")
            else manager.enqueueUniquePeriodicWork("otr-daily-feeds", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FeedUpdates>(24, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
        }
    }
}
