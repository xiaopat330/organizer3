package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeStructureDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PartitionResolver#resolvePartitionId}.
 *
 * <p>Assertions are verified against what {@link com.organizer3.sync.scanner.ConventionalScanner}
 * and {@link com.organizer3.sync.scanner.ExhibitionScanner} would assign for the same path.
 */
class PartitionResolverTest {

    // ── Shared structure definitions ──────────────────────────────────────────

    /** Conventional (tiered stars): models volumes like s, a, hj, etc. */
    private static final VolumeStructureDef CONVENTIONAL = new VolumeStructureDef(
            "conventional",
            List.of(
                    new PartitionDef("queue",     "queue"),
                    new PartitionDef("attention", "attention"),
                    new PartitionDef("archive",   "archive")
            ),
            new StructuredPartitionDef("stars", List.of(
                    new PartitionDef("library",   "library"),
                    new PartitionDef("minor",     "minor"),
                    new PartitionDef("popular",   "popular"),
                    new PartitionDef("superstar", "superstar"),
                    new PartitionDef("goddess",   "goddess")
            ))
    );

    /**
     * Exhibition (flat stars, empty tiers): models the qnap volume.
     * Actress folders sit directly under /stars/ — no tier sub-folders.
     * ExhibitionScanner hardcodes partition_id = "stars" for all titles here.
     */
    private static final VolumeStructureDef EXHIBITION = new VolumeStructureDef(
            "exhibition",
            List.of(),
            new StructuredPartitionDef("stars", List.of()) // empty tiers
    );

    // ── Conventional (tiered stars) ───────────────────────────────────────────

    @Test
    void tieredStars_popular() {
        // ConventionalScanner line 46: "stars/" + sub.id() → "stars/popular"
        assertEquals("stars/popular",
                resolve(CONVENTIONAL, "/stars/popular/Actress/Actress (MIDE-123)"));
    }

    @Test
    void tieredStars_superstar() {
        assertEquals("stars/superstar",
                resolve(CONVENTIONAL, "/stars/superstar/Shoko Akiyama/Shoko Akiyama (ABC-001)"));
    }

    @Test
    void tieredStars_library() {
        assertEquals("stars/library",
                resolve(CONVENTIONAL, "/stars/library/Actress/Actress (OLDZ-001)"));
    }

    @Test
    void tieredStars_goddessDeepPath() {
        // Extra depth (e.g. a sub-folder inside the title folder) still resolves correctly
        assertEquals("stars/goddess",
                resolve(CONVENTIONAL, "/stars/goddess/Saki/Saki (MIMK-190)/h265"));
    }

    @Test
    void unstructuredPartition_queue() {
        // ConventionalScanner: unstructured partition id="queue", path="queue"
        assertEquals("queue", resolve(CONVENTIONAL, "/queue/Actress (MIDE-001)"));
    }

    @Test
    void unstructuredPartition_attention() {
        assertEquals("attention", resolve(CONVENTIONAL, "/attention/review"));
    }

    @Test
    void unstructuredPartition_archive() {
        assertEquals("archive", resolve(CONVENTIONAL, "/archive/old-title"));
    }

    // ── Exhibition (flat stars / qnap) ────────────────────────────────────────

    /**
     * Bug repro: /stars/Shoko Akiyama on the qnap exhibition volume.
     * The old helper returned "Shoko Akiyama"; the correct value is "stars".
     */
    @Test
    void exhibition_flatStars_doesNotReturnActressName() {
        String result = resolve(EXHIBITION, "/stars/Shoko Akiyama");
        assertEquals("stars", result,
                "flat stars (empty tiers) should resolve to 'stars', not the actress name");
    }

    @Test
    void exhibition_flatStars_deepPath() {
        // Title nested under /stars/<actressName>/title-folder
        assertEquals("stars",
                resolve(EXHIBITION, "/stars/Shoko Akiyama/Shoko Akiyama (ABC-001)"));
    }

    @Test
    void exhibition_starsSingleSegment() {
        // Just /stars itself
        assertEquals("stars", resolve(EXHIBITION, "/stars"));
    }

    // ── Path normalisation ─────────────────────────────────────────────────────

    @Test
    void pathWithLeadingSlash() {
        // Java's Path.of("/stars/popular/...").getName(0) == "stars" — leading slash is stripped
        assertEquals("stars/popular",
                resolve(CONVENTIONAL, "/stars/popular/Actress/Title"));
    }

    @Test
    void pathWithoutLeadingSlash() {
        assertEquals("stars/popular",
                resolve(CONVENTIONAL, "stars/popular/Actress/Title"));
    }

    // ── Null/empty path ───────────────────────────────────────────────────────

    @Test
    void nullPath_returnsUnknown() {
        assertEquals("unknown", PartitionResolver.resolvePartitionId(CONVENTIONAL, null));
    }

    /**
     * Verifies the id-vs-path distinction in PartitionDef: the queue structure has
     * id="queue" but path="fresh", so /fresh/Foo resolves to "queue" (not "fresh").
     * This is the case ConventionalScanner's scanner code explicitly exercises.
     */
    @Test
    void idVsPath_queueStructure_returnsId_notPath() {
        VolumeStructureDef queueType = new VolumeStructureDef(
                "queue",
                List.of(new PartitionDef("queue", "fresh")),
                null
        );
        assertEquals("queue", resolve(queueType, "/fresh/Foo (ABC-123)"),
                "resolver must return p.id() ('queue'), not p.path() ('fresh')");
    }

    // ── Null structure (unknown volume type) ──────────────────────────────────

    @Test
    void nullStructure_fallsBackToFirstSegment() {
        // When no structure is known (null), fall back to first segment.
        assertEquals("queue",
                PartitionResolver.resolvePartitionId(null, Path.of("/queue/Foo (ABC-001)")));
        assertEquals("stars",
                PartitionResolver.resolvePartitionId(null, Path.of("/stars/SomeName")));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String resolve(VolumeStructureDef structure, String pathStr) {
        return PartitionResolver.resolvePartitionId(structure, Path.of(pathStr));
    }
}
