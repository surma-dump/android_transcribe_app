package dev.surma.parakeeb;

import android.view.KeyEvent;

final class ImeVolumeKeyHandler {
    private ImeVolumeKeyHandler() {
    }

    static boolean shouldConsumeKeyDown(boolean imeShown, int keyCode) {
        return imeShown && isHandledHardwareKey(keyCode);
    }

    static boolean shouldTrackVolumeDownOnKeyDown(boolean imeShown, int keyCode, int repeatCount) {
        return imeShown && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && repeatCount == 0;
    }

    static boolean shouldTrackVolumeUpOnKeyDown(boolean imeShown, int keyCode, int repeatCount) {
        return imeShown && keyCode == KeyEvent.KEYCODE_VOLUME_UP && repeatCount == 0;
    }

    static boolean shouldTriggerSelectAllOnLongPress(boolean imeShown, int keyCode) {
        return imeShown && keyCode == KeyEvent.KEYCODE_VOLUME_UP;
    }

    static boolean shouldResolveVolumeDownOnKeyUp(boolean imeShown, int keyCode) {
        return imeShown && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    static boolean shouldResolveVolumeUpOnKeyUp(boolean imeShown, int keyCode, boolean longPressHandled) {
        return imeShown && keyCode == KeyEvent.KEYCODE_VOLUME_UP && !longPressHandled;
    }

    static boolean shouldConsumeKeyUp(boolean imeShown, int keyCode) {
        return imeShown && isHandledHardwareKey(keyCode);
    }

    private static boolean isHandledHardwareKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP;
    }
}
