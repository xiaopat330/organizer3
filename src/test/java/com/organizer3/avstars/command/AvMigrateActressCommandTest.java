package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvMigrateActressCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvMigrateActressCommand command;

    @BeforeEach
    void setUp() {
        command = new AvMigrateActressCommand(actressRepo);
    }

    @Test
    void noArgsPrintsUsage() {
        command.execute(new String[]{"av migrate"}, ctx, io);
        verify(io, atLeast(1)).println(contains("Usage:"));
        verifyNoInteractions(actressRepo);
    }

    @Test
    void singleTokenArgPrintsUsage() {
        command.execute(new String[]{"av migrate", "onlyone"}, ctx, io);
        verify(io, atLeast(1)).println(contains("Usage:"));
    }

    @Test
    void twoTokenSpaceFormWorksForSingleWordNames() {
        AvActress from = actress(1L, "old", true /*fav*/);
        AvActress to   = actress(2L, "new", false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(from, to));

        command.execute(new String[]{"av migrate", "old", "new"}, ctx, io);

        verify(actressRepo).migrateCuration(1L, 2L);
    }

    @Test
    void arrowSeparatorFormAcceptsMultiWordNames() {
        AvActress from = actress(1L, "Old Name", false);
        AvActress to   = actress(2L, "New Name", false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(from, to));

        command.execute(new String[]{"av migrate", "Old", "Name", ">", "New", "Name"}, ctx, io);

        verify(actressRepo).migrateCuration(1L, 2L);
    }

    @Test
    void identicalOldAndNewIsRejected() {
        command.execute(new String[]{"av migrate", "same", "same"}, ctx, io);
        verify(io).println(contains("nothing to migrate"));
        verify(actressRepo, never()).migrateCuration(anyLong(), anyLong());
    }

    @Test
    void oldNotFoundPrintsDiagnostic() {
        AvActress to = actress(2L, "new", false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(to));

        command.execute(new String[]{"av migrate", "old", "new"}, ctx, io);

        verify(io).println(contains("No actress row found for old name"));
        verify(actressRepo, never()).migrateCuration(anyLong(), anyLong());
    }

    @Test
    void newNotFoundPrintsDiagnostic() {
        AvActress from = actress(1L, "old", false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(from));

        command.execute(new String[]{"av migrate", "old", "new"}, ctx, io);

        verify(io).println(contains("No actress row found for new name"));
        verify(actressRepo, never()).migrateCuration(anyLong(), anyLong());
    }

    @Test
    void folderLookupIsCaseSensitive() {
        // Production findByFolderName uses equals(), not equalsIgnoreCase() — avoids
        // accidental matches on visually-similar folder names. We verify by using
        // names that only differ by case: old is looked up as "Alina" but the row
        // only has "alina" — lookup must fail.
        AvActress from = actress(1L, "alina", false);
        AvActress to   = actress(2L, "Bob", false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(from, to));

        command.execute(new String[]{"av migrate", "Alina", "Bob"}, ctx, io);

        verify(io).println(contains("No actress row found for old name"));
        verify(actressRepo, never()).migrateCuration(anyLong(), anyLong());
    }

    @Test
    void noCurationDataStillProceedsButWarns() {
        AvActress from = actress(1L, "old", false);
        AvActress to   = actress(2L, "new", false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(from, to));

        command.execute(new String[]{"av migrate", "old", "new"}, ctx, io);

        verify(io).println(contains("no curation data to migrate"));
        verify(actressRepo).migrateCuration(1L, 2L);
    }

    @Test
    void happyPathPrintsCurationSummaryAndCallsMigrate() {
        AvActress from = AvActress.builder().id(1L).stageName("Old Name").folderName("old")
                .volumeId("qnap_av").favorite(true).bookmark(true).grade("SSS")
                .iafdId("abc").build();
        AvActress to = actress(2L, "new", false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(from, to));

        command.execute(new String[]{"av migrate", "old", "new"}, ctx, io);

        verify(io, atLeastOnce()).println(contains("FROM:"));
        verify(io, atLeastOnce()).println(contains("TO:"));
        verify(actressRepo).migrateCuration(1L, 2L);
        verify(io).println(contains("Migration complete"));
    }

    private static AvActress actress(long id, String folder, boolean favorite) {
        return AvActress.builder().id(id).stageName(folder).folderName(folder)
                .volumeId("qnap_av").favorite(favorite).build();
    }
}
