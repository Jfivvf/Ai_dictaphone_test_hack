package com.example;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.Locale;

/** Экран записи: уровень сигнала, таймер, управление и выбор режима записи. */
final class RecordScreen extends BaseScreen implements RecordingService.Listener {
    private static final String[] MODE_HINTS = {
            "Звук без обработки телефона, максимум деталей. Лучше всего в тихой комнате",
            "Компактные файлы и быстрая обработка. Для чёткой речи рядом с микрофоном",
            "Для шумных мест: подавление шума при записи и перед расшифровкой"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScrollView scroll;

    private View dot;
    private TextView stateLabel;
    private TextView dbLabel;
    private TextView timer;
    private TextView errorText;
    private TextView recordLabel;
    private TextView pauseLabel;
    private Ui.Waveform wave;
    private FrameLayout recordButton;
    private FrameLayout pauseButton;
    private Ui.Segmented modeChoice;
    private TextView modeHint;
    private TextView sizeHint;
    private int lastState = RecordingService.STATE_IDLE;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (RecordingService.state() == RecordingService.STATE_IDLE) return;
            timer.setText(Fmt.clock(RecordingService.elapsedMs()));
            ui.postDelayed(this, 200);
        }
    };

    RecordScreen(MainActivity host) {
        super(host);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(build(), new FrameLayout.LayoutParams(-1, -2));
    }

    @Override View view() {
        return scroll;
    }

    private View build() {
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(24));

        page.addView(Ui.bold(ctx, "Запись", 28, Palette.TEXT));
        page.addView(Ui.space(ctx, 18));

        // Карточка записи
        LinearLayout card = Ui.card(ctx);
        card.setPadding(dp(18), dp(18), dp(18), dp(20));

        LinearLayout status = new LinearLayout(ctx);
        status.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(ctx);
        dot.setBackground(Ui.oval(Palette.MUTED));
        status.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        stateLabel = Ui.text(ctx, "Микрофон готов", 14, Palette.TEXT);
        status.addView(stateLabel, Ui.margins(Ui.lp(0, -2, 1f), ctx, 10, 0, 0, 0));
        dbLabel = Ui.mono(ctx, "— dB", 13, Palette.MUTED);
        status.addView(dbLabel);
        card.addView(status);

        timer = Ui.mono(ctx, "00:00", 56, Palette.TEXT);
        timer.setGravity(Gravity.CENTER);
        card.addView(timer, Ui.margins(Ui.lp(-1, -2), ctx, 0, 20, 0, 12));

        wave = new Ui.Waveform(ctx);
        card.addView(wave, new LinearLayout.LayoutParams(-1, dp(64)));

        LinearLayout controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        pauseButton = Ui.iconButton(ctx, R.drawable.ic_pause, Palette.TEXT, Palette.SURFACE_2, 56, 26,
                "Пауза", v -> togglePause());
        recordButton = Ui.iconButton(ctx, R.drawable.ic_mic, 0xFFFFFFFF, Palette.RECORD, 84, 38,
                "Запись", v -> toggleRecording());
        FrameLayout importButton = Ui.iconButton(ctx, R.drawable.ic_import, Palette.TEXT, Palette.SURFACE_2, 56, 26,
                "Импорт аудио", v -> host.pickAudio());

        pauseLabel = Ui.text(ctx, "Пауза", 12, Palette.MUTED);
        recordLabel = Ui.text(ctx, "Начать запись", 12, Palette.TEXT);
        controls.addView(column(pauseButton, pauseLabel), Ui.lp(0, -2, 1f));
        controls.addView(column(recordButton, recordLabel), Ui.lp(0, -2, 1.3f));
        controls.addView(column(importButton, Ui.text(ctx, "Импорт", 12, Palette.MUTED)), Ui.lp(0, -2, 1f));
        card.addView(controls, Ui.margins(Ui.lp(-1, -2), ctx, 0, 20, 0, 0));

        errorText = Ui.text(ctx, "", 13, Palette.RED);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        card.addView(errorText, Ui.margins(Ui.lp(-1, -2), ctx, 0, 14, 0, 0));
        page.addView(card);

        // Настройка звука
        page.addView(Ui.space(ctx, 16));
        LinearLayout preset = Ui.card(ctx);
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.icon(ctx, R.drawable.ic_tune, Palette.ACCENT_SOFT, 20));
        head.addView(Ui.bold(ctx, "Настройка звука", 16, Palette.TEXT),
                Ui.margins(Ui.lp(-2, -2), ctx, 10, 0, 0, 0));
        preset.addView(head);

        preset.addView(Ui.text(ctx, "Режим", 12, Palette.MUTED), Ui.margins(Ui.lp(-2, -2), ctx, 0, 16, 0, 8));
        modeChoice = new Ui.Segmented(ctx, new String[]{"Точный", "Быстрый", "Real-life"},
                settings.recordMode(), index -> {
            settings.setRecordMode(index);
            modeHint.setText(MODE_HINTS[index]);
            sizeHint.setText(sizeText(settings.bitrate()));
        });
        preset.addView(modeChoice);
        modeHint = Ui.text(ctx, MODE_HINTS[settings.recordMode()], 12, Palette.MUTED);
        modeHint.setLineSpacing(dp(1), 1f);
        preset.addView(modeHint, Ui.margins(Ui.lp(-1, -2), ctx, 2, 8, 0, 0));
        sizeHint = Ui.text(ctx, sizeText(settings.bitrate()), 12, Palette.MUTED);
        preset.addView(sizeHint, Ui.margins(Ui.lp(-2, -2), ctx, 2, 4, 0, 0));

        LinearLayout autoRow = new LinearLayout(ctx);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout autoText = new LinearLayout(ctx);
        autoText.setOrientation(LinearLayout.VERTICAL);
        autoText.addView(Ui.text(ctx, "Расшифровать после записи", 15, Palette.TEXT));
        autoText.addView(Ui.text(ctx, "Запустится в фоне сразу после остановки", 12, Palette.MUTED),
                Ui.margins(Ui.lp(-2, -2), ctx, 0, 3, 0, 0));
        autoRow.addView(autoText, Ui.lp(0, -2, 1f));
        Switch autoSwitch = Ui.toggle(ctx, settings.autoTranscribe(), (b, on) -> settings.setAutoTranscribe(on));
        autoRow.addView(autoSwitch);
        preset.addView(autoRow, Ui.margins(Ui.lp(-1, -2), ctx, 0, 18, 0, 0));
        page.addView(preset);
        return page;
    }

    private LinearLayout column(View top, TextView label) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(top);
        col.addView(label, Ui.margins(Ui.lp(-2, -2), ctx, 0, 8, 0, 0));
        return col;
    }

    private static String sizeText(int bitrate) {
        double mbPerHour = bitrate * 3600.0 / 8.0 / 1_000_000.0;
        return String.format(Locale.ROOT, "≈ %.0f МБ на час записи", mbPerHour);
    }

    // ---- действия -----------------------------------------------------------------------------

    private void toggleRecording() {
        if (RecordingService.state() == RecordingService.STATE_IDLE) {
            host.withRecordPermission(() -> {
                errorText.setVisibility(View.GONE);
                ContextCompat.startForegroundService(ctx,
                        new Intent(ctx, RecordingService.class).setAction(RecordingService.ACTION_START));
            });
        } else {
            ctx.startService(new Intent(ctx, RecordingService.class).setAction(RecordingService.ACTION_STOP));
        }
    }

    private void togglePause() {
        int state = RecordingService.state();
        if (state == RecordingService.STATE_RECORDING) {
            ctx.startService(new Intent(ctx, RecordingService.class).setAction(RecordingService.ACTION_PAUSE));
        } else if (state == RecordingService.STATE_PAUSED) {
            ctx.startService(new Intent(ctx, RecordingService.class).setAction(RecordingService.ACTION_RESUME));
        }
    }

    // ---- отображение --------------------------------------------------------------------------

    /** Приводит экран в соответствие с реальным состоянием службы записи. */
    @Override void refresh() {
        render();
    }

    @Override void onShow() {
        super.onShow();
        render();
    }

    private void render() {
        int state = RecordingService.state();
        boolean idle = state == RecordingService.STATE_IDLE;
        boolean paused = state == RecordingService.STATE_PAUSED;

        int color = idle ? Palette.MUTED : paused ? Palette.ORANGE : Palette.RECORD;
        dot.setBackground(Ui.oval(color));
        stateLabel.setText(idle ? "Микрофон готов" : paused ? "Пауза" : "Идёт запись");
        wave.setActiveColor(paused ? Palette.ORANGE : idle ? Palette.MUTED : Palette.RECORD);

        Ui.setButtonIcon(recordButton, idle ? R.drawable.ic_mic : R.drawable.ic_stop);
        recordLabel.setText(idle ? "Начать запись" : "Остановить и сохранить");
        Ui.setButtonIcon(pauseButton, paused ? R.drawable.ic_play : R.drawable.ic_pause);
        pauseLabel.setText(paused ? "Продолжить" : "Пауза");
        pauseButton.setEnabled(!idle);
        pauseButton.setAlpha(idle ? 0.35f : 1f);

        modeChoice.setEnabled(idle);

        ui.removeCallbacks(tick);
        if (idle) {
            timer.setText("00:00");
            dbLabel.setText("— dB");
            wave.clear();
        } else {
            timer.setText(Fmt.clock(RecordingService.elapsedMs()));
            ui.post(tick);
        }
    }

    // ---- события службы записи ----------------------------------------------------------------

    @Override public void onLevel(double db) {
        wave.push(db);
        dbLabel.setText(String.format(Locale.ROOT, "%.0f dB", Math.max(-60.0, Math.min(0.0, db))));
    }

    @Override public void onState(int state, String error) {
        boolean finished = lastState != RecordingService.STATE_IDLE && state == RecordingService.STATE_IDLE;
        lastState = state;
        render();
        if (error != null && !error.isEmpty()) {
            errorText.setText(error);
            errorText.setVisibility(View.VISIBLE);
        } else {
            errorText.setVisibility(View.GONE);
            if (finished) host.openLatestRecording();
        }
    }

    /** Подтягивает состояние после возвращения в приложение, пока слушатель был отключён. */
    void syncState() {
        lastState = RecordingService.state();
        render();
    }

    @Override void release() {
        ui.removeCallbacks(tick);
        super.release();
    }
}
