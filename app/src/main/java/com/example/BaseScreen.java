package com.example;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Основа экрана. Обновление данных откладывается и склеивается: во время расшифровки база сообщает
 * об изменениях очень часто, а перерисовывать список на каждое из них незачем.
 * Скрытый экран только помечается устаревшим и обновится при показе.
 */
abstract class BaseScreen {
    protected final MainActivity host;
    protected final Context ctx;
    protected final RecordingDb db;
    protected final Settings settings;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean shown = false;
    private boolean dirty = true;
    private boolean scheduled = false;

    BaseScreen(MainActivity host) {
        this.host = host;
        this.ctx = host;
        this.db = RecordingDb.get(host);
        this.settings = new Settings(host);
    }

    abstract View view();

    /** Перечитать данные и перерисовать. Вызывается только когда экран виден. */
    abstract void refresh();

    void onShow() {
        shown = true;
        if (dirty) {
            handler.removeCallbacksAndMessages(null);
            scheduled = false;
            dirty = false;
            refresh();
        }
    }

    void onHide() {
        shown = false;
    }

    /** Данные записи изменились; id < 0 — изменились сразу несколько записей или глобальное состояние. */
    void onDataChanged(long recordingId) {
        if (!shown) {
            dirty = true;
            return;
        }
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(() -> {
            scheduled = false;
            dirty = false;
            refresh();
        }, 150);
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        shown = false;
    }

    protected int dp(float value) {
        return Ui.dp(ctx, value);
    }
}
