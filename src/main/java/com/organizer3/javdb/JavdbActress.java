package com.organizer3.javdb;

/**
 * An actress entry extracted from a javdb title detail page.
 *
 * @param kanjiName    the actress's stage name in kanji (e.g. "波多野結衣")
 * @param actressSlug  the javdb actress page slug (e.g. "B2g5"), usable for future lookups
 */
public record JavdbActress(String kanjiName, String actressSlug) {}
