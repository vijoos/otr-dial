package com.example.otrdial

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retries = 0
    private var retry: Runnable? = null
    private val library by lazy { LibraryStore(this) }
    private var previousDuration = 0L
    private val progressTick = object : Runnable {
        override fun run() {
            mediaSession?.player?.let { p ->
                if (p.isPlaying) library.saveProgress(p.currentMediaItem?.mediaId, p.currentPosition, p.duration)
                previousDuration = p.duration
            }
            handler.postDelayed(this, 5000)
        }
    }

    private fun cancelRetry() {
        retry?.let { handler.removeCallbacks(it) }
        retry = null
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
                val ended = reason == androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                if (oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId) {
                    library.saveProgress(newPosition.mediaItem?.mediaId, newPosition.positionMs, player.duration)
                } else library.saveProgress(oldPosition.mediaItem?.mediaId, oldPosition.positionMs, previousDuration, ended)
                if (ended) library.setQueue(library.queue() - oldPosition.mediaItem?.mediaId.orEmpty())
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                cancelRetry()
                if (!player.playWhenReady) return
                val current = player.currentMediaItem
                val fallback = current?.mediaMetadata?.extras?.getString("fallback_url").orEmpty()
                if (EpisodeCatalogue.validUrl(fallback) && current?.localConfiguration?.uri.toString() != fallback) {
                    val position = player.currentPosition
                    val index = player.currentMediaItemIndex
                    player.replaceMediaItem(index, current!!.buildUpon().setUri(fallback).build())
                    player.seekTo(index, position)
                    player.prepare()
                    return
                }
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
                previousDuration = 0
                val episode = item?.mediaId?.startsWith("episode:") == true
                val intent = android.content.Intent(this@PlaybackService, if (episode) LibraryActivity::class.java else MainActivity::class.java)
                    .putExtra("player", true)
                mediaSession?.setSessionActivity(android.app.PendingIntent.getActivity(this@PlaybackService, if (episode) 2 else 1, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
                if (episode && reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    val resume = library.progress(item!!.mediaId)
                    if (resume > 0) player.seekTo(resume)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    cancelRetry()
                    retries = 0
                    previousDuration = player.duration
                } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                    library.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration, true)
                    library.setQueue(library.queue() - player.currentMediaItem?.mediaId.orEmpty())
                }
            }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                if (!ready) {
                    library.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration,
                        player.playbackState == androidx.media3.common.Player.STATE_ENDED)
                    cancelRetry()
                    retries = 0
                }
            }
        })
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        mediaSession = MediaSession.Builder(this, player).build()
        handler.post(progressTick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        cancelRetry()
        handler.removeCallbacks(progressTick)
        mediaSession?.run {
            library.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration,
                player.playbackState == androidx.media3.common.Player.STATE_ENDED)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
