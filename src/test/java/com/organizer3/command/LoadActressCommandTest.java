package com.organizer3.command;

import com.organizer3.db.AgeAtReleaseRecomputer;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Collections;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LoadActressCommand} focusing on the age_at_release recompute trigger (Task 2b).
 *
 * <p>Uses Mockito mocks for {@link ActressYamlLoader} and {@link AgeAtReleaseRecomputer}.
 */
class LoadActressCommandTest {

    private ActressYamlLoader loader;
    private AgeAtReleaseRecomputer recomputer;
    private LoadActressCommand cmd;
    private SessionContext ctx;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        loader = mock(ActressYamlLoader.class);
        recomputer = mock(AgeAtReleaseRecomputer.class);
        when(recomputer.recomputeAll()).thenReturn(0);
        cmd = new LoadActressCommand(loader, recomputer);
        ctx = new SessionContext();
        io = new PlainCommandIO(new PrintWriter(new StringWriter()));
    }

    // ── loadOne triggers ──────────────────────────────────────────────────────

    @Test
    void recomputeCalledOnceAfterSuccessfulLoadOne() throws Exception {
        when(loader.loadOne("sora_aoi", false))
                .thenReturn(new ActressYamlLoader.LoadResult("Sora Aoi", 1L, 0, 0, List.of(), false));

        cmd.execute(new String[]{"load actress", "sora_aoi"}, ctx, io);

        verify(recomputer, times(1)).recomputeAll();
    }

    @Test
    void recomputeNotCalledWhenLoadOneFails_notFound() throws Exception {
        when(loader.loadOne("no_such_slug", false))
                .thenThrow(new IllegalArgumentException("not found: no_such_slug"));

        cmd.execute(new String[]{"load actress", "no_such_slug"}, ctx, io);

        verify(recomputer, never()).recomputeAll();
    }

    @Test
    void recomputeNotCalledWhenLoadOneFails_ioException() throws Exception {
        when(loader.loadOne("bad_yaml", false))
                .thenThrow(new IOException("parse error"));

        cmd.execute(new String[]{"load actress", "bad_yaml"}, ctx, io);

        verify(recomputer, never()).recomputeAll();
    }

    @Test
    void recomputeNotCalledWhenSlugMissing() throws Exception {
        // "load actress" with no slug prints usage and does nothing
        cmd.execute(new String[]{"load actress"}, ctx, io);

        verify(recomputer, never()).recomputeAll();
        verifyNoInteractions(loader);
    }

    // ── loadAll triggers ──────────────────────────────────────────────────────

    @Test
    void recomputeCalledOnceAfterSuccessfulLoadAll() throws Exception {
        when(loader.loadAll(false)).thenReturn(List.of(
                new ActressYamlLoader.LoadResult("Sora Aoi", 1L, 0, 0, List.of(), false),
                new ActressYamlLoader.LoadResult("Yua Mikami", 2L, 3, 0, List.of(), true)));

        cmd.execute(new String[]{"load actresses"}, ctx, io);

        verify(recomputer, times(1)).recomputeAll();
    }

    @Test
    void recomputeNotCalledWhenLoadAllThrows() throws Exception {
        when(loader.loadAll(false)).thenThrow(new IOException("disk error"));

        cmd.execute(new String[]{"load actresses"}, ctx, io);

        verify(recomputer, never()).recomputeAll();
    }

    @Test
    void recomputeNotCalledWhenLoadAllReturnsEmpty() throws Exception {
        when(loader.loadAll(false)).thenReturn(List.of());

        cmd.execute(new String[]{"load actresses"}, ctx, io);

        // Empty list → early return before recompute
        verify(recomputer, never()).recomputeAll();
    }
}
