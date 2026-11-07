package com.example;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Локальный конвейер: сначала диаризация по всему файлу, затем расшифровка каждой реплики отдельно.
 *
 * Whisper в Sherpa-ONNX обрабатывает не больше 30 секунд звука за один проход и молча отбрасывает остальное,
 * поэтому весь файл целиком в него подавать нельзя. Расшифровка по репликам снимает это ограничение,
 * а граница каждой реплики берётся из диаризации, так что таймкоды и говорящие сходятся точно.
 * Текст не обрабатывается: слова-паразиты и повторы остаются как их распознала модель.
 */
final class LocalTranscriptionEngine {
    interface Progress {
        void onStep(String stage, int done, int total);
    }

    static final class Result {
        final List<Segment> segments;
        /** null, если говорящие определены; иначе причина, по которой реплики не разделены. */
        final String diarizationError;

        Result(List<Segment> segments, String diarizationError) {
            this.segments = segments;
            this.diarizationError = diarizationError;
        }
    }

    private static final class Turn {
        long startMs;
        long endMs;
        final int speaker;

        Turn(long startMs, long endMs, int speaker) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.speaker = speaker;
        }
    }

    private static final int RATE = AudioDecoder.TARGET_RATE;
    /** Запас под лимит Whisper в 30 с. */
    private static final int MAX_CHUNK = 25 * RATE;
    private static final int MIN_SPLIT = 15 * RATE;
    private static final long MERGE_GAP_MS = 800;
    private static final long MIN_TURN_MS = 300;
    private static final int CONTEXT = (int) (0.15 * RATE);

    private LocalTranscriptionEngine() {}

    static Result process(AudioDecoder.AudioData audio, File encoder, File decoder, File tokens,
                          File segmentation, File embedding, String language, int threads,
                          Progress progress) throws Exception {
        final float[] samples = audio.samples;
        final long totalMs = samples.length * 1000L / audio.sampleRate;

        progress.onStep("Определение говорящих", 0, 0);
        List<Turn> turns = null;
        String diarizationError = null;
        try {
            turns = diarize(audio, segmentation, embedding, threads);
        } catch (Exception e) {
            diarizationError = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (turns == null || turns.isEmpty()) {
            if (diarizationError == null) diarizationError = "Говорящие не обнаружены";
            turns = new ArrayList<>();
            turns.add(new Turn(0, totalMs, -1));
        }

        List<int[]> chunks = new ArrayList<>();           // {turnIndex, fromSample, toSample}
        for (int t = 0; t < turns.size(); t++) {
            Turn turn = turns.get(t);
            int from = (int) Math.max(0, Math.min(samples.length, turn.startMs * RATE / 1000));
            int to = (int) Math.max(from, Math.min(samples.length, turn.endMs * RATE / 1000));
            int position = from;
            while (to - position > MAX_CHUNK) {
                int split = quietestPoint(samples, position + MIN_SPLIT, position + MAX_CHUNK);
                chunks.add(new int[]{t, position, split});
                position = split;
            }
            if (to > position) chunks.add(new int[]{t, position, to});
        }

        List<Segment> result = new ArrayList<>();
        OfflineRecognizer recognizer = createRecognizer(encoder, decoder, tokens, language, threads);
        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Обработка остановлена");
                int[] chunk = chunks.get(i);
                int from = Math.max(0, chunk[1] - CONTEXT);
                int to = Math.min(samples.length, chunk[2] + CONTEXT);
                String text = recognize(recognizer, Arrays.copyOfRange(samples, from, to));
                progress.onStep("Расшифровка", i + 1, chunks.size());
                if (text.isEmpty()) continue;
                result.add(Segment.of(chunk[1] * 1000L / RATE, chunk[2] * 1000L / RATE,
                        turns.get(chunk[0]).speaker, text));
            }
        } finally {
            recognizer.release();
        }
        if (result.isEmpty()) throw new IllegalStateException("Речь в записи не распознана");
        return new Result(result, diarizationError);
    }

    private static OfflineRecognizer createRecognizer(File encoder, File decoder, File tokens,
                                                      String language, int threads) {
        // Android Sherpa-ONNX AAR 2.0.1 отдаёт Kotlin data-классы: используем конструкторы без аргументов и сеттеры.
        OfflineWhisperModelConfig whisper = new OfflineWhisperModelConfig();
        whisper.setEncoder(encoder.getAbsolutePath());
        whisper.setDecoder(decoder.getAbsolutePath());
        whisper.setLanguage(language == null ? "" : language);
        whisper.setTask("transcribe");

        OfflineModelConfig model = new OfflineModelConfig();
        model.setWhisper(whisper);
        model.setTokens(tokens.getAbsolutePath());
        model.setNumThreads(Math.max(1, threads));
        model.setDebug(false);
        model.setProvider("cpu");

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        return new OfflineRecognizer(null, config);
    }

    private static String recognize(OfflineRecognizer recognizer, float[] slice) {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(slice, RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            String text = result.getText();
            return text == null ? "" : text.trim();
        } finally {
            stream.release();
        }
    }

    private static List<Turn> diarize(AudioDecoder.AudioData audio, File segmentation, File embedding,
                                      int threads) {
        OfflineSpeakerDiarization diarizer = null;
        try {
            OfflineSpeakerSegmentationPyannoteModelConfig pyannote =
                    new OfflineSpeakerSegmentationPyannoteModelConfig();
            pyannote.setModel(segmentation.getAbsolutePath());
            pyannote.setWindowShiftRatio(0.1f);
            OfflineSpeakerSegmentationModelConfig segmentationConfig =
                    new OfflineSpeakerSegmentationModelConfig();
            segmentationConfig.setPyannote(pyannote);
            segmentationConfig.setNumThreads(Math.max(1, threads));
            segmentationConfig.setProvider("cpu");
            segmentationConfig.setDebug(false);

            SpeakerEmbeddingExtractorConfig embeddingConfig = new SpeakerEmbeddingExtractorConfig(
                    embedding.getAbsolutePath(), Math.max(1, threads), false, "cpu");

            FastClusteringConfig clustering = new FastClusteringConfig();
            clustering.setNumClusters(-1);
            clustering.setThreshold(0.5f);
            clustering.setComputeConfidence(true);

            OfflineSpeakerDiarizationConfig config = new OfflineSpeakerDiarizationConfig();
            config.setSegmentation(segmentationConfig);
            config.setEmbedding(embeddingConfig);
            config.setClustering(clustering);
            config.setMinDurationOn(0.2f);
            config.setMinDurationOff(0.5f);

            diarizer = new OfflineSpeakerDiarization(null, config);
            if (diarizer.sampleRate() != audio.sampleRate) {
                throw new IllegalStateException("Модель диаризации требует " + diarizer.sampleRate()
                        + " Гц, декодировано " + audio.sampleRate + " Гц");
            }
            OfflineSpeakerDiarizationSegment[] raw = diarizer.process(audio.samples);
            return mergeTurns(raw);
        } finally {
            if (diarizer != null) diarizer.release();
        }
    }

    /** Склеивает соседние куски одного говорящего с короткой паузой и отбрасывает совсем короткие. */
    private static List<Turn> mergeTurns(OfflineSpeakerDiarizationSegment[] raw) {
        List<Turn> sorted = new ArrayList<>();
        if (raw == null) return sorted;
        for (OfflineSpeakerDiarizationSegment s : raw) {
            sorted.add(new Turn(Math.round(s.getStart() * 1000), Math.round(s.getEnd() * 1000), s.getSpeaker()));
        }
        Collections.sort(sorted, new Comparator<Turn>() {
            @Override public int compare(Turn a, Turn b) { return Long.compare(a.startMs, b.startMs); }
        });
        List<Turn> merged = new ArrayList<>();
        for (Turn turn : sorted) {
            Turn last = null;
            for (int i = merged.size() - 1; i >= 0; i--) {
                if (merged.get(i).speaker == turn.speaker) { last = merged.get(i); break; }
            }
            boolean directlyAfter = !merged.isEmpty() && merged.get(merged.size() - 1) == last;
            if (last != null && directlyAfter && turn.startMs - last.endMs <= MERGE_GAP_MS) {
                last.endMs = Math.max(last.endMs, turn.endMs);
            } else {
                merged.add(turn);
            }
        }
        List<Turn> result = new ArrayList<>();
        for (Turn turn : merged) {
            if (turn.endMs - turn.startMs >= MIN_TURN_MS) result.add(turn);
        }
        return result;
    }

    /** Самая тихая точка в окне: режем длинную реплику между словами, а не посреди слова. */
    private static int quietestPoint(float[] samples, int from, int to) {
        final int frame = RATE / 20; // 50 мс
        int end = Math.min(to, samples.length);
        int best = end;
        float bestEnergy = Float.MAX_VALUE;
        for (int start = from; start + frame <= end; start += frame) {
            float energy = 0;
            for (int i = start; i < start + frame; i++) energy += Math.abs(samples[i]);
            if (energy < bestEnergy) {
                bestEnergy = energy;
                best = start + frame / 2;
            }
        }
        return best;
    }
}
