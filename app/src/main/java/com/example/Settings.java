package com.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaRecorder;

/** Настройки приложения. Текст расшифровки в базе всегда дословный; чистка и шумоподавление включаются отдельно. */
final class Settings {
    /** Режимы записи: определяют источник, битрейт и частоту дискретизации. */
    static final int MODE_ACCURATE = 0;
    static final int MODE_FAST = 1;
    static final int MODE_REAL_LIFE = 2;

    static final int MAX_SPEAKERS = 20;

    private final Context app;
    private final SharedPreferences prefs;

    Settings(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Убирать слова-паразиты и повторы при показе и экспорте (кроме JSON). По умолчанию выключено. */
    boolean cleanText() { return prefs.getBoolean("clean_text", false); }
    void setCleanText(boolean value) { prefs.edit().putBoolean("clean_text", value).apply(); }

    /**
     * Шумоподавление перед расшифровкой. Если пользователь ничего не выбирал,
     * включено только в режиме записи «Real-life».
     */
    boolean denoise() {
        return prefs.contains("denoise") ? prefs.getBoolean("denoise", false) : recordMode() == MODE_REAL_LIFE;
    }
    void setDenoise(boolean value) { prefs.edit().putBoolean("denoise", value).apply(); }

    boolean autoTranscribe() { return prefs.getBoolean("auto_transcribe", true); }
    void setAutoTranscribe(boolean value) { prefs.edit().putBoolean("auto_transcribe", value).apply(); }

    boolean speakerColors() { return prefs.getBoolean("speaker_colors", true); }
    void setSpeakerColors(boolean value) { prefs.edit().putBoolean("speaker_colors", value).apply(); }

    /** Код языка Whisper: «ru», «en» или пустая строка для автоопределения. */
    String language() { return prefs.getString("language", "ru"); }
    void setLanguage(String value) { prefs.edit().putString("language", value).apply(); }

    /** Точное число говорящих (1..20); 0 — определять автоматически. */
    int speakerCount() { return prefs.getInt("speaker_count", 0); }
    void setSpeakerCount(int value) {
        prefs.edit().putInt("speaker_count", Math.max(0, Math.min(MAX_SPEAKERS, value))).apply();
    }

    /** Один раз при первом запуске предлагаем скачать модели. */
    boolean modelsPrompted() { return prefs.getBoolean("models_prompted", false); }
    void setModelsPrompted(boolean value) { prefs.edit().putBoolean("models_prompted", value).apply(); }

    // ---- режим записи -------------------------------------------------------------------------

    int recordMode() { return prefs.getInt("record_mode", MODE_ACCURATE); }
    void setRecordMode(int value) { prefs.edit().putInt("record_mode", value).apply(); }

    int bitrate() {
        switch (recordMode()) {
            case MODE_FAST: return 64000;
            case MODE_REAL_LIFE: return 128000;
            default: return 192000;
        }
    }

    int sampleRate() {
        return recordMode() == MODE_FAST ? 16000 : 44100;
    }

    /** Источник для MediaRecorder. «Точный» берёт сырой звук, если устройство его поддерживает. */
    int mediaRecorderSource() {
        if (recordMode() != MODE_ACCURATE) return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        boolean raw = am != null
                && "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        return raw ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC;
    }
}
