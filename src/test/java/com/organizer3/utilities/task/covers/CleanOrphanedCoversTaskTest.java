package com.organizer3.utilities.task.covers;

import com.organizer3.covers.CoverPath;
import com.organizer3.repository.TitleRepository;
import com.organizer3.utilities.covers.OrphanedCoversService;
import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CleanOrphanedCoversTaskTest {

    @TempDir Path tmp;
    private CoverPath coverPath;
    private TitleRepository titleRepo;
    private OrphanedCoversService service;

    @BeforeEach
    void setup() throws IOException {
        coverPath = new CoverPath(tmp);
        Files.createDirectories(coverPath.root());
        titleRepo = mock(TitleRepository.class);
        service = new OrphanedCoversService(coverPath, titleRepo);
    }

    @Test
    void deletesOrphansAndEmitsOkPhase() throws Exception {
        Path label = coverPath.root().resolve("ABP");
        Files.createDirectories(label);
        Files.writeString(label.resolve("ABP-00001.jpg"), "x");
        Files.writeString(label.resolve("ABP-00002.jpg"), "y");
        when(titleRepo.findByCode(anyString())).thenReturn(Optional.empty());

        CleanOrphanedCoversTask task = new CleanOrphanedCoversTask(service);
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start(CleanOrphanedCoversTask.ID, new TaskInputs(Map.of()));
            awaitCompletion(run);
            assertEquals(TaskRun.Status.OK, run.status());
            assertFalse(Files.exists(label.resolve("ABP-00001.jpg")));
            assertFalse(Files.exists(label.resolve("ABP-00002.jpg")));
            boolean endedOk = run.eventSnapshot().stream()
                    .anyMatch(e -> e instanceof TaskEvent.PhaseEnded pe && "ok".equals(pe.status()));
            assertTrue(endedOk);
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void emptyCoversDirIsNoop() throws Exception {
        CleanOrphanedCoversTask task = new CleanOrphanedCoversTask(service);
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start(CleanOrphanedCoversTask.ID, new TaskInputs(Map.of()));
            awaitCompletion(run);
            assertEquals(TaskRun.Status.OK, run.status());
        } finally {
            runner.shutdown();
        }
    }

    private static void awaitCompletion(TaskRun run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (run.status() == TaskRun.Status.RUNNING) {
            if (System.nanoTime() > deadline) fail("task did not complete");
            Thread.sleep(10);
        }
    }
}
