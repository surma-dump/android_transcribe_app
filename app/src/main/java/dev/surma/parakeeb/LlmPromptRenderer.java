package dev.surma.parakeeb;

final class LlmPromptRenderer {

    private LlmPromptRenderer() {
    }

    static String defaultSystemPrompt() {
        return "You clean up speech-to-text dictation. Your job is to produce readable text "
                + "that faithfully represents what the speaker said.\n\n"
                + "Rules:\n"
                + "- Remove duplications, false starts, and filler words (um, uh, like, you know).\n"
                + "- Add proper punctuation, capitalization, and paragraph breaks where appropriate.\n"
                + "- Correct obvious spelling and transcription errors.\n"
                + "- If a word does not make sense in context, consider whether it is a phonetic "
                + "misrecognition and replace it with the most likely intended word.\n"
                + "- Stay as close to the speaker's original wording as grammar allows. "
                + "Do not rephrase or paraphrase.\n"
                + "- Preserve the speaker's tone, style, and emphasis. If they swear, keep the "
                + "swearing. If a particular phrasing adds emphasis or personality, leave it.\n"
                + "- Do not add new information or meaning.\n"
                + "- Do not censor, sanitize, or soften the language.\n"
                + "- Preserve the original language (do not translate).\n\n"
                + "Return the final text only inside <rewritten_text>...</rewritten_text>.";
    }

    /**
     * Returns the custom prompt if non-empty, otherwise the default.
     */
    static String effectiveSystemPrompt(String customPrompt) {
        String trimmed = safe(customPrompt).trim();
        return trimmed.isEmpty() ? defaultSystemPrompt() : trimmed;
    }

    static String rewriteUserPrompt(String coreText) {
        return "Text to rewrite:\n"
                + "<input_text>\n"
                + safe(coreText) + "\n"
                + "</input_text>";
    }

    static String testSystemPrompt() {
        return "Return exactly <rewritten_text>ok</rewritten_text>";
    }

    static String testUserPrompt() {
        return "Reply now.";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
