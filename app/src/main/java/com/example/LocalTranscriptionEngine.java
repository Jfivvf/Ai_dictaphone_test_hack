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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class LocalTranscriptionEngine {
    interface Progress {
        /** fraction — доля этапа 0..1 или -1, если неизвестна; detail — например «реплика 12 из 47». */
        void onProgress(ProgressTracker.Stage stage, float fraction, String detail);
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
        int speaker;

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
    private static final long MIN_TURN_MS = 400;
    private static final int CONTEXT = (int) (0.15 * RATE);
    /** Ниже этой громкости (RMS после выравнивания) кусок считается тишиной и не расшифровывается. */
    private static final float SILENCE_RMS = 0.004f;

    private LocalTranscriptionEngine() {}

    /** speakerCount: 0 — определить автоматически, иначе точное число говорящих. */
    static Result process(AudioDecoder.Pcm pcm, File encoder, File decoder, File tokens,
                          File segmentation, File embedding, String language, int threads,
                          int speakerCount, Progress progress) throws Exception {
        final long totalMs = pcm.durationMs();

        progress.onProgress(ProgressTracker.Stage.DIARIZE, -1f, "");
        List<Turn> turns = null;
        String diarizationError = null;
        try {
            turns = diarize(pcm, segmentation, embedding, threads, speakerCount);
        } catch (OutOfMemoryError e) {
            diarizationError = "не хватило памяти для разделения говорящих";
        } catch (Exception e) {
            diarizationError = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (turns == null || turns.isEmpty()) {
            if (diarizationError == null) diarizationError = "Говорящие не обнаружены";
            turns = new ArrayList<>();
            turns.add(new Turn(0, totalMs, -1));
        } else if (speakerCount <= 0) {
            dropPhantomSpeakers(turns);
        }

        List<int[]> chunks = new ArrayList<>();           // {turnIndex, fromSample, toSample}
        for (int t = 0; t < turns.size(); t++) {
            Turn turn = turns.get(t);
            long from = Math.max(0, Math.min(pcm.samples, turn.startMs * RATE / 1000));
            long to = Math.max(from, Math.min(pcm.samples, turn.endMs * RATE / 1000));
            long position = from;
            while (to - position > MAX_CHUNK) {
                long split = quietestPoint(pcm, position + MIN_SPLIT, position + MAX_CHUNK);
                chunks.add(new int[]{t, (int) position, (int) split});
                position = split;
            }
            if (to > position) chunks.add(new int[]{t, (int) position, (int) to});
        }

        long totalSpeech = 0;
        for (int[] chunk : chunks) totalSpeech += chunk[2] - chunk[1];
        long doneSpeech = 0;

        List<Segment> result = new ArrayList<>();
        OfflineRecognizer recognizer = createRecognizer(encoder, decoder, tokens, language, threads);
        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Обработка остановлена");
                int[] chunk = chunks.get(i);
                progress.onProgress(ProgressTracker.Stage.RECOGNIZE,
                        totalSpeech > 0 ? doneSpeech / (float) totalSpeech : 0f,
                        "реплика " + (i + 1) + " из " + chunks.size());
                doneSpeech += chunk[2] - chunk[1];

                float[] slice = pcm.read(chunk[1] - CONTEXT, chunk[2] + CONTEXT);
                if (rms(slice) < SILENCE_RMS) continue;
                long durationMs = (chunk[2] - chunk[1]) * 1000L / RATE;
                String text = HallucinationFilter.clean(recognize(recognizer, slice), durationMs, rms(slice));
                if (text.isEmpty()) continue;
                result.add(Segment.of(chunk[1] * 1000L / RATE, chunk[2] * 1000L / RATE,
                        turns.get(chunk[0]).speaker, text));
            }
            progress.onProgress(ProgressTracker.Stage.RECOGNIZE, 1f,
                    "реплика " + chunks.size() + " из " + chunks.size());
        } finally {
            recognizer.release();
        }
        if (result.isEmpty()) throw new IllegalStateException("Речь в записи не распознана");
        return new Result(result, diarizationError);
    }

    private static float rms(float[] data) {
        if (data.length == 0) return 0;
        double sum = 0;
        for (float v : data) sum += (double) v * v;
        return (float) Math.sqrt(sum / data.length);
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

    private static List<Turn> diarize(AudioDecoder.Pcm pcm, File segmentation, File embedding,
                                      int threads, int speakerCount) throws java.io.IOException {
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
            clustering.setNumClusters(speakerCount > 0 ? speakerCount : -1);
            clustering.setThreshold(0.5f);
            clustering.setComputeConfidence(true);

            OfflineSpeakerDiarizationConfig config = new OfflineSpeakerDiarizationConfig();
            config.setSegmentation(segmentationConfig);
            config.setEmbedding(embeddingConfig);
            config.setClustering(clustering);
            config.setMinDurationOn(0.3f);
            config.setMinDurationOff(0.5f);

            diarizer = new OfflineSpeakerDiarization(null, config);
            if (diarizer.sampleRate() != pcm.sampleRate) {
                throw new IllegalStateException("Модель диаризации требует " + diarizer.sampleRate()
                        + " Гц, декодировано " + pcm.sampleRate + " Гц");
            }
            // Единственное место, где звук загружается в память целиком; массив освобождается сразу после.
            float[] samples = pcm.readAll();
            OfflineSpeakerDiarizationSegment[] raw = diarizer.process(samples);
            samples = null;
            return mergeTurns(raw);
        } finally {
            if (diarizer != null) diarizer.release();
        }
    }

    /**
     * Расставляет говорящих в уже готовой расшифровке (режим Real-life): текст получен во время записи,
     * здесь по звуку определяется, кто говорил. Реплика получает того, чьи речевые отрезки перекрывают её
     * сильнее всего; соседние реплики одного человека склеиваются в одну.
     */
    static Result assignSpeakers(AudioDecoder.Pcm pcm, File segmentation, File embedding, int threads,
                                 int speakerCount, List<Segment> live, Progress progress) {
        progress.onProgress(ProgressTracker.Stage.DIARIZE, -1f, "");
        List<Turn> turns = null;
        String error = null;
        try {
            turns = diarize(pcm, segmentation, embedding, threads, speakerCount);
        } catch (OutOfMemoryError e) {
            error = "не хватило памяти для разделения говорящих";
        } catch (Exception e) {
            error = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (turns == null || turns.isEmpty()) {
            return new Result(live, error == null ? "Говорящие не обнаружены" : error);
        }
        if (speakerCount <= 0) dropPhantomSpeakers(turns);

        List<Segment> labelled = new ArrayList<>();
        int previous = turns.get(0).speaker;
        for (Segment segment : live) {
            int speaker = speakerFor(turns, segment.startMs, segment.endMs, previous);
            previous = speaker;
            labelled.add(Segment.of(segment.startMs, segment.endMs, speaker, segment.text));
        }
        return new Result(mergeSameSpeaker(labelled), null);
    }

    private static int speakerFor(List<Turn> turns, long startMs, long endMs, int fallback) {
        java.util.Map<Integer, Long> overlap = new java.util.HashMap<>();
        int nearest = fallback;
        long nearestGap = Long.MAX_VALUE;
        for (Turn t : turns) {
            if (t.speaker < 0) continue;
            long common = Math.min(endMs, t.endMs) - Math.max(startMs, t.startMs);
            if (common > 0) {
                Long old = overlap.get(t.speaker);
                overlap.put(t.speaker, (old == null ? 0 : old) + common);
            } else {
                long gap = Math.max(t.startMs - endMs, startMs - t.endMs);
                if (gap < nearestGap) {
                    nearestGap = gap;
                    nearest = t.speaker;
                }
            }
        }
        int best = -1;
        long bestOverlap = 0;
        for (java.util.Map.Entry<Integer, Long> e : overlap.entrySet()) {
            if (e.getValue() > bestOverlap) {
                bestOverlap = e.getValue();
                best = e.getKey();
            }
        }
        if (best >= 0) return best;
        return nearestGap <= 1500 ? nearest : fallback;
    }

    /** Склеивает подряд идущие реплики одного говорящего, если пауза между ними короткая. */
    private static List<Segment> mergeSameSpeaker(List<Segment> segments) {
        final long maxGap = 1500;
        final long maxLength = 45000;
        List<Segment> result = new ArrayList<>();
        for (Segment s : segments) {
            Segment last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && last.speaker == s.speaker && s.startMs - last.endMs <= maxGap
                    && s.endMs - last.startMs <= maxLength) {
                result.set(result.size() - 1,
                        Segment.of(last.startMs, Math.max(last.endMs, s.endMs), s.speaker, last.text + " " + s.text));
            } else {
                result.add(s);
            }
        }
        return result;
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

    /**
     * Самая тихая точка в окне: режем длинную реплику между словами, а не посреди слова.
     * Окно читается с диска отдельно, поэтому память не растёт.
     */
    private static long quietestPoint(AudioDecoder.Pcm pcm, long from, long to) throws java.io.IOException {
        final int frame = RATE / 20; // 50 мс
        long end = Math.min(to, pcm.samples);
        if (end - from < frame) return end;
        float[] window = pcm.read(from, end);
        int best = window.length;
        float bestEnergy = Float.MAX_VALUE;
        for (int start = 0; start + frame <= window.length; start += frame) {
            float energy = 0;
            for (int i = start; i < start + frame; i++) energy += Math.abs(window[i]);
            if (energy < bestEnergy) {
                bestEnergy = energy;
                best = start + frame / 2;
            }
        }
        return from + best;
    }

    /**
     * Убирает «призрачных» говорящих: шумовые всплески кластеризация иногда считает отдельным человеком.
     * Говорящий с совсем малым временем речи передаёт свои реплики ближайшему соседу, остальные нумеруются
     * заново в порядке появления. Порог мягкий, чтобы на встречах с 6–8 людьми не терять тех, кто говорил мало.
     */
    private static void dropPhantomSpeakers(List<Turn> turns) {
        java.util.Map<Integer, Long> spoken = new java.util.HashMap<>();
        long total = 0;
        for (Turn t : turns) {
            long d = t.endMs - t.startMs;
            total += d;
            Long old = spoken.get(t.speaker);
            spoken.put(t.speaker, (old == null ? 0 : old) + d);
        }
        long threshold = Math.max(4000, Math.round(total * 0.015));
        java.util.Set<Integer> tiny = new java.util.HashSet<>();
        for (java.util.Map.Entry<Integer, Long> e : spoken.entrySet()) {
            if (e.getValue() < threshold) tiny.add(e.getKey());
        }
        if (!tiny.isEmpty() && tiny.size() < spoken.size()) {
            for (int i = 0; i < turns.size(); i++) {
                Turn t = turns.get(i);
                if (!tiny.contains(t.speaker)) continue;
                int before = -1;
                int after = -1;
                for (int k = i - 1; k >= 0; k--) {
                    if (!tiny.contains(turns.get(k).speaker)) { before = k; break; }
                }
                for (int k = i + 1; k < turns.size(); k++) {
                    if (!tiny.contains(turns.get(k).speaker)) { after = k; break; }
                }
                long gapBefore = before < 0 ? Long.MAX_VALUE : t.startMs - turns.get(before).endMs;
                long gapAfter = after < 0 ? Long.MAX_VALUE : turns.get(after).startMs - t.endMs;
                int pick = gapBefore <= gapAfter ? before : after;
                if (pick >= 0) t.speaker = turns.get(pick).speaker;
            }
        }
        java.util.Map<Integer, Integer> renumber = new java.util.HashMap<>();
        for (Turn t : turns) {
            if (t.speaker < 0) continue;
            Integer mapped = renumber.get(t.speaker);
            if (mapped == null) {
                mapped = renumber.size();
                renumber.put(t.speaker, mapped);
            }
            t.speaker = mapped;
        }
    }
}
