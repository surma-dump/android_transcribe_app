package dev.surma.parakeeb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LlmPromptRendererTest {
    @Test
    public void systemPrompt_preservesSwearing() {
        String prompt = LlmPromptRenderer.systemPrompt();

        assertTrue(prompt.contains("Do not censor"));
        assertTrue(prompt.contains("keep the swearing"));
    }

    @Test
    public void systemPrompt_includesPhoneticCorrection() {
        String prompt = LlmPromptRenderer.systemPrompt();

        assertTrue(prompt.contains("phonetic misrecognition"));
    }

    @Test
    public void systemPrompt_doesNotContainCensoringLanguage() {
        String prompt = LlmPromptRenderer.systemPrompt();

        assertFalse(prompt.contains("appropriate language"));
        assertFalse(prompt.contains("clean up profanity"));
    }

    @Test
    public void rewriteUserPrompt_includesExtraInstructionsWhenProvided() {
        String prompt = LlmPromptRenderer.rewriteUserPrompt("Remove filler words.", "hello there");

        assertTrue(prompt.contains("Remove filler words."));
        assertTrue(prompt.contains("<input_text>\nhello there\n</input_text>"));
    }

    @Test
    public void rewriteUserPrompt_usesNoneWhenInstructionsBlank() {
        String prompt = LlmPromptRenderer.rewriteUserPrompt("   ", "hello there");

        assertTrue(prompt.contains("<user_instructions>\n(none)\n</user_instructions>"));
    }
}
