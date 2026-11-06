package com.example;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class RecordingDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "audio_vault.db";
    private static final int DB_VERSION = 1;

    RecordingDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recordings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "audio_path TEXT NOT NULL," +
                "transcript TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'Импортировано')");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema migrations are added here when the database format changes.
    }

    long add(String title, String path, String status) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("audio_path", path);
        values.put("created_at", System.currentTimeMillis());
        values.put("status", status);
        return getWritableDatabase().insertOrThrow("recordings", null, values);
    }

    void updateTranscript(long id, String transcript) {
        ContentValues values = new ContentValues();
        values.put("transcript", transcript);
        getWritableDatabase().update("recordings", values, "id=?", new String[]{String.valueOf(id)});
    }

    List<Recording> all() {
        List<Recording> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,audio_path,transcript,created_at,status FROM recordings ORDER BY created_at DESC",
                null)) {
            while (c.moveToNext()) {
                items.add(new Recording(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getLong(4), c.getString(5)));
            }
        }
        return items;
    }

    static final class Recording {
        final long id;
        final String title;
        final String audioPath;
        final String transcript;
        final long createdAt;
        final String status;

        Recording(long id, String title, String audioPath, String transcript, long createdAt, String status) {
            this.id = id;
            this.title = title;
            this.audioPath = audioPath;
            this.transcript = transcript;
            this.createdAt = createdAt;
            this.status = status;
        }
    }
}