package dev.surma.parakeeb;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class TranscriptHistoryStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "transcript_history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "transcripts";
    private static final int MAX_ENTRIES = 100;

    public TranscriptHistoryStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "text TEXT NOT NULL, "
                + "timestamp INTEGER NOT NULL, "
                + "char_count INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations go here
    }

    public void insert(String text) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("text", text);
        values.put("timestamp", System.currentTimeMillis());
        values.put("char_count", text.length());
        db.insert(TABLE, null, values);
        prune(db);
    }

    public List<TranscriptEntry> getRecent() {
        List<TranscriptEntry> entries = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE, null, null, null, null, null,
                "timestamp DESC", String.valueOf(MAX_ENTRIES));
        try {
            while (cursor.moveToNext()) {
                entries.add(new TranscriptEntry(
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("text")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("char_count"))
                ));
            }
        } finally {
            cursor.close();
        }
        return entries;
    }

    private void prune(SQLiteDatabase db) {
        db.execSQL("DELETE FROM " + TABLE + " WHERE id NOT IN ("
                + "SELECT id FROM " + TABLE + " ORDER BY timestamp DESC LIMIT " + MAX_ENTRIES + ")");
    }
}
