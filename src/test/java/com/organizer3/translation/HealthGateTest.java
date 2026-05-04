package com.organizer3.translation;

import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaModel;
import com.organizer3.translation.repository.TranslationCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthGateTest {

    @Mock
    private OllamaAdapter ollamaAdapter;

    @Mock
    private TranslationCacheRepository cacheRepo;

    private HealthGate healthGate;

    @BeforeEach
    void setUp() {
        healthGate = new HealthGate(ollamaAdapter, cacheRepo, TranslationConfig.DEFAULTS);
    }

    @Test
    void isHealthy_trueWhenOllamaUpAndModelPresent() {
        when(ollamaAdapter.isHealthy()).thenReturn(true);
        when(ollamaAdapter.listModels()).thenReturn(
                List.of(new OllamaModel("gemma4:e4b", 1_000_000L)));
        when(cacheRepo.latencyP95(anyInt())).thenReturn(5_000L);

        HealthStatus status = healthGate.refresh();

        assertTrue(status.ollamaReachable());
        assertTrue(status.tier1ModelPresent());
        assertTrue(status.overall());
        assertEquals("OK", status.message());
    }

    @Test
    void isHealthy_falseWhenOllamaDown() {
        when(ollamaAdapter.isHealthy()).thenReturn(false);

        HealthStatus status = healthGate.refresh();

        assertFalse(status.ollamaReachable());
        assertFalse(status.tier1ModelPresent());
        assertFalse(status.overall());
        assertTrue(status.message().contains("unreachable"));
    }

    @Test
    void isHealthy_falseWhenModelMissing() {
        when(ollamaAdapter.isHealthy()).thenReturn(true);
        when(ollamaAdapter.listModels()).thenReturn(List.of()); // no models installed

        HealthStatus status = healthGate.refresh();

        assertTrue(status.ollamaReachable());
        assertFalse(status.tier1ModelPresent());
        assertFalse(status.overall());
        assertTrue(status.message().contains("gemma4:e4b"));
    }

    @Test
    void isHealthy_trueWhenModelPresentWithDifferentVersionSuffix() {
        // Only exact name match counts
        when(ollamaAdapter.isHealthy()).thenReturn(true);
        when(ollamaAdapter.listModels()).thenReturn(
                List.of(new OllamaModel("gemma4:latest", 1_000_000L)));
        // latencyP95 not reached because model is not found — don't stub it

        HealthStatus status = healthGate.refresh();

        // gemma4:latest != gemma4:e4b — should be unhealthy
        assertFalse(status.tier1ModelPresent());
        assertFalse(status.overall());
    }

    @Test
    void latencyHigh_overallStillTrue() {
        // High latency is advisory — overall remains true if daemon + model are present
        when(ollamaAdapter.isHealthy()).thenReturn(true);
        when(ollamaAdapter.listModels()).thenReturn(
                List.of(new OllamaModel("gemma4:e4b", 1_000_000L)));
        when(cacheRepo.latencyP95(anyInt())).thenReturn(120_000L); // 120s — above 90s threshold

        HealthStatus status = healthGate.refresh();

        assertTrue(status.ollamaReachable());
        assertTrue(status.tier1ModelPresent());
        assertFalse(status.latencyOk());
        assertTrue(status.overall()); // daemon + model present → overall still true
        assertTrue(status.message().contains("high"));
    }

    @Test
    void ollamaExceptionTreatedAsDown() {
        when(ollamaAdapter.isHealthy()).thenThrow(new RuntimeException("connect refused"));

        HealthStatus status = healthGate.refresh();

        assertFalse(status.ollamaReachable());
        assertFalse(status.overall());
    }

    @Test
    void cachedResultReturnedWithinTtl() {
        when(ollamaAdapter.isHealthy()).thenReturn(true);
        when(ollamaAdapter.listModels()).thenReturn(
                List.of(new OllamaModel("gemma4:e4b", 1_000_000L)));
        when(cacheRepo.latencyP95(anyInt())).thenReturn(null);

        // First call computes
        healthGate.refresh();

        // Reset mocks — if cached result is returned, adapter won't be called again
        clearInvocations(ollamaAdapter, cacheRepo);

        // currentStatus() should return cached value (not call adapter again)
        HealthStatus cached = healthGate.currentStatus();
        assertTrue(cached.overall());

        // Adapter should NOT be called again within TTL
        verify(ollamaAdapter, never()).isHealthy();
    }

    @Test
    void isHealthy_delegatesToCurrentStatus() {
        when(ollamaAdapter.isHealthy()).thenReturn(true);
        when(ollamaAdapter.listModels()).thenReturn(
                List.of(new OllamaModel("gemma4:e4b", 1_000_000L)));
        when(cacheRepo.latencyP95(anyInt())).thenReturn(null);

        assertTrue(healthGate.isHealthy());
    }
}
