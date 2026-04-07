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
    void collections_hasNoSyncConfig() {
        assertTrue(config.findSyncConfigForStructure("collections").isEmpty());
    }
}
