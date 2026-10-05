package com.dreamtime.reader

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var pendingListenMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val sleepCheck = object : Runnable {
        override fun run() {
            val prefs = BookPrefs(this@PlaybackService)
            val end = prefs.sleepTimerEnd
            val player = mediaSession?.player
            if (player?.isPlaying == true) pendingListenMs += 1_000L
            if (pendingListenMs >= 10_000L) flushListenTime()
            if (end > 0L) {
                val remaining = end - System.currentTimeMillis()
                if (remaining <= 0L) {
                    player?.pause(); player?.volume = 1f; prefs.sleepTimerEnd = 0L
                } else if (remaining <= 60_000L) {
                    player?.volume = (remaining / 60_000f).coerceIn(0f, 1f)
                } else {
                    player?.volume = 1f
                }
            } else {
                player?.volume = 1f
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
        handler.post(sleepCheck)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.let { player ->
            BookPrefs(this).audioPosition = player.currentPosition
            val prefs = BookPrefs(this)
            val shelf = BookShelf(this)
            shelf.updateProgress(shelf.activeId, prefs.page, player.currentPosition)
            flushListenTime()
            if (!player.playWhenReady) stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepCheck)
        flushListenTime()
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }

    private fun flushListenTime() {
        if (pendingListenMs <= 0L) return
        BookShelf(this).addListeningTime(BookPrefs(this).activeBookId, pendingListenMs)
        pendingListenMs = 0L
    }
}
