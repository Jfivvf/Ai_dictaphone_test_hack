package com.example.data.engine

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class AudioPlayerManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _currentPlayingId = MutableStateFlow<String?>(null)
    val currentPlayingId: StateFlow<String?> = _currentPlayingId.asStateFlow()

    fun play(recordingId: String, filePath: String?, syntheticDurationMs: Long = 0L) {
        if (_currentPlayingId.value == recordingId && mediaPlayer != null) {
            if (!_isPlaying.value) {
                mediaPlayer?.start()
                _isPlaying.value = true
                startProgressPolling()
            }
            return
        }

        stop()

        _currentPlayingId.value = recordingId

        val file = filePath?.let { File(it) }
        if (file != null && file.exists()) {
            try {
                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        _isPlaying.value = false
                        _currentPositionMs.value = 0L
                        progressJob?.cancel()
                    }
                }
                setSpeedOnPlayer(player, _playbackSpeed.value)
                player.start()
                mediaPlayer = player
                _isPlaying.value = true
                _durationMs.value = player.duration.toLong()
                startProgressPolling()
                return
            } catch (e: Exception) {
                Log.e("AudioPlayerManager", "Failed to play actual audio file, falling back to simulated playback", e)
            }
        }

        // Simulated playback for sample recordings or when no file exists
        val dur = if (syntheticDurationMs > 0) syntheticDurationMs else 60000L
        _durationMs.value = dur
        _isPlaying.value = true
        _currentPositionMs.value = 0L
        startSimulatedPlayback(dur)
    }

    private fun startSimulatedPlayback(totalDuration: Long) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                delay(100)
                val step = (100 * _playbackSpeed.value).toLong()
                val next = _currentPositionMs.value + step
                if (next >= totalDuration) {
                    _currentPositionMs.value = 0L
                    _isPlaying.value = false
                    break
                } else {
                    _currentPositionMs.value = next
                }
            }
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                try {
                    mediaPlayer?.let {
                        if (it.isPlaying) {
                            _currentPositionMs.value = it.currentPosition.toLong()
                        }
                    }
                } catch (ignored: Exception) {}
                delay(100)
            }
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
        } catch (ignored: Exception) {}
        _isPlaying.value = false
        progressJob?.cancel()
    }

    fun resume() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer?.start()
                _isPlaying.value = true
                startProgressPolling()
            } catch (e: Exception) {
                Log.e("AudioPlayerManager", "Resume error", e)
            }
        } else if (_currentPlayingId.value != null) {
            _isPlaying.value = true
            startSimulatedPlayback(_durationMs.value)
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, _durationMs.value)
        _currentPositionMs.value = target
        try {
            mediaPlayer?.seekTo(target.toInt())
        } catch (ignored: Exception) {}
    }

    fun skipForward(deltaMs: Long = 5000L) {
        seekTo(_currentPositionMs.value + deltaMs)
    }

    fun skipBackward(deltaMs: Long = 5000L) {
        seekTo(_currentPositionMs.value - deltaMs)
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        mediaPlayer?.let { setSpeedOnPlayer(it, speed) }
    }

    private fun setSpeedOnPlayer(player: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = speed
                player.playbackParams = params
            } catch (e: Exception) {
                Log.w("AudioPlayerManager", "Could not set playback speed", e)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _currentPlayingId.value = null
    }
}
