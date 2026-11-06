package com.example;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int REQUEST_AUDIO_PERMISSION = 701;
    private RecordingDb database;
    private LinearLayout root;
    private LinearLayout library;
    private LinearLayout segmentList;
    private MediaPlayer audioPlayer;
    private EditText transcriptEditor;
    private TextView selectedTitle;
    private TextView statusText;
    private RecordingDb.Recording selected;
    private boolean recording = false;
    private ModelRepository modelRepository;
    private ExecutorService transcriptionExecutor;
    private Handler ui = new Handler(Looper.getMainLooper());
    private Button transcribeButton;
    private Button recordButton;
    private Button pauseButton;
    private TextView levelText;
    private ProgressBar levelBar;
    private TextView elapsedText;
    private long recordingStartedAt;
    private long pausedAt;
    private long pausedTotalMs;
    private boolean recordingPaused = false;
    private long processingStartedAt;
    private final Runnable recordingTicker = new Runnable() {
        @Override public void run() {
            if (!recording || elapsedText == null) return;
            long end = recordingPaused ? pausedAt : System.currentTimeMillis();
            long elapsed = Math.max(0, end - recordingStartedAt - pausedTotalMs) / 1000;
            elapsedText.setText(String.format(Locale.ROOT, "%02d:%02d", elapsed / 60, elapsed % 60));
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable processingTicker = new Runnable() {
        @Override public void run() {
            if (processingStartedAt == 0) return;
            long elapsed = (System.currentTimeMillis() - processingStartedAt) / 1000;
            setStatus("Локальное распознавание и диаризация · " + elapsed + " с");
            ui.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<String[]> importAudio =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importSelectedAudio);
    private final ActivityResultLauncher<String> exportPdf =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), this::writePdf);

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (RecordingService.ACTION_FINISHED.equals(action)) {
                String path = intent.getStringExtra(RecordingService.EXTRA_PATH);
                if (path != null) {
                    String title = "Запись " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date());
                    database.add(title, path, "Записано");
                    setStatus("Запись сохранена");
                    refreshLibrary();
                }
                recording = false;
                recordingPaused = false;
                pausedTotalMs = 0;
                ui.removeCallbacks(recordingTicker);
                updateRecordButton();
                if (pauseButton != null) { pauseButton.setEnabled(false); pauseButton.setText("Пауза"); }
                if (elapsedText != null) elapsedText.setText("00:00");
            } else if (RecordingService.ACTION_LEVEL.equals(action)) {
                updateLevel(intent.getDoubleExtra(RecordingService.EXTRA_DB, -60.0));
            } else if (RecordingService.ACTION_FAILED.equals(action)) {
                recording = false;
                recordingPaused = false;
                ui.removeCallbacks(recordingTicker);
                updateRecordButton();
                if (pauseButton != null) { pauseButton.setEnabled(false); pauseButton.setText("Пауза"); }
                setStatus(intent.getStringExtra("error"));
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        database = new RecordingDb(this);
        modelRepository = new ModelRepository(this);
        transcriptionExecutor = Executors.newSingleThreadExecutor();
        buildInterface();
        refreshLibrary();

        IntentFilter filter = new IntentFilter();
        filter.addAction(RecordingService.ACTION_FINISHED);
        filter.addAction(RecordingService.ACTION_FAILED);
        filter.addAction(RecordingService.ACTION_LEVEL);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(recordingReceiver, filter);
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(0xFF0B0D12);
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = new TextView(this);
        heading.setText("AudioVault");
        heading.setTextSize(28);
        heading.setTextColor(0xFFF5F5FA);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);

        statusText = new TextView(this);
        statusText.setText("Готово");
        statusText.setTextSize(13);
        statusText.setTextColor(0xFFB6BBC9);
        statusText.setPadding(0, dp(6), 0, dp(16));
        root.addView(statusText);

        TextView levelLabel = new TextView(this);
        levelLabel.setText("УРОВЕНЬ МИКРОФОНА");
        levelLabel.setTextColor(0xFF9B8CFF);
        levelLabel.setTextSize(11);
        root.addView(levelLabel);
        levelText = new TextView(this);
        levelText.setText("−60 dBFS");
        levelText.setTextColor(0xFFE4E1FF);
        levelText.setTextSize(14);
        root.addView(levelText);
        levelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelBar.setMax(60);
        levelBar.setProgress(0);
        root.addView(levelBar, new LinearLayout.LayoutParams(-1, dp(12)));
        elapsedText = new TextView(this);
        elapsedText.setText("00:00");
        elapsedText.setTextColor(0xFFFFFFFF);
        elapsedText.setTextSize(24);
        elapsedText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(elapsedText);

        recordButton = button("Начать запись");
        recordButton.setOnClickListener(v -> toggleRecording());
        recordButton.setBackgroundColor(0xFFE32662);
        recordButton.setTextColor(0xFFFFFFFF);
        root.addView(recordButton);
        pauseButton = button("Пауза");
        pauseButton.setEnabled(false);
        pauseButton.setOnClickListener(v -> togglePauseRecording());
        root.addView(pauseButton);
        Button importButton = button("Импортировать аудио");
        importButton.setOnClickListener(v -> importAudio.launch(new String[]{"audio/*"}));
        root.addView(importButton);

        TextView libraryTitle = sectionTitle("Записи");
        root.addView(libraryTitle);
        library = new LinearLayout(this);
        library.setOrientation(LinearLayout.VERTICAL);
        root.addView(library);

        selectedTitle = sectionTitle("Расшифровка");
        root.addView(selectedTitle);
        transcriptEditor = new EditText(this);
        transcriptEditor.setMinLines(8);
        transcriptEditor.setTextColor(0xFFE9EAF1);
        transcriptEditor.setHintTextColor(0xFF777E8D);
        transcriptEditor.setBackgroundColor(0xFF171B24);
        transcriptEditor.setGravity(Gravity.TOP | Gravity.START);
        transcriptEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        transcriptEditor.setHint("Текст расшифровки");
        root.addView(transcriptEditor, new LinearLayout.LayoutParams(-1, dp(220)));
        segmentList = new LinearLayout(this);
        segmentList.setOrientation(LinearLayout.VERTICAL);
        root.addView(segmentList);

        Button saveText = button("Сохранить текст");
        saveText.setOnClickListener(v -> saveTranscript());
        root.addView(saveText);
        Button pdfButton = button("Экспортировать PDF");
        pdfButton.setOnClickListener(v -> {
            if (selected == null) { setStatus("Сначала выберите запись"); return; }
            exportPdf.launch(safeFileName(selected.title) + ".pdf");
        });
        root.addView(pdfButton);
        transcribeButton = button("Расшифровать и определить говорящих");
        transcribeButton.setOnClickListener(v -> transcribeSelected());
        root.addView(transcribeButton);
    }

    private void transcribeSelected() {
        if (selected == null) {
            setStatus("Сначала выберите аудиозапись");
            return;
        }
        final RecordingDb.Recording recordingToProcess = selected;
        File audio = new File(recordingToProcess.audioPath);
        if (!audio.isFile()) {
            setStatus("Исходный аудиофайл не найден");
            return;
        }
        transcribeButton.setEnabled(false);
        database.updateStatus(recordingToProcess.id, "Подготовка моделей");
        refreshLibrary();
        setStatus("Подготовка локальных моделей…");
        modelRepository.prepare(new ModelRepository.Callback() {
            @Override public void progress(String label, int percent) {
                setStatus(percent >= 0 ? label + " · " + percent + "%" : label);
            }

            @Override public void ready(File encoder, File decoder, File tokens,
                                        File segmentation, File embedding) {
                setStatus("Декодирование аудио…");
                final String audioPath = recordingToProcess.audioPath;
                final long recordingId = recordingToProcess.id;
                transcriptionExecutor.execute(() -> {
                    try {
                        AudioDecoder.AudioData data = AudioDecoder.decode(MainActivity.this, audioPath);
                        processingStartedAt = System.currentTimeMillis();
                        ui.post(() -> {
                            setStatus("Распознавание речи и диаризация…");
                            ui.removeCallbacks(processingTicker);
                            ui.post(processingTicker);
                        });
                        LocalTranscriptionEngine.Result result = LocalTranscriptionEngine.process(
                                data, encoder, decoder, tokens, segmentation, embedding, "ru",
                                Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
                        StringBuilder formatted = new StringBuilder();
                        for (LocalTranscriptionEngine.Segment segment : result.segments) {
                            formatted.append("[")
                                    .append(LocalTranscriptionEngine.formatTime(segment.startMs))
                                    .append(" – ")
                                    .append(LocalTranscriptionEngine.formatTime(segment.endMs))
                                    .append("] Спикер ")
                                    .append(segment.speaker + 1)
                                    .append(": ")
                                    .append(segment.text)
                                    .append("\n\n");
                        }
                        String transcript = formatted.length() == 0 ? result.transcript : formatted.toString().trim();
                        ui.post(() -> {
                            ui.removeCallbacks(processingTicker);
                            long processingSeconds = (System.currentTimeMillis() - processingStartedAt) / 1000;
                            processingStartedAt = 0;
                            database.updateTranscript(recordingId, transcript);
                            database.updateStatus(recordingId, result.diarizationError == null ? "Готово" : "Расшифровка готова · нет диаризации");
                            refreshLibrary();
                            if (selected != null && selected.id == recordingId) {
                                selected = new RecordingDb.Recording(selected.id, selected.title,
                                        selected.audioPath, transcript, selected.createdAt, "Обработано");
                                transcriptEditor.setText(transcript);
                                renderTranscriptSegments(transcript);
                            }
                            setStatus(result.diarizationError == null
                                    ? "Расшифровка и диаризация готовы · " + processingSeconds + " с"
                                    : "Исходная расшифровка сохранена; диаризация не завершилась: " + result.diarizationError);
                            transcribeButton.setEnabled(true);
                        });
                    } catch (Exception e) {
                        ui.post(() -> {
                            ui.removeCallbacks(processingTicker);
                            processingStartedAt = 0;
                            database.updateStatus(recordingId, "Ошибка");
                            refreshLibrary();
                            setStatus("Ошибка обработки: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                            transcribeButton.setEnabled(true);
                        });
                    }
                });
            }

            @Override public void failed(Exception error) {
                database.updateStatus(recordingToProcess.id, "Ошибка моделей");
                refreshLibrary();
                setStatus("Не удалось подготовить модели: " + error.getMessage());
                transcribeButton.setEnabled(true);
            }
        });
    }

    private void updateLevel(double db) {
        if (levelText == null || levelBar == null) return;
        double bounded = Math.max(-60.0, Math.min(0.0, db));
        levelText.setText(String.format(Locale.getDefault(), "%.0f dBFS", bounded));
        levelBar.setProgress((int) Math.round(bounded + 60.0));
    }

    private void togglePauseRecording() {
        if (!recording) return;
        if (!recordingPaused) {
            startService(new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_PAUSE));
            recordingPaused = true;
            pausedAt = System.currentTimeMillis();
            pauseButton.setText("Продолжить");
            setStatus("Запись приостановлена");
            updateLevel(-60);
        } else {
            startService(new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_RESUME));
            pausedTotalMs += System.currentTimeMillis() - pausedAt;
            recordingPaused = false;
            pauseButton.setText("Пауза");
            setStatus("Запись продолжается");
        }
    }

    private void toggleRecording() {
        if (!recording) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
                return;
            }
            recording = true;
            recordingPaused = false;
            recordingStartedAt = System.currentTimeMillis();
            pausedTotalMs = 0;
            ui.removeCallbacks(recordingTicker);
            ui.post(recordingTicker);
            updateRecordButton();
            Intent service = new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            setStatus("Запись выполняется");
        } else {
            startService(new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_STOP));
            setStatus("Сохранение записи…");
        }
    }

    private void updateRecordButton() {
        if (recordButton != null) recordButton.setText(recording ? "Остановить и сохранить" : "Начать запись");
        if (pauseButton != null) pauseButton.setEnabled(recording && Build.VERSION.SDK_INT >= 24);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_AUDIO_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) toggleRecording();
        else if (requestCode == REQUEST_AUDIO_PERMISSION) setStatus("Для записи нужен доступ к микрофону");
    }

    private void importSelectedAudio(Uri uri) {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Не удалось открыть аудиофайл");
            File dir = new File(getFilesDir(), "recordings");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Не удалось создать каталог");
            String name = "import_" + System.currentTimeMillis() + ".audio";
            File target = new File(dir, name);
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            database.add(name, target.getAbsolutePath(), "Импортировано");
            refreshLibrary();
            setStatus("Файл сохранён локально");
        } catch (Exception e) {
            setStatus("Ошибка импорта: " + e.getMessage());
        }
    }

    private void refreshLibrary() {
        library.removeAllViews();
        List<RecordingDb.Recording> items = database.all();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Нет записей");
            empty.setPadding(0, dp(8), 0, dp(8));
            library.addView(empty);
            return;
        }
        for (RecordingDb.Recording item : items) {
            Button row = button(item.title + "  ·  " + item.status);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> selectRecording(item));
            library.addView(row);
        }
    }

    private void selectRecording(RecordingDb.Recording item) {
        selected = item;
        selectedTitle.setText("Расшифровка · " + item.title);
        transcriptEditor.setText(item.transcript);
        renderTranscriptSegments(item.transcript);
        setStatus("Выбрана запись");
    }

    private void renderTranscriptSegments(String transcript) {
        if (segmentList == null) return;
        segmentList.removeAllViews();
        if (transcript == null || transcript.trim().isEmpty()) return;
        for (String line : transcript.split("\\n")) {
            if (!line.startsWith("[")) continue;
            int close = line.indexOf(']');
            int speakerAt = line.indexOf("Спикер ", close);
            int colon = line.indexOf(':', speakerAt);
            if (close < 0 || speakerAt < 0 || colon < 0) continue;
            String timeRange = line.substring(1, close);
            String startText = timeRange.split("–")[0].trim();
            long startMs = parseTimecode(startText);
            if (startMs < 0) continue;
            String speaker = line.substring(speakerAt, colon);
            String speech = line.substring(colon + 1).trim();
            Button segment = button(startText + "   " + speaker + "\n" + speech);
            segment.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            segment.setOnClickListener(v -> playFromTimestamp(startMs));
            segmentList.addView(segment);
        }
    }

    private long parseTimecode(String value) {
        try {
            String[] parts = value.split(":");
            if (parts.length != 3) return -1;
            String[] secondsAndMillis = parts[2].split("\\.");
            if (secondsAndMillis.length != 2) return -1;
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            long seconds = Long.parseLong(secondsAndMillis[0]);
            String millisText = secondsAndMillis[1];
            while (millisText.length() < 3) millisText += "0";
            if (millisText.length() > 3) millisText = millisText.substring(0, 3);
            long millis = Long.parseLong(millisText);
            return (((hours * 60 + minutes) * 60 + seconds) * 1000) + millis;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private void playFromTimestamp(long positionMs) {
        if (selected == null || !new File(selected.audioPath).isFile()) {
            setStatus("Аудиофайл недоступен");
            return;
        }
        try {
            if (audioPlayer != null) {
                audioPlayer.release();
                audioPlayer = null;
            }
            audioPlayer = new MediaPlayer();
            audioPlayer.setDataSource(selected.audioPath);
            audioPlayer.setOnPreparedListener(player -> {
                player.seekTo((int) positionMs);
                player.start();
                setStatus("Воспроизведение с " + LocalTranscriptionEngine.formatTime(positionMs));
            });
            audioPlayer.setOnCompletionListener(player -> setStatus("Воспроизведение завершено"));
            audioPlayer.prepareAsync();
        } catch (Exception e) {
            setStatus("Не удалось воспроизвести аудио: " + e.getMessage());
        }
    }

    private void saveTranscript() {
        if (selected == null) { setStatus("Сначала выберите запись"); return; }
        String text = transcriptEditor.getText().toString();
        database.updateTranscript(selected.id, text);
        renderTranscriptSegments(text);
        selected = new RecordingDb.Recording(selected.id, selected.title, selected.audioPath,
                text, selected.createdAt, selected.status);
        setStatus("Текст сохранён");
    }

    private void writePdf(Uri destination) {
        if (destination == null || selected == null) return;
        String text = transcriptEditor.getText().toString();
        PdfDocument pdf = new PdfDocument();
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = pdf.startPage(info);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(18);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        page.getCanvas().drawText(selected.title, 48, 58, paint);
        paint.setTextSize(10);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        int y = 88;
        int maxChars = 92;
        String[] paragraphs = text.split("\\n");
        for (String paragraph : paragraphs) {
            String rest = paragraph;
            while (!rest.isEmpty()) {
                if (y > 790) {
                    pdf.finishPage(page);
                    info = new PdfDocument.PageInfo.Builder(595, 842, pdf.getPages().size() + 1).create();
                    page = pdf.startPage(info);
                    y = 52;
                }
                int end = Math.min(maxChars, rest.length());
                if (end < rest.length()) {
                    int space = rest.lastIndexOf(' ', end);
                    if (space > 0) end = space;
                }
                page.getCanvas().drawText(rest.substring(0, end), 48, y, paint);
                rest = rest.substring(end).trim();
                y += 15;
            }
            y += 8;
        }
        pdf.finishPage(page);
        try (OutputStream out = getContentResolver().openOutputStream(destination)) {
            if (out == null) throw new IllegalStateException("Не удалось создать PDF");
            pdf.writeTo(out);
            setStatus("PDF экспортирован");
        } catch (Exception e) {
            setStatus("Ошибка PDF: " + e.getMessage());
        } finally {
            pdf.close();
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(20), 0, dp(8));
        return view;
    }

    private void setStatus(String text) {
        if (statusText != null && text != null) statusText.setText(text);
    }

    private String safeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9а-яА-Я_-]", "_");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(recordingReceiver); } catch (Exception ignored) {}
        ui.removeCallbacks(recordingTicker);
        ui.removeCallbacks(processingTicker);
        if (modelRepository != null) modelRepository.close();
        if (transcriptionExecutor != null) transcriptionExecutor.shutdownNow();
        if (audioPlayer != null) {
            try { audioPlayer.release(); } catch (Exception ignored) {}
            audioPlayer = null;
        }
        database.close();
        super.onDestroy();
    }
}