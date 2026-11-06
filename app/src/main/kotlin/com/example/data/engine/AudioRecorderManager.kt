package com.example.data.engine

import android.content.Context
import android.media.MediaRecorder
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
import java.io.IOException

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    private var startTimeMs: Long = 0L
    private var accumulatedDurationMs: Long = 0L

    fun startRecording(): File? {
        if (_isRecording.value) return currentOutputFile

        val recordingsDir = File(context.filesDir, "recordings").apply {
            if (!exists()) mkdirs()
        }
        val file = File(recordingsDir, "rec_${System.currentTimeMillis()}.m4a")
        currentOutputFile = file

        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            _isRecording.value = true
            _isPaused.value = false
            startTimeMs = System.currentTimeMillis()
            accumulatedDurationMs = 0L
            _recordingDurationMs.value = 0L
            _amplitudes.value = emptyList()

            startAmplitudePolling()
            file
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to start recording", e)
            cleanup()
            null
        }
    }

    private fun startAmplitudePolling() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            val maxHistory = 40
            val currentList = mutableListOf<Float>()
            while (isActive && _isRecording.value) {
                if (!_isPaused.value) {
                    val now = System.currentTimeMillis()
                    _recordingDurationMs.value = accumulatedDurationMs + (now - startTimeMs)

                    val maxAmp = try {
                        mediaRecorder?.maxAmplitude?.toFloat() ?: 0f
                    } catch (e: Exception) {
                        0f
                    }
                    val normalized = (maxAmp / 32767f).coerceIn(0.05f, 1f)
                    currentList.add(normalized)
                    if (currentList.size > maxHistory) {
                        currentList.removeAt(0)
                    }
                    _amplitudes.value = currentList.toList()
                }
                delay(100)
            }
        }
    }

    fun pauseRecording() {
        if (_isRecording.value && !_isPaused.value) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.pause()
                }
                accumulatedDurationMs += (System.currentTimeMillis() - startTimeMs)
                _isPaused.value = true
            } catch (e: Exception) {
                Log.e("AudioRecorderManager", "Failed to pause", e)
            }
        }
    }

    fun resumeRecording() {
        if (_isRecording.value && _isPaused.value) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.resume()
                }
                startTimeMs = System.currentTimeMillis()
                _isPaused.value = false
            } catch (e: Exception) {
                Log.e("AudioRecorderManager", "Failed to resume", e)
            }
        }
    }

    fun stopRecording(): Pair<File?, Long> {
        if (!_isRecording.value) return Pair(null, 0L)

        val finalDuration = if (!_isPaused.value) {
            accumulatedDurationMs + (System.currentTimeMillis() - startTimeMs)
        } else {
            accumulatedDurationMs
        }

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to stop mediaRecorder", e)
        } finally {
            mediaRecorder = null
            recordingJob?.cancel()
            recordingJob = null
            _isRecording.value = false
            _isPaused.value = false
        }

        val file = currentOutputFile
        currentOutputFile = null
        return Pair(file, finalDuration)
    }

    private fun cleanup() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (ignored: Exception) {}
        mediaRecorder = null
        recordingJob?.cancel()
        recordingJob = null
        _isRecording.value = false
        _isPaused.value = false
    }
}
