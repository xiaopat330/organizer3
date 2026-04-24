package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.covers.OrphanedCoversService;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Thin wrapper over {@link OrphanedCoversService}. The service has its own tests;
 * this just verifies the check adapts preview rows into {@link LibraryHealthCheck.Finding}
 * correctly and passes through the total count.
 */
class OrphanedCoversCheckTest {

    @Test
    void reportsZeroWhenServicePreviewIsEmpty() {
        OrphanedCoversService svc = mock(OrphanedCoversService.class);
        when(svc.preview()).thenReturn(new OrphanedCoversService.OrphanPreview(List.of(), 0));

        LibraryHealthCheck.CheckResult result = new OrphanedCoversCheck(svc).run();
        assertEquals(0, result.total());
        assertEquals(0, result.rows().size());
    }

    @Test
    void mapsOrphanRowsToFindings() {
        OrphanedCoversService svc = mock(OrphanedCoversService.class);
        var row = new OrphanedCoversService.OrphanRow(
                "ABP", "ABP-001.jpg", "/covers/ABP/ABP-001.jpg", 2048);
        when(svc.preview()).thenReturn(new OrphanedCoversService.OrphanPreview(List.of(row), 2048));

        LibraryHealthCheck.CheckResult result = new OrphanedCoversCheck(svc).run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        LibraryHealthCheck.Finding f = result.rows().get(0);
        assertEquals("ABP/ABP-001.jpg", f.id());
        assertEquals("/covers/ABP/ABP-001.jpg", f.label());
        assertTrue(f.detail().contains("2.0 KB"));
        assertTrue(f.detail().contains("ABP-001"));
    }

    @Test
    void capsSampleAtLimitButPreservesTotal() {
        OrphanedCoversService svc = mock(OrphanedCoversService.class);
        List<OrphanedCoversService.OrphanRow> many = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new OrphanedCoversService.OrphanRow(
                    "ABP", "ABP-" + i + ".jpg", "/covers/ABP/ABP-" + i + ".jpg", 100));
        }
        when(svc.preview()).thenReturn(new OrphanedCoversService.OrphanPreview(many, 20_000));

        LibraryHealthCheck.CheckResult result = new OrphanedCoversCheck(svc).run();
        assertEquals(200, result.total(), "total must pass through regardless of sample cap");
        assertEquals(50, result.rows().size(), "sample capped at 50");
    }
}
