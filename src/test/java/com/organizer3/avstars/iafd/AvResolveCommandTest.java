package com.organizer3.avstars.iafd;

import com.organizer3.avstars.command.AvResolveCommand;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvResolveCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock IafdClient iafdClient;

    @TempDir Path tempDir;

    private AvResolveCommand cmd;
    private StringWriter out;
    private PlainCommandIO io;

    @BeforeEach
    void setUp() {
        cmd = new AvResolveCommand(actressRepo, iafdClient,
                new IafdSearchParser(), new IafdProfileParser(), tempDir);
        out = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(out));
    }

    private AvActress actress(String name, String iafdId) {
        return AvActress.builder()
                .id(1L)
                .volumeId("qnap_av")
                .folderName(name)
                .stageName(name)
                .iafdId(iafdId)
                .firstSeenAt(LocalDateTime.now())
                .build();
    }

    // ── missing subcommand ─────────────────────────────────────────────────────

    @Test
    void noArgsShowsUsage() {
        // dispatcher merges two-word prefix into args[0]; no further args → length 1
        cmd.execute(new String[]{"av resolve"}, null, io);
        assertTrue(out.toString().contains("Usage:"));
        verifyNoInteractions(iafdClient);
    }

    // ── av resolve <name> ──────────────────────────────────────────────────────

    @Test
    void unknownActressShowsMessage() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());
        cmd.execute(new String[]{"av resolve", "Nobody"}, null, io);
        assertTrue(out.toString().contains("No actress found"));
        verifyNoInteractions(iafdClient);
    }

    @Test
    void iafdSearchFailureReportsError() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(actress("Anissa Kate", null)));
        when(iafdClient.fetchSearch("Anissa Kate")).thenThrow(new IafdFetchException("timeout"));

        cmd.execute(new String[]{"av resolve", "Anissa Kate"}, null, io);

        assertTrue(out.toString().contains("IAFD search failed"));
        verify(actressRepo, never()).updateIafdFields(anyLong(), any(), any());
    }

    @Test
    void noIafdResultsReportsNotFound() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(actress("Anissa Kate", null)));
        when(iafdClient.fetchSearch("Anissa Kate")).thenReturn("<html><table id='tblFeatured'></table></html>");

        cmd.execute(new String[]{"av resolve", "Anissa Kate"}, null, io);

        assertTrue(out.toString().contains("No IAFD results"));
        verify(actressRepo, never()).updateIafdFields(anyLong(), any(), any());
    }

    @Test
    void singleSearchResultAutoSelectedAndProfileFetched() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(actress("Anissa Kate", null)));

        String searchHtml = wrapInTable(buildSearchRow("53696199-bf71-4219-b58a-bd1e2fae9f1e", "Anissa Kate",
                "", "2010", "2024", "352", ""));
        when(iafdClient.fetchSearch("Anissa Kate")).thenReturn(searchHtml);
        when(iafdClient.fetchProfile("53696199-bf71-4219-b58a-bd1e2fae9f1e"))
                .thenReturn("<html><div id='perftabs'>Performer Credits (352)</div></html>");

        cmd.execute(new String[]{"av resolve", "Anissa Kate"}, null, io);

        verify(actressRepo).updateIafdFields(eq(1L), any(), any());
        assertTrue(out.toString().contains("Resolved"));
    }

    // ── av resolve all ─────────────────────────────────────────────────────────

    @Test
    void resolveAllNothingToResolve() {
        when(actressRepo.findUnresolved()).thenReturn(List.of());
        cmd.execute(new String[]{"av resolve", "all"}, null, io);
        assertTrue(out.toString().contains("All actresses are already resolved"));
        verifyNoInteractions(iafdClient);
    }

    @Test
    void resolveAllSingleMatchAutoResolves() {
        when(actressRepo.findUnresolved()).thenReturn(List.of(actress("Asa Akira", null)));

        String asaUuid = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa";
        String searchHtml = wrapInTable(buildSearchRow(asaUuid, "Asa Akira", "", "2006", "2016", "700", ""));
        when(iafdClient.fetchSearch("Asa Akira")).thenReturn(searchHtml);
        when(iafdClient.fetchProfile(asaUuid))
                .thenReturn("<html><div id='perftabs'>Performer Credits (700)</div></html>");

        cmd.execute(new String[]{"av resolve", "all"}, null, io);

        verify(actressRepo).updateIafdFields(eq(1L), any(), any());
        assertTrue(out.toString().contains("resolved"));
    }

    @Test
    void resolveAllAmbiguousSkipsAndReports() {
        when(actressRepo.findUnresolved()).thenReturn(List.of(actress("Jane Doe", null)));

        String searchHtml = buildSearchRow("11111111-1111-1111-1111-111111111111", "Jane Doe", "", "2010", "2020", "50", "")
                + buildSearchRow("22222222-2222-2222-2222-222222222222", "Jane Doe", "J. Doe", "2015", "2023", "12", "");
        when(iafdClient.fetchSearch("Jane Doe")).thenReturn(wrapInTable(searchHtml));

        cmd.execute(new String[]{"av resolve", "all"}, null, io);

        // Should not auto-resolve ambiguous
        verify(actressRepo, never()).updateIafdFields(anyLong(), any(), any());
        assertTrue(out.toString().contains("ambiguous") || out.toString().contains("candidates"));
    }

    // ── av resolve refresh <name> ──────────────────────────────────────────────

    @Test
    void refreshActressNotFoundShowsMessage() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());
        cmd.execute(new String[]{"av resolve", "refresh", "Unknown"}, null, io);
        assertTrue(out.toString().contains("No actress found"));
    }

    @Test
    void refreshActressNotYetResolvedShowsMessage() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(actress("Anissa Kate", null)));
        cmd.execute(new String[]{"av resolve", "refresh", "Anissa Kate"}, null, io);
        assertTrue(out.toString().contains("not yet resolved"));
        verifyNoInteractions(iafdClient);
    }

    @Test
    void refreshReFetchesProfileAndUpdates() {
        when(actressRepo.findAllByVideoCountDesc())
                .thenReturn(List.of(actress("Anissa Kate", "53696199-bf71-4219-b58a-bd1e2fae9f1e")));
        when(iafdClient.fetchProfile("53696199-bf71-4219-b58a-bd1e2fae9f1e"))
                .thenReturn("<html><div id='perftabs'>Performer Credits (360)</div></html>");

        cmd.execute(new String[]{"av resolve", "refresh", "Anissa Kate"}, null, io);

        verify(iafdClient).fetchProfile("53696199-bf71-4219-b58a-bd1e2fae9f1e");
        verify(actressRepo).updateIafdFields(eq(1L), any(), any());
        assertTrue(out.toString().contains("Updated"));
    }

    // ── command metadata ───────────────────────────────────────────────────────

    @Test
    void nameIsAvResolve() {
        assertEquals("av resolve", cmd.name());
    }

    // ── HTML fixture helpers ───────────────────────────────────────────────────

    private String buildSearchRow(String uuid, String name, String akas,
                                  String from, String to, String count, String headshotUrl) {
        return "<tr>"
                + "<td>" + (headshotUrl.isEmpty() ? "<img src='' />" : "<img src='" + headshotUrl + "' />") + "</td>"
                + "<td><a href='/person.rme/id=" + uuid + "'>" + name + "</a></td>"
                + "<td>" + akas + "</td>"
                + "<td>" + from + "</td>"
                + "<td>" + to + "</td>"
                + "<td>" + count + "</td>"
                + "</tr>";
    }

    private String wrapInTable(String rows) {
        return "<html><table id='tblFeatured'><tbody>" + rows + "</tbody></table></html>";
    }
}
