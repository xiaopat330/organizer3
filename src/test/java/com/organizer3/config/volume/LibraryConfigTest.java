package com.organizer3.config.volume;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LibraryConfigTest {

    @Test
    void defaults_applyWhenFieldsNull() {
        LibraryConfig empty = new LibraryConfig(null, null, null, null, null);
        assertEquals(3,   empty.effectiveStar());
        assertEquals(5,   empty.effectiveMinor());
        assertEquals(20,  empty.effectivePopular());
        assertEquals(50,  empty.effectiveSuperstar());
        assertEquals(100, empty.effectiveGoddess());
    }

    @Test
    void explicitValues_overrideDefaults() {
        LibraryConfig custom = new LibraryConfig(1, 2, 3, 4, 5);
        assertEquals(1, custom.effectiveStar());
        assertEquals(2, custom.effectiveMinor());
        assertEquals(3, custom.effectivePopular());
        assertEquals(4, custom.effectiveSuperstar());
        assertEquals(5, custom.effectiveGoddess());
    }

    @Test
    void tierFor_belowStar_isPool() {
        LibraryConfig c = LibraryConfig.DEFAULTS;
        assertEquals("pool", c.tierFor(0));
        assertEquals("pool", c.tierFor(2));
    }

    @Test
    void tierFor_atThresholds_isInclusiveLower() {
        LibraryConfig c = LibraryConfig.DEFAULTS;
        assertEquals("library",   c.tierFor(3));
        assertEquals("minor",     c.tierFor(5));
        assertEquals("popular",   c.tierFor(20));
        assertEquals("superstar", c.tierFor(50));
        assertEquals("goddess",   c.tierFor(100));
    }

    @Test
    void tierFor_wellAboveGoddess_stillGoddess() {
        assertEquals("goddess", LibraryConfig.DEFAULTS.tierFor(10000));
    }

    @Test
    void withDefaultsApplied_fillsAllNulls() {
        LibraryConfig partial = new LibraryConfig(null, 7, null, null, null);
        LibraryConfig filled = partial.withDefaultsApplied();
        assertEquals(3, filled.star());
        assertEquals(7, filled.minor());
        assertEquals(20, filled.popular());
    }
}
