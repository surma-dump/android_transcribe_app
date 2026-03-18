package dev.surma.parakeeb;

import android.view.KeyEvent;

final class ImeVolumeKeyHandler {
    private ImeVolumeKeyHandler() {
    }

    static boolean shouldConsumeKeyDown(boolean imeShown, int keyCode) {
        return imeShown && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    static boolean shouldToggleRecordingOnKeyDown(boolean imeShown, int keyCode, int repeatCount) {
        return shouldConsumeKeyDown(imeShown, keyCode) && repeatCount == 0;
    }

    static boolean shouldConsumeKeyUp(boolean imeShown, int keyCode) {
        return imeShown && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }
}
