package com.example.data.model

data class UserProfile(
    val name: String = "Ульяна Фомина",
    val email: String = "fominauliana2009@yandex.ru",
    val totalTranscribedMinutes: Int = 142,
    val totalWordsRecognized: Int = 18450,
    val totalMeetingsProcessed: Int = 24
)

data class OnDeviceModel(
    val id: String,
    val name: String,
    val type: String,
    val sizeMb: Int,
    val isDownloaded: Boolean,
    val description: String
)
