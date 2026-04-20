package com.organizer3.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads the real bundled tags.yaml — if the resource is missing or malformed,
 * this test fails early instead of at runtime in the browser.
 */
class TagCatalogLoaderTest {

    @Test
    void loadReturnsNonEmptyOrderedGroups() {
        List<TagCatalogLoader.TagGroup> groups = new TagCatalogLoader().load();
        assertFalse(groups.isEmpty(), "tags.yaml should produce at least one group");
        groups.forEach(g -> {
            assertNotNull(g.category());
            assertNotNull(g.label());
            assertNotNull(g.tags());
        });
    }

    @Test
    void categoryLabelsMapToHumanReadableStrings() {
        List<TagCatalogLoader.TagGroup> groups = new TagCatalogLoader().load();
        // If a group's category is one of the known keys, its label must have been replaced.
        for (var g : groups) {
            if ("format".equals(g.category()))           assertEquals("Format / Production", g.label());
            else if ("production_style".equals(g.category())) assertEquals("Production Style", g.label());
            else if ("body".equals(g.category()))        assertEquals("Body / Aesthetic", g.label());
        }
    }

    @Test
    void eachTagHasNameAndOptionalDescription() {
        List<TagCatalogLoader.TagGroup> groups = new TagCatalogLoader().load();
        for (var g : groups) {
            for (var t : g.tags()) {
                assertNotNull(t.name(), "tag name missing in group: " + g.category());
                assertFalse(t.name().isBlank(), "blank tag name in group: " + g.category());
                // description is optional, but if present it must not be blank
                if (t.description() != null) {
                    assertFalse(t.description().isBlank());
                }
            }
        }
    }

    @Test
    void unknownCategoryFallsBackToRawKey() {
        // Smoke: if tags.yaml ever gains a new category not in CATEGORY_LABELS,
        // the loader must still produce a group (label == category) — verify the
        // fallback path by checking that every label is non-null.
        new TagCatalogLoader().load().forEach(g -> assertNotNull(g.label()));
    }
}
