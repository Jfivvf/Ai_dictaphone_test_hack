package com.example;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ModelRepository {
    private static final String WHISPER = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2";
    private static final String SEGMENT = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2";
    private static final String EMBED = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx";
    private final File root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    interface Callback {
        void progress(String label, int percent);
        void ready(File encoder, File decoder, File tokens, File segmentation, File embedding);
        void failed(Exception error);
    }

    ModelRepository(Context context) {
        root = new File(context.getFilesDir(), "models");
        root.mkdirs();
    }

    void prepare(Callback callback) {
        worker.execute(() -> {
            try {
                File whisperDir = new File(root, "whisper");
                whisperDir.mkdirs();
                File enc = new File(whisperDir, "small-encoder.int8.onnx");
                File dec = new File(whisperDir, "small-decoder.onnx");
                File tok = new File(whisperDir, "small-tokens.txt");
                if (!enc.isFile() || !dec.isFile() || !tok.isFile()) {
                    File archive = new File(root, "whisper.tar.bz2");
                    download(WHISPER, archive, "Загрузка Whisper", callback);
                    extractFlat(archive, whisperDir, "small-encoder.int8.onnx", "small-decoder.onnx", "small-tokens.txt");
                    archive.delete();
                }

                File diarDir = new File(root, "diarization");
                diarDir.mkdirs();
                File seg = new File(diarDir, "segmentation.onnx");
                if (!seg.isFile()) {
                    File archive = new File(root, "segmentation.tar.bz2");
                    download(SEGMENT, archive, "Загрузка сегментации речи", callback);
                    extractFlat(archive, diarDir, "model.onnx");
                    archive.delete();
                    File model = new File(diarDir, "model.onnx");
                    if (model.isFile() && !model.renameTo(seg)) throw new IOException("Не удалось подготовить модель сегментации");
                }
                File emb = new File(diarDir, "embedding.onnx");
                if (!emb.isFile()) download(EMBED, emb, "Загрузка модели голосов", callback);

                if (!enc.isFile() || !dec.isFile() || !tok.isFile() || !seg.isFile() || !emb.isFile())
                    throw new IOException("Загрузка моделей завершилась не полностью");
                main.post(() -> callback.ready(enc, dec, tok, seg, emb));
            } catch (Exception e) {
                main.post(() -> callback.failed(e));
            }
        });
    }

    private void download(String url, File target, String label, Callback callback) throws Exception {
        if (target.isFile() && target.length() > 0) return;
        File part = new File(target.getParentFile(), target.getName() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.connect();
        if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) throw new IOException("HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] b = new byte[65536];
            long done = 0;
            int n, previous = -1;
            while ((n = in.read(b)) >= 0) {
                out.write(b, 0, n);
                done += n;
                int p = total > 0 ? (int)(done * 100 / total) : -1;
                if (p != previous) {
                    previous = p;
                    final int progress = p;
                    main.post(() -> callback.progress(label, progress));
                }
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(target)) throw new IOException("Не удалось сохранить скачанную модель");
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

    void close() { worker.shutdownNow(); }
}