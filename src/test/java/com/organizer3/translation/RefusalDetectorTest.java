package com.organizer3.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the refusal-detection logic in {@link TranslationWorker#isRefusal}.
 *
 * <p>Covers both genuine refusals (should return true) and false-positive cases
 * from Japanese title translations that contain words like "sorry", "can't", or
 * "unable to" as part of the legitimate translated text.
 */
class RefusalDetectorTest {

    // ── True refusals ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "I cannot assist with this request.",
            "I cannot translate this content.",
            "I cannot provide a translation.",
            "Unable to translate this text.",
            "Unable to assist with your request.",
            "I'm not able to translate this content.",
            "Sorry, but I cannot translate this.",
            "I'm sorry, I cannot help with this.",
            "Sorry, but we cannot process this request.",
            "I'm sorry, but I can't assist with that.",
            "Sorry, however I am unable to translate.",
            "Sorry, I can't translate this.",
            "Sorry, but I can't provide a translation.",
            "I can't help with this request.",
            "I can't assist with adult content.",
            "I can't translate this material.",
            "I am programmed to avoid harmful content.",
            "I'm programmed not to generate this.",
            "This content is not appropriate.",
            "I refuse to translate this.",
            "I refuse to process this content.",
            "Safety guidelines prevent me from translating this.",
    })
    void isRefusal_trueForGenuineRefusals(String text) {
        assertTrue(TranslationWorker.isRefusal(text),
                "Expected refusal for: " + text);
    }

    // ── False-positive guard: legitimate title translations ──────────────────

    /**
     * Each of these is a plausible correct English translation of a short JAV title.
     * They contain words that the old pattern would have flagged (sorry, can't, unable to)
     * but in non-refusal context.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // ごめんなさい → "I'm sorry" / "sorry"
            "Widow, I'm Sorry... Hara Sarasa",
            "I Felt Too Much and Squirted, I'm Sorry...4 AIKA",
            "Honey, I'm Sorry, I Can't Stop",
            // できない / ガマンできない → "can't"
            "My Sister Is a Nudist and I Can't Hold Back, Asami Yuma",
            "I Can't Hold Back Anymore",
            "I Can't Stop Wanting You",
            // 忘れられない → "can't forget"
            "I Can't Forget My Father-in-Law's Kiss. Natsume Ayashun",
            "I Can't Forget That Night",
            // 身動きできない → "unable to move"
            "Unable to Move, Forcibly Ejaculated Inside",
            "I Was Unable to Move or Resist",
            // 断れない → "cannot refuse" — standalone "refuse" no longer matches
            "A Wife Who Cannot Refuse Her Brother-in-Law",
            // ごめんなさい + できない together, but no translation-action continuation
            "I'm Sorry, I Can't Stop",
    })
    void isRefusal_falseForLegitimateTranslations(String text) {
        assertFalse(TranslationWorker.isRefusal(text),
                "False positive for legitimate title: " + text);
    }

    // ── Null / blank → always refusal ────────────────────────────────────────

    @Test
    void isRefusal_trueForNull() {
        assertTrue(TranslationWorker.isRefusal(null));
    }

    @Test
    void isRefusal_trueForBlank() {
        assertTrue(TranslationWorker.isRefusal("   "));
    }

    @Test
    void isRefusal_trueForEmpty() {
        assertTrue(TranslationWorker.isRefusal(""));
    }

    // ── Length gate: long output never flagged ───────────────────────────────

    @Test
    void isRefusal_falseForLongOutputEvenWithKeyword() {
        // Over 80 chars with a refusal keyword embedded — not a refusal
        String longOk = "I cannot stop loving you, my step-sister — a tale of forbidden passion that spans three decades and two continents.";
        assertTrue(longOk.length() > 80);
        assertFalse(TranslationWorker.isRefusal(longOk));
    }
}
