package com.organizer3.utilities.health.checks;

import com.organizer3.repository.AttributionFindingsRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttributionFindingsCheckTest {

    @Test
    void openCountIsReflectedInCheckResult() {
        AttributionFindingsRepository repo = mock(AttributionFindingsRepository.class);
        when(repo.count("open")).thenReturn(5);
        when(repo.list("open", 20)).thenReturn(List.of(
                new AttributionFindingsRepository.Finding(
                        101L, "cast_mismatch", 0.75, "[\"A\"]",
                        "2026-01-01", "2026-01-02", "open", null, null, null)));

        AttributionFindingsCheck check = new AttributionFindingsCheck(repo);
        LibraryHealthCheck.CheckResult result = check.run();

        assertEquals(5, result.total());
        assertEquals(1, result.rows().size());
        assertEquals("101:cast_mismatch", result.rows().get(0).id());
        assertTrue(result.rows().get(0).detail().contains("0.75"));
    }

    @Test
    void emptyReturnsZeroCount() {
        AttributionFindingsRepository repo = mock(AttributionFindingsRepository.class);
        when(repo.count("open")).thenReturn(0);
        when(repo.list("open", 20)).thenReturn(List.of());

        AttributionFindingsCheck check = new AttributionFindingsCheck(repo);
        LibraryHealthCheck.CheckResult result = check.run();

        assertEquals(0, result.total());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void checkMetadata() {
        AttributionFindingsRepository repo = mock(AttributionFindingsRepository.class);
        when(repo.count("open")).thenReturn(0);
        when(repo.list("open", 20)).thenReturn(List.of());

        AttributionFindingsCheck check = new AttributionFindingsCheck(repo);
        assertEquals("attribution_findings", check.id());
        assertEquals(LibraryHealthCheck.FixRouting.SURFACE_ONLY, check.fixRouting());
    }
}
