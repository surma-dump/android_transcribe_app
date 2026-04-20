package dev.surma.parakeeb;

public class TranscriptEntry {
    public final long id;
    public final String text;
    public final long timestamp;
    public final int charCount;

    public TranscriptEntry(long id, String text, long timestamp, int charCount) {
        this.id = id;
        this.text = text;
        this.timestamp = timestamp;
        this.charCount = charCount;
    }
}
