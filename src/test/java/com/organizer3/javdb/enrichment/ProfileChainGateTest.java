package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbConfig;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ProfileChainGateTest {

    private Connection connection;
    private Jdbi jdbi;
    private ProfileChainGate gate;

    private static final JavdbConfig CONFIG_MIN3 =
            new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        gate = new ProfileChainGate(jdbi, CONFIG_MIN3);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long insertActress(String stageName, boolean sentinel) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel) " +
                               "VALUES (:cn, :sn, 'LIBRARY', '2024-01-01', :s)")
                        .bind("cn", stageName)
                        .bind("sn", stageName)
                        .bind("s",  sentinel ? 1 : 0)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertTitle(String code) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES (:c, :c, 'TST', 1)")
                        .bind("c", code)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void creditActress(long actressId, long titleId) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void actressId_zero_returnsFalse() {
        assertFalse(gate.shouldChainProfile(0L));
    }

    @Test
    void missingActress_returnsFalse() {
        assertFalse(gate.shouldChainProfile(9999L));
    }

    @Test
    void sentinelActress_returnsFalse_regardlessOfTitleCount() {
        long aId = insertActress("Various", true);
        long t1 = insertTitle("VAR-001"); creditActress(aId, t1);
        long t2 = insertTitle("VAR-002"); creditActress(aId, t2);
        long t3 = insertTitle("VAR-003"); creditActress(aId, t3);

        assertFalse(gate.shouldChainProfile(aId));
    }

    @Test
    void realActress_belowThreshold_returnsFalse() {
        long aId = insertActress("Yui Hatano", false);
        long t1 = insertTitle("PRED-001"); creditActress(aId, t1);
        long t2 = insertTitle("PRED-002"); creditActress(aId, t2);
        // only 2 titles, threshold is 3

        assertFalse(gate.shouldChainProfile(aId));
    }

    @Test
    void realActress_exactlyAtThreshold_returnsTrue() {
        long aId = insertActress("Yui Hatano", false);
        long t1 = insertTitle("PRED-001"); creditActress(aId, t1);
        long t2 = insertTitle("PRED-002"); creditActress(aId, t2);
        long t3 = insertTitle("PRED-003"); creditActress(aId, t3);

        assertTrue(gate.shouldChainProfile(aId));
    }

    @Test
    void realActress_aboveThreshold_returnsTrue() {
        long aId = insertActress("Yui Hatano", false);
        for (int i = 1; i <= 5; i++) {
            creditActress(aId, insertTitle("PRED-00" + i));
        }

        assertTrue(gate.shouldChainProfile(aId));
    }

    @Test
    void collectionAppearances_countTowardThreshold() {
        // Multi-actress credits (title_actresses rows without titles.actress_id set) count
        long aId = insertActress("Ai Mukai", false);
        long multiTitle = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('HMN-001','HMN','HMN',1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        creditActress(aId, multiTitle);
        long t2 = insertTitle("SONE-001"); creditActress(aId, t2);
        long t3 = insertTitle("SONE-002"); creditActress(aId, t3);

        assertTrue(gate.shouldChainProfile(aId));
    }

    @Test
    void configurable_thresholdOf1_passesWithOneTitle() {
        JavdbConfig min1 = new JavdbConfig(true, 1.0, 3, new int[]{1}, 5, null, null, null, null, 1, null, null);
        ProfileChainGate gateMin1 = new ProfileChainGate(jdbi, min1);

        long aId = insertActress("Sola Aoi", false);
        creditActress(aId, insertTitle("SOA-001"));

        assertTrue(gateMin1.shouldChainProfile(aId));
    }
}
