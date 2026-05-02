package com.example.audiobook.ui.screens.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.example.audiobook.AudiobookApp
import com.example.audiobook.data.Audiobook
import com.example.audiobook.data.PrefsRepository
import com.example.audiobook.playback.PlayerHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as AudiobookApp).bookRepository
    private val prefs = PrefsRepository(app)
    private val player: ExoPlayer = PlayerHolder.get(app)

    private val _book = MutableStateFlow<Audiobook?>(null)
    val book: StateFlow<Audiobook?> = _book.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private var loadedBookId: String? = null
    private var pollJob: Job? = null
    private var saveJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _speed.value = playbackParameters.speed
        }
    }

    init {
        player.addListener(playerListener)
        startPolling()
    }

    fun loadBook(bookId: String) {
        if (loadedBookId == bookId) return
        val b = repo.getBook(bookId) ?: return
        savePositionNow()
        loadedBookId = bookId
        _book.value = b

        viewModelScope.launch {
            val savedSpeed = prefs.getSpeed()
            val savedPos = prefs.getPosition(bookId)
            _speed.value = savedSpeed

            player.setMediaItem(MediaItem.fromUri(b.uri), savedPos)
            player.playbackParameters = PlaybackParameters(savedSpeed)
            player.prepare()
        }
    }

    fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBack(ms: Long = 10_000L) {
        val target = (player.currentPosition - ms).coerceAtLeast(0L)
        player.seekTo(target)
    }

    fun seekForward(ms: Long = 10_000L) {
        val dur = player.duration
        val rawTarget = player.currentPosition + ms
        val target = if (dur > 0) rawTarget.coerceAtMost(dur) else rawTarget
        player.seekTo(target)
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
    }

    fun cycleSpeed() {
        val next = when (_speed.value) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 1.75f
            1.75f -> 2.0f
            else -> 1.0f
        }
        setSpeed(next)
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _speed.value = speed
        viewModelScope.launch { prefs.setSpeed(speed) }
    }

    fun jumpToChapter(startMs: Long) {
        player.seekTo(startMs)
        if (!player.isPlaying) player.play()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                val d = player.duration
                if (d > 0L) _durationMs.value = d
                delay(250)
            }
        }
    }

    fun savePositionNow() {
        val id = loadedBookId ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            prefs.setPosition(id, pos)
        }
    }

    override fun onCleared() {
        super.onCleared()
        savePositionNow()
        player.removeListener(playerListener)
        pollJob?.cancel()
        // Do not release player here — kept alive for persistent playback across nav.
    }
}
