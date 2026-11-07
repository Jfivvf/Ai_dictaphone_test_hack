package com.example;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Оболочка приложения: четыре раздела с нижней навигацией и экран записи поверх них.
 * Здесь же всё, что требует системных окон: права, выбор файлов, сохранение PDF и markdown.
 */
public class MainActivity extends ComponentActivity {
    private static final int TAB_RECORD = 0;
    private static final int TAB_LIBRARY = 1;
    private static final int TAB_TASKS = 2;
    private static final int TAB_SETTINGS = 3;
    private static final int EXPORT_PDF = 0;
    private static final int EXPORT_MARKDOWN = 1;
    private static final int EXPORT_JSON = 2;

    private static final String[] TAB_LABELS = {"Запись", "Записи", "Задачи", "Настройки"};
    private static final int[] TAB_ICONS = {R.drawable.ic_mic, R.drawable.ic_list, R.drawable.ic_tasks,
            R.drawable.ic_settings};

    private RecordingDb db;
    private Settings settings;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private BaseScreen[] screens;
    private RecordScreen recordScreen;
    private DetailScreen detail;
    private int tab = -1;
    private boolean imeShown = false;

    private LinearLayout root;
    private FrameLayout contentFrame;
    private FrameLayout detailFrame;
    private LinearLayout navBar;
    private LinearLayout navRow;
    private FrameLayout[] navPills;
    private ImageView[] navIcons;
    private TextView[] navLabels;
    private OnBackPressedCallback backCallback;

    private Runnable afterPermission;
    private long exportTarget = -1;

    private final RecordingDb.Listener dbListener = recordingId -> {
        if (screens != null) for (BaseScreen screen : screens) screen.onDataChanged(recordingId);
        if (detail != null) detail.onDataChanged(recordingId);
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Runnable next = afterPermission;
                afterPermission = null;
                if (Boolean.TRUE.equals(result.get(Manifest.permission.RECORD_AUDIO))) {
                    if (next != null) next.run();
                } else {
                    toast("Для записи нужен доступ к микрофону");
                }
            });
    private final ActivityResultLauncher<String> notificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::importFiles);
    private final ActivityResultLauncher<Uri> realtimeModelsLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) RealtimeModels.importAsync(this, uri);
            });
    private final ActivityResultLauncher<String> pdfLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"),
                    uri -> writeExport(uri, EXPORT_PDF));
    private final ActivityResultLauncher<String> markdownLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/markdown"),
                    uri -> writeExport(uri, EXPORT_MARKDOWN));
    private final ActivityResultLauncher<String> jsonLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> writeExport(uri, EXPORT_JSON));

    // ---- создание -----------------------------------------------------------------------------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = RecordingDb.get(this);
        settings = new Settings(this);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);

        recordScreen = new RecordScreen(this);
        screens = new BaseScreen[]{recordScreen, new LibraryScreen(this), new TasksScreen(this),
                new SettingsScreen(this)};
        buildShell();
        RecordingDb.addListener(dbListener);

        backCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                // Сначала выходим из режима выбора реплик, и только потом закрываем запись.
                if (detail != null && detail.handleBack()) return;
                closeDetail();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        showTab(state == null ? TAB_RECORD : state.getInt("tab", TAB_RECORD));
        if (state != null) {
            long open = state.getLong("open", -1);
            if (open >= 0) openRecording(open);
        } else {
            handleIntent(getIntent());
            offerModelDownload();
        }
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);

        FrameLayout stack = new FrameLayout(this);
        contentFrame = new FrameLayout(this);
        for (BaseScreen screen : screens) {
            screen.view().setVisibility(View.GONE);
            contentFrame.addView(screen.view(), new FrameLayout.LayoutParams(-1, -1));
        }
        stack.addView(contentFrame, new FrameLayout.LayoutParams(-1, -1));
        detailFrame = new FrameLayout(this);
        detailFrame.setVisibility(View.GONE);
        stack.addView(detailFrame, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1f));

        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setBackgroundColor(Palette.SURFACE);
        View divider = new View(this);
        divider.setBackgroundColor(Palette.BORDER);
        navBar.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(this, 1))));
        navRow = new LinearLayout(this);
        navRow.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        navPills = new FrameLayout[TAB_LABELS.length];
        navIcons = new ImageView[TAB_LABELS.length];
        navLabels = new TextView[TAB_LABELS.length];
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setOnClickListener(v -> {
                if (detail != null) closeDetail();
                showTab(index);
            });
            FrameLayout pill = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(TAB_ICONS[i]);
            pill.addView(icon, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
            item.addView(pill, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 32)));
            TextView label = Ui.text(this, TAB_LABELS[i], 11, Palette.MUTED);
            label.setGravity(Gravity.CENTER);
            item.addView(label, Ui.margins(Ui.lp(-2, -2), this, 0, 4, 0, 0));
            navPills[i] = pill;
            navIcons[i] = icon;
            navLabels[i] = label;
            navRow.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        navBar.addView(navRow);
        root.addView(navBar, new LinearLayout.LayoutParams(-1, -2));

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            imeShown = ime.bottom > bars.bottom;
            view.setPadding(bars.left, bars.top, bars.right, imeShown ? ime.bottom : 0);
            navBar.setPadding(0, 0, 0, imeShown ? 0 : bars.bottom);
            updateNavVisibility();
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);
    }

    private void updateNavVisibility() {
        navBar.setVisibility(detail == null && !imeShown ? View.VISIBLE : View.GONE);
    }

    private void updateNav() {
        for (int i = 0; i < TAB_LABELS.length; i++) {
            boolean selected = i == tab;
            navPills[i].setBackground(selected ? Ui.box(this, Palette.alpha(Palette.ACCENT, 0x40), 16) : Ui.transparent());
            navIcons[i].setColorFilter(selected ? Palette.ACCENT_SOFT : Palette.MUTED);
            navLabels[i].setTextColor(selected ? Palette.TEXT : Palette.MUTED);
        }
    }

    // ---- навигация ----------------------------------------------------------------------------

    void showTab(int index) {
        if (tab >= 0 && detail == null) screens[tab].onHide();
        tab = index;
        for (int i = 0; i < screens.length; i++) {
            screens[i].view().setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (detail == null) screens[index].onShow();
        updateNav();
    }

    void openRecording(long id) {
        openRecording(id, -1);
    }

    /** Открывает запись; focusMs ≥ 0 — подвести список к реплике на этом времени (без запуска звука). */
    void openRecording(long id, long focusMs) {
        if (detail != null) {
            detail.release();
            detailFrame.removeAllViews();
            detail = null;
        } else if (tab >= 0) {
            screens[tab].onHide();
        }
        detail = new DetailScreen(this, id, focusMs);
        detailFrame.removeAllViews();
        detailFrame.addView(detail.view(), new FrameLayout.LayoutParams(-1, -1));
        contentFrame.setVisibility(View.GONE);
        detailFrame.setVisibility(View.VISIBLE);
        backCallback.setEnabled(true);
        updateNavVisibility();
    }

    void openLatestRecording() {
        List<Recording> all = db.all();
        if (!all.isEmpty()) openRecording(all.get(0).id);
    }

    void closeDetail() {
        if (detail == null) return;
        detail.release();
        detailFrame.removeAllViews();
        detail = null;
        detailFrame.setVisibility(View.GONE);
        contentFrame.setVisibility(View.VISIBLE);
        backCallback.setEnabled(false);
        updateNavVisibility();
        if (tab >= 0) screens[tab].onShow();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        long id = intent.getLongExtra(TranscriptionService.EXTRA_OPEN, -1);
        if (id >= 0) {
            intent.removeExtra(TranscriptionService.EXTRA_OPEN);
            openRecording(id);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ---- жизненный цикл -----------------------------------------------------------------------

    @Override protected void onStart() {
        super.onStart();
        RecordingService.setListener(recordScreen);
        recordScreen.syncState();
        if (detail != null) detail.onDataChanged(-1);
        else if (tab >= 0) screens[tab].onDataChanged(-1);
    }

    @Override protected void onStop() {
        RecordingService.setListener(null);
        if (detail != null) detail.pausePlayer();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", Math.max(0, tab));
        out.putLong("open", detail != null ? detail.id : -1);
    }

    @Override protected void onDestroy() {
        RecordingDb.removeListener(dbListener);
        RecordingService.setListener(null);
        if (detail != null) detail.release();
        if (screens != null) for (BaseScreen screen : screens) screen.release();
        io.shutdown();
        super.onDestroy();
    }

    // ---- права --------------------------------------------------------------------------------

    /** Выполняет действие, когда есть доступ к микрофону; при необходимости сначала спрашивает его. */
    void withRecordPermission(Runnable next) {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            next.run();
            return;
        }
        afterPermission = next;
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS});
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // ---- модели -------------------------------------------------------------------------------

    private void offerModelDownload() {
        if (settings.modelsPrompted() || new ModelRepository(this).installed()) return;
        settings.setModelsPrompted(true);
        Ui.dialog(this).setTitle("Скачать модели распознавания?")
                .setMessage("Для расшифровки нужны модели речи и голосов — около 690 МБ. "
                        + "Они скачиваются один раз, дальше приложение работает без интернета. "
                        + "Лучше загружать по Wi-Fi.")
                .setPositiveButton("Скачать", (d, w) -> downloadModels())
                .setNegativeButton("Позже", null)
                .show();
    }

    void downloadModels() {
        askNotifications();
        try {
            TranscriptionService.downloadModels(this);
        } catch (RuntimeException e) {
            toast("Не удалось начать загрузку: " + e.getMessage());
        }
    }

    /** Выбор папки с моделями Real-life (распакованный models_bundle или сама папка models). */
    void pickRealtimeModels() {
        realtimeModelsLauncher.launch(null);
    }

    void transcribe(long recordingId) {
        askNotifications();
        try {
            TranscriptionService.enqueue(this, recordingId);
        } catch (RuntimeException e) {
            toast("Не удалось запустить расшифровку: " + e.getMessage());
        }
    }

    /** Повторно расшифровывает только выбранные реплики (по idx); время и говорящие сохраняются. */
    void transcribeFragments(long recordingId, int[] indexes) {
        askNotifications();
        try {
            TranscriptionService.enqueueFragments(this, recordingId, indexes);
        } catch (RuntimeException e) {
            toast("Не удалось запустить расшифровку: " + e.getMessage());
        }
    }

    // ---- импорт -------------------------------------------------------------------------------

    void pickAudio() {
        importLauncher.launch(new String[]{"audio/*", "video/*", "application/ogg"});
    }

    private void importFiles(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        io.execute(() -> {
            int imported = 0;
            long lastId = -1;
            String error = null;
            for (Uri uri : uris) {
                try {
                    lastId = importOne(uri);
                    imported++;
                } catch (Exception e) {
                    error = e.getMessage() == null ? "Не удалось импортировать файл" : e.getMessage();
                }
            }
            final int count = imported;
            final long openId = lastId;
            final String message = error;
            runOnUiThread(() -> {
                if (message != null) toast(message);
                if (count == 1 && uris.size() == 1) openRecording(openId);
                else if (count > 0) {
                    if (detail != null) closeDetail();
                    showTab(TAB_LIBRARY);
                }
            });
        });
    }

    private long importOne(Uri uri) throws IOException {
        String name = displayName(uri);
        File dir = new File(getFilesDir(), "recordings");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать каталог записей");
        String extension = extensionOf(name);
        File target = File.createTempFile("import_", extension, dir);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) throw new IOException("Не удалось открыть «" + name + "»");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (IOException e) {
            target.delete();
            throw e;
        }
        long duration = AudioDecoder.durationMs(target.getAbsolutePath());
        if (duration <= 0) {
            target.delete();
            throw new IOException("Не удалось прочитать «" + name + "»: формат не поддерживается");
        }
        long id = db.add(titleOf(name), target.getAbsolutePath(), Status.RECORDED, duration);
        if (settings.autoTranscribe()) {
            try {
                TranscriptionService.enqueue(this, id);
            } catch (RuntimeException ignored) {
                // Запись сохранена; расшифровку можно запустить вручную.
            }
        }
        return id;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // Имя необязательно: ниже подставляется запасное.
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "Импорт" : last;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 8) return ".audio";
        return name.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
    }

    private static String titleOf(String name) {
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        title = title.trim();
        return title.isEmpty() ? "Импорт" : title;
    }

    // ---- экспорт ------------------------------------------------------------------------------

    void exportPdf(long recordingId) {
        Recording r = db.get(recordingId);
        if (r == null) return;
        exportTarget = recordingId;
        pdfLauncher.launch(safeName(r.title) + ".pdf");
    }

    void exportMarkdown(long recordingId) {
        Recording r = db.get(recordingId);
        if (r == null) return;
        exportTarget = recordingId;
        markdownLauncher.launch(safeName(r.title) + ".md");
    }

    void exportJson(long recordingId) {
        Recording r = db.get(recordingId);
        if (r == null) return;
        exportTarget = recordingId;
        jsonLauncher.launch(safeName(r.title) + ".json");
    }

    private void writeExport(Uri destination, int kind) {
        final long id = exportTarget;
        exportTarget = -1;
        if (destination == null || id < 0) return;
        final boolean clean = settings.cleanText();
        io.execute(() -> {
            String message;
            try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                Recording r = db.get(id);
                if (out == null || r == null) throw new IOException("Не удалось создать файл");
                List<Segment> segments = db.segments(id);
                List<Task> tasks = db.tasksFor(id);
                if (kind == EXPORT_PDF) {
                    PdfExporter.export(r, segments, tasks, clean, out);
                } else if (kind == EXPORT_JSON) {
                    out.write(NoteBuilder.json(segments).getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(NoteBuilder.markdown(r, segments, tasks, clean).getBytes(StandardCharsets.UTF_8));
                }
                message = kind == EXPORT_PDF ? "PDF сохранён"
                        : kind == EXPORT_JSON ? "JSON сохранён" : "Markdown сохранён";
            } catch (Exception e) {
                message = "Не удалось сохранить файл" + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            final String result = message;
            runOnUiThread(() -> toast(result));
        });
    }

    private static String safeName(String title) {
        String name = title.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
        if (name.length() > 80) name = name.substring(0, 80).trim();
        return name.isEmpty() ? "Запись" : name;
    }

    // ---- изменения записей --------------------------------------------------------------------

    void rename(long recordingId, String title) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            toast("Название не может быть пустым");
            return;
        }
        db.rename(recordingId, clean);
    }

    void updateSegment(long recordingId, int idx, int speaker, String text) {
        io.execute(() -> {
            db.updateSegment(recordingId, idx, speaker, text);
            db.replaceTasks(recordingId, NoteBuilder.tasks(db.segments(recordingId)));
        });
    }

    void confirmDelete(Recording r) {
        if (r == null) return;
        if (Status.isBusy(r.status)) {
            toast("Дождитесь окончания расшифровки");
            return;
        }
        Ui.dialog(this).setTitle("Удалить запись?")
                .setMessage("«" + r.title + "» будет удалена вместе с аудио, расшифровкой и задачами.")
                .setPositiveButton("Удалить", (d, w) -> io.execute(() -> {
                    String path = db.delete(r.id);
                    if (path != null) new File(path).delete();
                    runOnUiThread(() -> {
                        if (detail != null && detail.id == r.id) closeDetail();
                    });
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ---- диалоги ------------------------------------------------------------------------------

    void prompt(String title, String initial, String hint, Consumer<String> onOk) {
        EditText edit = new EditText(this);
        edit.setText(initial);
        edit.setHint(hint);
        edit.setTextColor(Palette.TEXT);
        edit.setHintTextColor(Palette.MUTED);
        edit.setTextSize(16);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setBackground(Ui.box(this, Palette.SURFACE_2, 12, Palette.BORDER));
        edit.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        edit.setSelection(edit.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(edit, new FrameLayout.LayoutParams(-1, -2));
        Ui.dialog(this).setTitle(title).setView(box)
                .setPositiveButton("Сохранить", (d, w) -> onOk.accept(edit.getText().toString()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
