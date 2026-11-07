package com.example;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Запись с микрофона в WAV с одновременной расшифровкой. Один поток читает микрофон и пишет файл,
 * второй расшифровывает: распознавание не задерживает запись, а при нехватке скорости отстаёт только текст.
 */
final class RealtimeRecorder {
    interface Callbacks {
        void onLevel(double db);
        void onText(String committed, String partial);
        /** Запись или расшифровка остановились из-за ошибки. */
        void onFailure(String message);
    }

    private static final int RATE = RealtimeTranscriber.RATE;
    private static final float[] END = new float[0];

    private final AudioRecord record;
    private final WavWriter wav;
    private final RealtimeTranscriber asr;
    private final Callbacks callbacks;
    private final LinkedBlockingQueue<float[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean failed;
    private Thread capture;
    private Thread recognize;

    private RealtimeRecorder(AudioRecord record, WavWriter wav, RealtimeTranscriber asr, Callbacks callbacks) {
        this.record = record;
        this.wav = wav;
        this.asr = asr;
        this.callbacks = callbacks;
    }

    static RealtimeRecorder start(File target, RealtimeModels.Files models, int threads, Callbacks callbacks)
            throws IOException {
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Микрофон не поддерживает запись 16 кГц");
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 4, RATE * 2));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IOException("Не удалось открыть микрофон");
        }
        WavWriter wav = new WavWriter(target, RATE);
        RealtimeTranscriber asr;
        try {
            final RealtimeRecorder[] self = new RealtimeRecorder[1];
            asr = RealtimeTranscriber.open(models, threads, (committed, partial) -> callbacks.onText(committed, partial));
            self[0] = new RealtimeRecorder(record, wav, asr, callbacks);
            self[0].begin();
            return self[0];
        } catch (RuntimeException | Error e) {
            record.release();
            wav.close();
            throw new IOException(e.getMessage() == null ? "Не удалось запустить модель расшифровки" : e.getMessage());
        }
    }

    private void begin() {
        record.startRecording();
        capture = new Thread(this::captureLoop, "realtime-capture");
        recognize = new Thread(this::recognizeLoop, "realtime-recognize");
        capture.setPriority(Thread.MAX_PRIORITY);
        capture.start();
        recognize.start();
    }

    void pause() { paused = true; }

    void resume() { paused = false; }

    private void captureLoop() {
        short[] buffer = new short[RATE / 10];
        boolean stopped = false;
        try {
            while (running) {
                if (paused) {
                    if (!stopped) {
                        record.stop();
                        stopped = true;
                    }
                    Thread.sleep(40);
                    continue;
                }
                if (stopped) {
                    record.startRecording();
                    stopped = false;
                }
                int n = record.read(buffer, 0, buffer.length);
                if (n < 0) throw new IOException("Ошибка чтения микрофона");
                if (n == 0) continue;
                wav.write(buffer, n);
                float[] chunk = new float[n];
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    chunk[i] = buffer[i] / 32768f;
                    int a = Math.abs((int) buffer[i]);
                    if (a > peak) peak = a;
                }
                queue.offer(chunk);
                callbacks.onLevel(peak <= 0 ? -60.0 : Math.max(-60.0, 20.0 * Math.log10(peak / 32768.0)));
            }
        } catch (Exception e) {
            fail(e.getMessage() == null ? "Ошибка записи" : e.getMessage());
        } finally {
            try { record.stop(); } catch (RuntimeException ignored) { }
            record.release();
            queue.offer(END);
        }
    }

    private void recognizeLoop() {
        try {
            while (true) {
                float[] chunk = queue.take();
                if (chunk == END) break;
                if (!failed) asr.accept(chunk);
            }
            asr.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            fail(e.getMessage() == null ? "Ошибка расшифровки" : e.getMessage());
        }
    }

    private void fail(String message) {
        if (failed) return;
        failed = true;
        running = false;
        callbacks.onFailure(message);
    }

    /** Останавливает запись, ждёт окончания расшифровки и возвращает реплики. Вызывать не из главного потока. */
    List<Segment> stop() throws IOException {
        running = false;
        try {
            capture.join(3000);
            recognize.join(120000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Segment> result = asr.segments();
        try {
            wav.close();
        } finally {
            asr.release();
        }
        return result;
    }
}
