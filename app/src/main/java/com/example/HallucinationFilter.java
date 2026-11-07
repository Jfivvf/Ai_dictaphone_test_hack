package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whisper на шуме и паузах склонен «досочинять»: зацикливает слова и выдаёт фразы из обучающих субтитров.
 * Фильтр вырезает только такие артефакты. Обычная речь, слова-паразиты и заикание не затрагиваются.
 */
final class HallucinationFilter {
    /** Артефакты субтитров, которых в живой речи не бывает. */
    private static final String[] ALWAYS_DROP = {
            "субтитры сделал", "субтитры создавал", "субтитры подогнал", "dimatorzok", "редактор субтитров",
            "корректор а.", "amara.org", "субтитры добавил", "переведено на сайте"
    };
    /** Фразы-«прощания»: настоящие в длинной громкой реплике, мусорные в короткой тихой. */
    private static final String[] DROP_IF_SHORT_AND_QUIET = {
            "спасибо за просмотр", "продолжение следует", "подписывайтесь на канал", "до новых встреч",
            "спасибо за внимание"
    };
    private static final int MAX_CHARS_PER_SECOND = 28;

    private HallucinationFilter() {}

    /** Возвращает очищенный текст или пустую строку, если реплика — выдумка. */
    static String clean(String text, long durationMs, float rms) {
        if (text == null) return "";
        String result = text.trim();
        if (result.isEmpty()) return "";

        String lower = result.toLowerCase(Locale.ROOT);
        for (String bad : ALWAYS_DROP) {
            if (lower.contains(bad)) return "";
        }
        boolean shortAndQuiet = durationMs <= 5000 && rms < 0.03f;
        if (shortAndQuiet) {
            for (String phrase : DROP_IF_SHORT_AND_QUIET) {
                if (lower.contains(phrase) && lower.length() < phrase.length() + 25) return "";
            }
        }

        int letters = 0;
        for (int i = 0; i < result.length(); i++) if (Character.isLetterOrDigit(result.charAt(i))) letters++;
        double seconds = Math.max(0.2, durationMs / 1000.0);
        if (letters / seconds > MAX_CHARS_PER_SECOND && letters > 12) return "";

        return collapseLoops(result);
    }

    /** Если одно слово или короткая фраза повторяется 4+ раз подряд, оставляет два повтора. */
    static String collapseLoops(String text) {
        String[] words = text.split("\\s+");
        if (words.length < 4) return text;
        String[] keys = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            keys[i] = words[i].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        }
        boolean[] drop = new boolean[words.length];
        boolean changed = false;
        for (int n = 1; n <= 4; n++) {
            int i = 0;
            while (i + n <= words.length) {
                int repeats = 1;
                while (matches(keys, i, i + repeats * n, n)) repeats++;
                if (repeats >= 4 && notEmpty(keys, i, n)) {
                    for (int k = i + 2 * n; k < i + repeats * n; k++) drop[k] = true;
                    changed = true;
                    i += repeats * n;
                } else {
                    i++;
                }
            }
        }
        if (!changed) return text;
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < words.length; i++) if (!drop[i]) kept.add(words[i]);
        return android.text.TextUtils.join(" ", kept);
    }

    private static boolean matches(String[] keys, int a, int b, int n) {
        if (b + n > keys.length) return false;
        for (int k = 0; k < n; k++) if (!keys[a + k].equals(keys[b + k])) return false;
        return true;
    }

    private static boolean notEmpty(String[] keys, int from, int n) {
        for (int k = 0; k < n; k++) if (!keys[from + k].isEmpty()) return true;
        return false;
    }
}
