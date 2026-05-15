package dev.surma.parakeeb;

final class LlmPromptRenderer {
    private static final String DEFAULT_INSTRUCTIONS = "(none)";

    private LlmPromptRenderer() {
    }

    static String systemPrompt() {
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

    static String rewriteUserPrompt(String extraInstructions, String coreText) {
        String instructions = normalizeExtraInstructions(extraInstructions);
        return "Additional instructions:\n"
                + "<user_instructions>\n"
                + instructions + "\n"
                + "</user_instructions>\n\n"
                + "Text to rewrite:\n"
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

    private static String normalizeExtraInstructions(String extraInstructions) {
        String trimmed = safe(extraInstructions).trim();
        return trimmed.isEmpty() ? DEFAULT_INSTRUCTIONS : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
