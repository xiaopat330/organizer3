package com.organizer3.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OllamaModelBootstrap}.
 *
 * <p>Mocks {@link HttpClient} (following the {@code HttpOllamaAdapterTest} pattern). The
 * implementation under test consumes pull stream bodies as a single multi-line string —
 * tests therefore supply newline-joined JSON objects rather than a true streaming body.
 * This matches the buffered approach chosen for the production code (see class javadoc).
 */
class OllamaModelBootstrapTest {

    private final ObjectMapper json = new ObjectMapper();
    private static final String BASE = "http://localhost:11434";

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> resp(int code, String body) {
        HttpResponse<String> r = (HttpResponse<String>) mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(code);
        when(r.body()).thenReturn(body);
        return r;
    }

    private static String tagsBody(String... names) {
        StringBuilder sb = new StringBuilder("{\"models\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(names[i]).append("\",\"size\":1}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String successPullStream() {
        return String.join("\n",
                "{\"status\":\"pulling manifest\"}",
                "{\"status\":\"downloading\",\"completed\":50,\"total\":100}",
                "{\"status\":\"downloading\",\"completed\":100,\"total\":100}",
                "{\"status\":\"success\"}");
    }

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

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void allModelsAlreadyPresent_returnsTrue_noPull() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> tags = resp(200, tagsBody("phi4:latest", "gemma3:12b"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tags);

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertTrue(b.ensureModelsReady(List.of("phi4", "gemma3:12b")));

        // Exactly one call (tags); no pull
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(cap.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(cap.getValue().uri().toString().endsWith("/api/tags"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneModelMissing_pullsIt_returnsTrue() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> tags = resp(200, tagsBody("phi4:latest"));
        HttpResponse<String> pull = resp(200, successPullStream());
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        when(httpClient.send(cap.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tags)
                .thenReturn(pull);

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertTrue(b.ensureModelsReady(List.of("phi4", "gemma3:12b")));

        List<HttpRequest> calls = cap.getAllValues();
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).uri().toString().endsWith("/api/tags"));
        assertTrue(calls.get(1).uri().toString().endsWith("/api/pull"));
        assertEquals("POST", calls.get(1).method());

        JsonNode body = json.readTree(captureBody(calls.get(1)));
        assertEquals("gemma3:12b", body.path("name").asText());
        assertTrue(body.path("stream").asBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ollamaUnreachable_returnsFalse_noException() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection refused"));

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertFalse(b.ensureModelsReady(List.of("phi4")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pullEndsWithoutSuccess_returnsFalse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        String noSuccess = String.join("\n",
                "{\"status\":\"pulling manifest\"}",
                "{\"status\":\"downloading\",\"completed\":50,\"total\":100}");
        HttpResponse<String> tags = resp(200, tagsBody());
        HttpResponse<String> pull = resp(200, noSuccess);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tags)
                .thenReturn(pull);

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertFalse(b.ensureModelsReady(List.of("phi4")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tagsReturnsNon2xx_returnsFalse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> err = resp(500, "internal server error");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(err);

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertFalse(b.ensureModelsReady(List.of("phi4")));
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestingBareName_matchesLocalLatestSuffixed_noPull() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> tags = resp(200, tagsBody("phi4:latest"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tags);

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertTrue(b.ensureModelsReady(List.of("phi4")));
        // Only the tags call — no pull
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestingLatestSuffixed_matchesLocalBareName_noPull() throws Exception {
        // Inverse direction of normalisation
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> tags = resp(200, tagsBody("phi4"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tags);

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertTrue(b.ensureModelsReady(List.of("phi4:latest")));
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamParseErrorOnOneLine_doesNotAbort_succeedsIfTerminalSuccessArrives() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        String mixed = String.join("\n",
                "{\"status\":\"pulling manifest\"}",
                "this is not json at all",
                "{\"status\":\"downloading\",\"completed\":100,\"total\":100}",
                "{\"status\":\"success\"}");
        HttpResponse<String> tags = resp(200, tagsBody());
        HttpResponse<String> pull = resp(200, mixed);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tags)
                .thenReturn(pull);

        OllamaModelBootstrap b = new OllamaModelBootstrap(httpClient, BASE, json);

        assertTrue(b.ensureModelsReady(List.of("phi4")));
    }
}
