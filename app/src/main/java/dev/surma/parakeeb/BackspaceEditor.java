package dev.surma.parakeeb;

import android.view.inputmethod.InputConnection;

final class BackspaceEditor {
    private BackspaceEditor() {}

    static void performBackspace(InputConnection ic) {
        if (ic == null) {
            return;
        }

        CharSequence selectedText = ic.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            ic.commitText("", 1);
            return;
        }

        ic.deleteSurroundingText(1, 0);
    }
}
