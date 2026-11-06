package com.example;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
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

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_AUDIO_PERMISSION = 701;
    private RecordingDb database;
    private LinearLayout root;
    private LinearLayout library;
    private EditText transcriptEditor;
    private TextView selectedTitle;
    private TextView statusText;
    private RecordingDb.Recording selected;
    private boolean recording = false;

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
                updateRecordButton();
            } else if (RecordingService.ACTION_FAILED.equals(action)) {
                recording = false;
                updateRecordButton();
                setStatus(intent.getStringExtra("error"));
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        database = new RecordingDb(this);
        buildInterface();
        refreshLibrary();

        IntentFilter filter = new IntentFilter();
        filter.addAction(RecordingService.ACTION_FINISHED);
        filter.addAction(RecordingService.ACTION_FAILED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(recordingReceiver, filter);
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = new TextView(this);
        heading.setText("AudioVault");
        heading.setTextSize(28);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);

        statusText = new TextView(this);
        statusText.setText("Готово");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(6), 0, dp(16));
        root.addView(statusText);

        Button recordButton = button("Начать запись");
        recordButton.setOnClickListener(v -> toggleRecording());
        root.addView(recordButton);
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
        transcriptEditor.setGravity(Gravity.TOP | Gravity.START);
        transcriptEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        transcriptEditor.setHint("Текст расшифровки");
        root.addView(transcriptEditor, new LinearLayout.LayoutParams(-1, dp(220)));

        Button saveText = button("Сохранить текст");
        saveText.setOnClickListener(v -> saveTranscript());
        root.addView(saveText);
        Button pdfButton = button("Экспортировать PDF");
        pdfButton.setOnClickListener(v -> {
            if (selected == null) { setStatus("Сначала выберите запись"); return; }
            exportPdf.launch(safeFileName(selected.title) + ".pdf");
        });
        root.addView(pdfButton);
    }

    private void toggleRecording() {
        if (!recording) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
                return;
            }
            recording = true;
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
        // The control label is updated by rebuilding only the first action button.
        if (root.getChildCount() > 2 && root.getChildAt(2) instanceof Button) {
            ((Button) root.getChildAt(2)).setText(recording ? "Остановить запись" : "Начать запись");
        }
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
        setStatus("Выбрана запись");
    }

    private void saveTranscript() {
        if (selected == null) { setStatus("Сначала выберите запись"); return; }
        String text = transcriptEditor.getText().toString();
        database.updateTranscript(selected.id, text);
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
        database.close();
        super.onDestroy();
    }
}