package com.example;

/** Коды статуса записи. В базе хранятся коды, подписи и цвета выводятся только в интерфейсе. */
final class Status {
    static final String RECORDED = "recorded";
    static final String QUEUED = "queued";
    static final String MODELS = "models";
    static final String DECODING = "decoding";
    static final String RECOGNIZING = "recognizing";
    static final String DONE = "done";
    static final String DONE_NO_SPEAKERS = "done_no_speakers";
    static final String ERROR = "error";

    private Status() {}

    static String label(String code) {
        if (code == null) return "";
        switch (code) {
            case RECORDED: return "Не расшифровано";
            case QUEUED: return "В очереди";
            case MODELS: return "Загрузка моделей";
            case DECODING: return "Подготовка аудио";
            case RECOGNIZING: return "Расшифровка";
            case DONE: return "Готово";
            case DONE_NO_SPEAKERS: return "Без разметки говорящих";
            case ERROR: return "Ошибка";
            default: return code;
        }
    }

    static boolean isBusy(String code) {
        return QUEUED.equals(code) || MODELS.equals(code) || DECODING.equals(code) || RECOGNIZING.equals(code);
    }

    static boolean isFinished(String code) {
        return DONE.equals(code) || DONE_NO_SPEAKERS.equals(code);
    }

    static int color(String code) {
        if (isFinished(code)) return Palette.GREEN;
        if (isBusy(code)) return Palette.ACCENT_SOFT;
        if (ERROR.equals(code)) return Palette.RED;
        return Palette.MUTED;
    }
}
