package dev.surma.parakeeb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class OpenAiChatClient {
    interface Callback {
        void onSuccess(String text);
        void onFailure(String message, Throwable error);
        void onCanceled();
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final double TEMPERATURE = 0.2;

    private final OkHttpClient httpClient;
    private final Gson gson;

    OpenAiChatClient() {
        this(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
    }

    OpenAiChatClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
        this.gson = new Gson();
    }

    Call rewriteAsync(LlmSettings settings, String coreText, Callback callback) {
        String systemPrompt = LlmPromptRenderer.effectiveSystemPrompt(settings.systemPrompt);
        String userPrompt = LlmPromptRenderer.rewriteUserPrompt(coreText);
        return enqueueChatCompletion(settings, systemPrompt, userPrompt, callback);
    }

    Call testConnectionAsync(LlmSettings settings, Callback callback) {
        return enqueueChatCompletion(settings, LlmPromptRenderer.testSystemPrompt(), LlmPromptRenderer.testUserPrompt(), callback);
    }

    private Call enqueueChatCompletion(LlmSettings settings, String systemPrompt, String userPrompt, Callback callback) {
        if (settings == null || !settings.hasRequiredFields()) {
            callback.onFailure("Missing LLM configuration", null);
            return null;
        }

        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", settings.model);

            JsonArray messages = new JsonArray();
            messages.add(message("system", systemPrompt));
            messages.add(message("user", userPrompt));
            payload.add("messages", messages);

            Request request = new Request.Builder()
                    .url(settings.chatCompletionsUrl())
                    .header("Authorization", "Bearer " + settings.apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(gson.toJson(payload), JSON))
                    .build();

            Call call = httpClient.newCall(request);
            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (call.isCanceled()) {
                        callback.onCanceled();
                        return;
                    }
                    callback.onFailure(e.getMessage() != null ? e.getMessage() : "Request failed", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (Response ignored = response) {
                        if (!response.isSuccessful()) {
                            callback.onFailure("HTTP " + response.code(), null);
                            return;
                        }

                        String body = response.body() != null ? response.body().string() : "";
                        String content = extractMessageContent(body);
                        callback.onSuccess(LlmResponseParser.extractTaggedText(content));
                    } catch (Exception e) {
                        callback.onFailure(e.getMessage() != null ? e.getMessage() : "Invalid response", e);
                    }
                }
            });
            return call;
        } catch (Exception e) {
            callback.onFailure(e.getMessage() != null ? e.getMessage() : "Could not build request", e);
            return null;
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }

    private static String extractMessageContent(String responseBody) {
        JsonElement root = JsonParser.parseString(responseBody);
        JsonArray choices = root.getAsJsonObject().getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IllegalArgumentException("Missing choices in response");
        }

        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalArgumentException("Missing message content in response");
        }
        return message.get("content").getAsString();
    }
}
