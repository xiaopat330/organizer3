package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvTagDefinition;
import com.organizer3.avstars.repository.AvTagDefinitionRepository;
import com.organizer3.avstars.repository.AvVideoTagRepository;
import com.organizer3.avstars.sync.AvTagYamlLoader;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvTagsCommandTest {

    @Mock AvTagDefinitionRepository tagDefRepo;
    @Mock AvVideoTagRepository       videoTagRepo;
    @Mock SessionContext             ctx;
    @Mock CommandIO                  io;

    @TempDir Path tmpDir;

    AvTagsCommand command;

    @BeforeEach
    void setUp() {
        AvTagYamlLoader loader = new AvTagYamlLoader(tagDefRepo);
        command = new AvTagsCommand(tagDefRepo, videoTagRepo, loader,
                tmpDir.resolve("av_tags.yaml"));
    }

    @Test
    void nameIsAvTags() {
        assertEquals("av tags", command.name());
    }

    @Test
    void unknownSubcommandPrintsUsage() {
        command.execute(new String[]{"av tags", "unknown"}, ctx, io);
        verify(io).println(contains("Usage:"));
    }

    @Test
    void dumpWithNoTagsInformUser() {
        when(videoTagRepo.getAllTagsJson()).thenReturn(List.of());
        command.execute(new String[]{"av tags", "dump"}, ctx, io);
        verify(io).println(contains("No tags found"));
    }

    @Test
    void dumpPrintsFreqSortedTokens() {
        when(videoTagRepo.getAllTagsJson()).thenReturn(List.of(
                "[\"big-tits\",\"blonde\"]",
                "[\"big-tits\",\"milf\"]",
                "[\"blonde\"]"
        ));
        command.execute(new String[]{"av tags", "dump"}, ctx, io);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(io, atLeast(3)).println(captor.capture());
        List<String> lines = captor.getAllValues();

        // Most frequent first: big-tits (2), blonde (2), milf (1)
        // big-tits and blonde both 2 — sorted by name after count
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("2\tbig-tits")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("2\tblonde")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("1\tmilf")));
    }

    @Test
    void applyReportsYamlNotFound() {
        // tagsYamlPath does not exist — tmpDir/av_tags.yaml was not created
        command.execute(new String[]{"av tags", "apply"}, ctx, io);
        verify(io).println(contains("not found"));
    }

    @Test
    void applyLoadsYamlAndTagsVideos() throws Exception {
        // Write a minimal YAML file
        Files.writeString(tmpDir.resolve("av_tags.yaml"),
                "- slug: big-tits\n  displayName: Big Tits\n  category: body\n  aliases: [bigtits]\n");

        when(tagDefRepo.findAll()).thenReturn(List.of(
                AvTagDefinition.builder().slug("big-tits").displayName("Big Tits")
                        .aliasesJson("[\"bigtits\"]").build()));
        when(videoTagRepo.getAllVideoIdAndTagsJson()).thenReturn(List.of(
                new String[]{"1", "[\"bigtits\"]"},
                new String[]{"2", "[\"blonde\"]"}   // no matching alias
        ));

        command.execute(new String[]{"av tags", "apply"}, ctx, io);

        verify(tagDefRepo).upsert(any(AvTagDefinition.class));
        verify(videoTagRepo).deleteByVideoIdAndSource(1L, "apply");
        verify(videoTagRepo).insertVideoTag(1L, "big-tits", "apply");
        // video 2 has no match — should not be tagged
        verify(videoTagRepo, never()).insertVideoTag(eq(2L), any(), any());

        var captor = ArgumentCaptor.forClass(String.class);
        verify(io, atLeast(2)).println(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(l -> l.contains("1 tag")));
        assertTrue(captor.getAllValues().stream().anyMatch(l -> l.contains("1 video")));
    }
}
