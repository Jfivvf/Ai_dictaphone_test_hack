package com.example;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

final class LocalModelManager {
    private static final String WHISPER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2";
    private static final String DIARIZATION_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2";
    private static final String EMBEDDING_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx";

    private final File root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    LocalModelManager(Context context) {
        root = new File(context.getFilesDir(), "models");
        if (!root.exists()) root.mkdirs();
    }

    interface Callback {
        void onProgress(String stage, int percent);
        void onReady(File whisperEncoder, File whisperDecoder, File whisperTokens,
                     File segmentation, File embedding);
        void onError(Exception error);
    }

    void ensureModels(Callback callback) {
        executor.execute(() -> {
            try {
                File whisperDir = new File(root, "whisper-tiny");
                File encoder = new File(whisperDir, "tiny-encoder.int8.onnx");
                File decoder = new File(whisperDir, "tiny-decoder.onnx");
                File tokens = new File(whisperDir, "tiny-tokens.txt");
                if (!encoder.exists() || !decoder.exists() || !tokens.exists()) {
                    downloadAndExtract(WHISPER_URL, new File(root, "whisper-tiny.tar.bz2"),
                            whisperDir, "Загрузка Whisper", callback);
                }

                File diarizationDir = new File(root, "speaker-diarization");
                File segmentation = new File(diarizationDir, "segmentation.onnx");
                if (!segmentation.exists()) {
                    File archive = new File(root, "speaker-segmentation.tar.bz2");
                    downloadAndExtract(DIARIZATION_URL, archive, diarizationDir,
                            "Загрузка модели диаризации", callback);
                    File original = new File(diarizationDir, "model.onnx");
                    if (original.exists() && !original.renameTo(segmentation)) {
                        throw new IllegalStateException("Не удалось подготовить модель сегментации");
                    }
                }

                File embedding = new File(diarizationDir, "embedding.onnx");
                if (!embedding.exists()) {
                    download(EMBEDDING_URL, embedding, "Загрузка модели голосов", callback);
                }

                if (!encoder.isFile() || !decoder.isFile() || !tokens.isFile()
                        || !segmentation.isFile() || !embedding.isFile()) {
                    throw new IllegalStateException("Не все файлы моделей были загружены");
                }
                main.post(() -> callback.onReady(encoder, decoder, tokens, segmentation, embedding));
            } catch (Exception e) {
                main.post(() -> callback.onError(e));
            }
        });
    }

    private void downloadAndExtract(String url, File archive, File destination, String stage,
                                    Callback callback) throws Exception {
        download(url, archive, stage, callback);
        if (!destination.exists()) destination.mkdirs();
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             InputStream bz = new BZip2CompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry;
            String rootPath = destination.getCanonicalPath() + File.separator;
            while ((entry = tar.getNextTarEntry()) != null) {
                File output = new File(destination, entry.getName());
                if (!output.getCanonicalPath().startsWith(rootPath)) {
                    throw new SecurityException("Недопустимый путь внутри архива модели");
                }
                if (entry.isDirectory()) {
                    output.mkdirs();
                } else {
                    File parent = output.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream out = new FileOutputStream(output)) {
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = tar.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                }
            }
        }
        archive.delete();
    }

    private void download(String urlText, File target, String stage, Callback callback) throws Exception {
        if (target.exists() && target.length() > 0) return;
        File part = new File(target.getParentFile(), target.getName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Ошибка загрузки модели: HTTP " + status);
        }
        long total = connection.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[65536];
            long done = 0;
            int n;
            int lastPercent = -1;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                done += n;
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    final int p = percent;
                    main.post(() -> callback.onProgress(stage, p));
                }
            }
        } finally {
            connection.disconnect();
        }
        if (part.length() == 0 || !part.renameTo(target)) {
            part.delete();
            throw new IllegalStateException("Не удалось сохранить файл модели");
        }
    }

    void close() { executor.shutdownNow(); }
}