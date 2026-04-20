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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvDeleteActressCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvDeleteActressCommand command;

    @BeforeEach
    void setUp() {
        command = new AvDeleteActressCommand(actressRepo);
    }

    @Test
    void noArgsPrintsUsage() {
        command.execute(new String[]{"av delete"}, ctx, io);
        verify(io).println(contains("Usage:"));
        verifyNoInteractions(actressRepo);
    }

    @Test
    void notFoundPrintsNoMatch() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());
        command.execute(new String[]{"av delete", "Unknown"}, ctx, io);
        verify(io).println(contains("No actress found"));
        verify(actressRepo, never()).delete(anyLong());
    }

    @Test
    void cancelChoiceSkipsDelete() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(io.pick(any())).thenReturn(Optional.of("Cancel"));

        command.execute(new String[]{"av delete", "asa"}, ctx, io);

        verify(io).println(contains("Cancelled"));
        verify(actressRepo, never()).delete(anyLong());
    }

    @Test
    void confirmedDeletionCallsRepo() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(io.pick(any())).thenReturn(Optional.of("Yes, delete permanently"));

        command.execute(new String[]{"av delete", "asa"}, ctx, io);

        verify(actressRepo).delete(1L);
        verify(io).println(contains("Deleted:"));
    }

    @Test
    void emptyPickResultTreatedAsCancel() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(io.pick(any())).thenReturn(Optional.empty());

        command.execute(new String[]{"av delete", "asa"}, ctx, io);

        verify(actressRepo, never()).delete(anyLong());
    }

    private static AvActress actress(long id, String stage, String folder) {
        return AvActress.builder().id(id).stageName(stage).folderName(folder)
                .volumeId("qnap_av").build();
    }
}
