package com.example;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** PDF для печати: светлая страница, цветные говорящие, таймкоды, краткое содержание и задачи. */
final class PdfExporter {
    private static final int W = 595;
    private static final int H = 842;
    private static final float M = 44f;
    private static final float TOP = 52f;
    private static final float BOTTOM = H - 56f;
    /** Ширина колонки с таймкодом. */
    private static final float TC = 58f;

    private static final int INK = 0xFF16161D;
    private static final int MUTED = 0xFF6B6B78;
    private static final int ACCENT = 0xFF4F46E5;
    private static final int RULE = 0xFFE3E3EA;
    private static final int PANEL = 0xFFF3F2FF;

    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);

    private final PdfDocument doc = new PdfDocument();
    private final String title;
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNumber = 0;
    private float y = TOP;

    private PdfExporter(String title) {
        this.title = title;
    }

    static void export(Recording recording, List<Segment> segments, List<Task> tasks, boolean clean,
                       OutputStream out) throws IOException {
        PdfExporter exporter = new PdfExporter(recording.title);
        try {
            exporter.build(recording, segments, tasks, clean);
            exporter.finishPage();
            exporter.doc.writeTo(out);
        } finally {
            exporter.doc.close();
        }
    }

    private void build(Recording r, List<Segment> segments, List<Task> tasks, boolean clean) {
        newPage();

        drawBlock(layout(r.title, paint(22, INK, BOLD), W - 2 * M), M);
        y += 4;
        StringBuilder meta = new StringBuilder(Fmt.date(r.createdAt));
        if (r.durationMs > 0) meta.append("  ·  ").append(Fmt.duration(r.durationMs));
        drawBlock(layout(meta, paint(10, MUTED, REGULAR), W - 2 * M), M);
        y += 8;

        List<Integer> speakers = NoteBuilder.speakersInOrder(segments);
        if (!speakers.isEmpty()) legend(r, speakers);
        y += 6;
        rule();
        y += 14;

        heading("Расшифровка");
        TextPaint body = paint(10.5f, INK, REGULAR);
        TextPaint stamp = paint(8.5f, MUTED, MONO);
        for (Segment s : segments) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (s.speaker >= 0) {
                sb.append(r.speakerName(s.speaker));
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Palette.speakerPrint(s.speaker)), 0, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append('\n');
            }
            sb.append(NoteBuilder.text(s, clean));
            StaticLayout layout = layout(sb, body, W - 2 * M - TC);
            ensure(keepTogether(layout));
            canvas.drawText(Fmt.stamp(s.startMs), M, y + 9.5f, stamp);
            drawBlock(layout, M + TC);
            y += 9;
        }

        String summary = NoteBuilder.summary(segments, clean);
        if (!summary.isEmpty()) {
            y += 6;
            heading("Краткое содержание");
            StaticLayout inner = layout(summary, paint(10.5f, INK, REGULAR), W - 2 * M - 24);
            float height = inner.getHeight() + 20;
            if (height <= BOTTOM - TOP) {
                ensure(height);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(PANEL);
                canvas.drawRoundRect(new RectF(M, y, W - M, y + height), 8, 8, fill);
                canvas.save();
                canvas.translate(M + 12, y + 10);
                inner.draw(canvas);
                canvas.restore();
                y += height + 10;
            } else {
                drawBlock(layout(summary, paint(10.5f, INK, REGULAR), W - 2 * M), M);
            }
        }

        if (!tasks.isEmpty()) {
            y += 6;
            heading("Задачи");
            TextPaint taskPaint = paint(10.5f, INK, REGULAR);
            Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(1.2f);
            box.setColor(ACCENT);
            for (Task t : tasks) {
                SpannableStringBuilder sb = new SpannableStringBuilder(clean ? TextCleaner.clean(t.text) : t.text);
                StringBuilder tail = new StringBuilder();
                if (t.speaker >= 0) tail.append(r.speakerName(t.speaker)).append("  ·  ");
                tail.append(Fmt.stamp(t.startMs));
                if (!t.deadline.isEmpty()) tail.append("  ·  срок: ").append(t.deadline);
                int from = sb.length() + 1;
                sb.append('\n').append(tail);
                sb.setSpan(new ForegroundColorSpan(MUTED), from, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(0.85f), from, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                StaticLayout layout = layout(sb, taskPaint, W - 2 * M - 20);
                ensure(keepTogether(layout));
                canvas.drawRoundRect(new RectF(M, y + 2, M + 10, y + 12), 2, 2, box);
                if (t.done) {
                    canvas.drawLine(M + 2, y + 7, M + 4.5f, y + 9.5f, box);
                    canvas.drawLine(M + 4.5f, y + 9.5f, M + 8.5f, y + 4, box);
                }
                drawBlock(layout, M + 20);
                y += 7;
            }
        }
    }

    // ---- строительные блоки ------------------------------------------------------------------

    private TextPaint paint(float size, int color, Typeface typeface) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(typeface);
        return p;
    }

    private StaticLayout layout(CharSequence text, TextPaint paint, float width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.round(width))
                .setLineSpacing(0f, 1.22f)
                .build();
    }

    private void newPage() {
        finishPage();
        pageNumber++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNumber).create());
        canvas = page.getCanvas();
        y = TOP;
    }

    private void finishPage() {
        if (page == null) return;
        TextPaint p = paint(8, MUTED, REGULAR);
        CharSequence shown = TextUtils.ellipsize(title, p, W - 2 * M - 70, TextUtils.TruncateAt.END);
        canvas.drawText(shown, 0, shown.length(), M, H - 30, p);
        String number = "стр. " + pageNumber;
        canvas.drawText(number, W - M - p.measureText(number), H - 30, p);
        doc.finishPage(page);
        page = null;
    }

    /** Переходит на новую страницу, если блок указанной высоты не помещается. */
    private void ensure(float height) {
        if (y + height > BOTTOM && y > TOP) newPage();
    }

    /** Не оставляет на странице одну строку реплики: держит вместе первые две строки. */
    private float keepTogether(StaticLayout layout) {
        if (layout.getLineCount() < 2) return layout.getHeight();
        return Math.min(layout.getHeight(), layout.getLineBottom(1));
    }

    /** Рисует текст, при необходимости разрезая его по строкам между страницами. */
    private void drawBlock(StaticLayout layout, float x) {
        int lines = layout.getLineCount();
        int line = 0;
        while (line < lines) {
            float available = BOTTOM - y;
            float top = layout.getLineTop(line);
            int last = line;
            while (last < lines && layout.getLineBottom(last) - top <= available) last++;
            if (last == line) {
                if (y > TOP) {
                    newPage();
                    continue;
                }
                last = line + 1;
            }
            float bottom = layout.getLineBottom(last - 1);
            canvas.save();
            canvas.translate(x, y - top);
            canvas.clipRect(0, top, layout.getWidth(), bottom);
            layout.draw(canvas);
            canvas.restore();
            y += bottom - top;
            line = last;
            if (line < lines) newPage();
        }
    }

    private void heading(String text) {
        ensure(60);
        TextPaint p = paint(13, INK, BOLD);
        canvas.drawText(text, M, y + 12, p);
        y += 20;
        Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        accent.setColor(ACCENT);
        canvas.drawRect(M, y, M + 26, y + 2, accent);
        y += 12;
    }

    private void rule() {
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(RULE);
        line.setStrokeWidth(0.8f);
        canvas.drawLine(M, y, W - M, y, line);
    }

    private void legend(Recording r, List<Integer> speakers) {
        TextPaint p = paint(10, INK, REGULAR);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float lineHeight = 16f;
        float x = M;
        ensure(lineHeight);
        for (int speaker : speakers) {
            String name = r.speakerName(speaker);
            float width = 12 + p.measureText(name) + 16;
            if (x + width > W - M && x > M) {
                x = M;
                y += lineHeight;
                ensure(lineHeight);
            }
            dot.setColor(Palette.speakerPrint(speaker));
            canvas.drawCircle(x + 4, y + 7, 3.5f, dot);
            canvas.drawText(name, x + 12, y + 10.5f, p);
            x += width;
        }
        y += lineHeight;
    }
}
