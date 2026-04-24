package com.organizer3.utilities.task.organize;

import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.shell.SessionContext;
import com.organizer3.utilities.task.TaskRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class OrganizeAllTaskTest extends OrganizeTaskTestBase {

    @Test
    void previewPassesDryRunTrueAndAllPhases() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeAllPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(OrganizeVolumeService.ALL),
                anyInt(), anyInt(), eq(true));
    }

    @Test
    void executePassesDryRunFalseAndAllPhases() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeAllTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(OrganizeVolumeService.ALL),
                anyInt(), anyInt(), eq(false));
    }

    @Test
    void resultJsonEmbeddedInOrganizePhaseEnded() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeAllPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        var json = resultJson(runAndAwait(task));
        assertTrue(json.has("volumeId"));
        assertTrue(json.has("summary"));
    }

    @Test
    void mountUnmountOrganizePhasesAllPresent() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeAllPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);
        var phases = phaseEnded(run);
        assertTrue(phases.stream().anyMatch(p -> "mount".equals(p.phaseId())));
        assertTrue(phases.stream().anyMatch(p -> "organize".equals(p.phaseId())));
        assertTrue(phases.stream().anyMatch(p -> "unmount".equals(p.phaseId())));
    }
}
