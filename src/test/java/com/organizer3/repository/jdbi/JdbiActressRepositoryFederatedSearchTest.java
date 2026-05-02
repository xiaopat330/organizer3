package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.ActressRepository.FederatedActressResult;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the COALESCE(custom_avatar_path, local_avatar_path) resolution in
 * searchForEditor and searchForFederated.
 */
class JdbiActressRepositoryFederatedSearchTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiActressRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long insertActress(String name) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES (:n, 'LIBRARY', '2024-01-01')")
                        .bind("n", name)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class).one());
    }

    private void setCustomAvatar(long actressId, String path) {
        jdbi.useHandle(h -> h.execute(
                "UPDATE actresses SET custom_avatar_path = ? WHERE id = ?", path, actressId));
    }

    private void insertStagingWithAvatar(long actressId, String localAvatarPath) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status, local_avatar_path) VALUES (?, 'slug-" + actressId + "', 'fetched', ?)",
                actressId, localAvatarPath));
    }

    private int titleSeq = 0;

    private void insertTitle(long actressId) {
        int n = ++titleSeq;
        String code = "T-%03d".formatted(n);
        String baseCode = "T-%05d".formatted(n);
        jdbi.useHandle(h -> {
            long tid = h.createUpdate(
                    "INSERT INTO titles (code, base_code, label, seq_num, actress_id) VALUES (:c,:bc,'T',:n,:a)")
                    .bind("c", code).bind("bc", baseCode).bind("n", n).bind("a", actressId)
                    .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", tid, actressId);
        });
    }

    // ── searchForEditor COALESCE tests ────────────────────────────────────────

    @Test
    void searchForEditor_neitherAvatar_returnsNullAvatarAndFalseCustom() {
        long id = insertActress("Aya Sazanami");
        insertTitle(id);

        List<FederatedActressResult> results = repo.searchForEditor("Aya", false, 10);
        assertEquals(1, results.size());
        assertNull(results.get(0).localAvatarPath());
        assertFalse(results.get(0).hasCustomAvatar());
    }

    @Test
    void searchForEditor_enrichedOnly_returnsEnrichedPath() {
        long id = insertActress("Hibiki Otsuki");
        insertStagingWithAvatar(id, "actress-avatars/hibiki.jpg");

        List<FederatedActressResult> results = repo.searchForEditor("Hibiki", false, 10);
        assertEquals(1, results.size());
        assertEquals("actress-avatars/hibiki.jpg", results.get(0).localAvatarPath());
        assertFalse(results.get(0).hasCustomAvatar());
    }

    @Test
    void searchForEditor_customOnly_returnsCustomPath() {
        long id = insertActress("Sora Aoi");
        setCustomAvatar(id, "actress-custom-avatars/" + id + ".jpg");

        List<FederatedActressResult> results = repo.searchForEditor("Sora", false, 10);
        assertEquals(1, results.size());
        assertEquals("actress-custom-avatars/" + id + ".jpg", results.get(0).localAvatarPath());
        assertTrue(results.get(0).hasCustomAvatar());
    }

    @Test
    void searchForEditor_bothAvatars_prefersCustom() {
        long id = insertActress("Yua Aida");
        insertStagingWithAvatar(id, "actress-avatars/yua.jpg");
        setCustomAvatar(id, "actress-custom-avatars/" + id + ".jpg");

        List<FederatedActressResult> results = repo.searchForEditor("Yua", false, 10);
        assertEquals(1, results.size());
        assertEquals("actress-custom-avatars/" + id + ".jpg", results.get(0).localAvatarPath());
        assertTrue(results.get(0).hasCustomAvatar());
    }

    // ── searchForFederated COALESCE tests ──────────────────────────────────────

    @Test
    void searchForFederated_neitherAvatar_returnsNullAndFalseCustom() {
        long id = insertActress("Nana Ogura");
        insertTitle(id);
        insertTitle(id);

        List<FederatedActressResult> results = repo.searchForFederated("Nana", false, 10);
        assertEquals(1, results.size());
        assertNull(results.get(0).localAvatarPath());
        assertFalse(results.get(0).hasCustomAvatar());
    }

    @Test
    void searchForFederated_enrichedOnly_returnsEnrichedPath() {
        long id = insertActress("Yuma Asami");
        insertStagingWithAvatar(id, "actress-avatars/yuma.jpg");
        insertTitle(id);
        insertTitle(id);

        List<FederatedActressResult> results = repo.searchForFederated("Yuma", false, 10);
        assertEquals(1, results.size());
        assertEquals("actress-avatars/yuma.jpg", results.get(0).localAvatarPath());
        assertFalse(results.get(0).hasCustomAvatar());
    }

    @Test
    void searchForFederated_customOnly_returnsCustomPath() {
        long id = insertActress("Julia Boin");
        setCustomAvatar(id, "actress-custom-avatars/" + id + ".jpg");
        insertTitle(id);
        insertTitle(id);

        List<FederatedActressResult> results = repo.searchForFederated("Julia", false, 10);
        assertEquals(1, results.size());
        assertEquals("actress-custom-avatars/" + id + ".jpg", results.get(0).localAvatarPath());
        assertTrue(results.get(0).hasCustomAvatar());
    }

    @Test
    void searchForFederated_bothAvatars_prefersCustom() {
        long id = insertActress("Erina Oka");
        insertStagingWithAvatar(id, "actress-avatars/erina.jpg");
        setCustomAvatar(id, "actress-custom-avatars/" + id + ".jpg");
        insertTitle(id);
        insertTitle(id);

        List<FederatedActressResult> results = repo.searchForFederated("Erina", false, 10);
        assertEquals(1, results.size());
        assertEquals("actress-custom-avatars/" + id + ".jpg", results.get(0).localAvatarPath());
        assertTrue(results.get(0).hasCustomAvatar());
    }
}
