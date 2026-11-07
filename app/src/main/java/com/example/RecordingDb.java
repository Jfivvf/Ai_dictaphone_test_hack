package com.example;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Локальная база: записи, реплики и задачи.
 * Один экземпляр на процесс, его используют и интерфейс, и фоновые сервисы.
 */
final class RecordingDb extends SQLiteOpenHelper {
    interface Listener {
        void onChanged(long recordingId);
    }

    private static final String DB_NAME = "audio_vault.db";
    private static final int DB_VERSION = 2;
    private static final String SELECT_RECORDING =
            "SELECT id,title,audio_path,transcript,created_at,status,duration_ms,error,speaker_names FROM recordings";

    private static RecordingDb instance;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static synchronized RecordingDb get(Context context) {
        if (instance == null) instance = new RecordingDb(context.getApplicationContext());
        return instance;
    }

    static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private RecordingDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recordings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "audio_path TEXT NOT NULL," +
                "transcript TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'recorded'," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "error TEXT NOT NULL DEFAULT ''," +
                "speaker_names TEXT NOT NULL DEFAULT '')");
        createDerivedTables(db);
    }

    private void createDerivedTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE segments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "recording_id INTEGER NOT NULL," +
                "idx INTEGER NOT NULL," +
                "start_ms INTEGER NOT NULL," +
                "end_ms INTEGER NOT NULL," +
                "speaker INTEGER NOT NULL," +
                "text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_segments_recording ON segments(recording_id, idx)");
        db.execSQL("CREATE TABLE tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "recording_id INTEGER NOT NULL," +
                "segment_idx INTEGER NOT NULL," +
                "start_ms INTEGER NOT NULL," +
                "speaker INTEGER NOT NULL," +
                "text TEXT NOT NULL," +
                "deadline TEXT NOT NULL DEFAULT ''," +
                "done INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_tasks_recording ON tasks(recording_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE recordings ADD COLUMN error TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE recordings ADD COLUMN speaker_names TEXT NOT NULL DEFAULT ''");
            createDerivedTables(db);
            db.execSQL("UPDATE recordings SET status = CASE WHEN transcript <> '' THEN 'done' ELSE 'recorded' END");
            migrateV1Transcripts(db);
        }
    }

    private static final Pattern V1_LINE = Pattern.compile(
            "^\\[(\\d+):(\\d\\d):(\\d\\d)\\.(\\d{3}) – (\\d+):(\\d\\d):(\\d\\d)\\.(\\d{3})\\] Спикер (\\d+): (.*)$");

    /** В версии 1 расшифровка хранилась одним текстом; разбираем её в реплики, чтобы ничего не потерять. */
    private void migrateV1Transcripts(SQLiteDatabase db) {
        List<long[]> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, transcript FROM recordings WHERE transcript <> ''", null)) {
            while (c.moveToNext()) {
                ids.add(new long[]{c.getLong(0)});
                texts.add(c.getString(1));
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            long recordingId = ids.get(i)[0];
            int idx = 0;
            for (String line : texts.get(i).split("\n")) {
                Matcher m = V1_LINE.matcher(line.trim());
                if (!m.matches()) continue;
                long start = toMs(m, 1);
                long end = toMs(m, 5);
                int speaker = Integer.parseInt(m.group(9)) - 1;
                insertSegment(db, recordingId, idx++, start, end, speaker, m.group(10).trim());
            }
            if (idx == 0) insertSegment(db, recordingId, 0, 0, 0, -1, texts.get(i).trim());
        }
    }

    private static long toMs(Matcher m, int first) {
        long hours = Long.parseLong(m.group(first));
        long minutes = Long.parseLong(m.group(first + 1));
        long seconds = Long.parseLong(m.group(first + 2));
        long millis = Long.parseLong(m.group(first + 3));
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
    }

    private static void insertSegment(SQLiteDatabase db, long recordingId, int idx, long start, long end,
                                      int speaker, String text) {
        ContentValues v = new ContentValues();
        v.put("recording_id", recordingId);
        v.put("idx", idx);
        v.put("start_ms", start);
        v.put("end_ms", end);
        v.put("speaker", speaker);
        v.put("text", text);
        db.insertOrThrow("segments", null, v);
    }

    private void changed(final long recordingId) {
        notifyChanged(recordingId);
    }

    /** Сообщает слушателям, что запись изменилась (в том числе при смене прогресса обработки). */
    void notifyChanged(final long recordingId) {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onChanged(recordingId);
        });
    }

    // ---- записи -------------------------------------------------------------------------------

    long add(String title, String path, String status, long durationMs) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("audio_path", path);
        v.put("created_at", System.currentTimeMillis());
        v.put("status", status);
        v.put("duration_ms", durationMs);
        long id = getWritableDatabase().insertOrThrow("recordings", null, v);
        changed(id);
        return id;
    }

    Recording get(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(SELECT_RECORDING + " WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    List<Recording> all() {
        List<Recording> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(SELECT_RECORDING + " ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) items.add(read(c));
        }
        return items;
    }

    /** Для каждой записи: {число говорящих, всего задач, невыполненных задач}. */
    java.util.Map<Long, int[]> stats() {
        java.util.Map<Long, int[]> map = new java.util.HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT recording_id, COUNT(DISTINCT speaker) FROM segments " +
                "WHERE speaker >= 0 GROUP BY recording_id", null)) {
            while (c.moveToNext()) map.put(c.getLong(0), new int[]{c.getInt(1), 0, 0});
        }
        try (Cursor c = db.rawQuery("SELECT recording_id, COUNT(*), SUM(CASE WHEN done = 0 THEN 1 ELSE 0 END) " +
                "FROM tasks GROUP BY recording_id", null)) {
            while (c.moveToNext()) {
                int[] row = map.get(c.getLong(0));
                if (row == null) {
                    row = new int[3];
                    map.put(c.getLong(0), row);
                }
                row[1] = c.getInt(1);
                row[2] = c.getInt(2);
            }
        }
        return map;
    }

    private static Recording read(Cursor c) {
        return new Recording(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4),
                c.getString(5), c.getLong(6), c.getString(7), c.getString(8));
    }

    void setStatus(long id, String status, String error) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("error", error == null ? "" : error);
        getWritableDatabase().update("recordings", v, "id=?", new String[]{String.valueOf(id)});
        changed(id);
    }

    void setDuration(long id, long durationMs) {
        ContentValues v = new ContentValues();
        v.put("duration_ms", durationMs);
        getWritableDatabase().update("recordings", v, "id=?", new String[]{String.valueOf(id)});
        changed(id);
    }

    void rename(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("recordings", v, "id=?", new String[]{String.valueOf(id)});
        changed(id);
    }

    void setSpeakerName(long id, int speaker, String name) {
        Recording recording = get(id);
        if (recording == null) return;
        try {
            JSONObject json = recording.speakerNamesJson.isEmpty()
                    ? new JSONObject() : new JSONObject(recording.speakerNamesJson);
            String clean = name == null ? "" : name.trim();
            if (clean.isEmpty()) json.remove(String.valueOf(speaker));
            else json.put(String.valueOf(speaker), clean);
            ContentValues v = new ContentValues();
            v.put("speaker_names", json.toString());
            getWritableDatabase().update("recordings", v, "id=?", new String[]{String.valueOf(id)});
            changed(id);
        } catch (Exception ignored) {
            // Повреждённый JSON имён не должен ронять приложение.
        }
    }

    /** Удаляет запись вместе с репликами и задачами, возвращает путь к аудио, чтобы вызвавший удалил файл. */
    String delete(long id) {
        Recording recording = get(id);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] arg = {String.valueOf(id)};
            db.delete("tasks", "recording_id=?", arg);
            db.delete("segments", "recording_id=?", arg);
            db.delete("recordings", "id=?", arg);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed(id);
        return recording == null ? null : recording.audioPath;
    }

    // ---- реплики ------------------------------------------------------------------------------

    /** Сохраняет итог расшифровки; message — пояснение (например, почему нет разметки говорящих) или пустая строка. */
    void saveResult(long id, List<Segment> segments, String status, String message, long durationMs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("segments", "recording_id=?", new String[]{String.valueOf(id)});
            StringBuilder flat = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                Segment s = segments.get(i);
                insertSegment(db, id, i, s.startMs, s.endMs, s.speaker, s.text);
                if (flat.length() > 0) flat.append('\n');
                flat.append(s.text);
            }
            ContentValues v = new ContentValues();
            v.put("transcript", flat.toString());
            v.put("status", status);
            v.put("error", message == null ? "" : message);
            if (durationMs > 0) v.put("duration_ms", durationMs);
            db.update("recordings", v, "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed(id);
    }

    List<Segment> segments(long recordingId) {
        List<Segment> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT idx,start_ms,end_ms,speaker,text FROM segments WHERE recording_id=? ORDER BY idx",
                new String[]{String.valueOf(recordingId)})) {
            while (c.moveToNext()) {
                items.add(new Segment(c.getInt(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4)));
            }
        }
        return items;
    }

    void updateSegment(long recordingId, int idx, int speaker, String text) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("speaker", speaker);
            v.put("text", text);
            db.update("segments", v, "recording_id=? AND idx=?",
                    new String[]{String.valueOf(recordingId), String.valueOf(idx)});
            refreshFlatTranscript(db, recordingId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed(recordingId);
    }

    /** Заменяет текст нескольких реплик одной транзакцией; время и говорящие не меняются. */
    void updateSegmentsText(long recordingId, java.util.Map<Integer, String> texts) {
        if (texts == null || texts.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (java.util.Map.Entry<Integer, String> e : texts.entrySet()) {
                ContentValues v = new ContentValues();
                v.put("text", e.getValue());
                db.update("segments", v, "recording_id=? AND idx=?",
                        new String[]{String.valueOf(recordingId), String.valueOf(e.getKey())});
            }
            refreshFlatTranscript(db, recordingId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed(recordingId);
    }

    /** Пересобирает плоский текст записи (для поиска) из реплик. Вызывать внутри транзакции. */
    private static void refreshFlatTranscript(SQLiteDatabase db, long recordingId) {
        StringBuilder flat = new StringBuilder();
        try (Cursor c = db.rawQuery("SELECT text FROM segments WHERE recording_id=? ORDER BY idx",
                new String[]{String.valueOf(recordingId)})) {
            while (c.moveToNext()) {
                if (flat.length() > 0) flat.append('\n');
                flat.append(c.getString(0));
            }
        }
        ContentValues t = new ContentValues();
        t.put("transcript", flat.toString());
        db.update("recordings", t, "id=?", new String[]{String.valueOf(recordingId)});
    }

    // ---- задачи -------------------------------------------------------------------------------

    /** Заменяет задачи записи; отметки «выполнено» сохраняются для задач с тем же текстом. */
    void replaceTasks(long recordingId, List<Task> tasks) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] arg = {String.valueOf(recordingId)};
            Set<String> done = new HashSet<>();
            try (Cursor c = db.rawQuery("SELECT text FROM tasks WHERE recording_id=? AND done=1", arg)) {
                while (c.moveToNext()) done.add(c.getString(0).toLowerCase(Locale.ROOT));
            }
            db.delete("tasks", "recording_id=?", arg);
            for (Task t : tasks) {
                ContentValues v = new ContentValues();
                v.put("recording_id", recordingId);
                v.put("segment_idx", t.segmentIdx);
                v.put("start_ms", t.startMs);
                v.put("speaker", t.speaker);
                v.put("text", t.text);
                v.put("deadline", t.deadline);
                v.put("done", t.done || done.contains(t.text.toLowerCase(Locale.ROOT)) ? 1 : 0);
                db.insertOrThrow("tasks", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed(recordingId);
    }

    List<Task> tasksFor(long recordingId) {
        return queryTasks("WHERE t.recording_id=? ORDER BY t.start_ms", new String[]{String.valueOf(recordingId)});
    }

    List<Task> allTasks() {
        return queryTasks("ORDER BY t.done ASC, r.created_at DESC, t.start_ms ASC", null);
    }

    private List<Task> queryTasks(String tail, String[] args) {
        List<Task> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t.id,t.recording_id,r.title,t.segment_idx,t.start_ms,t.speaker,t.text,t.deadline,t.done " +
                        "FROM tasks t JOIN recordings r ON r.id=t.recording_id " + tail, args)) {
            while (c.moveToNext()) {
                items.add(new Task(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getLong(4),
                        c.getInt(5), c.getString(6), c.getString(7), c.getInt(8) == 1));
            }
        }
        return items;
    }

    void setTaskDone(long taskId, long recordingId, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        getWritableDatabase().update("tasks", v, "id=?", new String[]{String.valueOf(taskId)});
        changed(recordingId);
    }
}
