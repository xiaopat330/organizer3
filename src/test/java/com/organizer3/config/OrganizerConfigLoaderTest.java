package com.organizer3.config;

import com.organizer3.config.sync.SyncOperationType;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.OrganizerConfigLoader;
import com.organizer3.config.volume.VolumeStructureDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class OrganizerConfigLoaderTest {

    private OrganizerConfig config;

    @BeforeEach
    void load() throws IOException {
        config = new OrganizerConfigLoader().load();
    }

    // -------------------------------------------------------------------------
    // Volumes
    // -------------------------------------------------------------------------

    @Test
    void volumes_loaded() {
        assertFalse(config.volumes().isEmpty());
    }

    @Test
    void volume_findById_works() {
        assertTrue(config.findById("a").isPresent());
        assertTrue(config.findById("unsorted").isPresent());
        assertFalse(config.findById("zzz").isPresent());
    }

    // -------------------------------------------------------------------------
    // Structures
    // -------------------------------------------------------------------------

    @Test
    void structures_loaded() {
        assertFalse(config.structures().isEmpty());
    }

    @Test
    void conventionalStructure_hasUnstructuredPartitions() {
        VolumeStructureDef def = config.findStructureById("conventional").orElseThrow();
        assertFalse(def.unstructuredPartitions().isEmpty());
        assertTrue(def.findUnstructuredById("queue").isPresent());
    }

    @Test
    void conventionalStructure_hasStarsPartition() {
        VolumeStructureDef def = config.findStructureById("conventional").orElseThrow();
        assertNotNull(def.structuredPartition());
        assertEquals("stars", def.structuredPartition().path());
        assertFalse(def.structuredPartition().partitions().isEmpty());
    }

    @Test
    void queueStructure_hasOnlyFreshPartition() {
        VolumeStructureDef def = config.findStructureById("queue").orElseThrow();
        assertEquals(1, def.unstructuredPartitions().size());
        assertEquals("fresh", def.findUnstructuredById("queue").orElseThrow().path());
        assertNull(def.structuredPartition());
    }

    // -------------------------------------------------------------------------
    // SyncConfig
    // -------------------------------------------------------------------------

    @Test
    void syncConfig_loaded() {
        assertFalse(config.syncConfig().isEmpty());
    }

    @Test
    void conventional_hasSyncQueueAndSyncAll() {
        var syncCfg = config.findSyncConfigForStructure("conventional").orElseThrow();
        var terms = syncCfg.commands().stream().map(c -> c.term()).toList();
        assertTrue(terms.contains("sync queue"));
        assertTrue(terms.contains("sync all"));
    }

    @Test
    void syncQueue_isPartitionOperation_withQueuePartition() {
        var def = config.findSyncConfigForStructure("conventional").orElseThrow()
                .commands().stream()
                .filter(c -> c.term().equals("sync queue"))
                .findFirst().orElseThrow();
        assertEquals(SyncOperationType.PARTITION, def.operation());
        assertTrue(def.partitions().contains("queue"));
    }

    @Test
    void syncAll_isFullOperation() {
        var def = config.findSyncConfigForStructure("conventional").orElseThrow()
                .commands().stream()
                .filter(c -> c.term().equals("sync all"))
                .findFirst().orElseThrow();
        assertEquals(SyncOperationType.FULL, def.operation());
    }

    @Test
    void queue_hasSyncAndSyncAll() {
        var syncCfg = config.findSyncConfigForStructure("queue").orElseThrow();
        var terms = syncCfg.commands().stream().map(c -> c.term()).toList();
        assertTrue(terms.contains("sync"));
        assertTrue(terms.contains("sync all"));
    }

    @Test
    void collections_hasSyncAllConfig() {
        var syncCfg = config.findSyncConfigForStructure("collections").orElseThrow();
        var terms = syncCfg.commands().stream().map(c -> c.term()).toList();
        assertTrue(terms.contains("sync all"));
    }

    // -------------------------------------------------------------------------
    // Organize pipeline — library / normalize / media blocks
    // -------------------------------------------------------------------------

    @Test
    void library_tierThresholds_loaded() {
        var lib = config.libraryOrDefaults();
        assertEquals(3,   lib.effectiveStar());
        assertEquals(5,   lib.effectiveMinor());
        assertEquals(20,  lib.effectivePopular());
        assertEquals(50,  lib.effectiveSuperstar());
        assertEquals(100, lib.effectiveGoddess());
    }

    @Test
    void library_tierFor_classifies() {
        var lib = config.libraryOrDefaults();
        assertEquals("pool",      lib.tierFor(0));
        assertEquals("pool",      lib.tierFor(2));
        assertEquals("library",   lib.tierFor(3));
        assertEquals("library",   lib.tierFor(4));
        assertEquals("minor",     lib.tierFor(5));
        assertEquals("minor",     lib.tierFor(19));
        assertEquals("popular",   lib.tierFor(20));
        assertEquals("popular",   lib.tierFor(49));
        assertEquals("superstar", lib.tierFor(50));
        assertEquals("superstar", lib.tierFor(99));
        assertEquals("goddess",   lib.tierFor(100));
        assertEquals("goddess",   lib.tierFor(500));
    }

    @Test
    void normalize_removelist_hasLegacyPatterns() {
        var n = config.normalizeOrEmpty();
        assertFalse(n.effectiveRemovelist().isEmpty());
        assertTrue(n.effectiveRemovelist().contains("hhd800.com@"));
        assertTrue(n.effectiveRemovelist().contains("[Thz.la]"));
        assertTrue(n.effectiveRemovelist().contains("-1080P"));
    }

    @Test
    void normalize_replacelist_hasLegacyPatterns() {
        var n = config.normalizeOrEmpty();
        assertFalse(n.effectiveReplacelist().isEmpty());
        boolean hasFc2 = n.effectiveReplacelist().stream()
                .anyMatch(r -> "FC2-PPV".equals(r.from()) && "FC2PPV".equals(r.to()));
        assertTrue(hasFc2, "expected FC2-PPV -> FC2PPV rewrite");
        boolean hasUncen = n.effectiveReplacelist().stream()
                .anyMatch(r -> "-Uncen".equals(r.from()) && "_U".equals(r.to()));
        assertTrue(hasUncen, "expected -Uncen -> _U rewrite");
    }

    @Test
    void media_extensions_loaded() {
        var m = config.mediaOrDefaults();
        assertTrue(m.effectiveVideoExtensions().contains("mp4"));
        assertTrue(m.effectiveVideoExtensions().contains("mkv"));
        assertTrue(m.effectiveCoverExtensions().contains("jpg"));
        assertTrue(m.effectiveCoverExtensions().contains("webp"));
    }
}
