package com.example.audiobook.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
object PlayerHolder {
    @Volatile
    private var player: ExoPlayer? = null

    fun get(context: Context): ExoPlayer {
        return player ?: synchronized(this) {
            player ?: ExoPlayer.Builder(context.applicationContext)
                .setHandleAudioBecomingNoisy(true)
                .build()
                .also { player = it }
        }
    }

    fun release() {
        synchronized(this) {
            player?.release()
            player = null
        }
    }
}
