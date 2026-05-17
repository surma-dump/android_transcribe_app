package dev.surma.parakeeb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LlmPromptRendererTest {
    @Test
    public void defaultSystemPrompt_preservesSwearing() {
        String prompt = LlmPromptRenderer.defaultSystemPrompt();

        assertTrue(prompt.contains("Do not censor"));
        assertTrue(prompt.contains("keep the swearing"));
    }

    @Test
    public void defaultSystemPrompt_includesPhoneticCorrection() {
        String prompt = LlmPromptRenderer.defaultSystemPrompt();

        assertTrue(prompt.contains("phonetic misrecognition"));
    }

    @Test
    public void defaultSystemPrompt_doesNotContainCensoringLanguage() {
        String prompt = LlmPromptRenderer.defaultSystemPrompt();

        assertFalse(prompt.contains("appropriate language"));
        assertFalse(prompt.contains("clean up profanity"));
    }

    @Test
    public void effectiveSystemPrompt_returnsDefaultWhenEmpty() {
        String result = LlmPromptRenderer.effectiveSystemPrompt("");
        assertEquals(LlmPromptRenderer.defaultSystemPrompt(), result);
    }

    @Test
    public void effectiveSystemPrompt_returnsDefaultWhenNull() {
        String result = LlmPromptRenderer.effectiveSystemPrompt(null);
        assertEquals(LlmPromptRenderer.defaultSystemPrompt(), result);
    }

    @Test
    public void effectiveSystemPrompt_returnsCustomWhenProvided() {
        String custom = "You are a pirate.";
        String result = LlmPromptRenderer.effectiveSystemPrompt(custom);
        assertEquals(custom, result);
    }

    @Test
    public void rewriteUserPrompt_wrapsInputText() {
        String prompt = LlmPromptRenderer.rewriteUserPrompt("hello there");

        assertTrue(prompt.contains("<input_text>\nhello there\n</input_text>"));
        assertFalse(prompt.contains("user_instructions"));
    }
}
