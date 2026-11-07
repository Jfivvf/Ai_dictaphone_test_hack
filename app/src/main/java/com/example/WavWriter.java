package com.example;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Пишет моно WAV PCM16 на диск по мере поступления звука; заголовок дописывается при закрытии. */
final class WavWriter implements Closeable {
    private final RandomAccessFile file;
    private final int sampleRate;
    private long dataBytes;
    private byte[] bytes = new byte[0];

    WavWriter(File target, int sampleRate) throws IOException {
        this.sampleRate = sampleRate;
        file = new RandomAccessFile(target, "rw");
        file.setLength(0);
        file.write(new byte[44]);
    }

    void write(short[] pcm, int count) throws IOException {
        if (bytes.length < count * 2) bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            bytes[2 * i] = (byte) (pcm[i] & 0xFF);
            bytes[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        file.write(bytes, 0, count * 2);
        dataBytes += count * 2L;
    }

    @Override public void close() throws IOException {
        try {
            file.seek(0);
            file.write(header());
        } finally {
            file.close();
        }
    }

    private byte[] header() {
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        putInt(h, 4, (int) (36 + dataBytes));
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        putInt(h, 16, 16);
        h[20] = 1;                       // PCM
        h[22] = 1;                       // моно
        putInt(h, 24, sampleRate);
        putInt(h, 28, sampleRate * 2);   // байт в секунду
        h[32] = 2;                       // блок
        h[34] = 16;                      // бит на отсчёт
        put(h, 36, "data");
        putInt(h, 40, (int) dataBytes);
        return h;
    }

    private static void put(byte[] h, int at, String text) {
        for (int i = 0; i < text.length(); i++) h[at + i] = (byte) text.charAt(i);
    }

    private static void putInt(byte[] h, int at, int value) {
        h[at] = (byte) value;
        h[at + 1] = (byte) (value >> 8);
        h[at + 2] = (byte) (value >> 16);
        h[at + 3] = (byte) (value >> 24);
    }
}
