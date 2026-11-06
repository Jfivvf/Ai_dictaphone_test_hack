package com.example.data.model

data class AudioPreconfig(
    val sampleRateHz: Int = 44100, // 16000 or 44100
    val audioSource: String = "MIC", // MIC, VOICE_RECOGNITION, CAMCORDER
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val diarizationSensitivity: String = "Сбалансированная", // Высокая, Сбалансированная, Быстрая
    val expectedSpeakers: Int = 2,
    val autoDiarization: Boolean = true
) {
    val sampleRateLabel: String
        get() = if (sampleRateHz == 16000) "16 kHz (Whisper STT)" else "44.1 kHz (Studio HD)"
}
