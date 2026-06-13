package com.organizer3.utilities.task.actress;

import com.organizer3.db.AgeAtReleaseRecomputer;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LoadAllActressesTask} focusing on the age_at_release recompute trigger
 * (Task 2b): recomputeAll() must be called exactly once per batch (not per actress), and only
 * when at least one actress was loaded successfully.
 */
class LoadAllActressesTaskTest {

    private ActressYamlLoader loader;
    private AgeAtReleaseRecomputer recomputer;
    private LoadAllActressesTask task;
    private TaskIO io;
    private TaskInputs inputs;

    @BeforeEach
    void setUp() {
        loader = mock(ActressYamlLoader.class);
        recomputer = mock(AgeAtReleaseRecomputer.class);
        when(recomputer.recomputeAll()).thenReturn(0);
        task = new LoadAllActressesTask(loader, recomputer);
        io = mock(TaskIO.class);
        inputs = mock(TaskInputs.class);
    }

    // ── recompute-on-success ──────────────────────────────────────────────────

    @Test
    void recomputeCalledOnceAfterBatchWithAllSuccesses() throws Exception {
        when(loader.listSlugs()).thenReturn(List.of("slug_a", "slug_b", "slug_c"));
        when(loader.loadOne("slug_a")).thenReturn(new ActressYamlLoader.LoadResult("A", 1L, 0, 0, List.of(), false));
        when(loader.loadOne("slug_b")).thenReturn(new ActressYamlLoader.LoadResult("B", 2L, 0, 0, List.of(), false));
        when(loader.loadOne("slug_c")).thenReturn(new ActressYamlLoader.LoadResult("C", 3L, 0, 0, List.of(), true));

        task.run(inputs, io);

        // once per batch, not once per actress
        verify(recomputer, times(1)).recomputeAll();
    }

    @Test
    void recomputeCalledOnceWhenSomeSlugsFail() throws Exception {
        when(loader.listSlugs()).thenReturn(List.of("ok_slug", "bad_slug"));
        when(loader.loadOne("ok_slug")).thenReturn(new ActressYamlLoader.LoadResult("OK", 1L, 0, 0, List.of(), false));
        when(loader.loadOne("bad_slug")).thenThrow(new RuntimeException("parse error"));

        task.run(inputs, io);

        // partial success: at least one loaded → recompute fires once
        verify(recomputer, times(1)).recomputeAll();
    }

    // ── recompute-not-called ──────────────────────────────────────────────────

    @Test
    void recomputeNotCalledWhenListSlugsFails() throws Exception {
        when(loader.listSlugs()).thenThrow(new IOException("classpath error"));

        task.run(inputs, io);

        verify(recomputer, never()).recomputeAll();
    }

    @Test
    void recomputeNotCalledWhenAllSlugsFail() throws Exception {
        when(loader.listSlugs()).thenReturn(List.of("bad_a", "bad_b"));
        when(loader.loadOne("bad_a")).thenThrow(new RuntimeException("fail a"));
        when(loader.loadOne("bad_b")).thenThrow(new RuntimeException("fail b"));

        task.run(inputs, io);

        // all failed: succeeded == 0 → recompute must not fire
        verify(recomputer, never()).recomputeAll();
    }

    @Test
    void recomputeNotCalledWhenSlugListIsEmpty() throws Exception {
        when(loader.listSlugs()).thenReturn(List.of());

        task.run(inputs, io);

        verify(recomputer, never()).recomputeAll();
    }

    // ── once-per-batch, not per-actress ──────────────────────────────────────

    @Test
    void recomputeCalledOnlyOnceEvenForManyActresses() throws Exception {
        List<String> slugs = List.of("a", "b", "c", "d", "e");
        when(loader.listSlugs()).thenReturn(slugs);
        for (String s : slugs) {
            when(loader.loadOne(s)).thenReturn(new ActressYamlLoader.LoadResult(s, 1L, 0, 0, List.of(), false));
        }

        task.run(inputs, io);

        // 5 actress loads → still exactly 1 recompute call
        verify(recomputer, times(1)).recomputeAll();
    }
}
