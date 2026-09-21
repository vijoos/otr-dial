package com.example.otrdial

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retries = 0
    private var retry: Runnable? = null

    private fun cancelRetry() {
        retry?.let { handler.removeCallbacks(it) }
        retry = null
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                cancelRetry()
                if (!player.playWhenReady) return
                if (retries >= 3) {
                    player.pause()
                    return
                }
                val id = player.currentMediaItem?.mediaId
                retries++
                retry = Runnable {
                    if (player.playWhenReady && player.currentMediaItem?.mediaId == id) player.prepare()
                }.also { handler.postDelayed(it, retries * 3000L) }
            }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                cancelRetry()
                retries = 0
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    cancelRetry()
                    retries = 0
                }
            }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                if (!ready) {
                    cancelRetry()
                    retries = 0
                }
            }
        })
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        cancelRetry()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
