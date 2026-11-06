package com.example;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingService extends Service {
    public static final String ACTION_START = "com.example.action.START_RECORDING";
    public static final String ACTION_STOP = "com.example.action.STOP_RECORDING";
    public static final String ACTION_FINISHED = "com.example.action.RECORDING_FINISHED";
    public static final String ACTION_FAILED = "com.example.action.RECORDING_FAILED";
    public static final String EXTRA_PATH = "audio_path";

    private static final String CHANNEL_ID = "audio_recording";
    private MediaRecorder recorder;
    private String outputPath;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (recorder == null) startRecording();
        return START_NOT_STICKY;
    }

    private void startRecording() {
        try {
            File dir = new File(getFilesDir(), "recordings");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать каталог записей");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File output = new File(dir, "recording_" + stamp + ".m4a");
            outputPath = output.getAbsolutePath();

            if (Build.VERSION.SDK_INT >= 31) recorder = new MediaRecorder(this);
            else recorder = new MediaRecorder();

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(outputPath);
            recorder.prepare();
            startForeground(41, buildNotification());
            recorder.start();
        } catch (Exception e) {
            releaseRecorder();
            sendBroadcast(new Intent(ACTION_FAILED).putExtra("error",
                    e.getMessage() == null ? "Ошибка записи" : e.getMessage()));
            stopSelf();
        }
    }

    private void stopRecording() {
        if (recorder == null) {
            stopSelf();
            return;
        }
        boolean saved = false;
        try {
            recorder.stop();
            saved = true;
        } catch (RuntimeException ignored) {
            // MediaRecorder can throw when stopped before a valid audio frame was written.
        } finally {
            releaseRecorder();
        }

        if (saved && outputPath != null) {
            sendBroadcast(new Intent(ACTION_FINISHED).putExtra(EXTRA_PATH, outputPath));
        } else {
            if (outputPath != null) new File(outputPath).delete();
            sendBroadcast(new Intent(ACTION_FAILED).putExtra("error", "Запись слишком короткая или повреждена"));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Идёт запись")
                .setContentText("Аудио сохраняется локально на устройстве")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
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
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}