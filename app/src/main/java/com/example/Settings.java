package com.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;

final class Settings {
    static final int MODE_ACCURATE = 0;
    static final int MODE_FAST = 1;
    static final int MODE_REAL = 2;

    private final SharedPreferences prefs;
    private final Context app;

    Settings(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // ---- расшифровка -----------------------------------------------------------

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
    void setSpeakerCount(int v) {
        prefs.edit().putInt("speaker_count", Math.max(0, Math.min(20, v))).apply();
    }

    /** Один раз при первом запуске предлагаем скачать модели. */
    boolean modelsPrompted() { return prefs.getBoolean("models_prompted", false); }
    void setModelsPrompted(boolean value) { prefs.edit().putBoolean("models_prompted", value).apply(); }

    // ---- запись ----------------------------------------------------------------

    int recordMode() { return prefs.getInt("record_mode", MODE_ACCURATE); }
    void setRecordMode(int v) { prefs.edit().putInt("record_mode", v).apply(); }

    /** Битрейт в бит/с, зависит от режима записи. */
    int bitrate() {
        switch (recordMode()) {
            case MODE_FAST: return 64000;
            case MODE_REAL: return 128000;
            default:        return 192000;
        }
    }

    /** Частота дискретизации в Гц, зависит от режима записи. */
    int sampleRate() {
        return recordMode() == MODE_FAST ? 16000 : 44100;
    }


    int mediaRecorderSource() {
        if (recordMode() != MODE_ACCURATE) {
            return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
        boolean raw = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                raw = "true".equals(am.getProperty(
                        AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            }
        }
        return raw ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC;
    }
}
