package com.organizer3.javdb.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActressAvatarStoreTest {

    @TempDir Path tempDir;

    @Test
    void download_writesFileWithExtensionFromContentType() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(new byte[]{1, 2, 3});
        when(resp.headers()).thenReturn(HttpHeaders.of(
                Map.of("content-type", List.of("image/png")), (a, b) -> true));
        doReturn(resp).when(client).send(any(HttpRequest.class), any());

        ActressAvatarStore store = new ActressAvatarStore(tempDir, client);
        String rel = store.download("ex3z", "https://cdn.example.com/avatar");

        assertEquals("actress-avatars/ex3z.png", rel);
        assertArrayEquals(new byte[]{1, 2, 3},
                Files.readAllBytes(tempDir.resolve(rel)));
    }

    @Test
    void download_idempotent_skipsWhenFileExists() throws Exception {
        Path dir = tempDir.resolve("actress-avatars");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("abc.jpg"), "cached");

        HttpClient client = mock(HttpClient.class);
        ActressAvatarStore store = new ActressAvatarStore(tempDir, client);

        String rel = store.download("abc", "https://cdn.example.com/whatever");

        assertEquals("actress-avatars/abc.jpg", rel);
        verify(client, never()).send(any(), any());
    }

    @Test
    void download_returnsNullOnNon2xx() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(403);
        when(resp.body()).thenReturn(new byte[0]);
        when(resp.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        doReturn(resp).when(client).send(any(HttpRequest.class), any());

        ActressAvatarStore store = new ActressAvatarStore(tempDir, client);
        assertNull(store.download("zzz", "https://cdn.example.com/missing"));
    }

    @Test
    void download_returnsNullOnIoException() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doThrow(new java.io.IOException("boom")).when(client).send(any(HttpRequest.class), any());

        ActressAvatarStore store = new ActressAvatarStore(tempDir, client);
        assertNull(store.download("zzz", "https://cdn.example.com/x"));
    }

    @Test
    void download_returnsNullForBlankInputs() {
        ActressAvatarStore store = new ActressAvatarStore(tempDir, mock(HttpClient.class));
        assertNull(store.download(null, "https://x"));
        assertNull(store.download("slug", null));
        assertNull(store.download("", "https://x"));
    }

    @Test
    void extensionFor_prefersContentTypeOverUrl() {
        assertEquals("jpg",  ActressAvatarStore.extensionFor("image/jpeg", "https://x/a.png"));
        assertEquals("png",  ActressAvatarStore.extensionFor("image/png",  "https://x/a"));
        assertEquals("webp", ActressAvatarStore.extensionFor("image/webp", "https://x/a"));
    }

    @Test
    void extensionFor_fallsBackToUrl() {
        assertEquals("jpg",  ActressAvatarStore.extensionFor(null, "https://x/a.jpeg?v=1"));
        assertEquals("png",  ActressAvatarStore.extensionFor(null, "https://x/a.png"));
        assertEquals("jpg",  ActressAvatarStore.extensionFor(null, "https://x/avatar"));
    }
}
