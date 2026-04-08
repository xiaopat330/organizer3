# Yuma Asami — Data Completion Recipe

This is a concrete, step-by-step plan for completing the Yuma Asami YAML file.
It replaces the curated 37-title YAML with a full 165-title owned-titles file,
with all fields populated from cross-referenced sources — no cover scanning needed.

---

## Current State (2026-04-07)

### What we have

| Source | Count | Has code | Has title | Has date |
|--------|-------|----------|-----------|----------|
| Local DB | 165 titles | Yes (all) | No | No |
| Wikipedia (Alice Japan) | ~105 titles | No | Yes | Yes |
| Wikipedia (S1) | ~101 titles | No | Yes | Yes |
| Wikipedia (Other) | 4 titles | No | Yes | Yes |
| DMM raw JSON | 785 entries | ID only | 314 visible / 471 独占 | No (list page) |
| Current YAML | 37 titles | No (all null) | Yes | Yes |

### DB-to-DMM match results

| Category | Count | Notes |
|----------|-------|-------|
| DMM match, title visible | 76 | Code + title available, just need date |
| DMM match, title hidden (独占) | 72 | Code known, title needs Wikipedia or product page fetch |
| No DMM match | 17 | Likely reissues, bundles, or niche labels |
| **Total owned** | **165** | |

### DB title breakdown by label

| Label | Count | Studio | Notes |
|-------|-------|--------|-------|
| DV | 73 | Alice Japan | Main series (ビデ倫) |
| SOE | 45 | S1 No.1 Style | Main series |
| ONED | 24 | S1 (older series) | Pre-SOE S1 releases |
| PDV | 4 | Alice Japan (reissue?) | |
| NDV | 4 | Alice Japan (reissue?) | |
| ONSD | 3 | S1 (compilations) | |
| Others | 12 | Various | EBOD, SSPD, KA, PGD, AAJ, MAD, MRJJ, AVGL, DMSM, NDTK, TJCA, TSDV |

### 17 titles with no DMM match

These are likely reissues, bundles, or niche-label releases not indexed under her actress page:

```
AAJ-030, AVGL-109, DMSM-6863, DV-598, KA-2242, MAD-034, NDTK-252,
NDV-0263, NDV-0363, NDV-0385, NDV-0397, PDV-158, PGD-481,
SOE-061, SOE-624, TJCA-10004, TSDV-41045
```

---

## Recipe

### Step 1 — Build the code-to-DMM-title map (76 titles)

For the 76 DB titles where DMM has a visible title:
1. Convert each DB code to DMM content ID format: `DV-563` → `(dv, 563)`
2. Look up in DMM raw JSON, extract `title.original`
3. This gives us code + title for 76 titles with no external fetches

**Output:** 76 titles with `code` + `title.original`

### Step 2 — Match 独占 titles to Wikipedia (72 titles)

For the 72 DB titles where DMM shows 独占:
1. These are mostly SOE (S1) and ONED titles — labels where DMM hides the title
2. Wikipedia has complete S1 and Alice Japan filmographies with dates and titles
3. Match by label + sequence position:
   - DB has `label` + `seq_num` → gives ordering within a label
   - Wikipedia lists titles chronologically within each studio
   - Match by chronological position within the label series
4. Cross-check: if the matched Wikipedia title contains the actress name or a recognizable keyword, it's a strong match

**Key insight:** Wikipedia S1 table has ~101 entries with exact dates. ONED and SOE titles map 1:1 to this table. The seq_num ordering in DB should align with Wikipedia's chronological ordering.

**Output:** 72 titles with `code` + `title.original` + `date`

### Step 3 — Fill dates from Wikipedia for Step 1 titles (76 titles)

Step 1 gave us DMM titles but no dates. Now:
1. For each of the 76 DMM-matched titles, fuzzy-match the `title.original` against Wikipedia filmography
2. Wikipedia Alice Japan list: ~105 entries with year + date
3. Wikipedia S1 table: ~101 entries with exact dates
4. Match by title text similarity (Japanese text)
5. Extract the date from the Wikipedia match

**Fallback:** If no Wikipedia match, the title may be a compilation/reissue not listed on Wikipedia. Set `date: null` and note in metadata.

**Output:** All 148 titles (Step 1 + Step 2) now have `code` + `title.original` + `date`

### Step 4 — Handle the 17 unmatched titles

For the 17 DB titles with no DMM match:
1. Search DMM by product code directly: `https://www.dmm.co.jp/search/?searchstr=<code>`
2. Or fetch the product page: `https://www.dmm.co.jp/digital/videoa/-/detail/=/cid=<normalized_code>/`
3. Try Wikipedia filmography — some may be listed under a different title or as compilation entries

**Expected:** Many of these are reissues (NDV, PDV = Alice Japan reissue labels), bundles, or niche compilations. Some may have no external data — set fields to null.

**Output:** 17 titles with whatever data can be found

### Step 5 — English translations

For all 165 titles:
1. Hand-translate `title.original` to `title.translation`
2. Be accurate, not literal — convey the meaning in natural English

This is pure text work, no fetches needed.

### Step 6 — Grades (optional, low priority)

Grades are subjective and not critical for the data pipeline. Options:
1. Keep grades only for the 37 titles that already have them
2. Add grades for milestone/notable titles based on Wikipedia descriptions (awards, debut, anniversary)
3. Leave remaining titles ungraded (`grade: null`)

### Step 7 — Assemble final YAML

1. Replace the current 37-title curated YAML with a full 165-title YAML
2. Split by studio: `filmography_alice_japan`, `filmography_s1`, `filmography_other`
3. Order within each studio section by date (ascending)
4. Keep the existing profile block (already complete from Wikipedia)
5. Update `meta` section with final counts and coverage notes

---

## What does NOT need cover scanning

- **All 76 DMM-visible titles:** title comes from DMM text
- **All 72 独占 titles:** title comes from Wikipedia text
- **All dates:** come from Wikipedia
- **All codes:** come from local DB (filesystem-parsed, most reliable source)

## When cover scanning WOULD be needed (last resort)

Only if a title meets ALL of these conditions:
1. Not in DMM (or DMM shows 独占) AND
2. Not in Wikipedia AND
3. Code is in DB but we can't determine `title.original` from any text source

Based on current data, this is likely **0 titles** — Wikipedia + DMM together cover the entire owned catalog.

---

## Estimated token cost

| Step | Method | Token cost |
|------|--------|------------|
| Steps 1-3 | Local JSON/text parsing, no fetches | Minimal (in-context) |
| Step 4 | Up to 17 DMM page fetches | ~17 × 2K = ~34K tokens |
| Step 5 | Translation (text generation) | ~20K tokens |
| Steps 6-7 | Assembly | ~15K tokens |
| **Total** | | **~70K tokens** (vs. 165 cover scans × ~5K = ~825K) |

Cover scanning would have cost **12× more tokens** for data that's available as text.
