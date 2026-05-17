package dev.surma.parakeeb;

final class LlmSettings {
    final String baseUrl;
    final String apiKey;
    final String model;
    final String systemPrompt;

    LlmSettings(String baseUrl, String apiKey, String model, String systemPrompt) {
        this.baseUrl = normalize(baseUrl);
        this.apiKey = safe(apiKey).trim();
        this.model = safe(model).trim();
        this.systemPrompt = safe(systemPrompt).trim();
    }

    boolean hasRequiredFields() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    String chatCompletionsUrl() {
        String trimmed = baseUrl.replaceAll("/+$", "");
        if (trimmed.endsWith("/v1/chat/completions")) {
            return trimmed;
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/chat/completions";
        }
        return trimmed + "/v1/chat/completions";
    }

    private static String normalize(String value) {
        return safe(value).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
