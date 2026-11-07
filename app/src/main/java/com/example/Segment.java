package com.example;

/** Одна реплика: говорящий, границы по времени и текст. */
final class Segment {
    final int idx;
    final long startMs;
    final long endMs;
    /** Номер говорящего с нуля; -1, если говорящие не определялись. */
    final int speaker;
    final String text;

    Segment(int idx, long startMs, long endMs, int speaker, String text) {
        this.idx = idx;
        this.startMs = startMs;
        this.endMs = endMs;
        this.speaker = speaker;
        this.text = text;
    }

    static Segment of(long startMs, long endMs, int speaker, String text) {
        return new Segment(-1, startMs, endMs, speaker, text);
    }
}
