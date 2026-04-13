package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckNamesCommandTest {

    private ActressRepository actressRepo;
    private CheckNamesCommand cmd;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        actressRepo = mock(ActressRepository.class);
        cmd = new CheckNamesCommand(actressRepo, new ActressNameCheckService());
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));

        // Minimal AppConfig with one volume so resolveSmbBase works
        VolumeConfig vol = new VolumeConfig("qnap", "//qnap2/jav", null, null, null);
        OrganizerConfig cfg = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(vol), List.of(), List.of());
        AppConfig.initializeForTest(cfg);
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    @Test
    void noSubcommand_printsSummary() {
        Actress a = actress(1, "Yuna Shiina");
        Actress b = actress(2, "Shiina Yuna");
        when(actressRepo.findAll()).thenReturn(List.of(a, b));
        when(actressRepo.countAllTitlesByActress()).thenReturn(Map.of(1L, 10, 2L, 1));

        cmd.execute(new String[]{"check names"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("Name-check summary"), "should print summary header");
        assertTrue(out.contains("Name-order swaps:  1"), "should count 1 swap");
    }

    @Test
    void swapsSubcommand_listsSwapPairs() {
        Actress a = actress(1, "Yuna Shiina");
        Actress b = actress(2, "Shiina Yuna");
        when(actressRepo.findAll()).thenReturn(List.of(a, b));
        when(actressRepo.countAllTitlesByActress()).thenReturn(Map.of(1L, 10, 2L, 1));
        when(actressRepo.findFilingLocations()).thenReturn(Map.of());

        cmd.execute(new String[]{"check names", "swaps"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("NAME-ORDER SWAPS"), "should print swap section header");
        assertTrue(out.contains("Shiina Yuna"), "should show suspect name");
        assertTrue(out.contains("Yuna Shiina"), "should show canonical name");
    }

    @Test
    void swapsSubcommand_showsSmbPaths() {
        Actress a = actress(1, "Yuna Shiina");
        Actress b = actress(2, "Shiina Yuna");
        when(actressRepo.findAll()).thenReturn(List.of(a, b));
        when(actressRepo.countAllTitlesByActress()).thenReturn(Map.of(1L, 10, 2L, 1));
        // Suspect is b (id=2); give it a filing location
        when(actressRepo.findFilingLocations()).thenReturn(Map.of(
                2L, List.of(new ActressRepository.FilingLocation(2L, "ABC-001", "qnap", "/stars/Shiina Yuna/Shiina Yuna (ABC-001)"))
        ));

        cmd.execute(new String[]{"check names", "swaps"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("//qnap2/jav/stars/Shiina Yuna/Shiina Yuna (ABC-001)"),
                "should show full SMB path for suspect filing location");
    }

    @Test
    void swapsSubcommand_zeroTitleSuspect_showsDbOnlyMessage() {
        Actress a = actress(1, "Yuna Shiina");
        Actress b = actress(2, "Shiina Yuna");
        // b has 0 titles in count map
        when(actressRepo.findAll()).thenReturn(List.of(a, b));
        when(actressRepo.countAllTitlesByActress()).thenReturn(Map.of(1L, 10));
        when(actressRepo.findFilingLocations()).thenReturn(Map.of());

        cmd.execute(new String[]{"check names", "swaps"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("DB record only"), "should annotate 0-title suspect as DB-only");
    }

    @Test
    void typosSubcommand_listsTypoPairs() {
        Actress a = actress(1, "Aoi Sola");
        Actress b = actress(2, "Aoy Sola");
        when(actressRepo.findAll()).thenReturn(List.of(a, b));
        when(actressRepo.countAllTitlesByActress()).thenReturn(Map.of(1L, 50, 2L, 1));
        when(actressRepo.findFilingLocations()).thenReturn(Map.of());

        cmd.execute(new String[]{"check names", "typos"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("LIKELY TYPO PAIRS"), "should print typo section header");
        assertTrue(out.contains("Aoy Sola"), "should show suspect name");
        assertTrue(out.contains("Aoi Sola"), "should show canonical name");
    }

    @Test
    void typosSubcommand_emptyWhenNone() {
        Actress a = actress(1, "Yuna Shiina");
        when(actressRepo.findAll()).thenReturn(List.of(a));
        when(actressRepo.countAllTitlesByActress()).thenReturn(Map.of(1L, 10));
        when(actressRepo.findFilingLocations()).thenReturn(Map.of());

        cmd.execute(new String[]{"check names", "typos"}, ctx, io);

        assertTrue(output.toString().contains("No likely typo pairs found."));
    }

    @Test
    void commandName_isCheckNames() {
        assertEquals("check names", cmd.name());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Actress actress(long id, String canonicalName) {
        return Actress.builder()
                .id(id)
                .canonicalName(canonicalName)
                .tier(Actress.Tier.LIBRARY)
                .build();
    }
}
