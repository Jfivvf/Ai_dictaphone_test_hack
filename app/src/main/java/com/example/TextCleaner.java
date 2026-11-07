package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Необязательное «причёсывание» текста: убирает слова-паразиты, заикания и повторы слов.
 * Работает только при показе и экспорте, исходная расшифровка в базе не меняется.
 */
final class TextCleaner {
    private TextCleaner() {}

    static String clean(String text) {
        if (text == null) return "";
        String[] words = text.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        boolean sentenceStart = true;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;
            String core = core(word);

            int skip = 0;
            if (isFiller(core)) skip = 1;
            else if (i + 1 < words.length && isFillerPair(core, core(words[i + 1]))) skip = 2;

            if (skip > 0) {
                String tail = tail(words[i + skip - 1]);
                if (!out.isEmpty() && endsSentence(tail) && !endsSentence(tail(out.get(out.size() - 1)))) {
                    out.set(out.size() - 1, stripTrailingComma(out.get(out.size() - 1)) + sentenceMark(tail));
                    sentenceStart = true;
                }
                i += skip - 1;
                continue;
            }

            if (!out.isEmpty() && !core.isEmpty() && core.equals(core(out.get(out.size() - 1)))
                    && !endsSentence(tail(out.get(out.size() - 1))) && core.length() <= 12) {
                // Повтор слова подряд («я я я думаю»): оставляем последнее вхождение с его пунктуацией.
                String previous = out.remove(out.size() - 1);
                if (startsUpper(previous) && !startsUpper(word)) word = capitalize(word);
            }

            if (sentenceStart && !word.isEmpty()) word = capitalize(word);
            out.add(word);
            sentenceStart = endsSentence(tail(word));
        }
        StringBuilder result = new StringBuilder();
        for (String w : out) {
            if (result.length() > 0) result.append(' ');
            result.append(w);
        }
        return result.toString().trim();
    }

    /** Слово без знаков препинания и дефисов в нижнем регистре. */
    private static String core(String word) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) b.append(c);
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }

    /** Знаки после последней буквы слова. */
    private static String tail(String word) {
        int end = word.length();
        while (end > 0 && !Character.isLetter(word.charAt(end - 1))) end--;
        return word.substring(end);
    }

    private static boolean endsSentence(String tail) {
        return tail.indexOf('.') >= 0 || tail.indexOf('!') >= 0 || tail.indexOf('?') >= 0 || tail.indexOf('…') >= 0;
    }

    private static String sentenceMark(String tail) {
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '…') return String.valueOf(c);
        }
        return "";
    }

    private static String stripTrailingComma(String word) {
        String result = word;
        while (result.endsWith(",")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static boolean isFiller(String core) {
        if (core.isEmpty()) return false;
        if (core.matches("э+|эм+|м{2,}|хм+|гм+|а{2,}|у{2,}|ну|типа|нуу+")) return true;
        return false;
    }

    private static boolean isFillerPair(String first, String second) {
        return (first.equals("как") && second.equals("бы"))
                || (first.equals("это") && second.equals("самое"))
                || (first.equals("в") && second.equals("общем"))
                || (first.equals("так") && second.equals("сказать"));
    }

    private static boolean startsUpper(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) return Character.isUpperCase(word.charAt(i));
        }
        return false;
    }

    private static String capitalize(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) {
                return word.substring(0, i) + Character.toUpperCase(word.charAt(i)) + word.substring(i + 1);
            }
        }
        return word;
    }
}
