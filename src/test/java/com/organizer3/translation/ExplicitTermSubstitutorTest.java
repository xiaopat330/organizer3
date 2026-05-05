package com.organizer3.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExplicitTermSubstitutor}. The vocabulary used here is
 * deliberately benign — the production vocabulary lives in a per-machine
 * properties file outside source control. These tests use synthetic
 * key/value pairs to exercise the loading, ordering, and substitution
 * mechanics without putting any sensitive vocabulary in the repository.
 */
class ExplicitTermSubstitutorTest {

    @Test
    void emptyInstanceIsNoOp() {
        assertEquals(0, ExplicitTermSubstitutor.EMPTY.size());
        assertEquals("hello world", ExplicitTermSubstitutor.EMPTY.substitute("hello world"));
    }

    @Test
    void nullAndEmptyInputPassThrough() {
        ExplicitTermSubstitutor s = new ExplicitTermSubstitutor(Map.of("foo", "bar"));
        assertNull(s.substitute(null));
        assertEquals("", s.substitute(""));
    }

    @Test
    void substitutesEverySingleOccurrence() {
        ExplicitTermSubstitutor s = new ExplicitTermSubstitutor(Map.of("foo", "BAR"));
        assertEquals("alpha BAR beta BAR gamma", s.substitute("alpha foo beta foo gamma"));
    }

    @Test
    void noMatchReturnsInputUnchanged() {
        ExplicitTermSubstitutor s = new ExplicitTermSubstitutor(Map.of("foo", "BAR"));
        assertEquals("nothing to see here", s.substitute("nothing to see here"));
    }

    @Test
    void longestKeyWins_regardlessOfInsertionOrder() {
        // Insert short key first; constructor must reorder so "foobar" wins
        // over "foo" when both could match.
        LinkedHashMap<String, String> raw = new LinkedHashMap<>();
        raw.put("foo",    "SHORT");
        raw.put("foobar", "LONG");
        ExplicitTermSubstitutor s = new ExplicitTermSubstitutor(raw);
        assertEquals("LONG baz", s.substitute("foobar baz"));
    }

    @Test
    void multipleDistinctKeys() {
        ExplicitTermSubstitutor s = new ExplicitTermSubstitutor(Map.of(
                "alpha", "A",
                "beta",  "B",
                "gamma", "C"));
        String result = s.substitute("alpha beta gamma delta");
        assertTrue(result.contains("A"));
        assertTrue(result.contains("B"));
        assertTrue(result.contains("C"));
        assertTrue(result.contains("delta"));
    }

    @Test
    void loadFromMissingFileReturnsEmpty(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.properties");
        ExplicitTermSubstitutor s = ExplicitTermSubstitutor.loadFromFile(missing);
        assertEquals(0, s.size());
        assertSame(ExplicitTermSubstitutor.EMPTY, s);
    }

    @Test
    void loadFromFileReadsKeyValuePairs(@TempDir Path tmp) throws IOException {
        Path props = tmp.resolve("subs.properties");
        Files.writeString(props,
                "# header comment\n"
              + "foo=BAR\n"
              + "longerkey=LONGER\n"
              + "key.with.dots=DOTS\n");
        ExplicitTermSubstitutor s = ExplicitTermSubstitutor.loadFromFile(props);
        assertEquals(3, s.size());
        assertEquals("BAR", s.substitute("foo"));
        assertEquals("LONGER", s.substitute("longerkey"));
        assertEquals("DOTS", s.substitute("key.with.dots"));
    }

    @Test
    void loadFromFileSupportsUnicodeEscapes(@TempDir Path tmp) throws IOException {
        // Properties files support backslash-u escapes in both key and value.
        // Use a generic non-ASCII char (Greek alpha) — no need to import the
        // production vocabulary into tests.
        Path props = tmp.resolve("u.properties");
        Files.writeString(props, "\\u03B1=alpha\n");          // α=alpha
        ExplicitTermSubstitutor s = ExplicitTermSubstitutor.loadFromFile(props);
        assertEquals(1, s.size());
        assertEquals("alpha world", s.substitute("α world"));
    }

    @Test
    void loadFromFileReorderByKeyLength(@TempDir Path tmp) throws IOException {
        // Properties is Hashtable-backed (no insertion order). Verify the
        // loader still produces longest-match-wins behavior after load.
        Path props = tmp.resolve("ord.properties");
        Files.writeString(props,
                "ab=SHORT\n"
              + "abc=LONG\n"
              + "abcd=LONGEST\n");
        ExplicitTermSubstitutor s = ExplicitTermSubstitutor.loadFromFile(props);
        assertEquals("LONGEST tail", s.substitute("abcd tail"));
        assertEquals("LONG tail",    s.substitute("abc tail"));
        assertEquals("SHORT tail",   s.substitute("ab tail"));
    }
}
