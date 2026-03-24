package dev.surma.parakeeb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class ImeVolumeKeyHandlerTest {
    @Test
    public void volumeDownFirstPressWhileImeShown_startsTrackingAndIsConsumed() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertTrue(ImeVolumeKeyHandler.shouldTrackVolumeDownOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN, 0));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeUpOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN, 0));
    }

    @Test
    public void repeatedVolumeDownPressWhileImeShown_isConsumedWithoutRestartingTracking() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeDownOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN, 2));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeUpOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_DOWN, 2));
    }

    @Test
    public void volumeDownKeyUpWhileImeShown_isResolvedAndConsumed() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyUp(true, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertTrue(ImeVolumeKeyHandler.shouldResolveVolumeDownOnKeyUp(true, KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test
    public void volumeUpFirstPressWhileImeShown_startsTrackingAndIsConsumed() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue(ImeVolumeKeyHandler.shouldTrackVolumeUpOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_UP, 0));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeDownOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_UP, 0));
    }

    @Test
    public void repeatedVolumeUpPressWhileImeShown_isConsumedWithoutRestartingTracking() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeUpOnKeyDown(true, KeyEvent.KEYCODE_VOLUME_UP, 3));
    }

    @Test
    public void volumeUpLongPressWhileImeShown_triggersSelectAll() {
        assertTrue(ImeVolumeKeyHandler.shouldTriggerSelectAllOnLongPress(true, KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void volumeUpKeyUpWhileImeShown_isResolvedUnlessLongPressHandled() {
        assertTrue(ImeVolumeKeyHandler.shouldConsumeKeyUp(true, KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue(ImeVolumeKeyHandler.shouldResolveVolumeUpOnKeyUp(true, KeyEvent.KEYCODE_VOLUME_UP, false));
        assertFalse(ImeVolumeKeyHandler.shouldResolveVolumeUpOnKeyUp(true, KeyEvent.KEYCODE_VOLUME_UP, true));
    }

    @Test
    public void volumeKeysWhileImeHidden_areIgnored() {
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyDown(false, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeDownOnKeyDown(false, KeyEvent.KEYCODE_VOLUME_DOWN, 0));
        assertFalse(ImeVolumeKeyHandler.shouldResolveVolumeDownOnKeyUp(false, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyUp(false, KeyEvent.KEYCODE_VOLUME_DOWN));

        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyDown(false, KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeUpOnKeyDown(false, KeyEvent.KEYCODE_VOLUME_UP, 0));
        assertFalse(ImeVolumeKeyHandler.shouldTriggerSelectAllOnLongPress(false, KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(ImeVolumeKeyHandler.shouldResolveVolumeUpOnKeyUp(false, KeyEvent.KEYCODE_VOLUME_UP, false));
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyUp(false, KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void nonVolumeKeys_areIgnored() {
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyDown(true, KeyEvent.KEYCODE_A));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeDownOnKeyDown(true, KeyEvent.KEYCODE_A, 0));
        assertFalse(ImeVolumeKeyHandler.shouldTrackVolumeUpOnKeyDown(true, KeyEvent.KEYCODE_A, 0));
        assertFalse(ImeVolumeKeyHandler.shouldResolveVolumeDownOnKeyUp(true, KeyEvent.KEYCODE_A));
        assertFalse(ImeVolumeKeyHandler.shouldTriggerSelectAllOnLongPress(true, KeyEvent.KEYCODE_A));
        assertFalse(ImeVolumeKeyHandler.shouldResolveVolumeUpOnKeyUp(true, KeyEvent.KEYCODE_A, false));
        assertFalse(ImeVolumeKeyHandler.shouldConsumeKeyUp(true, KeyEvent.KEYCODE_A));
    }
}
