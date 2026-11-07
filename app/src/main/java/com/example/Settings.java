package com.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;

/** Настройки приложения. Распознавание по умолчанию не правит текст: «причёсывание» выключено. */
final class Settings {
    static final int SOURCE_SPEECH = 0;
    static final int SOURCE_MIC = 1;
    static final int SOURCE_UNPROCESSED = 2;

    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Убирать слова-паразиты и повторы при показе и экспорте. По умолчанию выключено. */
    boolean cleanText() { return prefs.getBoolean("clean_text", false); }
    void setCleanText(boolean value) { prefs.edit().putBoolean("clean_text", value).apply(); }

    boolean autoTranscribe() { return prefs.getBoolean("auto_transcribe", true); }
    void setAutoTranscribe(boolean value) { prefs.edit().putBoolean("auto_transcribe", value).apply(); }

    boolean speakerColors() { return prefs.getBoolean("speaker_colors", true); }
    void setSpeakerColors(boolean value) { prefs.edit().putBoolean("speaker_colors", value).apply(); }

    /** Код языка Whisper: «ru», «en» или пустая строка для автоопределения. */
    String language() { return prefs.getString("language", "ru"); }
    void setLanguage(String value) { prefs.edit().putString("language", value).apply(); }

    /** Точное число говорящих; 0 — определять автоматически. */
    int speakerCount() { return prefs.getInt("speaker_count", 0); }
    void setSpeakerCount(int value) { prefs.edit().putInt("speaker_count", value).apply(); }

    /** Один раз при первом запуске предлагаем скачать модели. */
    boolean modelsPrompted() { return prefs.getBoolean("models_prompted", false); }
    void setModelsPrompted(boolean value) { prefs.edit().putBoolean("models_prompted", value).apply(); }

    int audioSource() { return prefs.getInt("audio_source", SOURCE_SPEECH); }
    void setAudioSource(int value) { prefs.edit().putInt("audio_source", value).apply(); }

    int bitrate() { return prefs.getInt("bitrate", 128000); }
    void setBitrate(int value) { prefs.edit().putInt("bitrate", value).apply(); }

    /** Источник для MediaRecorder; UNPROCESSED есть только с API 24, но не на всех устройствах. */
    int mediaRecorderSource() {
        switch (audioSource()) {
            case SOURCE_MIC: return MediaRecorder.AudioSource.MIC;
            case SOURCE_UNPROCESSED: return MediaRecorder.AudioSource.UNPROCESSED;
            default: return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
    }
}
