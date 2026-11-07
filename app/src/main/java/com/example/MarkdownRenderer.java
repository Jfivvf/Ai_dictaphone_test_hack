package com.example;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Небольшой рендерер markdown в Spannable: заголовки, жирный, курсив, код, списки, чекбоксы, цитаты.
 * Если передана карта цветов говорящих, подписи реплик (**[00:00:03] Имя:**) красятся в цвет говорящего.
 */
final class MarkdownRenderer {
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final Pattern CHECK = Pattern.compile("^\\s*[-*]\\s+\\[([ xX])\\]\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*(-{3,}|\\*{3,})\\s*$");

    private final Map<String, Integer> speakerColors;
    private final int textColor;
    private final int mutedColor;
    private final int accentColor;
    private final float density;

    MarkdownRenderer(Map<String, Integer> speakerColors, int textColor, int mutedColor, int accentColor,
                     float density) {
        this.speakerColors = speakerColors;
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        this.accentColor = accentColor;
        this.density = density;
    }

    CharSequence render(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean previousBlank = true;
        for (String line : markdown.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                if (!previousBlank) {
                    int at = out.length();
                    out.append('\n');
                    out.setSpan(new RelativeSizeSpan(0.45f), at, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                previousBlank = true;
                continue;
            }
            previousBlank = false;
            block(out, line);
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') end--;
        return out.subSequence(0, end);
    }

    private void block(SpannableStringBuilder out, String line) {
        int start = out.length();
        Matcher m;
        if ((m = HEADING.matcher(line)).matches()) {
            int level = m.group(1).length();
            inline(out, m.group(2));
            int len = out.length();
            out.setSpan(new StyleSpan(Typeface.BOLD), start, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(level == 1 ? 1.45f : level == 2 ? 1.22f : 1.1f), start, len,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append('\n');
            return;
        }
        if (RULE.matcher(line).matches()) {
            out.append("────────────\n");
            out.setSpan(new ForegroundColorSpan(mutedColor), start, out.length() - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        if ((m = CHECK.matcher(line)).matches()) {
            boolean done = !m.group(1).equals(" ");
            out.append(done ? "☑ " : "☐ ");
            out.setSpan(new ForegroundColorSpan(done ? accentColor : mutedColor), start, out.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            inline(out, m.group(2));
            hanging(out, start);
            return;
        }
        if ((m = BULLET.matcher(line)).matches()) {
            out.append("• ");
            inline(out, m.group(1));
            hanging(out, start);
            return;
        }
        if ((m = NUMBERED.matcher(line)).matches()) {
            out.append(m.group(1)).append(". ");
            inline(out, m.group(2));
            hanging(out, start);
            return;
        }
        if ((m = QUOTE.matcher(line)).matches()) {
            inline(out, m.group(1));
            out.append('\n');
            out.setSpan(new QuoteSpan(accentColor), start, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        inline(out, line);
        out.append('\n');
    }

    /** Висячий отступ: перенесённые строки пункта выравниваются по тексту, а не по маркеру. */
    private void hanging(SpannableStringBuilder out, int start) {
        out.append('\n');
        int indent = Math.round(18 * density);
        out.setSpan(new LeadingMarginSpan.Standard(0, indent), start, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void inline(SpannableStringBuilder out, String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && "\\*`#_".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '*' && s.startsWith("**", i)) {
                int end = findClose(s, "**", i + 2);
                if (end > i + 2) {
                    int from = out.length();
                    inline(out, s.substring(i + 2, end));
                    int to = out.length();
                    out.setSpan(new StyleSpan(Typeface.BOLD), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    colorSpeaker(out, from, to);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '*') {
                int end = findClose(s, "*", i + 1);
                if (end > i + 1) {
                    int from = out.length();
                    inline(out, s.substring(i + 1, end));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), from, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int from = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), from, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(Palette.alpha(0xFFFFFFFF, 0x22)), from, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }

    /** Ищет закрывающий маркер, пропуская экранированные символы. */
    private static int findClose(String s, String marker, int from) {
        int i = from;
        while (i < s.length()) {
            if (s.charAt(i) == '\\') {
                i += 2;
                continue;
            }
            if (s.startsWith(marker, i)) return i;
            i++;
        }
        return -1;
    }

    private void colorSpeaker(SpannableStringBuilder out, int from, int to) {
        if (speakerColors == null || speakerColors.isEmpty()) return;
        String label = out.subSequence(from, to).toString();
        for (Map.Entry<String, Integer> entry : speakerColors.entrySet()) {
            if (label.endsWith(entry.getKey() + ":")) {
                out.setSpan(new ForegroundColorSpan(entry.getValue()), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
        }
    }
}
