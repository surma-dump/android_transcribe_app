package dev.surma.parakeeb;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;

final class SelectionEditor {
    private SelectionEditor() {
    }

    static boolean collapseSelectionToEnd(InputConnection ic, ExtractedText extractedText) {
        if (ic == null || extractedText == null) {
            return false;
        }

        int selectionStart = extractedText.selectionStart;
        int selectionEnd = extractedText.selectionEnd;
        if (selectionStart < 0 || selectionEnd < 0 || selectionStart == selectionEnd) {
            return false;
        }

        int collapseTo = Math.max(selectionStart, selectionEnd);
        ic.setSelection(collapseTo, collapseTo);
        return true;
    }

    static boolean selectAll(InputConnection ic, ExtractedText extractedText) {
        if (ic == null) {
            return false;
        }

        int[] range = RewriteTargetResolver.fullFieldRange(extractedText);
        if (range != null) {
            ic.setSelection(range[0], range[1]);
            return true;
        }

        return ic.performContextMenuAction(android.R.id.selectAll);
    }
}
