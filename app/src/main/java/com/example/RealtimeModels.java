package com.example;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Модели для расшифровки во время записи (режим Real-life): transducer-модели sherpa-onnx для русского
 * и английского и Silero VAD. Подключаются из папки на устройстве, например распакованной models_bundle:
 * в ней ищутся каталоги sherpa-ru и sherpa-en (encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt)
 * и файл silero_vad.onnx. Файлы копируются в память приложения.
 */
final class RealtimeModels {
    static final class Files {
        final File encoder;
        final File decoder;
        final File joiner;
        final File tokens;
        /** Нужен только для непотоковых моделей; для потоковых может быть null. */
        final File vad;
        final boolean streaming;

        Files(File encoder, File decoder, File joiner, File tokens, File vad, boolean streaming) {
            this.encoder = encoder;
            this.decoder = decoder;
            this.joiner = joiner;
            this.tokens = tokens;
            this.vad = vad;
            this.streaming = streaming;
        }
    }

    private static final String[] LANGUAGES = {"ru", "en"};
    private static final String[] REQUIRED = {"encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"};

    private static volatile boolean importing;
    private static volatile String importLabel = "";
    private static volatile int importPercent = -1;
    private static volatile String importError = "";

    private final File root;
    private final Context appContext;

    RealtimeModels(Context context) {
        appContext = context.getApplicationContext();
        root = new File(context.getFilesDir(), "models/realtime");
    }

    static boolean isImporting() { return importing; }
    static String importLabel() { return importLabel; }
    static int importPercent() { return importPercent; }
    static String importError() { return importError; }

    /** «ru» или «en»; автоопределение языка для потоковой модели не поддерживается, берётся русский. */
    static String languageFor(String code) {
        return "en".equals(code) ? "en" : "ru";
    }

    private File dir(String language) {
        return new File(root, language);
    }

    /** Языки, для которых модель подключена. */
    List<String> installedLanguages() {
        List<String> result = new ArrayList<>();
        for (String language : LANGUAGES) if (complete(language)) result.add(language);
        return result;
    }

    private boolean complete(String language) {
        for (String name : REQUIRED) if (!new File(dir(language), name).isFile()) return false;
        return true;
    }

    /** Файлы модели для языка. Бросает IOException с понятным текстом, если чего-то не хватает. */
    Files open(String language) throws IOException {
        if (!complete(language)) {
            throw new IOException("Модель Real-life для языка «" + (language.equals("en") ? "English" : "Русский")
                    + "» не подключена. Настройки → Real-life: выберите папку с моделями");
        }
        File dir = dir(language);
        File encoder = new File(dir, "encoder.onnx");
        boolean streaming = isStreaming(encoder);
        File vad = new File(root, "silero_vad.onnx");
        if (!streaming && !vad.isFile()) {
            throw new IOException("Для этой модели нужен файл silero_vad.onnx: выберите папку, где он лежит");
        }
        return new Files(encoder, new File(dir, "decoder.onnx"), new File(dir, "joiner.onnx"),
                new File(dir, "tokens.txt"), vad.isFile() ? vad : null, streaming);
    }

    /**
     * Потоковые zipformer-модели хранят в метаданных encoder параметр decode_chunk_len; у непотоковых его нет.
     * Метаданные лежат в конце ONNX-файла, поэтому читаем только последние мегабайты. Проверка нужна, потому что
     * потоковый движок падает на непотоковой модели без возможности перехватить ошибку.
     */
    static boolean isStreaming(File encoder) throws IOException {
        byte[] key = "decode_chunk_len".getBytes(StandardCharsets.US_ASCII);
        try (RandomAccessFile in = new RandomAccessFile(encoder, "r")) {
            long length = in.length();
            int window = (int) Math.min(length, 4L << 20);
            byte[] data = new byte[window];
            in.seek(length - window);
            in.readFully(data);
            return indexOf(data, key) >= 0;
        }
    }

    private static int indexOf(byte[] data, byte[] key) {
        outer:
        for (int i = 0; i + key.length <= data.length; i++) {
            for (int k = 0; k < key.length; k++) if (data[i + k] != key[k]) continue outer;
            return i;
        }
        return -1;
    }

    // ---- подключение из папки -----------------------------------------------------------------

    /** Копирует модели из выбранной папки в фоне. Прогресс и ошибка доступны через статические методы. */
    static void importAsync(final Context context, final Uri tree) {
        if (importing) return;
        importing = true;
        importError = "";
        importLabel = "Поиск моделей";
        importPercent = -1;
        RecordingDb.get(context).notifyChanged(-1);
        new Thread(() -> {
            try {
                new RealtimeModels(context).importFrom(context.getContentResolver(), tree);
            } catch (Throwable e) {
                importError = e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                importing = false;
                RecordingDb.get(context).notifyChanged(-1);
            }
        }, "realtime-models-import").start();
    }

    private static final class Doc {
        final String id;
        final String name;
        final boolean directory;

        Doc(String id, String name, boolean directory) {
            this.id = id;
            this.name = name;
            this.directory = directory;
        }
    }

    private void importFrom(ContentResolver resolver, Uri tree) throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        // language -> id каталога; vad -> id файла
        String[] languageDir = new String[LANGUAGES.length];
        String[] vadId = new String[1];
        search(resolver, tree, rootId, 0, languageDir, vadId);

        long total = 0;
        List<String[]> jobs = new ArrayList<>();   // {документ, назначение}
        int found = 0;
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (languageDir[i] == null) continue;
            List<String[]> local = new ArrayList<>();
            for (Doc d : children(resolver, tree, languageDir[i])) {
                if (d.directory) continue;
                for (String required : REQUIRED) {
                    if (d.name.equals(required)) {
                        local.add(new String[]{d.id, new File(dir(LANGUAGES[i]), required).getAbsolutePath()});
                    }
                }
            }
            if (local.size() == REQUIRED.length) {
                jobs.addAll(local);
                found++;
            }
        }
        if (vadId[0] != null) jobs.add(new String[]{vadId[0], new File(root, "silero_vad.onnx").getAbsolutePath()});
        if (found == 0) {
            throw new IOException("В папке не найдены модели: нужны каталоги sherpa-ru или sherpa-en "
                    + "с файлами encoder.onnx, decoder.onnx, joiner.onnx и tokens.txt");
        }

        for (String[] job : jobs) total += size(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, job[0]));
        long done = 0;
        for (String[] job : jobs) {
            File target = new File(job[1]);
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            importLabel = "Копирование · " + target.getName();
            done = copy(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, job[0]), target, done, total);
        }
        importLabel = "";
        importPercent = -1;
    }

    private void search(ContentResolver resolver, Uri tree, String parentId, int depth,
                        String[] languageDir, String[] vadId) {
        if (depth > 4) return;
        for (Doc d : children(resolver, tree, parentId)) {
            if (d.directory) {
                for (int i = 0; i < LANGUAGES.length; i++) {
                    if (d.name.toLowerCase(Locale.ROOT).equals("sherpa-" + LANGUAGES[i]) && languageDir[i] == null) {
                        languageDir[i] = d.id;
                    }
                }
                search(resolver, tree, d.id, depth + 1, languageDir, vadId);
            } else if (d.name.equalsIgnoreCase("silero_vad.onnx") && vadId[0] == null) {
                vadId[0] = d.id;
            }
        }
    }

    private List<Doc> children(ContentResolver resolver, Uri tree, String parentId) {
        List<Doc> result = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = resolver.query(uri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return result;
            while (c.moveToNext()) {
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                result.add(new Doc(c.getString(0), c.getString(1) == null ? "" : c.getString(1), directory));
            }
        } catch (RuntimeException ignored) {
            // Папка недоступна — считаем пустой.
        }
        return result;
    }

    private static long size(ContentResolver resolver, Uri document) {
        try (Cursor c = resolver.query(document, new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    private long copy(ContentResolver resolver, Uri document, File target, long done, long total) throws IOException {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (InputStream in = new BufferedInputStream(resolver.openInputStream(document));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] buffer = new byte[1 << 16];
            int n;
            int previous = -2;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                done += n;
                int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                if (percent != previous) {
                    previous = percent;
                    importPercent = percent;
                    notifyProgress();
                }
            }
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
        if (target.exists() && !target.delete()) throw new IOException("Не удалось заменить " + target.getName());
        if (!part.renameTo(target)) throw new IOException("Не удалось сохранить " + target.getName());
        return done;
    }

    private long lastNotify;

    /** Обновляет экран настроек не чаще трёх раз в секунду. */
    private void notifyProgress() {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 300) return;
        lastNotify = now;
        RecordingDb.get(appContext).notifyChanged(-1);
    }
}
