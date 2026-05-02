# Titles Dashboard Design

> **Status: IMPLEMENTED**
> The Titles dashboard is implemented. This document remains the canonical design reference; the Actresses dashboard was subsequently designed using the same patterns.

Design spec for the Titles landing page dashboard. The Titles dashboard is the primary landing spot when a user clicks "Titles" in the main nav. Its charter is twofold: **check what's new** and **access titles the user has shown interest in**.

This document is the source of truth for all module design decisions locked during the Titles dashboard design phase. It also serves as a reference for the forthcoming Actresses dashboard, which will be designed using the same process and will inherit many of the patterns established here.

## Layout

```
┌──────────────────────────────────────────────────────┐
│  On Deck (8 cards, hero accent)                      │  hero
├──────────────────────────────────────────────────────┤
│  Just Added (6)                                      │  what's new
│  From Favorite Labels (6)                            │
├──────────────────────────────────────────────────────┤
│  Recently Viewed (6 compact)                         │  resume
├──────────────────────────────────────────────────────┤
│  Spotlight (1 big card)                              │  discovery
│  Forgotten Attic (6)                                 │
│  Forgotten Favorites (6)                             │
│  On This Day (5, hidden if empty)                    │
├──────────────────────────┬───────────────────────────┤
│  Top Labels (15 rows)    │  Library Stats (2x3)      │  footer
└──────────────────────────┴───────────────────────────┘
```

## Cross-cutting rules

### Strict no-overlap dedupe

Modules are processed in display order. Each module's candidate pool excludes any titles already picked by higher-positioned modules. This prevents the same title from appearing twice on a single dashboard load.

**Exempt modules** (history / narrow-query modules where dedupe would cause unwanted emptiness):
- Recently Viewed — history module, always shows the 6 most recently visited regardless of overlap
- On This Day — narrow anniversary query; losing even 1-2 titles to higher modules could empty it

### "Loved label" — derived concept

Several scoring formulas reference "loved labels." This is a calculated concept, not a stored flag.

**Formula:** `score = sum(visitCount) + 3*favoriteCount + 2*bookmarkCount` across all titles per label. A label is "loved" if it appears in the top-N by this score. N TBD (likely 10-20). The Top Labels leaderboard and the "loved label" signal share this single source of truth.

### Weighted random sampling

Modules that use weighted random selection use Efraimidis-Spirakis:
```sql
ORDER BY -LN(1 - random()) / weight LIMIT N
```

### Schema change required

A new `bookmarked_at TIMESTAMP` column is needed on the titles table (to support On Deck's unseen-first ordering and freshness). Drop-and-recreate per project policy.

### Revisit items

These ship with defaults but the user flagged them for revisit after initial visual review:
- **Weighted shuffle for On Deck** — shipping with pure uniform random
- **Stacked vs side-by-side for What's New row** — shipping stacked
- **Aging label format/placement** — shipping with defaults (relative, appended to meta line, Just Added + From Favorite Labels only, always shown, plain dim text)
- **Top Labels row count (15)** — may tune after seeing it rendered

## Modules

### Spotlight (hero discovery)

One large feature card, weighted-random pick from a curated "your taste" pool.

**Candidate pool:** titles where any of:
- Title is favorited or bookmarked
- Actress is favorited/bookmarked/loved
- Label is loved (see derivation above)
- Actress tier ≥ SUPERSTAR

**Scoring:**
```
score = age_weight * age_factor
      + 4.0 if actress is loved
      + 2.0 if label is loved
      + 6.0 if tier ≥ SUPERSTAR (unengaged goddess bonus)
      + 3.0 if favorited
      + 2.0 if bookmarked
```
Age factor dampens dominance of very old or very new titles. Anti-repetition via localStorage last-N title IDs.

**Position:** Discovery zone. Single big card, visually distinct from strip modules.

### Forgotten Attic

6-card strip, weighted-random from the neglected cold pool.

**Candidate pool:**
- Unseen (`visitCount = 0`) OR last visit > 180 days ago
- Age window: added < 60 days ago (recent forgotten) OR added > 365 days ago (old forgotten)
- **Explicitly skips** the 2-12 month middle — that's the "normal" age range not the "forgotten" range

**Scoring:** `sqrt(days_since_added)` dominates; taste signals (loved actress/label) are mild tiebreakers.

**Position:** Discovery zone, below fold.

### Forgotten Favorites

6-card strip, sibling of Forgotten Attic but targeted at the warm pool (stuff you already loved).

**Candidate pool:** `favorite = 1` AND (`lastVisitedAt IS NULL` OR `lastVisitedAt < now - 90d`). Two sub-branches mixed freely:
- **Neglected loves** — visited but last > 90 days ago
- **Aspirational loves** — never visited

**Scoring:**
```
score = staleness_days^0.6
      + 2.0 if actress is loved
      + 1.5 if label is loved
      + 3.0 if tier ≥ SUPERSTAR
```
For aspirational loves, `staleness_days` counts from `addedDate` instead of `lastVisitedAt` (otherwise infinite staleness).

**Empty state:** Show what's available (even 2-3 cards). If thin, relax the 90d threshold as fallback.

**Aging label:** Included on cards ("last seen 8mo ago" / "never visited" variant).

**Position:** Immediately after Forgotten Attic in the discovery zone.

### On Deck (watchlist hero)

8-card accented-header strip from bookmarks.

- Pure uniform shuffle (revisit weighted later)
- Soft unseen-first guarantee (up to 4 of 8 slots reserved for unseen)
- Fresh batch on shuffle button click
- Count badge clickable → jumps to full bookmarks view
- Accented header (visually distinct from other strips)
- Requires new `bookmarked_at` column

**Position:** Above-the-fold hero.

### Just Added + From Favorite Labels ("What's New" row)

Two stacked strips, 6 cards each.

**Just Added**
- Window: 30 days, fallback to most recent N if empty
- Sort: `addedDate DESC`
- Soft unseen-first guarantee (up to 4 of 6 slots)

**From Favorite Labels**
- Window: 90 days (same fallback)
- Sort: `addedDate DESC`
- Same soft unseen-first guarantee
- "Favorite labels" = loved labels (shared derivation)

**Aging label:** Relative format ("4d ago", "2mo ago", "1y ago"), appended to existing meta line, plain dim text, no colors. Scoped to these two modules only. Always shown.

Both participate in strict no-overlap dedupe.

### Recently Viewed

6-card strip using a new compact card variant.

**Compact variant:** Cover + title code + actress name + "visited Xh ago" aging label. No tags/label/date/grade. ~60% the vertical height of standard cards.

**Behavior:**
- Always shows the 6 most recently visited titles
- No time cap
- No action button
- **Dedupe-exempt** (history module)

Replaces both the current "Last Visited" and "Most Visited" modules. Most Visited dropped entirely (visit count alone is a weak signal once you've designed the rest of the dashboard around intent).

**Position:** Below "From Favorite Labels", above the discovery zone.

### On This Day

5-card strip, delight / time-machine module.

**Signals:** Release anniversary (primary) + added anniversary (mixed in). Both match on month-day equality with today.

```sql
WHERE strftime('%m-%d', releaseDate) = strftime('%m-%d', 'now')
   OR strftime('%m-%d', addedDate) = strftime('%m-%d', 'now')
```

**Sort:** Year ascending (oldest anniversaries first — more nostalgic).

**Card badge:** "N years ago today" + which signal (release/added).

**Empty day:** Hide the module entirely. No placeholder.

**Dedupe-exempt** (narrow query).

**Position:** After Forgotten Favorites in the discovery zone.

### Top Labels leaderboard

Compact vertical list module (not cards). 15 rows.

**Ranking formula:** `score = sum(visitCount) + 3*favoriteCount + 2*bookmarkCount`. Library size intentionally excluded — we want engagement, not inventory.

**Weighted randomness** injected so labels just outside strict top-15 can occasionally surface. Efraimidis-Spirakis on score.

**Row layout:** Rank + label name + bar/meter visualizing relative score (no raw number displayed).

**Click behavior:** Row click jumps into the existing label filter in titles browse view.

**Position:** Side-by-side with Library Stats as the footer row.

### Library Stats strip

Informational / non-clickable orientation widget. Footer row, side-by-side with Top Labels.

**Layout:** 2x3 grid of stat tiles. Each tile: big number + small caption.

**Tiles:**
1. Total titles
2. Total labels
3. Unseen count
4. Unseen ratio % (progress bar visualization)
5. Added this month
6. Added this year

**Style:** Tabular number styling. No flavor prose — strictly numeric. Recomputed on every dashboard load (cheap, no cache).

## Design philosophy notes for Actresses dashboard

When designing the Actresses dashboard, these patterns should be considered:

- **Charter first.** Titles dashboard has a clear two-line charter; the Actresses dashboard needs its own.
- **Module roles:** hero / what's new / resume / discovery / footer. Each role answers a different user job.
- **Strict no-overlap dedupe** with history/narrow-query exemptions is a solid default.
- **Derived "loved" concepts** beat stored flags — fewer things to keep in sync.
- **Weighted random via Efraimidis-Spirakis** is the default for "pick N with bias."
- **Soft guarantees** (up to N of M slots for a filter) beat hard filters when pools may be thin.
- **Anti-repetition via localStorage last-N IDs** keeps modules from feeling stale.
- **Compact card variants** are worth considering for history modules that don't need full detail.
- **Footer row** (info widgets side-by-side) is a good home for non-actionable orientation.
- **Revisit markers** — ship with defaults, flag items for post-visual-review iteration rather than overthinking up front.
