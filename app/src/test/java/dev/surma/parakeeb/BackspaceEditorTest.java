package dev.surma.parakeeb;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.inputmethod.InputConnection;
import org.junit.Test;

public class BackspaceEditorTest {

    @Test
    public void backspace_withSelection_replacesSelectionWithEmptyText() {
        InputConnection ic = mock(InputConnection.class);
        when(ic.getSelectedText(0)).thenReturn("hello");

        BackspaceEditor.performBackspace(ic);

        verify(ic).commitText("", 1);
        verify(ic, never()).deleteSurroundingText(1, 0);
    }

    @Test
    public void backspace_withoutSelection_deletesOneCharacterBeforeCursor() {
        InputConnection ic = mock(InputConnection.class);
        when(ic.getSelectedText(0)).thenReturn("");

        BackspaceEditor.performBackspace(ic);

        verify(ic).deleteSurroundingText(1, 0);
        verify(ic, never()).commitText("", 1);
    }
}
