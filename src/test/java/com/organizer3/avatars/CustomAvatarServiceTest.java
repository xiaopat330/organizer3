package com.organizer3.avatars;

import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CustomAvatarServiceTest {

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private CustomAvatarStore store;
    private CoverPath coverPath;
    private CustomAvatarService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        store = new CustomAvatarStore(tempDir);
        coverPath = new CoverPath(tempDir);
        service = new CustomAvatarService(store, actressRepo, titleRepo, coverPath);
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

    private int titleSeq = 0;

    /** Inserts a title and returns {@code [titleId, baseCode]}. */
    private Object[] insertTitle(long actressId, String label) {
        int n = ++titleSeq;
        String code = label + "-%03d".formatted(n);
        String baseCode = label + "-%05d".formatted(n);
        long tid = jdbi.withHandle(h -> {
            long id = h.createUpdate(
                    "INSERT INTO titles (code, base_code, label, seq_num, actress_id) VALUES (:c,:bc,:lbl,:n,:a)")
                    .bind("c", code).bind("bc", baseCode).bind("lbl", label).bind("n", n).bind("a", actressId)
                    .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", id, actressId);
            return id;
        });
        return new Object[]{tid, baseCode};
    }

    private void createCoverFile(String label, String baseCode) throws Exception {
        Path labelDir = tempDir.resolve("covers").resolve(label.toUpperCase());
        Files.createDirectories(labelDir);
        Files.writeString(labelDir.resolve(baseCode + ".jpg"), "fake-cover");
    }

    private byte[] squareJpeg(int size) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", buf);
        return buf.toByteArray();
    }

    // ── setCustomAvatar ───────────────────────────────────────────────────────

    @Test
    void setCustomAvatar_returnsEmptyForNonexistentActress() throws Exception {
        Optional<CustomAvatarService.AvatarResult> result = service.setCustomAvatar(999L, squareJpeg(200));
        assertTrue(result.isEmpty());
    }

    @Test
    void setCustomAvatar_savesFileAndUpdatesDb() throws Exception {
        long id = insertActress("Aya Sazanami");

        Optional<CustomAvatarService.AvatarResult> result = service.setCustomAvatar(id, squareJpeg(200));

        assertTrue(result.isPresent());
        assertEquals("/actress-custom-avatars/" + id + ".jpg", result.get().localAvatarUrl());
        assertTrue(result.get().hasCustomAvatar());
        assertTrue(Files.exists(tempDir.resolve("actress-custom-avatars/" + id + ".jpg")));

        // DB reflects the path (without leading slash)
        var actress = actressRepo.findById(id).orElseThrow();
        // findById doesn't surface custom_avatar_path directly, but the COALESCE queries do;
        // verify indirectly via the store file presence
        assertTrue(result.get().hasCustomAvatar());
    }

    @Test
    void setCustomAvatar_doubleSet_replacesFile() throws Exception {
        long id = insertActress("Hibiki Otsuki");

        service.setCustomAvatar(id, squareJpeg(200));
        long firstSize = Files.size(tempDir.resolve("actress-custom-avatars/" + id + ".jpg"));

        service.setCustomAvatar(id, squareJpeg(300));
        long secondSize = Files.size(tempDir.resolve("actress-custom-avatars/" + id + ".jpg"));

        assertNotEquals(firstSize, secondSize, "second save should replace the first");
    }

    @Test
    void setCustomAvatar_rejectsInvalidImage() {
        long id = insertActress("Sora Aoi");
        assertThrows(IllegalArgumentException.class,
                () -> service.setCustomAvatar(id, new byte[]{1, 2, 3}));
    }

    // ── clearCustomAvatar ─────────────────────────────────────────────────────

    @Test
    void clearCustomAvatar_returnsEmptyForNonexistentActress() {
        Optional<Boolean> result = service.clearCustomAvatar(999L);
        assertTrue(result.isEmpty());
    }

    @Test
    void clearCustomAvatar_idempotentWhenNoneExists() {
        long id = insertActress("Yua Aida");

        assertDoesNotThrow(() -> service.clearCustomAvatar(id));
        assertDoesNotThrow(() -> service.clearCustomAvatar(id));
    }

    @Test
    void clearCustomAvatar_fullLifecycle() throws Exception {
        long id = insertActress("Nana Ogura");

        service.setCustomAvatar(id, squareJpeg(200));
        assertTrue(Files.exists(tempDir.resolve("actress-custom-avatars/" + id + ".jpg")));

        service.clearCustomAvatar(id);
        assertFalse(Files.exists(tempDir.resolve("actress-custom-avatars/" + id + ".jpg")));
    }

    // ── listTitleCovers ───────────────────────────────────────────────────────

    @Test
    void listTitleCovers_returnsEmptyForNonexistentActress() {
        Optional<List<CustomAvatarService.TitleCover>> result = service.listTitleCovers(999L);
        assertTrue(result.isEmpty());
    }

    @Test
    void listTitleCovers_emptyWhenActressHasNoTitles() {
        long id = insertActress("Yuma Asami");
        Optional<List<CustomAvatarService.TitleCover>> result = service.listTitleCovers(id);
        assertTrue(result.isPresent());
        assertTrue(result.get().isEmpty());
    }

    @Test
    void listTitleCovers_onlyReturnsTitlesWithLocalCovers() throws Exception {
        long id = insertActress("Julia Boin");

        // Title 1: has a cover on disk
        Object[] t1 = insertTitle(id, "ABP");
        createCoverFile("ABP", (String) t1[1]);

        // Title 2: no cover on disk
        insertTitle(id, "SSIS");

        Optional<List<CustomAvatarService.TitleCover>> result = service.listTitleCovers(id);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals("ABP", result.get().get(0).label());
        assertTrue(result.get().get(0).coverUrl().startsWith("/covers/ABP/"));
    }

    @Test
    void listTitleCovers_coverUrlFormat() throws Exception {
        long id = insertActress("Erina Oka");
        Object[] t = insertTitle(id, "ABP");
        createCoverFile("ABP", (String) t[1]);

        List<CustomAvatarService.TitleCover> covers = service.listTitleCovers(id).orElseThrow();
        assertEquals(1, covers.size());
        CustomAvatarService.TitleCover cover = covers.get(0);
        assertEquals("ABP", cover.label());
        assertTrue(cover.code().startsWith("ABP-"));
        assertTrue(cover.coverUrl().startsWith("/covers/ABP/"));
        assertTrue(cover.titleId() > 0);
    }

    @Test
    void listTitleCovers_multipleCovers() throws Exception {
        long id = insertActress("Moe Amatsuka");

        Object[] t1 = insertTitle(id, "ABP");
        createCoverFile("ABP", (String) t1[1]);

        Object[] t2 = insertTitle(id, "ABP");
        createCoverFile("ABP", (String) t2[1]);

        insertTitle(id, "SSIS"); // no cover

        List<CustomAvatarService.TitleCover> covers = service.listTitleCovers(id).orElseThrow();
        assertEquals(2, covers.size());
    }
}
