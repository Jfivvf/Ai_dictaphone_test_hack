package com.example;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

final class AudioDecoder {
    static final int TARGET_RATE = 16000;

    static final class AudioData {
        final float[] samples;
        final int sampleRate;
        AudioData(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    static AudioData decode(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);
        return decodeExtractor(extractor);
    }

    static AudioData decode(Context context, String path) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(path);
        return decodeExtractor(extractor);
    }

    private static AudioData decodeExtractor(MediaExtractor extractor) throws IOException {
        MediaCodec codec = null;
        try {
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
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ArrayList<Float> mono = new ArrayList<>();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int outputRate = sourceRate;
            int outputChannels = channels;
            while (!outputDone) {
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
                    MediaFormat outFormat = codec.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outputRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (output != null && info.size > 0) {
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        ByteBuffer pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int count = info.size / 2;
                        int frames = count / Math.max(1, outputChannels);
                        for (int frame = 0; frame < frames; frame++) {
                            float sum = 0;
                            for (int ch = 0; ch < outputChannels; ch++) sum += pcm.getShort() / 32768.0f;
                            mono.add(sum / outputChannels);
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }

            float[] source = new float[mono.size()];
            for (int i = 0; i < source.length; i++) source[i] = mono.get(i);
            float[] resampled = resample(source, outputRate, TARGET_RATE);
            if (resampled.length == 0) throw new IOException("Аудиодорожка пуста");
            return new AudioData(resampled, TARGET_RATE);
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

    private static float[] resample(float[] input, int fromRate, int toRate) {
        if (fromRate == toRate) return input;
        int outputLength = (int) Math.ceil(input.length * (double) toRate / fromRate);
        float[] output = new float[outputLength];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < outputLength; i++) {
            double sourcePosition = i * step;
            int left = Math.min((int) sourcePosition, input.length - 1);
            int right = Math.min(left + 1, input.length - 1);
            float fraction = (float) (sourcePosition - left);
            output[i] = input[left] * (1 - fraction) + input[right] * fraction;
        }
        return output;
    }
}