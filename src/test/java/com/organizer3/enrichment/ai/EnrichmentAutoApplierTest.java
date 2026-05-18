package com.organizer3.enrichment.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.mcp.tools.PickReviewCandidateTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EnrichmentAutoApplier}: validates the agreed/slug guards, the
 * {@code pickTool.call({queue_row_id, slug})} invocation shape, success/failure
 * persistence behavior, and the INFO/WARN log lines surfaced to the in-app logs viewer.
 */
class EnrichmentAutoApplierTest {

    private EnrichmentReviewQueueRepository queueRepo;
    private PickReviewCandidateTool pickTool;
    private EnrichmentAutoApplier applier;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level priorLevel;

    @BeforeEach
    void setUp() {
        queueRepo = mock(EnrichmentReviewQueueRepository.class);
        pickTool  = mock(PickReviewCandidateTool.class);
        applier   = new EnrichmentAutoApplier(queueRepo, pickTool, new ObjectMapper());

        logger = (Logger) LoggerFactory.getLogger(EnrichmentAutoApplier.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        priorLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(priorLevel);
    }

    private OpenRow row(long id, String code, String confidence, String slug) {
        return new OpenRow(id, /* titleId */ 100L + id, code, /* slug */ null,
                "ambiguous", "javdb_search", "2026-05-17T12:00:00Z", "{}",
                slug, confidence, "ai reason", "2026-05-17T12:00:00Z", false);
    }

    private List<String> messagesAt(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ── 1. Happy path ─────────────────────────────────────────────────────────

    @Test
    void happyPath_agreedAndSlugPresent_invokesPickToolMarksRowAndLogsInfo() throws Exception {
        OpenRow r = row(42L, "ABC-123", "agreed", "abc-123-alpha");

        boolean result = applier.apply(r);

        assertTrue(result);

        ArgumentCaptor<JsonNode> argsCap = ArgumentCaptor.forClass(JsonNode.class);
        verify(pickTool).call(argsCap.capture());
        JsonNode args = argsCap.getValue();
        assertEquals(42L,             args.get("queue_row_id").asLong());
        assertEquals("abc-123-alpha", args.get("slug").asText());

        verify(queueRepo).markAiAutoApplied(42L);

        List<String> info = messagesAt(Level.INFO);
        assertEquals(1, info.size(), "expected exactly one INFO log line; got: " + info);
        assertTrue(info.get(0).contains("auto-applied"));
        assertTrue(info.get(0).contains("code=ABC-123"));
        assertTrue(info.get(0).contains("slug=abc-123-alpha"));
    }

    // ── 2. PickTool throws ────────────────────────────────────────────────────

    @Test
    void pickToolThrows_doesNotMark_returnsFalse_logsWarn() throws Exception {
        OpenRow r = row(7L, "XYZ-9", "agreed", "xyz-9-beta");
        doThrow(new IllegalStateException("javdb 503")).when(pickTool).call(any());

        boolean result = applier.apply(r);

        assertFalse(result);
        verify(pickTool).call(any());
        verify(queueRepo, never()).markAiAutoApplied(anyLong());

        List<String> warns = messagesAt(Level.WARN);
        assertEquals(1, warns.size(), "expected exactly one WARN log line; got: " + warns);
        assertTrue(warns.get(0).contains("auto-apply failed"));
        assertTrue(warns.get(0).contains("code=XYZ-9"));
        assertTrue(warns.get(0).contains("javdb 503"));
    }

    // ── 3. Non-agreed confidence ──────────────────────────────────────────────

    @Test
    void nonAgreedConfidence_neverCallsPickToolOrMarks_returnsFalse() throws Exception {
        OpenRow r = row(5L, "ABC-1", "conflict", "abc-1-alpha");

        boolean result = applier.apply(r);

        assertFalse(result);
        verify(pickTool, never()).call(any());
        verify(queueRepo, never()).markAiAutoApplied(anyLong());
    }

    // ── 4. Null slug (defensive: agreed but no slug) ──────────────────────────

    @Test
    void nullSlug_neverCallsPickTool_returnsFalse() throws Exception {
        OpenRow r = row(6L, "ABC-2", "agreed", null);

        boolean result = applier.apply(r);

        assertFalse(result);
        verify(pickTool, never()).call(any());
        verify(queueRepo, never()).markAiAutoApplied(anyLong());
    }

    // ── 5. Blank slug ─────────────────────────────────────────────────────────

    @Test
    void blankSlug_neverCallsPickTool_returnsFalse() throws Exception {
        OpenRow empty       = row(8L, "ABC-3", "agreed", "");
        OpenRow whitespace  = row(9L, "ABC-4", "agreed", "   ");

        assertFalse(applier.apply(empty));
        assertFalse(applier.apply(whitespace));

        verify(pickTool, never()).call(any());
        verify(queueRepo, never()).markAiAutoApplied(anyLong());
    }
}
