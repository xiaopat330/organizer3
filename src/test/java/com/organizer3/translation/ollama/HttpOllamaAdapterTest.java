package com.organizer3.translation.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpOllamaAdapter}.
 *
 * Uses a mock {@link HttpClient} (passed via the package-visible constructor) so no real
 * Ollama daemon is required.
 */
class HttpOllamaAdapterTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void generate_ioException_doesNotSetThreadInterruptFlag() throws Exception {
        // Regression: catch (IOException | InterruptedException) was calling
        // Thread.currentThread().interrupt() for both branches. When Ollama times out
        // (HttpTimeoutException extends IOException), the interrupt flag was falsely set,
        // causing the TranslationWorker loop to exit silently on the next iteration.
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("request timed out"));

        HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", mockHttp, json);

        OllamaRequest req = new OllamaRequest("gemma4:e4b", "Translate: テスト", null, null,
                Duration.ofSeconds(5));

        assertThrows(OllamaException.class, () -> adapter.generate(req));

        assertFalse(Thread.currentThread().isInterrupted(),
                "IOException from http.send() must not leave the thread interrupt flag set");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_interruptedException_setsInterruptFlag() throws Exception {
        // Verify the correct branch: a real InterruptedException must still set the flag.
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("interrupted"));

        HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", mockHttp, json);

        OllamaRequest req = new OllamaRequest("gemma4:e4b", "Translate: テスト", null, null,
                Duration.ofSeconds(5));

        assertThrows(OllamaException.class, () -> adapter.generate(req));

        assertTrue(Thread.currentThread().isInterrupted(),
                "InterruptedException from http.send() must set the thread interrupt flag");

        // Clear flag so it doesn't leak into subsequent tests.
        Thread.interrupted();
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_formatJsonTrue_setsTopLevelFormatJsonInBody() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"response\":\"{}\"}");
        ArgumentCaptor<HttpRequest> reqCap = ArgumentCaptor.forClass(HttpRequest.class);
        when(mockHttp.send(reqCap.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", mockHttp, json);

        OllamaRequest req = new OllamaRequest("gemma4:e4b", "pick something", null, null,
                Duration.ofSeconds(5), true);

        adapter.generate(req);

        JsonNode body = json.readTree(captureBody(reqCap.getValue()));
        assertTrue(body.has("format"), "expected top-level 'format' key when formatJson=true");
        assertEquals("json", body.get("format").asText());
        // sanity: must NOT be inside options
        assertFalse(body.path("options").has("format"),
                "'format' must be at top level, not in options");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_formatJsonFalse_omitsFormatKey() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"response\":\"hello\"}");
        ArgumentCaptor<HttpRequest> reqCap = ArgumentCaptor.forClass(HttpRequest.class);
        when(mockHttp.send(reqCap.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", mockHttp, json);

        // Uses 5-arg back-compat constructor → formatJson defaults to false
        OllamaRequest req = new OllamaRequest("gemma4:e4b", "Translate: テスト", null, null,
                Duration.ofSeconds(5));

        adapter.generate(req);

        JsonNode body = json.readTree(captureBody(reqCap.getValue()));
        assertFalse(body.has("format"),
                "request body must not include 'format' when formatJson=false (translation back-compat)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_keepAlive_setsTopLevelKeepAliveInBody() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"response\":\"hi\"}");
        ArgumentCaptor<HttpRequest> reqCap = ArgumentCaptor.forClass(HttpRequest.class);
        when(mockHttp.send(reqCap.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", mockHttp, json);

        OllamaRequest req = new OllamaRequest("gemma4:e4b", "x", null, null,
                Duration.ofSeconds(5), false, "15m");

        adapter.generate(req);

        JsonNode body = json.readTree(captureBody(reqCap.getValue()));
        assertEquals("15m", body.path("keep_alive").asText(), "expected top-level keep_alive=15m");
        assertFalse(body.path("options").has("keep_alive"),
                "'keep_alive' must be at top level, not in options");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_keepAliveNull_omitsKeepAliveKey() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"response\":\"hi\"}");
        ArgumentCaptor<HttpRequest> reqCap = ArgumentCaptor.forClass(HttpRequest.class);
        when(mockHttp.send(reqCap.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", mockHttp, json);

        // 5-arg back-compat constructor → keepAlive defaults to null
        OllamaRequest req = new OllamaRequest("gemma4:e4b", "x", null, null,
                Duration.ofSeconds(5));

        adapter.generate(req);

        JsonNode body = json.readTree(captureBody(reqCap.getValue()));
        assertFalse(body.has("keep_alive"),
                "request body must not include 'keep_alive' when keepAlive=null (translation back-compat)");
    }

    /** Extracts the JSON body string from an HttpRequest by draining its BodyPublisher. */
    private static String captureBody(HttpRequest req) {
        AtomicReference<String> captured = new AtomicReference<>();
        req.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            private final StringBuilder sb = new StringBuilder();
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buf) {
                byte[] b = new byte[buf.remaining()];
                buf.get(b);
                sb.append(new String(b));
            }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
            @Override public void onComplete() { captured.set(sb.toString()); }
        });
        return captured.get();
    }
}
