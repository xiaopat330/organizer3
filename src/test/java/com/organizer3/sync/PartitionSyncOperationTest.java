package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.Volume;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import com.organizer3.covers.CoverPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PartitionSyncOperation — verifies that a partition-scoped sync
 * only clears and re-scans the specified partitions.
 */
class PartitionSyncOperationTest {

    private TitleRepository titleRepo;
    private VideoRepository videoRepo;
    private ActressRepository actressRepo;
    private VolumeRepository volumeRepo;
    private TitleLocationRepository titleLocationRepo;
    private TitleActressRepository titleActressRepo;
    private IndexLoader indexLoader;
    private VolumeFileSystem fs;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;
    private SyncIdentityMatcher identityMatcher;
    @TempDir Path tmpDataDir;
    private CoverPath coverPath;

    private static final VolumeConfig VOLUME = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);

    private static final VolumeStructureDef STRUCTURE = new VolumeStructureDef(
            "conventional",
            List.of(new PartitionDef("queue", "queue"),
                    new PartitionDef("attention", "attention")),
            new StructuredPartitionDef("stars", List.of(new PartitionDef("library", "library")))
    );

    @BeforeEach
    void setUp() {
        titleRepo = mock(TitleRepository.class);
        videoRepo = mock(VideoRepository.class);
        actressRepo = mock(ActressRepository.class);
        volumeRepo = mock(VolumeRepository.class);
        titleLocationRepo = mock(TitleLocationRepository.class);
        titleActressRepo = mock(TitleActressRepository.class);
        indexLoader = mock(IndexLoader.class);
        fs = mock(VolumeFileSystem.class);
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));

        identityMatcher = mock(SyncIdentityMatcher.class);
        when(volumeRepo.findById("a")).thenReturn(Optional.of(new Volume("a", "conventional")));
        when(indexLoader.load("a")).thenReturn(VolumeIndex.empty("a"));
        when(titleRepo.findOrCreateByCode(any(Title.class))).thenAnswer(inv -> {
            Title t = inv.getArgument(0);
            return t.toBuilder().id(1L).build();
        });
        coverPath = new CoverPath(tmpDataDir);
    }

    @Test
    void onlyClearsSpecifiedPartition() throws IOException {
        when(fs.exists(Path.of("/queue"))).thenReturn(false);

        PartitionSyncOperation op = new PartitionSyncOperation(
                List.of("queue"), titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                mock(com.organizer3.db.TitleEffectiveTagsService.class), mock(com.organizer3.db.ActressCompaniesService.class), coverPath, null, identityMatcher);
        op.execute(VOLUME, STRUCTURE, fs, ctx, io);

        verify(videoRepo).deleteByVolumeAndPartition("a", "queue");
        verify(titleLocationRepo).deleteByVolumeAndPartition("a", "queue");
        // Should NOT clear the entire volume
        verify(videoRepo, never()).deleteByVolume(any());
        verify(titleLocationRepo, never()).deleteByVolume(any());
    }

    @Test
    void scansOnlyTheRequestedPartition() throws IOException {
        Path queueRoot = Path.of("/queue");
        Path titleDir = queueRoot.resolve("ABP-001");
        when(fs.exists(queueRoot)).thenReturn(true);
        when(fs.listDirectory(queueRoot)).thenReturn(List.of(titleDir));
        when(fs.isDirectory(titleDir)).thenReturn(true);
        when(fs.listDirectory(titleDir)).thenReturn(List.of());

        PartitionSyncOperation op = new PartitionSyncOperation(
                List.of("queue"), titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                mock(com.organizer3.db.TitleEffectiveTagsService.class), mock(com.organizer3.db.ActressCompaniesService.class), coverPath, null, identityMatcher);
        op.execute(VOLUME, STRUCTURE, fs, ctx, io);

        verify(titleRepo).findOrCreateByCode(argThat(t -> "ABP-001".equals(t.getCode())));
    }

    @Test
    void canSyncMultiplePartitionsInOneCall() throws IOException {
        when(fs.exists(Path.of("/queue"))).thenReturn(false);
        when(fs.exists(Path.of("/attention"))).thenReturn(false);

        PartitionSyncOperation op = new PartitionSyncOperation(
                List.of("queue", "attention"), titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                mock(com.organizer3.db.TitleEffectiveTagsService.class), mock(com.organizer3.db.ActressCompaniesService.class), coverPath, null, identityMatcher);
        op.execute(VOLUME, STRUCTURE, fs, ctx, io);

        verify(videoRepo).deleteByVolumeAndPartition("a", "queue");
        verify(titleLocationRepo).deleteByVolumeAndPartition("a", "queue");
        verify(videoRepo).deleteByVolumeAndPartition("a", "attention");
        verify(titleLocationRepo).deleteByVolumeAndPartition("a", "attention");
    }

    @Test
    void throwsOnUnknownPartitionId() {
        PartitionSyncOperation op = new PartitionSyncOperation(
                List.of("nonexistent"), titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                mock(com.organizer3.db.TitleEffectiveTagsService.class), mock(com.organizer3.db.ActressCompaniesService.class), coverPath, null, identityMatcher);

        assertThrows(IllegalArgumentException.class,
                () -> op.execute(VOLUME, STRUCTURE, fs, ctx, io));
    }

    @Test
    void prunesCoverFilesForOrphanedTitles() throws IOException {
        // Simulate a title that got orphaned by this sync: titleRepo.findOrphanedTitles()
        // reports it, and its cover file exists on disk. After the op, the file is gone.
        java.nio.file.Path labelDir = coverPath.root().resolve("ABP");
        java.nio.file.Files.createDirectories(labelDir);
        java.nio.file.Path coverFile = labelDir.resolve("ABP-00001.jpg");
        java.nio.file.Files.writeString(coverFile, "x");
        when(titleRepo.findOrphanedTitles()).thenReturn(
                List.of(new TitleRepository.OrphanedTitleRef("ABP", "ABP-00001")));
        when(fs.exists(Path.of("/queue"))).thenReturn(false);

        PartitionSyncOperation op = new PartitionSyncOperation(
                List.of("queue"), titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                mock(com.organizer3.db.TitleEffectiveTagsService.class), mock(com.organizer3.db.ActressCompaniesService.class), coverPath, null, identityMatcher);
        op.execute(VOLUME, STRUCTURE, fs, ctx, io);

        assertFalse(java.nio.file.Files.exists(coverFile), "orphaned title's cover should be deleted");
        verify(titleRepo).deleteOrphaned();
    }

    @Test
    void finalizesSync() throws IOException {
        when(fs.exists(Path.of("/queue"))).thenReturn(false);

        PartitionSyncOperation op = new PartitionSyncOperation(
                List.of("queue"), titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                mock(com.organizer3.db.TitleEffectiveTagsService.class), mock(com.organizer3.db.ActressCompaniesService.class), coverPath, null, identityMatcher);
        op.execute(VOLUME, STRUCTURE, fs, ctx, io);

        verify(volumeRepo).updateLastSyncedAt(eq("a"), any(LocalDateTime.class));
        verify(indexLoader).load("a");
    }
}
