package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvActressesCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvActressesCommand command;

    @BeforeEach
    void setUp() {
        command = new AvActressesCommand(actressRepo);
    }

    @Test
    void emptyResultPrintsGuidance() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());

        command.execute(new String[]{"av actresses"}, ctx, io);

        verify(io).println(contains("Run 'av sync'"));
    }

    @Test
    void listingIncludesFlagsAndSize() {
        AvActress a = AvActress.builder().id(1L).stageName("Asa Akira").folderName("Asa Akira")
                .volumeId("qnap_av").videoCount(52).totalSizeBytes(12L * 1024 * 1024 * 1024)
                .favorite(true).iafdId("abc").grade("A").build();
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));

        command.execute(new String[]{"av actresses"}, ctx, io);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(io, atLeast(3)).println(captor.capture());
        List<String> lines = captor.getAllValues();
        assertTrue(lines.stream().anyMatch(l -> l.contains("Asa Akira")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("♥") && l.contains("IAFD")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("1 actress")));
    }

    @Test
    void formatSizeHandlesZeroMbAndGb() {
        assertEquals("-",      AvActressesCommand.formatSize(0));
        assertEquals("-",      AvActressesCommand.formatSize(-5));
        assertTrue(AvActressesCommand.formatSize(500L * 1024 * 1024).endsWith("MB"));
        assertTrue(AvActressesCommand.formatSize(5L * 1024 * 1024 * 1024).endsWith("GB"));
    }
}
