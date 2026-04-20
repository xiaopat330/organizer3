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
class AvCurateCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvCurateCommand command;

    @BeforeEach
    void setUp() {
        command = new AvCurateCommand(actressRepo);
    }

    // ── Argument parsing ──────────────────────────────────────────────

    @Test
    void tooFewArgsPrintsUsage() {
        command.execute(new String[]{"av curate", "Asa"}, ctx, io);
        verify(io, atLeast(1)).println(contains("Usage:"));
        verifyNoInteractions(actressRepo);
    }

    @Test
    void unknownActionPrintsError() {
        command.execute(new String[]{"av curate", "Asa", "bogus"}, ctx, io);
        verify(io).println(contains("Unknown action"));
    }

    @Test
    void actionWithNoNameTokensPrintsUsage() {
        // args[1]="grade" would be parsed as the name, args[2]="SSS" as the grade value,
        // but nameEndExclusive becomes 1 → the "No actress name given" branch fires.
        command.execute(new String[]{"av curate", "grade", "SSS"}, ctx, io);
        verify(io).println(contains("No actress name given"));
        verify(actressRepo, never()).setGrade(anyLong(), any());
    }

    @Test
    void noMatchPrintsDiagnostic() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());
        command.execute(new String[]{"av curate", "Asa", "fav"}, ctx, io);
        verify(io).println(contains("No AV actress matching"));
        verify(actressRepo, never()).toggleFavorite(anyLong(), anyBoolean());
    }

    @Test
    void multipleMatchesAreDisambiguated() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira", false, false, false);
        AvActress b = actress(2L, "Asa Higa",  "Asa Higa",  false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a, b));

        command.execute(new String[]{"av curate", "Asa", "fav"}, ctx, io);

        verify(io).println(contains("Multiple matches"));
        verify(actressRepo, never()).toggleFavorite(anyLong(), anyBoolean());
    }

    // ── Toggle actions ────────────────────────────────────────────────

    @Test
    void favToggleFlipsFavoriteOn() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "Akira", "fav"}, ctx, io);

        verify(actressRepo).toggleFavorite(1L, true);
        verify(io).println(contains("favorite → ON"));
    }

    @Test
    void favToggleFlipsFavoriteOff() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira", true /*fav*/, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "Akira", "fav"}, ctx, io);

        verify(actressRepo).toggleFavorite(1L, false);
        verify(io).println(contains("favorite → off"));
    }

    @Test
    void bookmarkToggleCalled() {
        AvActress a = actress(1L, "Asa", "Asa", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "bookmark"}, ctx, io);

        verify(actressRepo).toggleBookmark(1L, true);
    }

    @Test
    void rejectToggleCalled() {
        AvActress a = actress(1L, "Asa", "Asa", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "reject"}, ctx, io);

        verify(actressRepo).toggleRejected(1L, true);
    }

    // ── Grade actions ─────────────────────────────────────────────────

    @Test
    void gradeWithValidValueSetsGrade() {
        AvActress a = actress(1L, "Asa", "Asa", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "grade", "SSS"}, ctx, io);

        verify(actressRepo).setGrade(1L, "SSS");
        verify(io).println(contains("grade → SSS"));
    }

    @Test
    void gradeLowercaseIsNormalizedToUppercase() {
        AvActress a = actress(1L, "Asa", "Asa", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "grade", "a+"}, ctx, io);

        verify(actressRepo).setGrade(1L, "A+");
    }

    @Test
    void gradeWithInvalidValueIsRejected() {
        AvActress a = actress(1L, "Asa", "Asa", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "grade", "XYZ"}, ctx, io);

        verify(io).println(contains("Unknown grade"));
        verify(actressRepo, never()).setGrade(anyLong(), any());
    }

    @Test
    void clearGradeSetsNull() {
        AvActress a = actress(1L, "Asa", "Asa", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "Asa", "clear-grade"}, ctx, io);

        verify(actressRepo).setGrade(1L, null);
        verify(io).println(contains("grade cleared"));
    }

    // ── Stage-name action ─────────────────────────────────────────────

    @Test
    void stageNameAcceptsMultiWordValue() {
        AvActress a = actress(1L, "old", "old-folder", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av curate", "old-folder", "stage-name", "Brand New Name"}, ctx, io);

        verify(actressRepo).updateStageName(1L, "Brand New Name");
    }

    @Test
    void stageNamePivotTokenIsFoundEvenWithNameTokensBefore() {
        AvActress a = actress(1L, "Foo Bar Baz", "foo-bar-baz", false, false, false);
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        // "Foo Bar" is the name, "stage-name New Display" is the action.
        command.execute(new String[]{"av curate", "Foo", "Bar", "stage-name", "New", "Display"}, ctx, io);

        verify(actressRepo).updateStageName(1L, "New Display");
    }

    private static AvActress actress(long id, String stage, String folder,
                                     boolean fav, boolean bm, boolean rej) {
        return AvActress.builder().id(id).stageName(stage).folderName(folder)
                .volumeId("qnap_av").favorite(fav).bookmark(bm).rejected(rej).build();
    }
}
