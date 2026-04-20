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
class AvRenameActressCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvRenameActressCommand command;

    @BeforeEach
    void setUp() {
        command = new AvRenameActressCommand(actressRepo);
    }

    @Test
    void noArgsPrintsUsage() {
        command.execute(new String[]{"av rename"}, ctx, io);
        verify(io).println(contains("Usage:"));
        verifyNoInteractions(actressRepo);
    }

    @Test
    void missingArrowSeparatorPrintsUsage() {
        command.execute(new String[]{"av rename", "foo", "bar"}, ctx, io);
        verify(io, atLeast(1)).println(contains("Usage:"));
        verifyNoInteractions(actressRepo);
    }

    @Test
    void emptySourceOrTargetPrintsUsage() {
        command.execute(new String[]{"av rename", ">", "new"}, ctx, io);
        verify(io, atLeast(1)).println(contains("Usage:"));
    }

    @Test
    void notFoundPrintsNoMatch() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());
        command.execute(new String[]{"av rename", "foo", ">", "bar"}, ctx, io);
        verify(io).println(contains("No actress found"));
    }

    @Test
    void multipleMatchesPromptDisambiguation() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        AvActress b = actress(2L, "Asa Higa", "Asa Higa");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a, b));

        command.execute(new String[]{"av rename", "asa", ">", "Asa A"}, ctx, io);

        verify(io).println(contains("Multiple matches"));
        verify(actressRepo, never()).updateStageName(anyLong(), any());
    }

    @Test
    void displayRenameOnlyCallsUpdateStageName() {
        AvActress a = actress(1L, "Alina lopez [teen]", "alina-lopez");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av rename", "alina", ">", "Alina Lopez"}, ctx, io);

        verify(actressRepo).updateStageName(1L, "Alina Lopez");
        verify(actressRepo, never()).migrateCuration(anyLong(), anyLong());
    }

    @Test
    void physicalFolderRenameMigratesAndUpdates() {
        // Source matches by unique search term, target distinct so single source match;
        // target identified separately by exact folder_name match to new name.
        AvActress source = actress(1L, "Old Name", "old-uniquefolder");
        AvActress target = actress(2L, "temp-stage", "New Folder");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(source, target));

        command.execute(new String[]{"av rename", "uniquefolder", ">", "New Folder"}, ctx, io);

        verify(actressRepo).migrateCuration(1L, 2L);
        verify(actressRepo).updateStageName(2L, "New Folder");
    }

    private static AvActress actress(long id, String stage, String folder) {
        return AvActress.builder().id(id).stageName(stage).folderName(folder)
                .volumeId("qnap_av").build();
    }
}
