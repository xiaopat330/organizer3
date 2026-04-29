package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CastMatcherTest {

    private Jdbi jdbi;
    private Connection connection;
    private JdbiActressRepository actressRepo;
    private CastMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        matcher = new CastMatcher(actressRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private long insertActress(String canonical, String stage) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES (:c, :s, 'LIBRARY', '2024-01-01')")
                .bind("c", canonical).bind("s", stage)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void addAlias(long actressId, String name) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actress_aliases (actress_id, alias_name) VALUES (?, ?)", actressId, name));
    }

    private static List<TitleExtract.CastEntry> cast(String... names) {
        var entries = new java.util.ArrayList<TitleExtract.CastEntry>();
        for (int i = 0; i < names.length; i++) {
            entries.add(new TitleExtract.CastEntry("slug" + i, names[i], "F"));
        }
        return entries;
    }

    @Test
    void match_returnsTrueWhenStageNameMatchesCast() {
        long id = insertActress("Yuma Asami", "麻美ゆま");
        var result = matcher.match(id, cast("麻美ゆま", "Other Actress"));
        assertTrue(result.matched());
        assertEquals("麻美ゆま", result.matchedName());
    }

    @Test
    void match_returnsTrueWhenAliasMatchesCast() {
        long id = insertActress("Yuma Asami", "麻美ゆま");
        addAlias(id, "Asami Yuma");
        var result = matcher.match(id, cast("Asami Yuma"));
        assertTrue(result.matched());
    }

    @Test
    void match_returnsFalseWhenNeitherStageNameNorAliasMatches() {
        long id = insertActress("Yuma Asami", "麻美ゆま");
        var result = matcher.match(id, cast("Unknown Actress", "Another One"));
        assertFalse(result.matched());
        assertNull(result.matchedName());
    }

    @Test
    void match_returnsFalseForEmptyCast() {
        long id = insertActress("Yuma Asami", "麻美ゆま");
        assertFalse(matcher.match(id, List.of()).matched());
    }

    @Test
    void match_returnsFalseForNullCast() {
        long id = insertActress("Yuma Asami", "麻美ゆま");
        assertFalse(matcher.match(id, null).matched());
    }

    @Test
    void match_returnsFalseForUnknownActressId() {
        assertFalse(matcher.match(9999L, cast("Some Actress")).matched());
    }

    @Test
    void match_handlesNullStageName() {
        // Actress has no stage name — only canonical
        long id = insertActress("No Stage Name", null);
        addAlias(id, "AlternateName");
        var result = matcher.match(id, cast("AlternateName"));
        assertTrue(result.matched());
    }
}
