package com.example;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.ServiceCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Запись с микрофона в фоне. Сама сохраняет запись в базу, поэтому результат не теряется,
 * даже если экран приложения закрыт во время записи.
 */
public class RecordingService extends Service {
    public static final String ACTION_START = "com.example.action.START_RECORDING";
    public static final String ACTION_STOP = "com.example.action.STOP_RECORDING";
    public static final String ACTION_PAUSE = "com.example.action.PAUSE_RECORDING";
    public static final String ACTION_RESUME = "com.example.action.RESUME_RECORDING";

    static final int STATE_IDLE = 0;
    static final int STATE_RECORDING = 1;
    static final int STATE_PAUSED = 2;
    /** Идёт загрузка модели перед записью или завершение расшифровки после остановки; подпись — {@link #busyLabel()}. */
    static final int STATE_BUSY = 3;

    interface Listener {
        /** Уровень сигнала в dBFS от -60 до 0. */
        void onLevel(double db);
        /** Состояние записи изменилось, либо запись завершилась ошибкой (error != null). */
        void onState(int state, String error);
        /** Расшифровка во время записи: законченные фразы и то, что говорится сейчас. */
        default void onLive(String committed, String partial) { }
    }

    private static volatile int state = STATE_IDLE;
    private static volatile long accumulatedMs = 0;
    private static volatile long resumedAt = 0;
    private static volatile Listener listener;

    static int state() { return state; }

    static void setListener(Listener value) { listener = value; }

    private static volatile String busyLabel = "";
    private static volatile String liveCommitted = "";
    private static volatile String livePartial = "";

    static String busyLabel() { return busyLabel; }
    static String liveCommitted() { return liveCommitted; }
    static String livePartial() { return livePartial; }

    /** Сколько секунд записано без учёта пауз. */
    static long elapsedMs() {
        if (state == STATE_RECORDING) return accumulatedMs + (SystemClock.elapsedRealtime() - resumedAt);
        if (state == STATE_PAUSED) return accumulatedMs;
        return 0;
    }

    private static final String CHANNEL_ID = "audio_recording";
    private static final int NOTIFICATION_ID = 41;

    private MediaRecorder recorder;
    /** Запись с расшифровкой в реальном времени (режим Real-life); null в остальных режимах. */
    private volatile RealtimeRecorder live;
    private String outputPath;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable meterTask = new Runnable() {
        @Override public void run() {
            if (recorder == null || state != STATE_RECORDING) return;
            try {
                int peak = recorder.getMaxAmplitude();
                double db = peak <= 0 ? -60.0 : Math.max(-60.0, 20.0 * Math.log10(peak / 32767.0));
                Listener l = listener;
                if (l != null) l.onLevel(db);
            } catch (RuntimeException ignored) { }
            main.postDelayed(this, 80);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
        } else if (ACTION_PAUSE.equals(action)) {
            pauseRecording();
        } else if (ACTION_RESUME.equals(action)) {
            resumeRecording();
        } else if (recorder == null && live == null && state == STATE_IDLE) {
            startRecording();
        }
        return START_NOT_STICKY;
    }

    private void startRecording() {
        // Сначала уведомление: система требует вызвать startForeground в течение нескольких секунд.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        try {
            File dir = new File(getFilesDir(), "recordings");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать каталог записей");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            outputPath = new File(dir, "recording_" + stamp + ".m4a").getAbsolutePath();

            Settings settings = new Settings(this);
            if (settings.recordMode() == Settings.MODE_REAL_LIFE) {
                startLive(settings, dir, stamp);
                return;
            }
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(settings.mediaRecorderSource());
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(settings.bitrate());
            recorder.setAudioSamplingRate(settings.sampleRate());
            recorder.setOutputFile(outputPath);
            recorder.prepare();
            recorder.start();

            accumulatedMs = 0;
            resumedAt = SystemClock.elapsedRealtime();
            setState(STATE_RECORDING, null);
            main.removeCallbacks(meterTask);
            main.post(meterTask);
        } catch (Exception e) {
            releaseRecorder();
            if (outputPath != null) new File(outputPath).delete();
            fail(e.getMessage() == null ? "Не удалось начать запись" : e.getMessage());
        }
    }

    private void pauseRecording() {
        if (live != null && state == STATE_RECORDING) {
            live.pause();
            accumulatedMs += SystemClock.elapsedRealtime() - resumedAt;
            setState(STATE_PAUSED, null);
            Listener l = listener;
            if (l != null) l.onLevel(-60);
            return;
        }
        if (recorder == null || state != STATE_RECORDING) return;
        try {
            recorder.pause();
            accumulatedMs += SystemClock.elapsedRealtime() - resumedAt;
            main.removeCallbacks(meterTask);
            setState(STATE_PAUSED, null);
            Listener l = listener;
            if (l != null) l.onLevel(-60);
        } catch (RuntimeException e) {
            Listener l = listener;
            if (l != null) l.onState(state, "Не удалось приостановить запись");
        }
    }

    private void resumeRecording() {
        if (live != null && state == STATE_PAUSED) {
            live.resume();
            resumedAt = SystemClock.elapsedRealtime();
            setState(STATE_RECORDING, null);
            return;
        }
        if (recorder == null || state != STATE_PAUSED) return;
        try {
            recorder.resume();
            resumedAt = SystemClock.elapsedRealtime();
            setState(STATE_RECORDING, null);
            main.removeCallbacks(meterTask);
            main.post(meterTask);
        } catch (RuntimeException e) {
            Listener l = listener;
            if (l != null) l.onState(state, "Не удалось продолжить запись");
        }
    }

    private void stopRecording() {
        main.removeCallbacks(meterTask);
        if (state == STATE_BUSY && live == null && "Завершение расшифровки".equals(busyLabel)) return;
        if (live != null) {
            finishLive(null);
            return;
        }
        if (recorder == null) {
            setState(STATE_IDLE, null);
            finish();
            return;
        }
        long elapsed = elapsedMs();
        boolean saved = false;
        try {
            recorder.stop();
            saved = true;
        } catch (RuntimeException ignored) {
            // MediaRecorder бросает исключение, если не записан ни один звуковой кадр.
        } finally {
            releaseRecorder();
        }

        String error = null;
        if (saved && outputPath != null) {
            try {
                long duration = AudioDecoder.durationMs(outputPath);
                if (duration <= 0) duration = elapsed;
                String title = "Запись " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date());
                long id = RecordingDb.get(this).add(title, outputPath, Status.RECORDED, duration);
                if (new Settings(this).autoTranscribe()) {
                    try {
                        TranscriptionService.enqueue(this, id);
                    } catch (RuntimeException notAllowed) {
                        // Система не разрешила запуск из фона: запись сохранена, расшифровку можно запустить вручную.
                    }
                }
            } catch (RuntimeException e) {
                error = "Не удалось сохранить запись в базу";
            }
        } else {
            if (outputPath != null) new File(outputPath).delete();
            error = "Запись слишком короткая или повреждена";
        }
        setState(STATE_IDLE, error);
        finish();
    }


    // ---- запись с расшифровкой в реальном времени ---------------------------------------------

    /** Загружает модель и запускает микрофон в фоновом потоке: загрузка большой модели занимает секунды. */
    private void startLive(final Settings settings, final File dir, final String stamp) {
        busyLabel = "Загрузка модели";
        liveCommitted = "";
        livePartial = "";
        setState(STATE_BUSY, null);
        new Thread(() -> {
            try {
                RealtimeModels.Files files = new RealtimeModels(this)
                        .open(RealtimeModels.languageFor(settings.language()));
                outputPath = new File(dir, "recording_" + stamp + ".wav").getAbsolutePath();
                int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
                final RealtimeRecorder started = RealtimeRecorder.start(new File(outputPath), files, threads,
                        new RealtimeRecorder.Callbacks() {
                            @Override public void onLevel(final double db) {
                                main.post(() -> {
                                    Listener l = listener;
                                    if (l != null && state == STATE_RECORDING) l.onLevel(db);
                                });
                            }

                            @Override public void onText(final String committed, final String partial) {
                                liveCommitted = committed;
                                livePartial = partial;
                                main.post(() -> {
                                    Listener l = listener;
                                    if (l != null) l.onLive(committed, partial);
                                });
                            }

                            @Override public void onFailure(final String message) {
                                main.post(() -> finishLive(message));
                            }
                        });
                main.post(() -> {
                    if (state != STATE_BUSY) {
                        // Пока грузилась модель, запись отменили.
                        new Thread(() -> {
                            try { started.stop(); } catch (Exception ignored) { }
                            new File(outputPath).delete();
                        }).start();
                        return;
                    }
                    live = started;
                    accumulatedMs = 0;
                    resumedAt = SystemClock.elapsedRealtime();
                    setState(STATE_RECORDING, null);
                });
            } catch (final Throwable e) {
                if (outputPath != null) new File(outputPath).delete();
                final String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? "Не удалось начать запись" : e.getMessage();
                main.post(() -> fail(message));
            }
        }, "realtime-start").start();
    }

    /** Останавливает запись, дожидается конца расшифровки и сохраняет результат. error — причина аварийной остановки. */
    private void finishLive(final String error) {
        final RealtimeRecorder recorder = live;
        if (recorder == null) return;
        live = null;
        final long elapsed = elapsedMs();
        busyLabel = "Завершение расшифровки";
        setState(STATE_BUSY, null);
        final String path = outputPath;
        new Thread(() -> {
            String problem = error;
            List<Segment> segments = null;
            try {
                segments = recorder.stop();
            } catch (Exception e) {
                if (problem == null) problem = e.getMessage() == null ? "Не удалось сохранить запись" : e.getMessage();
            }
            try {
                File file = path == null ? null : new File(path);
                if (file == null || file.length() <= 44 + 3200) {
                    if (file != null) file.delete();
                    if (problem == null) problem = "Запись слишком короткая или повреждена";
                } else {
                    long duration = (file.length() - 44) / 2 * 1000L / RealtimeTranscriber.RATE;
                    if (duration <= 0) duration = elapsed;
                    String title = "Запись " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date());
                    RecordingDb db = RecordingDb.get(this);
                    long id = db.add(title, path, Status.RECORDED, duration);
                    if (segments != null && !segments.isEmpty()) {
                        db.saveResult(id, segments, Status.DONE_NO_SPEAKERS, "", duration);
                        db.replaceTasks(id, NoteBuilder.tasks(segments));
                        try {
                            TranscriptionService.enqueueSpeakers(this, id);
                        } catch (RuntimeException notAllowed) {
                            // Расшифровка сохранена; говорящих можно определить позже.
                        }
                    } else if (problem == null) {
                        problem = "Речь в записи не распознана";
                    }
                }
            } catch (RuntimeException e) {
                problem = "Не удалось сохранить запись в базу";
            }
            final String shown = problem;
            main.post(() -> {
                liveCommitted = "";
                livePartial = "";
                setState(STATE_IDLE, shown);
                finish();
            });
        }, "realtime-finish").start();
    }

    private void fail(String message) {
        setState(STATE_IDLE, message);
        finish();
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void setState(int value, String error) {
        state = value;
        Listener l = listener;
        if (l != null) l.onState(value, error);
    }

    private void releaseRecorder() {
        main.removeCallbacks(meterTask);
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecordingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentTitle("Идёт запись")
                .setContentText("Аудио сохраняется на устройстве")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build());
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Запись аудио", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        releaseRecorder();
        if (state != STATE_IDLE && live == null && state != STATE_BUSY) state = STATE_IDLE;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
