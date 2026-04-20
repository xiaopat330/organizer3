package com.organizer3.avstars.command;

import com.organizer3.avstars.AvScreenshotService;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Progress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvScreenshotsCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock AvVideoRepository videoRepo;
    @Mock AvScreenshotRepository screenshotRepo;
    @Mock AvScreenshotService screenshotService;
    @Mock SessionContext ctx;
    @Mock CommandIO io;
    @Mock Progress progress;

    AvScreenshotsCommand command;

    @BeforeEach
    void setUp() {
        command = new AvScreenshotsCommand(actressRepo, videoRepo, screenshotRepo, screenshotService);
    }

    @Test
    void noArgsPrintsUsage() {
        command.execute(new String[]{"av screenshots"}, ctx, io);
        verify(io).println(contains("Usage:"));
        verifyNoInteractions(actressRepo, videoRepo, screenshotService);
    }

    @Test
    void noMatchPrintsNotFound() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of());
        command.execute(new String[]{"av screenshots", "ghost"}, ctx, io);
        verify(io).println(contains("No actress found"));
        verifyNoInteractions(videoRepo, screenshotService);
    }

    @Test
    void multipleMatchesPrintsAmbiguity() {
        when(actressRepo.findAllByVideoCountDesc())
                .thenReturn(List.of(actress(1L, "Asa Akira", "Asa Akira"),
                                    actress(2L, "Asami Ogawa", "Asami Ogawa")));
        command.execute(new String[]{"av screenshots", "asa"}, ctx, io);

        verify(io).println(contains("Multiple matches"));
        verifyNoInteractions(videoRepo, screenshotService);
    }

    @Test
    void matchByFolderNameWorks() {
        when(actressRepo.findAllByVideoCountDesc())
                .thenReturn(List.of(actress(1L, "Stage Name", "folder-slug")));
        when(videoRepo.findByActress(1L)).thenReturn(List.of());
        // No videos so it skips the progress loop — uses findByActress, which proves match worked.
        command.execute(new String[]{"av screenshots", "folder"}, ctx, io);
        verify(videoRepo).findByActress(1L);
    }

    @Test
    void allScreenshotsAlreadyGeneratedShortCircuits() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        AvVideo v = video(10L, "scene1.mp4");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v));
        when(screenshotRepo.countByVideoId(10L)).thenReturn(3);  // already has screenshots

        command.execute(new String[]{"av screenshots", "asa"}, ctx, io);

        verify(io).println(contains("0/1"));
        verify(io).println(contains("already generated"));
        verifyNoInteractions(screenshotService);
    }

    @Test
    void pendingVideoGeneratesScreenshotsAndReportsSuccess() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        AvVideo v = video(10L, "scene1.mp4");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v));
        when(screenshotRepo.countByVideoId(10L)).thenReturn(0);
        when(io.startProgress(anyString(), anyInt())).thenReturn(progress);
        when(screenshotService.generateForVideo(10L))
                .thenReturn(List.of("/s/1.jpg", "/s/2.jpg", "/s/3.jpg"));

        command.execute(new String[]{"av screenshots", "asa"}, ctx, io);

        verify(screenshotService).generateForVideo(10L);
        verify(progress).advance();
        verify(io).println(contains("scene1.mp4: 3 frames"));
        verify(io).println(contains("Done: 1 processed, 0 failed"));
    }

    @Test
    void failedVideoIsReportedAndCounted() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        AvVideo v = video(10L, "broken.mp4");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v));
        when(screenshotRepo.countByVideoId(10L)).thenReturn(0);
        when(io.startProgress(anyString(), anyInt())).thenReturn(progress);
        when(screenshotService.generateForVideo(10L)).thenReturn(List.of());

        command.execute(new String[]{"av screenshots", "asa"}, ctx, io);

        verify(io).println(contains("broken.mp4: failed"));
        verify(io).println(contains("Done: 0 processed, 1 failed"));
    }

    @Test
    void mixedPendingAndCompleteOnlyProcessesPending() {
        AvActress a = actress(1L, "Asa Akira", "Asa Akira");
        AvVideo alreadyDone = video(10L, "done.mp4");
        AvVideo needsWork   = video(20L, "pending.mp4");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(videoRepo.findByActress(1L)).thenReturn(List.of(alreadyDone, needsWork));
        when(screenshotRepo.countByVideoId(10L)).thenReturn(5);  // complete
        when(screenshotRepo.countByVideoId(20L)).thenReturn(0);  // pending
        when(io.startProgress(anyString(), anyInt())).thenReturn(progress);
        when(screenshotService.generateForVideo(20L)).thenReturn(List.of("/a.jpg"));

        command.execute(new String[]{"av screenshots", "asa"}, ctx, io);

        verify(screenshotService).generateForVideo(20L);
        verify(screenshotService, never()).generateForVideo(10L);
        verify(io).println(contains("1/2"));  // "1/2 video(s) need screenshots"
    }

    private static AvActress actress(long id, String stage, String folder) {
        return AvActress.builder().id(id).stageName(stage).folderName(folder)
                .volumeId("qnap_av").build();
    }

    private static AvVideo video(long id, String filename) {
        return AvVideo.builder().id(id).filename(filename).avActressId(1L).build();
    }
}
