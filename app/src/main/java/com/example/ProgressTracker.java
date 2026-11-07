package com.example;

import android.os.SystemClock;

/**
 * Прогресс расшифровки одной записи: этапы с весами, общий процент и примерное время до конца.
 * Веса — ожидаемая доля времени этапа. Время считается по скорости текущего этапа и весам оставшихся;
 * оценка появляется, когда этап выполнен хотя бы на 3 % и идёт не меньше 5 секунд, и сглаживается.
 */
final class ProgressTracker {
    enum Stage {
        DECODE("Подготовка аудио", 0.05f),
        DENOISE("Шумоподавление", 0.10f),
        DIARIZE("Определение говорящих", 0.20f),
        RECOGNIZE("Расшифровка", 0.65f);

        final String title;
        final float weight;

        Stage(String title, float weight) {
            this.title = title;
            this.weight = weight;
        }
    }

    private final boolean denoise;
    private Stage stage = Stage.DECODE;
    private long stageStart = SystemClock.elapsedRealtime();
    private float fraction = -1f;
    private String detail = "";
    private double eta = -1;
    private String noteLabel = null;
    private int notePercent = -1;

    ProgressTracker(boolean denoise) {
        this.denoise = denoise;
    }

    private float weight(Stage s) {
        return s == Stage.DENOISE && !denoise ? 0f : s.weight;
    }

    private float totalWeight() {
        float sum = 0;
        for (Stage s : Stage.values()) sum += weight(s);
        return sum;
    }

    /** Служебная строка вне этапов (загрузка моделей). null — вернуться к обычному выводу. */
    synchronized void setNote(String label, int percent) {
        noteLabel = label;
        notePercent = percent;
    }

    /** fraction — доля текущего этапа 0..1, либо -1, если неизвестна. detail — например «реплика 12 из 47». */
    synchronized void update(Stage next, float fraction, String detail) {
        long now = SystemClock.elapsedRealtime();
        if (next != stage) {
            stage = next;
            stageStart = now;
            eta = -1;
        }
        this.fraction = fraction < 0 ? -1f : Math.min(1f, fraction);
        this.detail = detail == null ? "" : detail;
        if (this.fraction < 0) {
            eta = -1;
            return;
        }
        long elapsed = now - stageStart;
        if (this.fraction < 0.03f || elapsed < 5000) return;

        double stageTotal = elapsed / (double) this.fraction;
        double later = 0;
        for (Stage s : Stage.values()) if (s.ordinal() > stage.ordinal()) later += weight(s);
        double w = weight(stage);
        double raw = stageTotal * (1 - this.fraction) + (w > 0 ? stageTotal / w * later : 0);
        eta = eta < 0 ? raw : eta * 0.7 + raw * 0.3;
    }

    /** Общий процент 0..100 или -1, если сейчас неизвестен. */
    synchronized int percent() {
        if (noteLabel != null) return notePercent;
        if (fraction < 0) return -1;
        float base = 0;
        for (Stage s : Stage.values()) if (s.ordinal() < stage.ordinal()) base += weight(s);
        return Math.min(100, Math.round((base + weight(stage) * fraction) / totalWeight() * 100f));
    }

    /** «Расшифровка · реплика 12 из 47 · 38% · ещё ~3 мин». */
    synchronized String label() {
        if (noteLabel != null) return noteLabel;
        StringBuilder b = new StringBuilder(stage.title);
        if (!detail.isEmpty()) b.append(" · ").append(detail);
        int p = percent();
        if (p >= 0) b.append(" · ").append(p).append('%');
        if (eta > 0) b.append(" · ").append(Fmt.eta((long) eta));
        return b.toString();
    }
}
