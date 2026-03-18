package dev.surma.parakeeb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class ImeVolumeKeyHandlerTest {
    @Test
    public void firstVolumeDownPressWhileImeShown_togglesAndConsumes() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertTrue(ImeVolumeKeyHandler.shouldToggleRecordingOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN, 0));
    }

    @Test
    public void repeatedVolumeDownPressWhileImeShown_isConsumedWithoutRetoggling() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse(ImeVolumeKeyHandler.shouldToggleRecordingOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN, 2));
    }

    @Test
    public void volumeDownWhileImeHidden_isIgnored() {
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyDown(false, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse(ImeVolumeKeyHandler.shouldToggleRecordingOnKeyDown(false, KeyEvent.KEYCODE_VOLUME_DOWN, 0));
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyUp(false, KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test
    public void nonVolumeDownKeys_areIgnored() {
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(ImeVolumeKeyHandler.shouldToggleRecordingOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_UP, 0));
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyUp(true, KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void volumeDownKeyUpWhileImeShown_isConsumed() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyUp(true, KeyEvent.KEYCODE_VOLUME_DOWN));
    }
}
