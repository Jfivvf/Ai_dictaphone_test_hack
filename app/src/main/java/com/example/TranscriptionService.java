package com.example;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фоновая обработка записей: загрузка моделей, декодирование, диаризация и расшифровка.
 * Работает как служба переднего плана, поэтому не прерывается при сворачивании приложения и блокировке экрана.
 */
public class TranscriptionService extends Service {
    static final String EXTRA_ID = "recording_id";
    static final String EXTRA_OPEN = "open_recording";
    static final String ACTION_MODELS = "com.example.action.DOWNLOAD_MODELS";

    private static final String CHANNEL_PROGRESS = "transcription";
    private static final String CHANNEL_DONE = "transcription_done";
    private static final int PROGRESS_NOTIFICATION = 42;

    private static final AtomicInteger PENDING = new AtomicInteger(0);
    private static volatile long activeId = -1;
    private static volatile String activeLabel = "";
    private static volatile int activePercent = -1;
    private static volatile boolean modelsBusy = false;
    private static volatile String modelsLabel = "";
    private static volatile int modelsPercent = -1;
    private static volatile String modelsError = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private ModelRepository models;
    private RecordingDb db;
    private Settings settings;

    /** Есть ли сейчас задачи в очереди или в работе. */
    static boolean isActive() {
        return PENDING.get() > 0;
    }

    /** Строка прогресса для записи, которая обрабатывается сейчас; иначе пустая. */
    static String progressLabel(long recordingId) {
        return recordingId == activeId ? activeLabel : "";
    }

    /** Доля выполнения 0..100 для записи в работе или -1, если неизвестна. */
    static int progressPercent(long recordingId) {
        return recordingId == activeId ? activePercent : -1;
    }

    static boolean isDownloadingModels() { return modelsBusy; }

    static String modelsLabel() { return modelsLabel; }

    static int modelsPercent() { return modelsPercent; }

    /** Текст последней ошибки загрузки моделей; пустая строка, если ошибок не было. */
    static String modelsError() { return modelsError; }

    /** Скачивает модели в фоне, без привязки к конкретной записи. */
    static void downloadModels(Context context) {
        Intent intent = new Intent(context, TranscriptionService.class).setAction(ACTION_MODELS);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Ставит запись в очередь. Бросает исключение, если система не разрешает запуск из фона. */
    static void enqueue(Context context, long recordingId) {
        Intent intent = new Intent(context, TranscriptionService.class).putExtra(EXTRA_ID, recordingId);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        db = RecordingDb.get(this);
        models = new ModelRepository(this);
        settings = new Settings(this);
        worker = Executors.newSingleThreadExecutor();
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean modelsAction = intent != null && ACTION_MODELS.equals(intent.getAction());
        ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION,
                modelsAction ? progressNotification("Загрузка моделей", "Подготовка", -1)
                        : progressNotification("Расшифровка записи", "Подготовка к расшифровке", -1),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (modelsAction) {
            if (!modelsBusy) startModelsDownload();
            else main.post(this::finishIfIdle);
            return START_NOT_STICKY;
        }
        final long id = intent == null ? -1 : intent.getLongExtra(EXTRA_ID, -1);
        if (id < 0) {
            finishIfIdle();
            return START_NOT_STICKY;
        }
        PENDING.incrementAndGet();
        db.setStatus(id, Status.QUEUED, "");
        worker.execute(() -> {
            try {
                process(id);
            } finally {
                activeId = -1;
                PENDING.decrementAndGet();
                main.post(this::finishIfIdle);
            }
        });
        return START_NOT_STICKY;
    }

    private void startModelsDownload() {
        modelsBusy = true;
        modelsError = "";
        modelsLabel = "Подготовка";
        modelsPercent = -1;
        PENDING.incrementAndGet();
        db.notifyChanged(-1);
        worker.execute(() -> {
            try {
                models.ensure(this::reportModels);
            } catch (Throwable e) {
                modelsError = e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                modelsBusy = false;
                PENDING.decrementAndGet();
                db.notifyChanged(-1);
                main.post(this::finishIfIdle);
            }
        });
    }

    private void reportModels(String label, int percent) {
        modelsLabel = label;
        modelsPercent = percent;
        db.notifyChanged(-1);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(PROGRESS_NOTIFICATION, progressNotification("Загрузка моделей", label, percent));
        }
    }

    private void finishIfIdle() {
        if (PENDING.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void process(long id) {
        try {
            Recording recording = db.get(id);
            if (recording == null) return;
            File audio = new File(recording.audioPath);
            if (!audio.isFile()) throw new IOException("Аудиофайл не найден");

            activeId = id;
            report(id, "Подготовка", -1);

            if (!models.installed()) db.setStatus(id, Status.MODELS, "");
            ModelRepository.Models files = models.ensure((label, percent) -> report(id, label, percent));

            db.setStatus(id, Status.DECODING, "");
            report(id, "Подготовка аудио", -1);
            AudioDecoder.AudioData data = AudioDecoder.decode(this, recording.audioPath);
            long durationMs = data.samples.length * 1000L / data.sampleRate;

            db.setStatus(id, Status.RECOGNIZING, "");
            report(id, "Расшифровка", -1);
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
            LocalTranscriptionEngine.Result result = LocalTranscriptionEngine.process(
                    data, files.encoder, files.decoder, files.tokens, files.segmentation, files.embedding,
                    settings.language(), threads, (stage, done, total) -> {
                        if (total > 0) report(id, stage + " · " + done + " из " + total, done * 100 / total);
                        else report(id, stage, -1);
                    });

            List<Segment> segments = result.segments;
            boolean withSpeakers = result.diarizationError == null;
            db.saveResult(id, segments, withSpeakers ? Status.DONE : Status.DONE_NO_SPEAKERS,
                    withSpeakers ? "" : "Говорящие не определены: " + result.diarizationError, durationMs);
            db.replaceTasks(id, NoteBuilder.tasks(segments));
            notifyDone(recording.title, id);
        } catch (Throwable e) {
            String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
            db.setStatus(id, Status.ERROR, message);
        }
    }

    private void report(long id, String label, int percent) {
        activeLabel = label;
        activePercent = percent;
        db.notifyChanged(id);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(PROGRESS_NOTIFICATION, progressNotification("Расшифровка записи", label, percent));
        }
    }

    private Notification progressNotification(String title, String text, int percent) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_PROGRESS) : new Notification.Builder(this);
        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(-1));
        if (percent >= 0) builder.setProgress(100, percent, false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void notifyDone(String title, long id) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_DONE) : new Notification.Builder(this);
        builder.setContentTitle("Расшифровка готова")
                .setContentText(title)
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(openApp(id));
        manager.notify((int) (1000 + id % 100000), builder.build());
    }

    private PendingIntent openApp(long recordingId) {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (recordingId >= 0) intent.putExtra(EXTRA_OPEN, recordingId);
        return PendingIntent.getActivity(this, (int) (recordingId + 1), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_PROGRESS, "Ход расшифровки",
                NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Расшифровка готова",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public void onDestroy() {
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
