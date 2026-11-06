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
import java.util.List;
import java.util.Locale;

final class LocalTranscriptionEngine {
    static final class Segment {
        final long startMs;
        final long endMs;
        final int speaker;
        final float confidence;
        final String text;
        Segment(long startMs, long endMs, int speaker, float confidence, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.speaker = speaker;
            this.confidence = confidence;
            this.text = text;
        }
    }

    static final class Result {
        final String transcript;
        final List<Segment> segments;
        Result(String transcript, List<Segment> segments) {
            this.transcript = transcript;
            this.segments = segments;
        }
    }

    static Result process(AudioDecoder.AudioData audio, File encoder, File decoder, File tokens,
                          File segmentation, File embedding, String language, int threads) {
        OfflineRecognizer recognizer = null;
        OfflineStream stream = null;
        OfflineSpeakerDiarization diarizer = null;
        try {
            OfflineWhisperModelConfig whisper = OfflineWhisperModelConfig.builder()
                    .setEncoder(encoder.getAbsolutePath())
                    .setDecoder(decoder.getAbsolutePath())
                    .setLanguage(language)
                    .setTask("transcribe")
                    .setEnableTokenTimestamps(true)
                    .setEnableSegmentTimestamps(true)
                    .build();
            OfflineModelConfig model = OfflineModelConfig.builder()
                    .setWhisper(whisper)
                    .setTokens(tokens.getAbsolutePath())
                    .setNumThreads(Math.max(1, threads))
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();
            OfflineRecognizerConfig asrConfig = OfflineRecognizerConfig.builder()
                    .setOfflineModelConfig(model)
                    .setDecodingMethod("greedy_search")
                    .build();
            recognizer = new OfflineRecognizer(asrConfig);
            stream = recognizer.createStream();
            stream.acceptWaveform(audio.samples, audio.sampleRate);
            recognizer.decode(stream);
            OfflineRecognizerResult asr = recognizer.getResult(stream);
            String transcript = asr.getText() == null ? "" : asr.getText().trim();

            OfflineSpeakerSegmentationPyannoteModelConfig pyannote =
                    OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                            .setModel(segmentation.getAbsolutePath())
                            .setWindowShiftRatio(0.1f).build();
            OfflineSpeakerSegmentationModelConfig segmentationConfig =
                    OfflineSpeakerSegmentationModelConfig.builder()
                            .setPyannote(pyannote).setNumThreads(Math.max(1, threads))
                            .setProvider("cpu").setDebug(false).build();
            SpeakerEmbeddingExtractorConfig embeddingConfig =
                    SpeakerEmbeddingExtractorConfig.builder()
                            .setModel(embedding.getAbsolutePath()).setNumThreads(Math.max(1, threads))
                            .setProvider("cpu").setDebug(false).build();
            OfflineSpeakerDiarizationConfig diarizationConfig =
                    OfflineSpeakerDiarizationConfig.builder()
                            .setSegmentation(segmentationConfig)
                            .setEmbedding(embeddingConfig)
                            .setClustering(FastClusteringConfig.builder()
                                    .setNumClusters(-1).setThreshold(0.5f).setComputeConfidence(true).build())
                            .setMinDurationOn(0.2f).setMinDurationOff(0.5f).build();
            diarizer = new OfflineSpeakerDiarization(diarizationConfig);
            if (diarizer.getSampleRate() != audio.sampleRate) {
                throw new IllegalStateException("Модель диаризации требует " + diarizer.getSampleRate()
                        + " Гц, декодировано " + audio.sampleRate + " Гц");
            }
            OfflineSpeakerDiarizationSegment[] speakerSegments = diarizer.process(audio.samples);
            float[] times = asr.getTimestamps();
            String[] wordTokens = asr.getTokens();
            List<Segment> result = alignWords(speakerSegments, times, wordTokens, transcript);
            return new Result(transcript, result);
        } finally {
            if (diarizer != null) diarizer.release();
            if (stream != null) stream.release();
            if (recognizer != null) recognizer.release();
        }
    }

    private static List<Segment> alignWords(OfflineSpeakerDiarizationSegment[] speakers,
                                            float[] timestamps, String[] tokens, String transcript) {
        List<Segment> result = new ArrayList<>();
        if (speakers == null || speakers.length == 0) {
            throw new IllegalStateException("Диаризация не обнаружила речевых сегментов; результат не будет размечен фиктивными спикерами.");
        }
        if (timestamps == null || tokens == null || timestamps.length == 0 || tokens.length == 0) {
            throw new IllegalStateException("Whisper не вернул временные метки токенов. Нельзя корректно совместить текст и говорящих.");
        }

        StringBuilder[] textBySpeakerTurn = new StringBuilder[speakers.length];
        for (int i = 0; i < textBySpeakerTurn.length; i++) textBySpeakerTurn[i] = new StringBuilder();

        int n = Math.min(tokens.length, timestamps.length);
        for (int i = 0; i < n; i++) {
            String token = tokens[i];
            if (token == null || token.isEmpty()) continue;
            float time = timestamps[i];
            int owner = -1;
            for (int j = 0; j < speakers.length; j++) {
                if (time >= speakers[j].getStart() && time < speakers[j].getEnd()) {
                    owner = j;
                    break;
                }
            }
            if (owner >= 0) textBySpeakerTurn[owner].append(token);
        }

        for (int i = 0; i < speakers.length; i++) {
            OfflineSpeakerDiarizationSegment s = speakers[i];
            String text = textBySpeakerTurn[i].toString().trim();
            if (text.isEmpty()) continue;
            result.add(new Segment(Math.round(s.getStart() * 1000),
                    Math.round(s.getEnd() * 1000), s.getSpeaker(), s.getConfidence(), text));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Не удалось сопоставить временные метки Whisper с сегментами диаризации.");
        }
        return result;
    }

    static String formatTime(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", seconds / 3600, (seconds / 60) % 60, seconds % 60, Math.max(0, ms) % 1000);
    }
}