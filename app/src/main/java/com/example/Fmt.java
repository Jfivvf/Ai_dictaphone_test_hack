package com.example;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Форматирование времени для интерфейса, markdown и PDF. */
final class Fmt {
    private static final Locale RU = Locale.forLanguageTag("ru");

    private Fmt() {}

    /** Таймкод реплики: 00:01:23. */
    static String stamp(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    /** Короткий таймер: 05:07 или 1:05:07. */
    static String clock(long ms) {
        long total = Math.max(0, ms) / 1000;
        if (total >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    /** Длительность словами: «45 с», «12 мин», «1 ч 27 мин». */
    static String duration(long ms) {
        long total = Math.max(0, ms) / 1000;
        if (total < 60) return total + " с";
        long minutes = total / 60;
        if (minutes < 60) return minutes + " мин";
        long rest = minutes % 60;
        return (minutes / 60) + " ч" + (rest == 0 ? "" : " " + rest + " мин");
    }

    /** Оставшееся время для строки прогресса: «ещё меньше минуты», «ещё ~3 мин», «ещё ~1 ч 5 мин». */
    static String eta(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        if (seconds < 45) return "ещё меньше минуты";
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) return "ещё ~" + minutes + " мин";
        long rest = minutes % 60;
        return "ещё ~" + (minutes / 60) + " ч" + (rest == 0 ? "" : " " + rest + " мин");
    }

    /** Склонение по числу: plural(2, "задача", "задачи", "задач") → «задачи». */
    static String plural(long n, String one, String few, String many) {
        long mod100 = Math.abs(n) % 100;
        long mod10 = mod100 % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }

    static String date(long millis) {
        return new SimpleDateFormat("d MMMM yyyy, HH:mm", RU).format(new Date(millis));
    }

    static String shortDate(long millis) {
        return new SimpleDateFormat("d MMM, HH:mm", RU).format(new Date(millis));
    }
}
