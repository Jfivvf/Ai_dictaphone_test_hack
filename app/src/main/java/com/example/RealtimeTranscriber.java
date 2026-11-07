package com.example;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Расшифровка во время записи. Звук подаётся кусками по ~100 мс; после каждого куска доступен
 * текст, который говорят прямо сейчас (partial), а законченные фразы накапливаются в списке реплик.
 *
 * Есть два движка, выбор по файлам модели:
 *  - потоковая модель (encoder с параметром decode_chunk_len): OnlineRecognizer, текст появляется
 *    с задержкой порядка 0,3–0,6 с, фраза закрывается по паузе;
 *  - непотоковая модель: Silero VAD режет речь на фразы, каждая фраза расшифровывается целиком,
 *    а пока человек говорит, последние секунды пересчитываются для предварительного текста.
 *
 * Все методы вызываются из одного потока.
 */
abstract class RealtimeTranscriber {
    interface Listener {
        /** committed — законченные фразы, partial — то, что говорится сейчас. */
        void onText(String committed, String partial);
    }

    static final int RATE = 16000;

    private final Listener listener;
    private final List<Segment> segments = new ArrayList<>();
    private final StringBuilder committed = new StringBuilder();

    RealtimeTranscriber(Listener listener) {
        this.listener = listener;
    }

    /** Подаёт очередной кусок звука: 16 кГц, моно, значения -1..1. */
    abstract void accept(float[] samples);

    /** Дообрабатывает хвост. Вызывается один раз после последнего accept. */
    abstract void flush();

    abstract void release();

    List<Segment> segments() {
        return segments;
    }

    static RealtimeTranscriber open(RealtimeModels.Files files, int threads, Listener listener) {
        return files.streaming ? new Streaming(files, threads, listener) : new VadOffline(files, threads, listener);
    }

    // ---- общее ------------------------------------------------------------------------------

    final void commit(long startMs, long endMs, String raw) {
        String text = polish(raw);
        if (text.isEmpty()) return;
        segments.add(Segment.of(startMs, Math.max(endMs, startMs + 300), -1, text));
        if (committed.length() > 0) committed.append(' ');
        committed.append(text);
        listener.onText(committed.toString(), "");
    }

    final void partial(String raw) {
        listener.onText(committed.toString(), polish(raw));
    }

    /** Приводит текст модели к читаемому виду: модели выдают строчные или целиком заглавные буквы без знаков. */
    static String polish(String raw) {
        if (raw == null) return "";
        String text = raw.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) return "";
        boolean allUpper = text.equals(text.toUpperCase(Locale.ROOT)) && !text.equals(text.toLowerCase(Locale.ROOT));
        if (allUpper) text = text.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---- потоковая модель ---------------------------------------------------------------------

    private static final class Streaming extends RealtimeTranscriber {
        private final OnlineRecognizer recognizer;
        private OnlineStream stream;
        private long total;
        /** Отсчёт, с которого начался текущий поток (после последнего сброса). */
        private long streamStart;

        Streaming(RealtimeModels.Files files, int threads, Listener listener) {
            super(listener);
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder(files.encoder.getAbsolutePath());
            transducer.setDecoder(files.decoder.getAbsolutePath());
            transducer.setJoiner(files.joiner.getAbsolutePath());

            OnlineModelConfig model = new OnlineModelConfig();
            model.setTransducer(transducer);
            model.setTokens(files.tokens.getAbsolutePath());
            model.setNumThreads(Math.max(1, threads));
            model.setProvider("cpu");
            model.setDebug(false);

            // Фраза закрывается после 0,6 с тишины (если что-то было сказано) или принудительно через 12 с.
            EndpointConfig endpoint = new EndpointConfig();
            endpoint.setRule1(new EndpointRule(false, 2.0f, 0.0f));
            endpoint.setRule2(new EndpointRule(true, 0.6f, 0.0f));
            endpoint.setRule3(new EndpointRule(false, 0.0f, 12.0f));

            OnlineRecognizerConfig config = new OnlineRecognizerConfig();
            config.setModelConfig(model);
            config.setEndpointConfig(endpoint);
            config.setEnableEndpoint(true);
            config.setDecodingMethod("greedy_search");

            recognizer = new OnlineRecognizer(null, config);
            stream = recognizer.createStream("");
        }

        @Override void accept(float[] samples) {
            stream.acceptWaveform(samples, RATE);
            total += samples.length;
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            OnlineRecognizerResult result = recognizer.getResult(stream);
            String text = result.getText() == null ? "" : result.getText().trim();
            if (recognizer.isEndpoint(stream)) {
                finishPhrase(result, text);
            } else if (!text.isEmpty()) {
                partial(text);
            }
        }

        private void finishPhrase(OnlineRecognizerResult result, String text) {
            if (!text.isEmpty()) {
                float[] times = result.getTimestamps();
                long base = streamStart * 1000L / RATE;
                long start = base;
                long end = total * 1000L / RATE;
                if (times != null && times.length > 0) {
                    start = base + Math.round(times[0] * 1000);
                    end = Math.min(end, base + Math.round(times[times.length - 1] * 1000) + 400);
                }
                commit(start, end, text);
            }
            recognizer.reset(stream);
            streamStart = total;
        }

        @Override void flush() {
            // Хвост тишины, чтобы модель выдала последнее слово.
            stream.acceptWaveform(new float[RATE / 2], RATE);
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            OnlineRecognizerResult result = recognizer.getResult(stream);
            String text = result.getText() == null ? "" : result.getText().trim();
            finishPhrase(result, text);
        }

        @Override void release() {
            try { stream.release(); } catch (RuntimeException ignored) { }
            recognizer.release();
        }
    }

    // ---- непотоковая модель + VAD -------------------------------------------------------------

    private static final class VadOffline extends RealtimeTranscriber {
        private static final int TAIL = 6 * RATE;
        private static final long PARTIAL_EVERY_MS = 700;

        private final Vad vad;
        private final OfflineRecognizer recognizer;
        private final float[] tail = new float[TAIL];
        private int tailLength;
        private long total;
        private boolean inSpeech;
        private long speechStart;
        private long lastPartialAt;

        VadOffline(RealtimeModels.Files files, int threads, Listener listener) {
            super(listener);
            SileroVadModelConfig silero = new SileroVadModelConfig();
            silero.setModel(files.vad.getAbsolutePath());
            silero.setThreshold(0.5f);
            silero.setMinSilenceDuration(0.45f);
            silero.setMinSpeechDuration(0.25f);
            silero.setWindowSize(512);
            silero.setMaxSpeechDuration(8.0f);
            VadModelConfig vadConfig = new VadModelConfig();
            vadConfig.setSileroVadModelConfig(silero);
            vadConfig.setSampleRate(RATE);
            vadConfig.setNumThreads(1);
            vadConfig.setProvider("cpu");
            vadConfig.setDebug(false);
            vad = new Vad(null, vadConfig);

            OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
            transducer.setEncoder(files.encoder.getAbsolutePath());
            transducer.setDecoder(files.decoder.getAbsolutePath());
            transducer.setJoiner(files.joiner.getAbsolutePath());
            OfflineModelConfig model = new OfflineModelConfig();
            model.setTransducer(transducer);
            model.setTokens(files.tokens.getAbsolutePath());
            model.setNumThreads(Math.max(1, threads));
            model.setProvider("cpu");
            model.setDebug(false);
            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(model);
            config.setDecodingMethod("greedy_search");
            recognizer = new OfflineRecognizer(null, config);
        }

        @Override void accept(float[] samples) {
            total += samples.length;
            remember(samples);
            vad.acceptWaveform(samples);
            boolean speaking = vad.isSpeechDetected();
            if (speaking && !inSpeech) {
                inSpeech = true;
                speechStart = total - samples.length;
                lastPartialAt = 0;
            }
            drain();
            if (!vad.isSpeechDetected()) {
                inSpeech = false;
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastPartialAt >= PARTIAL_EVERY_MS) {
                int count = (int) Math.min(tailLength, total - speechStart);
                if (count >= RATE / 2) {
                    float[] part = new float[count];
                    System.arraycopy(tail, tailLength - count, part, 0, count);
                    partial(recognize(part));
                }
                lastPartialAt = System.currentTimeMillis();
            }
        }

        private void remember(float[] samples) {
            int n = Math.min(samples.length, TAIL);
            if (tailLength + n > TAIL) {
                int keep = TAIL - n;
                System.arraycopy(tail, tailLength - keep, tail, 0, keep);
                tailLength = keep;
            }
            System.arraycopy(samples, samples.length - n, tail, tailLength, n);
            tailLength += n;
        }

        private void drain() {
            while (!vad.empty()) {
                SpeechSegment segment = vad.front();
                float[] audio = segment.getSamples();
                long start = segment.getStart() * 1000L / RATE;
                long end = start + audio.length * 1000L / RATE;
                vad.pop();
                commit(start, end, recognize(audio));
                inSpeech = false;
            }
        }

        private String recognize(float[] audio) {
            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(audio, RATE);
                recognizer.decode(stream);
                String text = recognizer.getResult(stream).getText();
                return text == null ? "" : text.trim();
            } finally {
                stream.release();
            }
        }

        @Override void flush() {
            vad.flush();
            drain();
        }

        @Override void release() {
            vad.release();
            recognizer.release();
        }
    }
}
