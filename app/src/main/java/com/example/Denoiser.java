package com.example;

import com.k2fsa.sherpa.onnx.DenoisedAudio;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;


final class Denoiser {
    interface Progress {
        void onFraction(float fraction);
    }

    private static final int RATE = AudioDecoder.TARGET_RATE;
    private static final int BLOCK = 20 * RATE;
    private static final int CONTEXT = RATE / 4;
    private static final double TARGET_RMS = 0.07;
    private static final double MAX_GAIN = 6.0;

    private Denoiser() {}

    static AudioDecoder.Pcm apply(AudioDecoder.Pcm in, File model, File out, int threads,
                                  Progress progress) throws IOException {
        OfflineSpeechDenoiserGtcrnModelConfig gtcrn = new OfflineSpeechDenoiserGtcrnModelConfig();
        gtcrn.setModel(model.getAbsolutePath());
        OfflineSpeechDenoiserModelConfig modelConfig = new OfflineSpeechDenoiserModelConfig();
        modelConfig.setGtcrn(gtcrn);
        modelConfig.setNumThreads(Math.max(1, threads));
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");
        OfflineSpeechDenoiserConfig config = new OfflineSpeechDenoiserConfig();
        config.setModel(modelConfig);

        OfflineSpeechDenoiser denoiser = new OfflineSpeechDenoiser(null, config);
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            if (denoiser.getSampleRate() != RATE) {
                throw new IOException("Модель шумоподавления требует " + denoiser.getSampleRate()
                        + " Гц, звук " + RATE + " Гц");
            }
            final long total = in.samples;
            double sumSquares = 0;
            float peak = 0;
            long count = 0;
            byte[] buffer = new byte[BLOCK * 2];

            for (long position = 0; position < total; position += BLOCK) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Обработка остановлена");
                long end = Math.min(total, position + BLOCK);
                long from = Math.max(0, position - CONTEXT);
                long to = Math.min(total, end + CONTEXT);
                float[] source = in.read(from, to);
                DenoisedAudio result = denoiser.run(source, RATE);
                float[] cleaned = result.getSamples();

                int lead = (int) (position - from);
                int length = (int) (end - position);
                for (int i = 0; i < length; i++) {
                    int k = lead + i;
                    float v = k < cleaned.length ? cleaned[k] : source[k];
                    if (v > 1f) v = 1f;
                    if (v < -1f) v = -1f;
                    sumSquares += (double) v * v;
                    float abs = Math.abs(v);
                    if (abs > peak) peak = abs;
                    int s = Math.round(v * 32767f);
                    buffer[2 * i] = (byte) s;
                    buffer[2 * i + 1] = (byte) (s >> 8);
                }
                stream.write(buffer, 0, length * 2);
                count += length;
                progress.onFraction(end / (float) total);
            }
            stream.flush();
            if (count == 0) throw new IOException("Аудиодорожка пуста");

            double gain = 1.0;
            double rms = Math.sqrt(sumSquares / count);
            if (rms >= 1e-6) {
                gain = Math.min(MAX_GAIN, Math.max(1.0, TARGET_RMS / rms));
                if (peak > 0) gain = Math.min(gain, 0.98 / peak);
                gain = Math.max(1.0, gain);
            }
            return new AudioDecoder.Pcm(out, count, RATE, (float) gain);
        } finally {
            denoiser.release();
        }
    }
}
