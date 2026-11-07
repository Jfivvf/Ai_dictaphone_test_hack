package com.example;

/** Задача, найденная в расшифровке. */
final class Task {
    final long id;
    final long recordingId;
    final String recordingTitle;
    final int segmentIdx;
    final long startMs;
    final int speaker;
    final String text;
    final String deadline;
    final boolean done;

    Task(long id, long recordingId, String recordingTitle, int segmentIdx, long startMs,
         int speaker, String text, String deadline, boolean done) {
        this.id = id;
        this.recordingId = recordingId;
        this.recordingTitle = recordingTitle;
        this.segmentIdx = segmentIdx;
        this.startMs = startMs;
        this.speaker = speaker;
        this.text = text;
        this.deadline = deadline == null ? "" : deadline;
        this.done = done;
    }

    static Task found(int segmentIdx, long startMs, int speaker, String text, String deadline) {
        return new Task(0, 0, "", segmentIdx, startMs, speaker, text, deadline, false);
    }
}
