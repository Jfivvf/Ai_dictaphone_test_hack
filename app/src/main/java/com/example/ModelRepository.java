package com.example;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;

/** Модели распознавания и диаризации: скачиваются при первой расшифровке и дальше работают без сети. */
final class ModelRepository {
    private static final String WHISPER = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2";
    private static final String SEGMENT = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2";
    private static final String EMBED = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx";
    /** Небольшая модель шумоподавления GTCRN; качается только если включено шумоподавление. */
    private static final String DENOISE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx";
    private static final long DENOISE_BYTES = 535638L;

    interface Progress {
        void onProgress(String label, int percent);
    }

    static final class Models {
        final File encoder;
        final File decoder;
        final File tokens;
        final File segmentation;
        final File embedding;

        Models(File encoder, File decoder, File tokens, File segmentation, File embedding) {
            this.encoder = encoder;
            this.decoder = decoder;
            this.tokens = tokens;
            this.segmentation = segmentation;
            this.embedding = embedding;
        }
    }

    private final File root;

    ModelRepository(Context context) {
        root = new File(context.getFilesDir(), "models");
        root.mkdirs();
    }

    private File encoderFile() { return new File(root, "whisper/small-encoder.int8.onnx"); }
    private File decoderFile() { return new File(root, "whisper/small-decoder.int8.onnx"); }
    private File tokensFile() { return new File(root, "whisper/small-tokens.txt"); }
    private File segmentationFile() { return new File(root, "diarization/segmentation.onnx"); }
    private File embeddingFile() { return new File(root, "diarization/embedding.onnx"); }
    private File denoiserFile() { return new File(root, "denoise/gtcrn_simple.onnx"); }

    boolean installed() {
        return encoderFile().isFile() && decoderFile().isFile() && tokensFile().isFile()
                && segmentationFile().isFile() && embeddingFile().isFile();
    }

    /** Блокирующий вызов: скачивает недостающее и возвращает пути к моделям. Вызывать не из главного потока. */
    Models ensure(Progress progress) throws Exception {
        File whisperDir = new File(root, "whisper");
        whisperDir.mkdirs();
        File enc = encoderFile();
        File dec = decoderFile();
        File tok = tokensFile();
        if (!enc.isFile() || !dec.isFile() || !tok.isFile()) {
            File archive = new File(root, "whisper.tar.bz2");
            download(WHISPER, archive, "Загрузка модели распознавания речи", 639387718L, progress);
            progress.onProgress("Распаковка модели распознавания", -1);
            extractFlat(archive, whisperDir, "small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt");
            archive.delete();
        }

        File diarDir = new File(root, "diarization");
        diarDir.mkdirs();
        File seg = segmentationFile();
        if (!seg.isFile()) {
            File archive = new File(root, "segmentation.tar.bz2");
            download(SEGMENT, archive, "Загрузка модели сегментации речи", 6958444L, progress);
            progress.onProgress("Распаковка модели сегментации", -1);
            extractFlat(archive, diarDir, "model.onnx");
            archive.delete();
            File model = new File(diarDir, "model.onnx");
            if (model.isFile() && !model.renameTo(seg)) throw new IOException("Не удалось подготовить модель сегментации");
        }
        File emb = embeddingFile();
        if (!emb.isFile()) download(EMBED, emb, "Загрузка модели голосов", 39593761L, progress);

        if (!enc.isFile() || !dec.isFile() || !tok.isFile() || !seg.isFile() || !emb.isFile()) {
            throw new IOException("Загрузка моделей завершилась не полностью");
        }
        return new Models(enc, dec, tok, seg, emb);
    }

    /** Блокирующий вызов: скачивает модель шумоподавления при первом использовании и возвращает путь к ней. */
    File ensureDenoiser(Progress progress) throws Exception {
        File file = denoiserFile();
        file.getParentFile().mkdirs();
        if (!file.isFile()) download(DENOISE, file, "Загрузка модели шумоподавления", DENOISE_BYTES, progress);
        if (!file.isFile()) throw new IOException("Не удалось загрузить модель шумоподавления");
        return file;
    }

    private void download(String url, File target, String label, long expectedBytes, Progress progress) throws Exception {
        if (target.isFile() && target.length() > 0) return;
        File part = new File(target.getParentFile(), target.getName() + ".part");
        progress.onProgress(label + " · подключение", -1);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.connect();
        if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) throw new IOException("HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] b = new byte[65536];
            long done = 0;
            int n;
            int previous = -2;
            while ((n = in.read(b)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Загрузка остановлена");
                out.write(b, 0, n);
                done += n;
                long progressTotal = total > 0 ? total : expectedBytes;
                int p = progressTotal > 0 ? (int) Math.min(100, done * 100 / progressTotal) : -1;
                if (p != previous) {
                    previous = p;
                    progress.onProgress(label + " · " + formatSize(done)
                            + (progressTotal > 0 ? " из " + formatSize(progressTotal) : ""), p);
                }
            }
        } finally {
            c.disconnect();
        }
        if (part.length() != expectedBytes) {
            long actual = part.length();
            part.delete();
            throw new IOException("Размер модели не совпадает с ожидаемым: " + actual + " из " + expectedBytes + " байт");
        }
        if (!part.renameTo(target)) throw new IOException("Не удалось сохранить скачанную модель");
    }

    private String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.0f МБ", bytes / (1024.0 * 1024.0));
    }

    private void extractFlat(File archive, File destination, String... keepNames) throws Exception {
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             InputStream bz = new BZip2CompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = new File(entry.getName()).getName();
                if (name.isEmpty() || entry.isDirectory() || !Arrays.asList(keepNames).contains(name)) continue;
                File outFile = new File(destination, name);
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = tar.read(b)) != -1) out.write(b, 0, n);
                }
            }
        }
    }
}
