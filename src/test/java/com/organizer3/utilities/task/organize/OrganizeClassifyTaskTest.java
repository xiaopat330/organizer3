package com.organizer3.utilities.task.organize;

import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.shell.SessionContext;
import com.organizer3.utilities.task.TaskRun;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class OrganizeClassifyTaskTest extends OrganizeTaskTestBase {

    @Test
    void previewPassesDryRunTrueAndClassifyPhase() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeClassifyPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(Set.of(OrganizeVolumeService.Phase.CLASSIFY)),
                anyInt(), anyInt(), eq(true));
    }

    @Test
    void executePassesDryRunFalseAndClassifyPhase() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeClassifyTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        TaskRun run = runAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).organize(any(), any(), any(), any(),
                eq(Set.of(OrganizeVolumeService.Phase.CLASSIFY)),
                anyInt(), anyInt(), eq(false));
    }

    @Test
    void resultJsonEmbeddedInOrganizePhaseEnded() throws Exception {
        SessionContext session = new SessionContext();
        var task = new OrganizeClassifyPreviewTask(mockService, jdbi, orgConfig,
                () -> buildInvoker(session));

        var json = resultJson(runAndAwait(task));
        assertTrue(json.has("volumeId"));
        assertTrue(json.has("summary"));
    }
}
