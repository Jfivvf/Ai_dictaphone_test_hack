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
import android.widget.Toast;

import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фоновая обработка записей: загрузка моделей, декодирование, шумоподавление, диаризация и расшифровка.
 * Работает как служба переднего плана, поэтому не прерывается при сворачивании приложения и блокировке экрана.
 */
public class TranscriptionService extends Service {
    static final String EXTRA_ID = "recording_id";
    static final String EXTRA_OPEN = "open_recording";
    static final String ACTION_MODELS = "com.example.action.DOWNLOAD_MODELS";
    /** Расшифровка уже есть (получена во время записи): нужно только определить говорящих. */
    static final String EXTRA_SPEAKERS_ONLY = "speakers_only";
    /** Номера реплик (idx), которые нужно расшифровать заново; остальная запись не меняется. */
    static final String EXTRA_FRAGMENTS = "fragment_idx";

    private static final String CHANNEL_PROGRESS = "transcription";
    private static final String CHANNEL_DONE = "transcription_done";
    private static final int PROGRESS_NOTIFICATION = 42;

    private static final AtomicInteger PENDING = new AtomicInteger(0);
    private static volatile long activeId = -1;
    private static volatile ProgressTracker activeTracker = null;
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

    /** Строка прогресса для записи, которая обрабатывается сейчас: этап, «N из M», процент, время до конца. */
    static String progressLabel(long recordingId) {
        ProgressTracker tracker = activeTracker;
        return recordingId == activeId && tracker != null ? tracker.label() : "";
    }

    /** Общая доля выполнения 0..100 для записи в работе или -1, если неизвестна. */
    static int progressPercent(long recordingId) {
        ProgressTracker tracker = activeTracker;
        return recordingId == activeId && tracker != null ? tracker.percent() : -1;
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

    /** Ставит в очередь определение говорящих для записи, текст которой получен во время записи. */
    static void enqueueSpeakers(Context context, long recordingId) {
        Intent intent = new Intent(context, TranscriptionService.class)
                .putExtra(EXTRA_ID, recordingId).putExtra(EXTRA_SPEAKERS_ONLY, true);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Ставит в очередь повторную расшифровку выбранных реплик. Время и говорящие сохраняются. */
    static void enqueueFragments(Context context, long recordingId, int[] indexes) {
        Intent intent = new Intent(context, TranscriptionService.class)
                .putExtra(EXTRA_ID, recordingId).putExtra(EXTRA_FRAGMENTS, indexes);
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
        final boolean speakersOnly = intent.getBooleanExtra(EXTRA_SPEAKERS_ONLY, false);
        final int[] fragments = intent.getIntArrayExtra(EXTRA_FRAGMENTS);
        // Для фрагментов запоминаем прежний статус: после работы запись должна вернуться к нему.
        Recording before = fragments != null ? db.get(id) : null;
        final String previousStatus = before == null ? Status.DONE : before.status;
        final String previousError = before == null ? "" : before.error;
        PENDING.incrementAndGet();
        db.setStatus(id, Status.QUEUED, "");
        worker.execute(() -> {
            try {
                if (fragments != null) processFragments(id, fragments, previousStatus, previousError);
                else if (speakersOnly) processSpeakers(id);
                else process(id);
            } finally {
                activeId = -1;
                activeTracker = null;
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

            final boolean denoise = settings.denoise();
            final ProgressTracker tracker = new ProgressTracker(denoise);
            activeId = id;
            activeTracker = tracker;
            tracker.update(ProgressTracker.Stage.DECODE, -1f, "");
            report(id);

            if (!models.installed()) db.setStatus(id, Status.MODELS, "");
            ModelRepository.Models files = models.ensure((label, percent) -> {
                tracker.setNote(label, percent);
                report(id);
            });
            File denoiseModel = denoise ? models.ensureDenoiser((label, percent) -> {
                tracker.setNote(label, percent);
                report(id);
            }) : null;
            tracker.setNote(null, -1);

            db.setStatus(id, Status.DECODING, "");
            tracker.update(ProgressTracker.Stage.DECODE, -1f, "");
            report(id);
            File pcmFile = new File(getCacheDir(), "pcm_" + id + ".raw");
            File denoisedFile = new File(getCacheDir(), "pcm_" + id + "_dn.raw");
            long durationMs;
            LocalTranscriptionEngine.Result result;
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
            try {
                AudioDecoder.Pcm pcm = AudioDecoder.decodeToPcm(recording.audioPath, pcmFile);
                durationMs = pcm.durationMs();

                if (denoise) {
                    tracker.update(ProgressTracker.Stage.DENOISE, 0f, "");
                    report(id);
                    pcm = Denoiser.apply(pcm, denoiseModel, denoisedFile, threads, fraction -> {
                        tracker.update(ProgressTracker.Stage.DENOISE, fraction, "");
                        report(id);
                    });
                }

                db.setStatus(id, Status.RECOGNIZING, "");
                result = LocalTranscriptionEngine.process(
                        pcm, files.encoder, files.decoder, files.tokens, files.segmentation, files.embedding,
                        settings.language(), threads, settings.speakerCount(), (stage, fraction, detail) -> {
                            tracker.update(stage, fraction, detail);
                            report(id);
                        });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                pcmFile.delete();
                //noinspection ResultOfMethodCallIgnored
                denoisedFile.delete();
            }

            List<Segment> segments = result.segments;
            boolean withSpeakers = result.diarizationError == null;
            db.saveResult(id, segments, withSpeakers ? Status.DONE : Status.DONE_NO_SPEAKERS,
                    withSpeakers ? "" : "Говорящие не определены: " + result.diarizationError, durationMs);
            db.replaceTasks(id, NoteBuilder.tasks(segments));
            notifyDone(recording.title, id);
        } catch (Throwable e) {
            String message = e instanceof OutOfMemoryError
                    ? "Не хватило памяти. Запись слишком длинная для этого устройства: закройте другие приложения или разделите запись на части"
                    : e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
            db.setStatus(id, Status.ERROR, message);
        }
    }

    /**
     * Повторная расшифровка выбранных реплик. Меняется только текст: время и говорящие остаются.
     * Любой исход возвращает запись в прежний статус, чтобы ошибка фрагмента не прятала готовую расшифровку.
     */
    private void processFragments(long id, int[] indexes, String previousStatus, String previousError) {
        File pcmFile = new File(getCacheDir(), "pcm_" + id + "_part.raw");
        String status = Status.isBusy(previousStatus) ? Status.DONE : previousStatus;
        try {
            Recording recording = db.get(id);
            if (recording == null) return;
            File audio = new File(recording.audioPath);
            if (!audio.isFile()) throw new IOException("Аудиофайл не найден");

            Set<Integer> wanted = new HashSet<>();
            for (int i : indexes) wanted.add(i);
            List<Segment> targets = new ArrayList<>();
            for (Segment s : db.segments(id)) if (wanted.contains(s.idx)) targets.add(s);
            if (targets.isEmpty()) return;

            final ProgressTracker tracker = ProgressTracker.fragments();
            activeId = id;
            activeTracker = tracker;
            tracker.update(ProgressTracker.Stage.DECODE, -1f, "");
            db.setStatus(id, Status.RECOGNIZING, "");
            report(id);

            ModelRepository.Models files = models.ensure((label, percent) -> {
                tracker.setNote(label, percent);
                report(id);
            });
            tracker.setNote(null, -1);

            // Записи Real-life — это WAV 16 кГц самого приложения; всё остальное декодируем.
            boolean own = audio.getName().startsWith("recording_") && audio.getName().endsWith(".wav");
            AudioDecoder.Pcm pcm = own ? AudioDecoder.fromWav(audio, pcmFile)
                    : AudioDecoder.decodeToPcm(recording.audioPath, pcmFile);
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);

            Map<Integer, String> texts = LocalTranscriptionEngine.retranscribe(pcm, targets,
                    files.encoder, files.decoder, files.tokens, settings.language(), threads,
                    (stage, fraction, detail) -> {
                        tracker.update(stage, fraction, detail);
                        report(id);
                    });

            db.updateSegmentsText(id, texts);
            db.replaceTasks(id, NoteBuilder.tasks(db.segments(id)));
            final int missed = targets.size() - texts.size();
            if (missed > 0) {
                main.post(() -> Toast.makeText(this, "Речь не распознана в репликах: " + missed
                        + ". Их текст остался прежним", Toast.LENGTH_LONG).show());
            }
        } catch (Throwable e) {
            final String reason = e instanceof OutOfMemoryError ? "не хватило памяти"
                    : e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
            main.post(() -> Toast.makeText(this, "Не удалось расшифровать реплики: " + reason,
                    Toast.LENGTH_LONG).show());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            pcmFile.delete();
            db.setStatus(id, status, previousError);
        }
    }

    /** Определение говорящих для готовой расшифровки. При неудаче расшифровка остаётся без разметки. */
    private void processSpeakers(long id) {
        File pcmFile = new File(getCacheDir(), "pcm_" + id + ".raw");
        try {
            Recording recording = db.get(id);
            if (recording == null) return;
            List<Segment> live = db.segments(id);
            if (live.isEmpty()) return;

            final ProgressTracker tracker = new ProgressTracker(false);
            activeId = id;
            activeTracker = tracker;
            tracker.update(ProgressTracker.Stage.DECODE, -1f, "");
            db.setStatus(id, Status.RECOGNIZING, "");
            File[] diarization = models.ensureDiarization((label, percent) -> {
                tracker.setNote(label, percent);
                report(id);
            });
            tracker.setNote(null, -1);

            db.setStatus(id, Status.RECOGNIZING, "");
            AudioDecoder.Pcm pcm = AudioDecoder.fromWav(new File(recording.audioPath), pcmFile);
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
            LocalTranscriptionEngine.Result result = LocalTranscriptionEngine.assignSpeakers(
                    pcm, diarization[0], diarization[1], threads, settings.speakerCount(), live,
                    (stage, fraction, detail) -> {
                        tracker.update(stage, fraction, detail);
                        report(id);
                    });
            boolean withSpeakers = result.diarizationError == null;
            db.saveResult(id, result.segments, withSpeakers ? Status.DONE : Status.DONE_NO_SPEAKERS,
                    withSpeakers ? "" : "Говорящие не определены: " + result.diarizationError, pcm.durationMs());
            db.replaceTasks(id, NoteBuilder.tasks(result.segments));
            notifyDone(recording.title, id);
        } catch (Throwable e) {
            String reason = e instanceof OutOfMemoryError ? "не хватило памяти"
                    : e.getMessage() == null || e.getMessage().trim().isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
            db.setStatus(id, Status.DONE_NO_SPEAKERS, "Говорящие не определены: " + reason);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            pcmFile.delete();
        }
    }

    /** Обновляет интерфейс и уведомление по текущему состоянию трекера. */
    private void report(long id) {
        db.notifyChanged(id);
        ProgressTracker tracker = activeTracker;
        if (tracker == null) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(PROGRESS_NOTIFICATION,
                    progressNotification("Расшифровка записи", tracker.label(), tracker.percent()));
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
