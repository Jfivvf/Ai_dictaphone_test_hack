package com.example;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Потоковое декодирование: звук в любом формате превращается в моно 16 кГц, 16 бит и пишется во временный файл.
 * В памяти одновременно только небольшой буфер, поэтому длина записи ограничена местом на диске, а не кучей.
 * По дороге применяются фильтр против дребезга частот при понижении частоты и срез низких частот (гул, ветер).
 */
final class AudioDecoder {
    static final int TARGET_RATE = 16000;
    private static final double TARGET_RMS = 0.07;
    private static final double MAX_GAIN = 6.0;

    /** Декодированный звук на диске. Чтение кусками, без загрузки файла целиком. */
    static final class Pcm {
        final File file;
        final long samples;
        final int sampleRate;
        /** Общее усиление, выравнивающее тихие записи; применяется при чтении. */
        final float gain;

        Pcm(File file, long samples, int sampleRate, float gain) {
            this.file = file;
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.gain = gain;
        }

        long durationMs() {
            return samples * 1000L / sampleRate;
        }

        /** Отсчёты [from, to) как float с применённым усилением. */
        float[] read(long from, long to) throws IOException {
            long start = Math.max(0, from);
            long end = Math.min(samples, to);
            int count = (int) Math.max(0, end - start);
            float[] result = new float[count];
            if (count == 0) return result;
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                in.seek(start * 2);
                byte[] block = new byte[1 << 20];
                int done = 0;
                while (done < count) {
                    int n = Math.min(count - done, block.length / 2);
                    in.readFully(block, 0, n * 2);
                    for (int i = 0; i < n; i++) {
                        short s = (short) ((block[2 * i] & 0xFF) | (block[2 * i + 1] << 8));
                        float v = s / 32768f * gain;
                        result[done + i] = v > 1f ? 1f : (v < -1f ? -1f : v);
                    }
                    done += n;
                }
            }
            return result;
        }

        float[] readAll() throws IOException {
            return read(0, samples);
        }
    }

    private AudioDecoder() {}

    /** Длительность файла по метаданным; 0, если определить не удалось. */
    static long durationMs(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    /** Декодирует аудио из файла в {@code out} (сырой PCM 16 бит, 16 кГц, моно). */
    static Pcm decodeToPcm(String path, File out) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        Chain chain = null;
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0 || format == null) throw new IOException("В файле не найдена аудиодорожка");
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Обработка остановлена");
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0) {
                        MediaFormat outFormat = codec.getOutputFormat();
                        int rate = outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                ? outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                : format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int channels = Math.max(1, outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                ? outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                : format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        if (chain == null) chain = new Chain(stream, rate);
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        ByteBuffer pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int frames = info.size / 2 / channels;
                        for (int frame = 0; frame < frames; frame++) {
                            float sum = 0;
                            for (int ch = 0; ch < channels; ch++) sum += pcm.getShort() / 32768.0f;
                            chain.push(sum / channels);
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
            if (chain == null) throw new IOException("Аудиодорожка пуста");
            chain.flush();
            stream.flush();
            if (chain.count == 0) throw new IOException("Аудиодорожка пуста");
            return new Pcm(out, chain.count, TARGET_RATE, chain.gain());
        } catch (RuntimeException e) {
            throw new IOException("Не удалось декодировать аудиофайл: " + e.getMessage(), e);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }

    /**
     * Цепочка обработки одного отсчёта: фильтр нижних частот (при понижении частоты) → линейная
     * передискретизация до 16 кГц → срез ниже 70 Гц → статистика громкости → запись 16 бит.
     */
    private static final class Chain {
        private static final int TAPS = 33;

        private final OutputStream out;
        private final byte[] buffer = new byte[1 << 16];
        private int length = 0;

        private final float[] taps;
        private final float[] ring;
        private int ringPos = 0;

        private final double step;
        private double next = 0;
        private long consumed = 0;
        private float previous;

        private final float highPass;
        private float hpIn = 0;
        private float hpOut = 0;

        private double sumSquares = 0;
        private float peak = 0;
        long count = 0;

        Chain(OutputStream out, int fromRate) {
            this.out = out;
            this.step = (double) fromRate / TARGET_RATE;
            this.highPass = (float) (1.0 / (1.0 + 2.0 * Math.PI * 70.0 / TARGET_RATE));
            if (fromRate > TARGET_RATE) {
                double cutoff = 0.45 * TARGET_RATE / fromRate;   // доля от частоты исходного звука
                taps = new float[TAPS];
                ring = new float[TAPS];
                int middle = (TAPS - 1) / 2;
                double total = 0;
                for (int n = 0; n < TAPS; n++) {
                    double x = n - middle;
                    double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
                    double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * n / (TAPS - 1));
                    taps[n] = (float) (sinc * window);
                    total += taps[n];
                }
                for (int n = 0; n < TAPS; n++) taps[n] /= (float) total;
            } else {
                taps = null;
                ring = null;
            }
        }

        void push(float value) throws IOException {
            float filtered = value;
            if (taps != null) {
                ring[ringPos] = value;
                float acc = 0;
                int p = ringPos;
                for (int k = 0; k < TAPS; k++) {
                    acc += taps[k] * ring[p];
                    if (--p < 0) p = TAPS - 1;
                }
                ringPos = (ringPos + 1) % TAPS;
                filtered = acc;
            }
            long index = consumed++;
            if (index == 0) {
                previous = filtered;
                return;
            }
            while (next < index) {
                float fraction = (float) (next - (index - 1));
                emit(previous + (filtered - previous) * fraction);
                next += step;
            }
            previous = filtered;
        }

        private void emit(float value) throws IOException {
            float y = highPass * (hpOut + value - hpIn);
            hpIn = value;
            hpOut = y;
            sumSquares += (double) y * y;
            float abs = Math.abs(y);
            if (abs > peak) peak = abs;
            count++;
            int v = Math.round(y * 32767f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            if (length + 2 > buffer.length) flushBuffer();
            buffer[length++] = (byte) v;
            buffer[length++] = (byte) (v >> 8);
        }

        private void flushBuffer() throws IOException {
            out.write(buffer, 0, length);
            length = 0;
        }

        void flush() throws IOException {
            flushBuffer();
        }

        float gain() {
            if (count == 0) return 1f;
            double rms = Math.sqrt(sumSquares / count);
            if (rms < 1e-6) return 1f;
            double gain = Math.min(MAX_GAIN, Math.max(1.0, TARGET_RMS / rms));
            if (peak > 0) gain = Math.min(gain, 0.98 / peak);
            return (float) Math.max(1.0, gain);
        }
    }
}
