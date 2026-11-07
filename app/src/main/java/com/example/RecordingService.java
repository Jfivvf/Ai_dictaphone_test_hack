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

    interface Listener {
        /** Уровень сигнала в dBFS от -60 до 0. */
        void onLevel(double db);
        /** Состояние записи изменилось, либо запись завершилась ошибкой (error != null). */
        void onState(int state, String error);
    }

    private static volatile int state = STATE_IDLE;
    private static volatile long accumulatedMs = 0;
    private static volatile long resumedAt = 0;
    private static volatile Listener listener;

    static int state() { return state; }

    static void setListener(Listener value) { listener = value; }

    /** Сколько секунд записано без учёта пауз. */
    static long elapsedMs() {
        if (state == STATE_RECORDING) return accumulatedMs + (SystemClock.elapsedRealtime() - resumedAt);
        if (state == STATE_PAUSED) return accumulatedMs;
        return 0;
    }

    private static final String CHANNEL_ID = "audio_recording";
    private static final int NOTIFICATION_ID = 41;

    private MediaRecorder recorder;
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
        } else if (recorder == null) {
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
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(settings.mediaRecorderSource());
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(settings.bitrate());
            recorder.setAudioSamplingRate(44100);
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
        if (state != STATE_IDLE) state = STATE_IDLE;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
