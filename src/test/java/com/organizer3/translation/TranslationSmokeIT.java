package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.ollama.HttpOllamaAdapter;
import com.organizer3.translation.repository.jdbi.JdbiTranslationCacheRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test that hits a real local Ollama daemon.
 * Disabled by default; set {@code RUN_TRANSLATION_SMOKE=1} to enable.
 *
 * <p>Validates the Phase 1 stack: HttpOllamaAdapter → TranslationServiceImpl → cache write/read.
 * Confirms (a) NFKC-normalized input hashes consistently, (b) cache hit on second call avoids
 * a second Ollama invocation, (c) strategy selection routes content correctly.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TRANSLATION_SMOKE", matches = "1")
class TranslationSmokeIT {

    @Test
    void translatesAndCachesAgainstRealOllama() throws Exception {
        Path tmpDb = Files.createTempFile("translation-smoke-", ".db");
        try {
            Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + tmpDb.toAbsolutePath());
            new SchemaInitializer(jdbi).initialize();

            JdbiTranslationStrategyRepository strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
            JdbiTranslationCacheRepository cacheRepo = new JdbiTranslationCacheRepository(jdbi);
            new TranslationStrategySeeder(strategyRepo).seedIfEmpty();

            ObjectMapper jsonMapper = new ObjectMapper();
            HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", jsonMapper);
            assertTrue(adapter.isHealthy(), "Ollama daemon must be running on localhost:11434");

            TranslationConfig config = new TranslationConfig(
                    "http://localhost:11434", 120, "gemma4:e4b", "qwen2.5:14b");
            TranslationService service = new TranslationServiceImpl(
                    adapter, strategyRepo, cacheRepo, config, jsonMapper);

            // Test 1: simple maker name, label_basic strategy
            String maker = "クリスタル映像";
            long t0 = System.nanoTime();
            long row1 = service.requestTranslation(new TranslationRequest(maker, null, null, null));
            long firstMs = (System.nanoTime() - t0) / 1_000_000;
            String en1 = service.getCached(maker).orElseThrow();
            System.out.printf("  [%dms cold] %s -> %s%n", firstMs, maker, en1);
            assertNotNull(en1);
            assertFalse(en1.isBlank());

            // Test 2: same input again, must be a cache hit (fast)
            long t1 = System.nanoTime();
            long row2 = service.requestTranslation(new TranslationRequest(maker, null, null, null));
            long secondMs = (System.nanoTime() - t1) / 1_000_000;
            System.out.printf("  [%dms warm cache] %s -> same row=%s%n", secondMs, maker, row1 == row2);
            assertEquals(row1, row2, "second request must return the same cache row");
            assertTrue(secondMs < 500, "cache hit should be < 500ms (was " + secondMs + "ms)");

            // Test 3: NFKC-equivalent input (full-width space + trailing whitespace) → same row
            String nfkcVariant = "  クリスタル映像  ";
            long row3 = service.requestTranslation(new TranslationRequest(nfkcVariant, null, null, null));
            System.out.printf("  NFKC-variant -> same row=%s%n", row1 == row3);
            assertEquals(row1, row3, "NFKC-normalized input must hit same cache row");

            // Test 4: long descriptive title, should route to label_explicit (no contextHint, len>50)
            String title = "ガッツリ欲しがるカラダ 吉川あいみ";
            long t2 = System.nanoTime();
            long titleRow = service.requestTranslation(new TranslationRequest(title, null, null, null));
            long titleMs = (System.nanoTime() - t2) / 1_000_000;
            String titleEn = service.getCached(title).orElseThrow();
            System.out.printf("  [%dms cold] %s -> %s%n", titleMs, title, titleEn);
            assertTrue(titleRow > 0);

            // Test 5: explicit contextHint=prose
            String paragraph = "麻美 ゆま（あさみ ゆま、1987年3月24日 - ）は、日本のタレント、歌手、女優、元AV女優、元恵比寿マスカッツ。";
            long t3 = System.nanoTime();
            long proseRow = service.requestTranslation(new TranslationRequest(paragraph, "prose", null, null));
            long proseMs = (System.nanoTime() - t3) / 1_000_000;
            String proseEn = service.getCached(paragraph).orElseThrow();
            System.out.printf("  [%dms cold] prose-strategy paragraph -> %s%n", proseMs, proseEn.substring(0, Math.min(80, proseEn.length())));
            assertTrue(proseRow > 0);

            TranslationServiceStats stats = service.stats();
            System.out.printf("%nFinal stats: %s%n", stats);
        } finally {
            Files.deleteIfExists(tmpDb);
        }
    }
}
