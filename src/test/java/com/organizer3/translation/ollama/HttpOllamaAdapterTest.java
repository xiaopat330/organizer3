package com.organizer3.translation.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
}
