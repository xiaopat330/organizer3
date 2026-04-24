package com.organizer3.utilities.task.organize;

import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.shell.SessionContext;
import com.organizer3.utilities.task.TaskRun;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class OrganizeNormalizeTaskTest extends OrganizeTaskTestBase {

    @Test
    void previewPassesDryRunTrueAndNormalizePhase() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeNormalizePreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(Set.of(OrganizeVolumeService.Phase.NORMALIZE)),
                anyInt(), anyInt(), eq(true));
    }

    @Test
    void executePassesDryRunFalseAndNormalizePhase() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeNormalizeTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(Set.of(OrganizeVolumeService.Phase.NORMALIZE)),
                anyInt(), anyInt(), eq(false));
    }

    @Test
    void resultJsonEmbeddedInOrganizePhaseEnded() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeNormalizePreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        var json = resultJson(run);
        assertTrue(json.has("volumeId"), "Result JSON should contain volumeId");
        assertTrue(json.has("summary"),  "Result JSON should contain summary");
    }

    @Test
    void mountAndUnmountPhasesAlwaysRun() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeNormalizePreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        var phases = phaseEnded(run);
        assertTrue(phases.stream().anyMatch(p -> "mount".equals(p.phaseId())));
        assertTrue(phases.stream().anyMatch(p -> "unmount".equals(p.phaseId())));
    }
}
