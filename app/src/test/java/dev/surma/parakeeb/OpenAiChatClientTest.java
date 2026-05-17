package dev.surma.parakeeb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

public class OpenAiChatClientTest {
    @Test
    public void rewriteAsync_postsChatCompletionRequestAndParsesTaggedResponse() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":\"prefix <rewritten_text>Hello there</rewritten_text> suffix\"}}]}"));
        server.start();

        try {
            OpenAiChatClient client = new OpenAiChatClient(new OkHttpClient());
            LlmSettings settings = new LlmSettings(server.url("/").toString(), "secret-key", "demo-model", "");
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> success = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();

            client.rewriteAsync(settings, "um hello there", new OpenAiChatClient.Callback() {
                @Override
                public void onSuccess(String text) {
                    success.set(text);
                    latch.countDown();
                }

                @Override
                public void onFailure(String message, Throwable error) {
                    failure.set(message);
                    latch.countDown();
                }

                @Override
                public void onCanceled() {
                    failure.set("canceled");
                    latch.countDown();
                }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("Hello there", success.get());
            assertEquals(null, failure.get());

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("/v1/chat/completions", request.getPath());
            assertEquals("Bearer secret-key", request.getHeader("Authorization"));
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("\"model\":\"demo-model\""));
            assertTrue(body.contains("um hello there"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void rewriteAsync_reportsFailureWhenResponseMissingXmlTags() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":\"Hello there\"}}]}"));
        server.start();

        try {
            OpenAiChatClient client = new OpenAiChatClient(new OkHttpClient());
            LlmSettings settings = new LlmSettings(server.url("/").toString(), "secret-key", "demo-model", "");
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>();

            client.rewriteAsync(settings, "hello there", new OpenAiChatClient.Callback() {
                @Override
                public void onSuccess(String text) {
                    fail("Expected failure callback");
                }

                @Override
                public void onFailure(String message, Throwable error) {
                    failure.set(message);
                    latch.countDown();
                }

                @Override
                public void onCanceled() {
                    fail("Expected failure, not cancel");
                }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(failure.get().contains("Missing rewritten_text XML tags"));
        } finally {
            server.shutdown();
        }
    }
}
