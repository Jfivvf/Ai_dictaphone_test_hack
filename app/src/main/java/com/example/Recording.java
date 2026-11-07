package com.example;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Запись: метаданные, плоский текст для поиска и имена говорящих. */
final class Recording {
    final long id;
    final String title;
    final String audioPath;
    final String transcript;
    final long createdAt;
    final String status;
    final long durationMs;
    final String error;
    final String speakerNamesJson;
    private final Map<Integer, String> speakerNames = new HashMap<>();

    Recording(long id, String title, String audioPath, String transcript, long createdAt,
              String status, long durationMs, String error, String speakerNamesJson) {
        this.id = id;
        this.title = title;
        this.audioPath = audioPath;
        this.transcript = transcript == null ? "" : transcript;
        this.createdAt = createdAt;
        this.status = status;
        this.durationMs = durationMs;
        this.error = error == null ? "" : error;
        this.speakerNamesJson = speakerNamesJson == null ? "" : speakerNamesJson;
        if (speakerNamesJson != null && !speakerNamesJson.isEmpty()) {
            try {
                JSONObject json = new JSONObject(speakerNamesJson);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    speakerNames.put(Integer.parseInt(key), json.getString(key));
                }
            } catch (Exception ignored) {
                // Повреждённые имена не должны ломать открытие записи.
            }
        }
    }

    /** Имя говорящего: заданное пользователем или «Спикер N». Для -1 возвращает пустую строку. */
    String speakerName(int speaker) {
        if (speaker < 0) return "";
        String custom = speakerNames.get(speaker);
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        return "Спикер " + (speaker + 1);
    }
}
