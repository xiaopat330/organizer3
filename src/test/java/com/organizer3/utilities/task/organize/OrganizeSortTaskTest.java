package com.organizer3.utilities.task.organize;

import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.shell.SessionContext;
import com.organizer3.utilities.task.TaskRun;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class OrganizeSortTaskTest extends OrganizeTaskTestBase {

    @Test
    void previewPassesDryRunTrueAndSortPhase() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeSortPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(Set.of(OrganizeVolumeService.Phase.SORT)),
                anyInt(), anyInt(), eq(true));
    }

    @Test
    void executePassesDryRunFalseAndSortPhase() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeSortTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(Set.of(OrganizeVolumeService.Phase.SORT)),
                anyInt(), anyInt(), eq(false));
    }

    @Test
    void resultJsonEmbeddedInOrganizePhaseEnded() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeSortPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        var json = resultJson(runAndAwait(task));
        assertTrue(json.has("volumeId"));
        assertTrue(json.has("summary"));
    }

    @Test
    void organizePhaseEndedIsOk() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeSortPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        var pe = phaseEnded(runAndAwait(task), "organize");
        assertEquals("ok", pe.status());
    }
}
