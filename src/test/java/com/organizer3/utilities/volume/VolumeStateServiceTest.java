package com.organizer3.utilities.volume;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VolumeRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class VolumeStateServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private VolumeStateService service;

    private static final VolumeConfig VOLUME_A =
            new VolumeConfig("a", "//nas/jav_a", "conventional", "nas", null);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        VolumeRepository volumeRepo = mock(VolumeRepository.class);
        TitleRepository titleRepo   = mock(TitleRepository.class);
        when(volumeRepo.findById(any())).thenReturn(Optional.empty());
        when(titleRepo.countByVolume(any())).thenReturn(0);

        StaleLocationsService stale = new StaleLocationsService(jdbi);
        service = new VolumeStateService(volumeRepo, titleRepo, stale, jdbi);

        AppConfig.initializeForTest(new OrganizerConfig(
                "Test", null, null, null, null, null, null, null,
                List.of(), List.of(VOLUME_A), List.of(), List.of(), null));
    }

    @AfterEach
    void tearDown() throws Exception {
        AppConfig.reset();
        connection.close();
    }

    @Test
    void queueCountIsZeroWhenNoQueueLocations() {
        var dto = service.find("a");
        assertTrue(dto.isPresent());
        assertEquals(0, dto.get().queueCount());
    }

    @Test
    void queueCountReflectsQueuePartitionRows() {
        insertTitle(1L, "ABP-001");
        insertTitle(2L, "ABP-002");
        insertLocation(1L, "a", "queue");
        insertLocation(2L, "a", "queue");

        var dto = service.find("a");
        assertTrue(dto.isPresent());
        assertEquals(2, dto.get().queueCount());
    }

    @Test
    void queueCountExcludesNonQueuePartitions() {
        insertTitle(1L, "ABP-001");
        insertTitle(2L, "ABP-002");
        insertLocation(1L, "a", "queue");
        insertLocation(2L, "a", "library");

        var dto = service.find("a");
        assertTrue(dto.isPresent());
        assertEquals(1, dto.get().queueCount());
    }

    @Test
    void queueCountExcludesOtherVolumes() {
        insertTitle(1L, "ABP-001");
        insertLocation(1L, "a", "queue");
        insertLocation(1L, "b", "queue");

        var dto = service.find("a");
        assertTrue(dto.isPresent());
        assertEquals(1, dto.get().queueCount());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertTitle(long id, String code) {
        jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (:id, :code, :code, 'ABP', 1)")
                .bind("id", id)
                .bind("code", code)
                .execute());
    }

    private void insertLocation(long titleId, String volumeId, String partitionId) {
        jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at, added_date)
                VALUES (:titleId, :vol, :part, '/path', date('now'), date('now'))
                """)
                .bind("titleId", titleId)
                .bind("vol", volumeId)
                .bind("part", partitionId)
                .execute());
    }
}
