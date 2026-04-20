package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.avstars.sync.AvFilenameParser;
import com.organizer3.avstars.sync.AvFilenameParser.ParsedFilename;
import com.organizer3.config.volume.VolumeConfig;
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
class AvParseFilenamesCommandTest {

    @Mock AvVideoRepository videoRepo;
    @Mock AvFilenameParser parser;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvParseFilenamesCommand command;

    @BeforeEach
    void setUp() {
        command = new AvParseFilenamesCommand(videoRepo, parser);
    }

    @Test
    void noMountAndNoArgPrintsGuidance() {
        when(ctx.getMountedVolume()).thenReturn(null);

        command.execute(new String[]{"av parse"}, ctx, io);

        verify(io).println(contains("No volume mounted"));
        verifyNoInteractions(videoRepo);
    }

    @Test
    void explicitVolumeWithNoVideosReportsEmpty() {
        when(videoRepo.findByVolume("vol-a")).thenReturn(List.of());

        command.execute(new String[]{"av parse", "vol-a"}, ctx, io);

        verify(io).println(contains("No videos found"));
    }

    @Test
    void videoWithExtractedFieldsIsUpdated() {
        AvVideo v = AvVideo.builder().id(1L).filename("a.mp4").volumeId("vol-a")
                .relativePath("a.mp4").extension("mp4").build();
        ParsedFilename parsed = new ParsedFilename("S1", "2024-01-01", "FHD", "H264", List.of("big-tits"));
        when(videoRepo.findByVolume("vol-a")).thenReturn(List.of(v));
        when(parser.parse("a.mp4")).thenReturn(parsed);

        command.execute(new String[]{"av parse", "vol-a"}, ctx, io);

        verify(videoRepo).updateParsedFields(eq(1L), eq("S1"), eq("2024-01-01"), eq("FHD"), eq("H264"),
                contains("big-tits"));
        verify(io).println(contains("1 updated"));
    }

    @Test
    void videoWithNoExtractedFieldsIsSkipped() {
        AvVideo v = AvVideo.builder().id(1L).filename("unparsable.mp4").volumeId("vol-a")
                .relativePath("unparsable.mp4").extension("mp4").build();
        when(videoRepo.findByVolume("vol-a")).thenReturn(List.of(v));
        when(parser.parse(any())).thenReturn(new ParsedFilename(null, null, null, null, List.of()));

        command.execute(new String[]{"av parse", "vol-a"}, ctx, io);

        verify(videoRepo, never()).updateParsedFields(anyLong(), any(), any(), any(), any(), any());
        verify(io).println(contains("1 no matches"));
    }

    @Test
    void mountedVolumeUsedWhenNoArg() {
        VolumeConfig vol = new VolumeConfig("qnap_av", "//q/AV", "avstars", "qnap2", null);
        when(ctx.getMountedVolume()).thenReturn(vol);
        when(videoRepo.findByVolume("qnap_av")).thenReturn(List.of());

        command.execute(new String[]{"av parse"}, ctx, io);

        verify(videoRepo).findByVolume("qnap_av");
    }
}
