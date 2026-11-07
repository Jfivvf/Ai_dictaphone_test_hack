package com.example;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** Общие элементы интерфейса: размеры, фоны, текст, чипы, переключатели, индикаторы. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    // ---- фоны ---------------------------------------------------------------------------------

    static GradientDrawable box(Context c, int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable box(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable d = box(c, fill, radiusDp);
        d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    static GradientDrawable oval(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    /** Фон с эффектом нажатия, повторяющий форму исходного фона. */
    static Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(Palette.alpha(0xFFFFFFFF, 0x26)), content, null);
    }

    static ColorDrawable transparent() {
        return new ColorDrawable(0);
    }

    // ---- текст и значки -----------------------------------------------------------------------

    static TextView text(Context c, CharSequence value, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView bold(Context c, CharSequence value, float sp, int color) {
        TextView t = text(c, value, sp, color);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    static TextView mono(Context c, CharSequence value, float sp, int color) {
        TextView t = text(c, value, sp, color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    static TextView ellipsized(TextView t, int lines) {
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static ImageView icon(Context c, int res, int tint, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Круглая или скруглённая кнопка со значком по центру. */
    static FrameLayout iconButton(Context c, int res, int tint, int fill, float boxDp, float iconDp,
                                  String description, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(ripple(fill == 0 ? transparent() : oval(fill)));
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setColorFilter(tint);
        i.setTag("icon");
        f.addView(i, new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, boxDp), dp(c, boxDp)));
        f.setContentDescription(description);
        f.setClickable(true);
        f.setFocusable(true);
        f.setOnClickListener(click);
        return f;
    }

    static void setButtonIcon(FrameLayout button, int res) {
        View icon = button.findViewWithTag("icon");
        if (icon instanceof ImageView) ((ImageView) icon).setImageResource(res);
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, Context c, float l, float t, float r, float b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static View space(Context c, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    /** Карточка: поверхность со скруглением и тонкой рамкой. */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(box(c, Palette.SURFACE, 18, Palette.BORDER));
        l.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        return l;
    }

    static TextView pill(Context c, String label, int color) {
        TextView t = bold(c, label, 11, color);
        t.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        t.setBackground(box(c, Palette.alpha(color, 0x26), 8));
        return t;
    }

    static TextView primaryButton(Context c, String label, View.OnClickListener click) {
        TextView t = bold(c, label, 15, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14));
        t.setBackground(ripple(box(c, Palette.ACCENT, 14)));
        t.setOnClickListener(click);
        return t;
    }

    static TextView secondaryButton(Context c, String label, View.OnClickListener click) {
        TextView t = bold(c, label, 15, Palette.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14));
        t.setBackground(ripple(box(c, Palette.SURFACE_2, 14, Palette.BORDER)));
        t.setOnClickListener(click);
        return t;
    }

    /** Переключатель-чип: включён — залит акцентом, выключен — контур. */
    static TextView toggleChip(Context c, String label, boolean on, View.OnClickListener click) {
        TextView t = text(c, label, 13, Palette.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        t.setOnClickListener(click);
        styleToggleChip(c, t, on);
        return t;
    }

    static void styleToggleChip(Context c, TextView t, boolean on) {
        t.setBackground(ripple(on
                ? box(c, Palette.alpha(Palette.ACCENT, 0x55), 16, Palette.ACCENT)
                : box(c, Palette.SURFACE, 16, Palette.BORDER)));
        t.setTextColor(on ? Palette.TEXT : Palette.MUTED);
    }

    static Switch toggle(Context c, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        Switch s = new Switch(c);
        s.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{Palette.ACCENT_SOFT, Palette.MUTED}));
        s.setTrackTintList(new ColorStateList(states, new int[]{Palette.alpha(Palette.ACCENT, 0x99), Palette.BORDER}));
        s.setOnCheckedChangeListener(listener);
        return s;
    }

    static void styleList(ListView list) {
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(transparent());
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setCacheColorHint(0);
    }

    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert);
    }

    // ---- сегментированный переключатель -------------------------------------------------------

    /** Ряд равных вариантов, выбран ровно один. */
    static final class Segmented extends LinearLayout {
        private final TextView[] items;
        private final Context context;

        Segmented(Context c, String[] labels, int selected, IntConsumer onSelect) {
            super(c);
            context = c;
            setOrientation(HORIZONTAL);
            setBackground(box(c, Palette.BG, 14, Palette.BORDER));
            setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
            items = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                TextView t = text(c, labels[i], 13, Palette.MUTED);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(c, 4), dp(c, 10), dp(c, 4), dp(c, 10));
                t.setSingleLine(true);
                t.setOnClickListener(v -> {
                    if (!isEnabled()) return;
                    select(index);
                    onSelect.accept(index);
                });
                items[i] = t;
                addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            select(selected);
        }

        void select(int index) {
            for (int i = 0; i < items.length; i++) {
                boolean on = i == index;
                items[i].setBackground(on ? ripple(box(context, Palette.ACCENT, 11)) : transparent());
                items[i].setTextColor(on ? 0xFFFFFFFF : Palette.MUTED);
                items[i].setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
            }
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.45f);
        }
    }

    // ---- тонкий индикатор прогресса ------------------------------------------------------------

    /** Полоса прогресса: при неизвестной доле бежит бегунок. */
    static final class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int percent = -1;

        ProgressLine(Context c) {
            super(c);
        }

        void set(int value) {
            percent = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            paint.setColor(Palette.BORDER);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setColor(Palette.ACCENT_SOFT);
            if (percent >= 0) {
                rect.set(0, 0, w * Math.min(100, percent) / 100f, h);
                canvas.drawRoundRect(rect, r, r, paint);
            } else {
                float seg = w * 0.3f;
                float t = (SystemClock.uptimeMillis() % 1400) / 1400f;
                float x = -seg + t * (w + seg);
                rect.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
                if (rect.right > rect.left) canvas.drawRoundRect(rect, r, r, paint);
                postInvalidateOnAnimation();
            }
        }
    }

    // ---- волна уровня сигнала -----------------------------------------------------------------

    /** История уровня микрофона в виде столбиков: новые справа. */
    static final class Waveform extends View {
        private final float[] bars = new float[56];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int head = 0;
        private int color = Palette.MUTED;

        Waveform(Context c) {
            super(c);
        }

        void setActiveColor(int value) {
            color = value;
            invalidate();
        }

        void push(double db) {
            double clamped = Math.max(-60.0, Math.min(0.0, db));
            bars[head] = (float) Math.pow((clamped + 60.0) / 60.0, 1.6);
            head = (head + 1) % bars.length;
            invalidate();
        }

        void clear() {
            java.util.Arrays.fill(bars, 0f);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            int n = bars.length;
            float slot = w / n;
            float barWidth = Math.max(2f, slot * 0.58f);
            float minHeight = barWidth;
            paint.setColor(color);
            for (int i = 0; i < n; i++) {
                float value = bars[(head + i) % n];
                float barHeight = Math.max(minHeight, value * h);
                float cx = slot * i + slot / 2f;
                rect.set(cx - barWidth / 2f, (h - barHeight) / 2f, cx + barWidth / 2f, (h + barHeight) / 2f);
                paint.setAlpha(value <= 0.001f ? 70 : 90 + Math.round(165f * i / n));
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
            }
        }
    }
}
