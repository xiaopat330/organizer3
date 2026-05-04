package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.ollama.HttpOllamaAdapter;
import com.organizer3.translation.repository.jdbi.JdbiTranslationCacheRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationQueueRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test that hits a real local Ollama daemon.
 * Disabled by default; set {@code RUN_TRANSLATION_SMOKE=1} to enable.
 *
 * <p>Validates the Phase 2 stack: HttpOllamaAdapter → TranslationServiceImpl (async) →
 * TranslationWorker → cache write/read. The test starts the worker thread and polls
 * {@link TranslationService#getCached} until the translation arrives (with a 90s timeout).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TRANSLATION_SMOKE", matches = "1")
class TranslationSmokeIT {

    private Path tmpDb;
    private ExecutorService workerExecutor;

    @BeforeEach
    void setUp() throws Exception {
        tmpDb = Files.createTempFile("translation-smoke-", ".db");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
        Files.deleteIfExists(tmpDb);
    }

    @Test
    void translatesAndCachesAgainstRealOllama() throws Exception {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + tmpDb.toAbsolutePath());
        new SchemaInitializer(jdbi).initialize();

        JdbiTranslationStrategyRepository strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        JdbiTranslationCacheRepository cacheRepo = new JdbiTranslationCacheRepository(jdbi);
        JdbiTranslationQueueRepository queueRepo = new JdbiTranslationQueueRepository(jdbi);
        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();

        ObjectMapper jsonMapper = new ObjectMapper();
        HttpOllamaAdapter adapter = new HttpOllamaAdapter("http://localhost:11434", jsonMapper);
        assertTrue(adapter.isHealthy(), "Ollama daemon must be running on localhost:11434");

        TranslationConfig config = new TranslationConfig(
                "http://localhost:11434", 120, "gemma4:e4b", "qwen2.5:14b",
                2, 3, 600, 300);

        CallbackDispatcher callbackDispatcher = new CallbackDispatcher(jdbi);
        TranslationService service = new TranslationServiceImpl(
                adapter, strategyRepo, cacheRepo, queueRepo, config, callbackDispatcher);

        TranslationWorker worker = new TranslationWorker(
                adapter, strategyRepo, cacheRepo, queueRepo,
                callbackDispatcher, config, jsonMapper);

        // Start the background worker
        workerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smoke-test-worker");
            t.setDaemon(true);
            return t;
        });
        workerExecutor.submit(worker);

        // Test 1: simple maker name, label_basic strategy
        String maker = "クリスタル映像";
        long t0 = System.nanoTime();
        long queueId1 = service.requestTranslation(new TranslationRequest(maker, null, null, null));
        assertTrue(queueId1 > 0);
        System.out.printf("  Enqueued maker '%s' as queue row=%d%n", maker, queueId1);

        // Poll until translation is available (up to 90s for cold Ollama)
        String en1 = pollUntilCached(service, maker, 90_000);
        long firstMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  [%dms] %s -> %s%n", firstMs, maker, en1);
        assertNotNull(en1);
        assertFalse(en1.isBlank());

        // Test 2: same input again — must be a cache hit (done queue row, no worker involvement)
        long t1 = System.nanoTime();
        service.requestTranslation(new TranslationRequest(maker, null, null, null));
        long secondMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.printf("  [%dms warm cache] %s -> same result=%s%n",
                secondMs, maker, service.getCached(maker).orElse("MISS"));
        assertTrue(secondMs < 500, "Cache hit enqueue should be < 500ms (was " + secondMs + "ms)");

        // Test 3: NFKC-equivalent input → same cached translation
        String nfkcVariant = "  クリスタル映像  ";
        String en3 = service.getCached(nfkcVariant).orElse(null);
        assertNotNull(en3, "NFKC-normalized variant should hit the cache");
        System.out.printf("  NFKC-variant cached -> %s%n", en3);

        // Test 4: long descriptive title — label_explicit via heuristic (len>50)
        String title = "ガッツリ欲しがるカラダ 吉川あいみ";
        long queueId4 = service.requestTranslation(new TranslationRequest(title, null, null, null));
        String titleEn = pollUntilCached(service, title, 90_000);
        System.out.printf("  Title '%s' -> '%s'%n", title,
                titleEn != null ? titleEn.substring(0, Math.min(80, titleEn.length())) : "MISS");
        assertTrue(queueId4 > 0);
        assertNotNull(titleEn);

        // Test 5: explicit contextHint=prose
        String paragraph = "麻美 ゆま（あさみ ゆま、1987年3月24日 - ）は、日本のタレント、歌手、女優、元AV女優、元恵比寿マスカッツ。";
        long queueId5 = service.requestTranslation(new TranslationRequest(paragraph, "prose", null, null));
        String proseEn = pollUntilCached(service, paragraph, 90_000);
        System.out.printf("  Prose paragraph -> %s%n",
                proseEn != null ? proseEn.substring(0, Math.min(80, proseEn.length())) : "MISS");
        assertTrue(queueId5 > 0);
        assertNotNull(proseEn);

        TranslationServiceStats stats = service.stats();
        System.out.printf("%nFinal stats: %s%n", stats);
    }

    /**
     * Poll getCached every 500ms until the translation appears or timeout is reached.
     */
    private String pollUntilCached(TranslationService service, String text, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<String> cached = service.getCached(text);
            if (cached.isPresent()) {
                return cached.get();
            }
            Thread.sleep(500);
        }
        return null; // timed out
    }
}
