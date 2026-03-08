package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImeProgressStatusParserTest {

    @Test
    public void parseProgressPercent_readsStandardProgressStatus() {
        assertEquals(0, ImeProgressStatusParser.parseProgressPercent("Transcribing... 0%"));
        assertEquals(67, ImeProgressStatusParser.parseProgressPercent("Transcribing... 67%"));
        assertEquals(100, ImeProgressStatusParser.parseProgressPercent("Transcribing... 100%"));
    }

    @Test
    public void parseProgressPercent_returnsMinusOneWhenNoProgressIsPresent() {
        assertEquals(-1, ImeProgressStatusParser.parseProgressPercent("Listening..."));
        assertEquals(-1, ImeProgressStatusParser.parseProgressPercent("Queued for transcription (1 ahead)"));
        assertEquals(-1, ImeProgressStatusParser.parseProgressPercent("Error: model not loaded"));
    }

    @Test
    public void parseProgressPercent_clampsOutOfRangeValues() {
        assertEquals(0, ImeProgressStatusParser.parseProgressPercent("Transcribing... -5%"));
        assertEquals(100, ImeProgressStatusParser.parseProgressPercent("Transcribing... 120%"));
    }
}
