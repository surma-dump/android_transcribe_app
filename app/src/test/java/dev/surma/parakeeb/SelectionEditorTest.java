package dev.surma.parakeeb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;

import org.junit.Test;

public class SelectionEditorTest {
    @Test
    public void collapseSelectionToEnd_withSelection_movesCursorToSelectionEnd() {
        InputConnection ic = mock(InputConnection.class);
        ExtractedText extractedText = new ExtractedText();
        extractedText.selectionStart = 4;
        extractedText.selectionEnd = 9;

        boolean handled = SelectionEditor.collapseSelectionToEnd(ic, extractedText);

        assertTrue(handled);
        verify(ic).setSelection(9, 9);
    }

    @Test
    public void collapseSelectionToEnd_withoutSelection_doesNothing() {
        InputConnection ic = mock(InputConnection.class);
        ExtractedText extractedText = new ExtractedText();
        extractedText.selectionStart = 6;
        extractedText.selectionEnd = 6;

        boolean handled = SelectionEditor.collapseSelectionToEnd(ic, extractedText);

        assertFalse(handled);
        verify(ic, never()).setSelection(6, 6);
    }

    @Test
    public void selectAll_withExtractedText_selectsEntireFieldRange() {
        InputConnection ic = mock(InputConnection.class);
        ExtractedText extractedText = new ExtractedText();
        extractedText.startOffset = 3;
        extractedText.text = "hello there";

        boolean handled = SelectionEditor.selectAll(ic, extractedText);

        assertTrue(handled);
        verify(ic).setSelection(3, 14);
        verify(ic, never()).performContextMenuAction(android.R.id.selectAll);
    }

    @Test
    public void selectAll_withoutExtractedText_fallsBackToContextMenuAction() {
        InputConnection ic = mock(InputConnection.class);
        when(ic.performContextMenuAction(android.R.id.selectAll)).thenReturn(true);

        boolean handled = SelectionEditor.selectAll(ic, null);

        assertTrue(handled);
        verify(ic).performContextMenuAction(android.R.id.selectAll);
    }
}
