package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
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
class AvActressCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock AvVideoRepository videoRepo;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvActressCommand command;

    @BeforeEach
    void setUp() {
        command = new AvActressCommand(actressRepo, videoRepo);
    }

    @Test
    void noArgsPrintsUsage() {
        command.execute(new String[]{"av actress"}, ctx, io);
        verify(io).println(contains("Usage:"));
        verifyNoInteractions(actressRepo);
    }

    @Test
    void noMatchPrintsNoMatchMessage() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());
        command.execute(new String[]{"av actress", "unknown"}, ctx, io);
        verify(io).println(contains("No AV actress matching"));
    }

    @Test
    void multipleMatchesPromptDisambiguation() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        AvActress b = actress(2L, "Asa Higa",  "Asa Higa");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a, b));

        command.execute(new String[]{"av actress", "asa"}, ctx, io);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(io, atLeast(3)).println(captor.capture());
        List<String> lines = captor.getAllValues();
        assertTrue(lines.stream().anyMatch(l -> l.contains("Multiple matches")));
        verify(videoRepo, never()).findByActress(anyLong());
    }

    @Test
    void singleMatchPrintsProfileAndVideos() {
        AvActress a = AvActress.builder().id(1L).stageName("Asa Akira").folderName("Asa Akira")
                .volumeId("qnap_av").videoCount(52).totalSizeBytes(10L * 1024 * 1024 * 1024)
                .iafdId("abc").nationality("American").favorite(true).grade("SSS").build();
        AvVideo v = AvVideo.builder().id(10L).avActressId(1L).filename("f.mp4")
                .relativePath("f.mp4").volumeId("qnap_av").extension("mp4")
                .sizeBytes(2L * 1024 * 1024 * 1024).build();
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v));

        command.execute(new String[]{"av actress", "asa", "akira"}, ctx, io);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(io, atLeast(5)).println(captor.capture());
        List<String> lines = captor.getAllValues();
        assertTrue(lines.stream().anyMatch(l -> l.contains("Asa Akira")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("IAFD")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Videos (1)")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("♥")));
    }

    @Test
    void singleMatchWithNoVideosPrintsNoVideosIndexed() {
        AvActress a = actress(1L, "Solo", "Solo");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(videoRepo.findByActress(1L)).thenReturn(List.of());

        command.execute(new String[]{"av actress", "Solo"}, ctx, io);

        verify(io).println(contains("no videos indexed"));
    }

    private static AvActress actress(long id, String stage, String folder) {
        return AvActress.builder().id(id).stageName(stage).folderName(folder)
                .volumeId("qnap_av").build();
    }
}
