# Actress Data Procurement — AI Operating Reference

This document is the authoritative procedure for researching, structuring, and verifying actress profile and filmography data for database seeding. Follow it exactly. Do not improvise field formats, skip verification steps, or trust memory for any factual claim.

> **Before starting any research session: set VPN exit node to Japan.** DMM/FANZA and Minnano-AV are geo-restricted and require a Japanese IP. A non-Japanese IP will result in geo-block pages instead of data.

---

## 1. Output Specification

### File Location and Naming

```
reference/actresses/<romanized_family_name>_<romanized_given_name>.yaml
```

### Scraped Data Cache

All raw data fetched from external sources must be saved to disk immediately after retrieval. Do not re-scrape a source if a cache file already exists — read it instead. Only re-scrape if the data is known to be stale (e.g. new titles released since last scrape).

**Cache file naming convention:**

```
reference/actresses/<romanized_name>_<source>_raw.<ext>
```

| Source | Filename | Format |
|--------|----------|--------|
| DMM/FANZA actress page (all titles) | `<name>_dmm_raw.json` | JSON |
| Japanese Wikipedia article | `<name>_wikipedia_ja_raw.txt` | Plain text (article body) |
| Minnano-AV profile page | `<name>_minnano_raw.json` | JSON |

**Examples:**
```
reference/actresses/yuma_asami_dmm_raw.json
reference/actresses/yuma_asami_wikipedia_ja_raw.txt
reference/actresses/yuma_asami_minnano_raw.json
```

**Cache file must include a header with:**
- `actress` — full name (kanji + romanized)
- `source_url` — the URL scraped
- `scraped_date` — ISO date (YYYY-MM-DD)
- `note` — any caveats about completeness or known gaps

**Before scraping any source**, check if the cache file exists and read it. If it exists and is less than 90 days old, use it. If it is stale or missing, scrape and overwrite.

All cache files are gitignored (they live in `reference/` which is already gitignored).

Use the actress's primary romanized name. Example: `reference/actresses/yuma_asami.yaml`

### YAML Formatting Rules

These are hard rules. Violations will break machine parsing or corrupt the UI.

| Rule | Correct | Wrong |
|------|---------|-------|
| Dates | `1987-03-24` | `"1987-03-24"` or `1987` |
| Career span | `active_from: 2005-10-28` | `active: "2005–2013"` |
| Unknown values | `null` | `""` or `~` |
| Integers | `158` | `"158"` or `~158` |
| Strings | `"text here"` | `> text here` or `\| text here` |
| Title structure | `title.original` + `title.translation` | flat `title: "..."` |

**Never use YAML block scalars (`>` or `|`).** They produce trailing newlines that render poorly in the UI.

**If only the year is known for a date**, use January 1 as placeholder: `2005-01-01`. Document this in `meta.confidence`.

---

## 2. Full File Schema

```yaml
profile:
  family_name:        # romanized family name
  given_name:         # romanized given name
  stage_name:         # Japanese kanji/kana stage name
  reading:            # hiragana reading of stage name
  alternate_names:    # list of {name, note} — other romanizations or prior names
  date_of_birth:      # ISO date (unquoted)
  birthplace:         # "City, Prefecture, Japan"
  blood_type:         # A / B / AB / O
  height_cm:          # integer
  measurements:
    bust:             # integer cm
    waist:            # integer cm
    hip:              # integer cm
  cup:                # letter (A–K)
  active_from:        # ISO date of first release
  active_to:          # ISO date of last release or formal retirement announcement
  biography:          # single quoted string, 3–6 sentences
  primary_studios:    # list of {name, company, from, to, role}
  awards:             # list of {event, year, category}
  legacy:             # single quoted string, 1–3 sentences

# Filmography: use one key if single studio, or split keys if multi-studio.
# Multi-studio example:
filmography_alice_japan:
  - code: null          # product code — null if not confirmed from DB or cover spine
    title:
      original: null    # Japanese title — null if unknown; fill from cover scan or Wikipedia
      translation: ""   # English translation (your own — be accurate, not literal)
    label:              # label name as it appears on the cover (e.g. "Alice Japan", "S1 No.1 Style")
    date:               # ISO release date
    grade:              # see Grade Scale below
    notes: null         # one quoted sentence or null

filmography_s1:
  - ...

filmography_other:
  - ...

meta:
  total_known_releases:   # integer estimate of full career catalog
  titles_listed_here:     # count of entries in this file
  coverage_note: ""       # what's included / excluded
  confidence: ""          # data quality — flag any fields sourced from memory
  data_source: ""         # URL(s) used
```

### Grade Scale

Grades reflect **general fan/collector consensus**, not personal opinion. Do not assign grades from memory alone — only when consensus is well-known or the title's reception is documented.

| Grade | Meaning |
|-------|---------|
| A+    | All-time fan favorite, milestone release, or award-winning |
| A     | Excellent, highly regarded |
| A-    | Very good, above average |
| B+    | Solid, well-received |
| B     | Average for a top actress |
| B-    | Below expectations but not bad |
| C+/C/C- | Formulaic, unremarkable, or clearly rushed |
| D     | Poorly received |
| F     | Notably bad — rare for top-label productions |

---

## 3. Data Procurement Procedure

Execute these phases in order. Do not skip phases or reorder them. Each phase feeds into the next.

There are two independent data categories that require separate fetch strategies:
- **Profile data** — DOB, measurements, studio history. Wikipedia is authoritative; DMM and Minnano-AV cross-check.
- **Product data** — codes, titles, release dates. DMM is authoritative; Wikipedia supplements titles/dates but never has codes.

---

### Phase 1 — Establish What We Own (Local DB)

Before fetching anything external, query the local DB to get the full set of titles attributed to this actress.

**Step 1 — find the actress ID:**

```sql
SELECT id, canonical_name, stage_name, tier, favorite
FROM actresses
WHERE canonical_name LIKE '%<name>%' OR stage_name LIKE '%<kanji>%'
```

**Step 2 — get all owned titles for that actress:**

```sql
SELECT code, base_code, label, seq_num
FROM titles
WHERE actress_id = <id>
ORDER BY label, seq_num
```

The `titles` table schema (confirmed):

| Column | Type | Notes |
|--------|------|-------|
| `id` | INTEGER | Internal PK |
| `code` | TEXT | Display code, e.g. `DV-563` |
| `base_code` | TEXT | Zero-padded canonical, e.g. `DV-00563` |
| `label` | TEXT | Label prefix, e.g. `DV`, `ONED`, `SOE` |
| `seq_num` | INTEGER | Numeric part of the code — use for ordering within a label |
| `actress_id` | INTEGER | FK to actresses |

There is no date column on `titles`. The `titles` table has no release date — only `first_seen_at` on `actresses`, which is the actress's first scan date, not a title date. **Use DB for codes only. Use Wikipedia or DMM for dates.**

This is the **primary title set**. Every code returned here is reliable — it was parsed from the filesystem. All DB titles must be represented in the YAML. Note the full code list by label before proceeding.

---

### Phase 2 — Establish Identity and Profile (Japanese Wikipedia)

**Before writing any profile field**, fetch the actress's Japanese Wikipedia article to confirm identity and source all profile data.

```
Tool: mcp__fetch__fetch
URL:  https://ja.wikipedia.org/wiki/<URL-encoded stage name>
max_length: 15000
```

URL-encode the stage name. Example: 麻美ゆま → `%E9%BA%BB%E7%BE%8E%E3%82%86%E3%81%BE`

If the article is truncated, call again with `start_index` incremented by `max_length` until you have the full article.

**Identity check — do this first, before extracting any data:**
- Confirm the article title matches the actress's stage name exactly.
- Read the biography section. Confirm it describes the career you expect (debut year, studio, key titles).
- If anything doesn't match, stop. You have the wrong article or wrong encoding. Re-verify before proceeding.

**Conflation is a silent error.** It produces plausible values for the wrong person. See Section 4.

**Extract from the Wikipedia article:**
- All infobox fields: DOB, birthplace, blood type, height, measurements, cup, debut date
- Studio affiliations and contract periods
- Full title list with Japanese titles and release dates
- Awards

**Wikipedia does NOT provide:**
- Product codes (品番) — never listed on Japanese Wikipedia
- English title translations

**Write the profile block:**

| Field | Source |
|-------|--------|
| `family_name`, `given_name` | Romanize from stage name (Hepburn) |
| `stage_name` | Article title (kanji/kana) |
| `reading` | Wikipedia infobox ふりがな |
| `date_of_birth` | Wikipedia infobox 生年月日 |
| `birthplace` | Wikipedia infobox 出身地 |
| `blood_type` | Wikipedia infobox 血液型 |
| `height_cm` | Wikipedia infobox 身長 |
| `measurements` + `cup` | Wikipedia infobox スリーサイズ / カップ |
| `active_from` | Date of first title from Wikipedia filmography |
| `active_to` | Date of last title or stated retirement date |
| `primary_studios` | Wikipedia career section, studio affiliations |
| `awards` | Wikipedia awards section |

For any field not found in Wikipedia, write `null` and note it in `meta.confidence`.

---

### Phase 3 — Cross-Check Profile (Minnano-AV)

Navigate to the actress's Minnano-AV profile page via Puppeteer. Compare measurements, DOB, and studio affiliations against the Wikipedia values.

```
Tool: mcp__puppeteer__puppeteer_navigate
URL:  https://www.minnano-av.com/actress<id>.html
```

Search Minnano-AV for the actress by name if you do not have her ID.

**Conflict resolution — profile fields:**

| Conflict | Winner | Action |
|----------|--------|--------|
| Wikipedia ≠ Minnano-AV for any physical stat | Wikipedia | Keep Wikipedia value; note discrepancy in `meta.confidence` |
| Minnano-AV has a field Wikipedia lacks | Minnano-AV | Use it; note source in `meta.confidence` |

Do not overwrite Wikipedia values with Minnano-AV values. Wikipedia is the authoritative profile source.

---

### Phase 4 — Product Data (DMM/FANZA)

Navigate to the actress's DMM/FANZA page via Puppeteer. This is the authoritative source for product codes, Japanese titles, and official release dates.

```
Tool: mcp__puppeteer__puppeteer_navigate
URL:  https://www.dmm.co.jp/digital/videoa/-/list/=/article=actress/id=<id>/
```

Search DMM for the actress by name if you do not have her ID. The actress page lists her full catalog with codes, titles, and release dates in a structured format.

**For each title in the DMM filmography:**
1. Check if the code is in your Phase 1 DB list — if yes, this is a confirmed owned title.
2. Record the code, `title.original`, and official release date.
3. Note any titles on DMM that are NOT in the DB — these are titles the actress has that we don't own.

**Conflict resolution — product fields:**

| Conflict | Winner | Action |
|----------|--------|--------|
| DMM date ≠ Wikipedia date | DMM | Publisher's own record for release dates |
| DMM title ≠ Wikipedia title | DMM | Publisher is authoritative for product names |
| DMM code ≠ DB code | Investigate | Likely a code format difference (e.g. padding). Flag in `meta.confidence`. |
| DB `first_seen_at` ≠ DMM release date | DMM | DB date is scan date, not release date |

---

### Phase 5 — Physical Verification (Cover Images) — LAST RESORT ONLY

**Do not scan cover images unless there is a specific unresolved gap that cannot be filled from DMM, Wikipedia, or Minnano-AV.** Cover scanning consumes significant tokens and should only be invoked when cross-referencing has failed for a specific title.

**Only scan a cover when ALL of the following are true for a specific title:**
1. `title.original` is still `null` after checking DMM and Wikipedia.
2. OR the code from DB conflicts with DMM and the discrepancy cannot be explained by padding differences.

**Do NOT scan covers to:**
- Confirm data that already matches across DMM and Wikipedia.
- Fill fields that are already populated from cross-referenced sources.
- Do batch verification of titles where DMM data is complete.

When cover scanning is warranted for a specific title:

```
Tool: Read
file_path: /Users/pyoung/workspace/organizer3/data/covers/<LABEL>/<LABEL>-<NNNNN>.jpg
```

The number in the filename is zero-padded to 5 digits. Example: `DV-1234` → `DV-01234.jpg`

From each cover extract only what is missing:
- `title.original` — the largest Japanese text block on the front (front cover is authoritative for title)
- Code on spine — only if resolving a specific DB/DMM conflict

**Conflict resolution — cover vs other sources:**

| Conflict | Winner | Action |
|----------|--------|--------|
| Cover spine code ≠ DB code | Cover spine | Physical media is ground truth |
| Cover spine code ≠ DMM code | Cover spine | Physical media is ground truth |
| Cover title ≠ DMM title | Cover title | Front cover is authoritative for `title.original` |

Do not OCR the back cover or spine for title — the front cover title is authoritative.

---

### Phase 6 — Gap Fill (NamuWiki / Wikipedia filmography)

After Phases 1–5, fill any remaining gaps:

- **Titles not on DMM and not in DB** — check Wikipedia filmography list. Match by date and position in sequence. Use Wikipedia title spelling verbatim.
- **Biography narrative** — NamuWiki (Korean) for richer biographical detail not in Wikipedia.
- **English translations** — hand-translate from `title.original`. Be accurate, not literal.

For any title where code, `title.original`, or date remains unknown after all phases: set the field to `null`. Never guess.

---

### Phase 7 — Filmography Assembly

#### What titles to include

1. All titles from Phase 1 (DB) — we own these; they must be in the YAML.
2. All titles found in Phase 4 (DMM) that are not in the DB — actress's broader catalog.
3. Any remaining titles from Phase 6 (Wikipedia) not covered above.

#### Multi-studio careers

If the actress held contracts at more than one studio, split filmography into separate keys:
- `filmography_<studio_slug>:` for each studio
- `filmography_other:` for guest appearances

Do not merge multi-studio work into a single `filmography:` key — it destroys the ability to filter by label.

**To detect dual-studio careers:** DMM and Wikipedia will both list titles under different label banners with overlapping date ranges. This is unusual — double-check before splitting.

#### Decision trees

**`code`:**
```
In local DB?  → Use it (parsed from filesystem, reliable)
  NO → On cover spine?  → Use it (physical ground truth)
    NO → On DMM?  → Use it (publisher record)
      NO → null. Never guess.
```

**`title.original`:**
```
On DMM?  → Use DMM title verbatim.
  NO → On Wikipedia filmography?  → Use Wikipedia spelling verbatim.
    NO → Local cover image available AND unresolved gap?  → Read cover. Use front-cover title text. (last resort — high token cost)
      NO → null.
```

**`date`:**
```
On DMM?  → Use DMM release date (preferred).
  NO → On Wikipedia filmography?  → Use Wikipedia date.
    NO → DB first_seen_at available?  → Use as last resort, note in meta.confidence.
      NO → null.
```

---

### Phase 8 — Mandatory Verification Checklist

**Do not write the final file without completing this checklist.**

#### Profile fields — verify against Wikipedia

| Field | Verification method |
|-------|-------------------|
| `stage_name` | Must exactly match the Wikipedia article title |
| `birthplace` | Compare against Wikipedia 出身地 — prefecture AND city |
| `blood_type` | Compare against Wikipedia 血液型 |
| `height_cm` | Compare against Wikipedia 身長 (strip "cm") |
| `measurements.bust/waist/hip` | Compare against Wikipedia スリーサイズ |
| `cup` | Compare against Wikipedia カップ |
| `active_from` | Should match first title date in Wikipedia filmography |
| `active_to` | Should match last title date or stated retirement |
| `primary_studios` | Check for dual-contract careers — Wikipedia will list both |

#### Identity — final check

1. Wikipedia article title matches the stage name you searched for.
2. Biography describes the career you expect (debut year, studio, key titles).
3. No alias or alternate name in the file belongs to a different person.

#### Known High-Risk Conflation Pairs

| Actress A | Actress B | Shared traits that cause conflation |
|-----------|-----------|--------------------------------------|
| 麻美ゆま (Yuma Asami) | 柚木ティナ (Tina Yuzuki / Rio) | Both top-tier, H-cup, Alice Japan, mid-2000s debut |

Add to this table whenever a conflation is caught.

---

## 4. Known Failure Modes

### Failure: Memory-generated profile data

**What it looks like:** Profile fields are populated with values that are close but not quite right — plausible enough that no single value is obviously wrong.

**Detection:** Compare against Wikipedia. If any field differs, all fields are suspect.

**Real example (Yuma Asami):** Every physical stat was wrong from memory:

| Field | Memory | Wikipedia |
|-------|--------|-----------|
| `birthplace` | Fukuoka Prefecture | Takasaki, Gunma Prefecture |
| `blood_type` | O | AB |
| `height_cm` | 162 | 158 |
| `measurements.bust` | 90 | 96 |
| `measurements.waist` | 58 | 58 (coincidence) |
| `measurements.hip` | 83 | 88 |
| `cup` | G | H |

None of these errors were detectable without sourcing. All were wrong.

**Fix:** Never write profile data from memory. Always fetch Wikipedia first, then populate fields from the article.

---

### Failure: Hallucinated product codes

**What it looks like:** Product codes that follow the right format (e.g. `SOE-001`, `DV-123`) but point to titles that don't exist or belong to a different actress or series.

**Detection:** There is no reliable way to detect hallucinated codes without a catalog database. If a code was not parsed from the local filesystem or read from a cover spine, it is unverified and must be `null`.

**Real example:** The initial Yuma Asami draft included `SOE-001` through `SOE-454` as her S1 series. Her actual S1 series is `ONED-` (ギリギリモザイク). `SOE-` is a different S1 sub-series. All hallucinated codes were removed and set to `null`.

**Fix:** Set all codes to `null` unless sourced from DB or cover scan. Never guess.

---

### Failure: Actress identity conflation

**What it looks like:** An alias or alternate name entry that names a different actress. Filmography entries for titles that belong to the wrong person. Biographical details that don't align with the target actress's known career.

**Detection:** Read the Wikipedia biography section. If it describes a different person, you have the wrong article or have mixed notes from two people.

**Real example:** The initial Yuma Asami draft conflated her with Tina Yuzuki (柚木ティナ). The draft included:
- Three `DV-8xx` titles that are Tina Yuzuki's, not Yuma Asami's
- Alice Japan early-career entries belonging to Tina Yuzuki
- "Tina Yuzuki" listed as an alternate name for Yuma Asami

These are two distinct people with separate Wikipedia articles, separate careers, and no relationship.

**Fix:** Confirm identity from the Wikipedia article header and biography before writing any data. If researching multiple actresses in one session, keep notes strictly separate per actress.

---

### Failure: Block scalar strings in YAML

**What it looks like:**
```yaml
notes: >
  This is a note that will have a trailing newline.
```

**Detection:** Any `>` or `|` after a field colon.

**Fix:** Replace with a plain quoted string:
```yaml
notes: "This is a note."
```

---

### Failure: Flat title fields

**What it looks like:** `title: "Some Japanese Title"`

**Fix:** Always use:
```yaml
title:
  original: "元の日本語タイトル"
  translation: "English Translation"
```

---

## 5. Label / Code Reference

Known label code → studio mappings discovered from cover scans and DB analysis.

| Code prefix | Studio | Series name | Notes |
|-------------|--------|-------------|-------|
| `DV-` | Alice Japan | ビデ倫 | Main Alice Japan series. 73 Yuma Asami titles in DB (DV-563 → DV-1514) |
| `ONED-` | S1 No.1 Style | ギリギリモザイク | S1 solo series. 24 Yuma Asami titles in DB (ONED-292 → ONED-972). All 独占 on FANZA |
| `SOE-` | S1 No.1 Style | (to be confirmed via cover) | Second S1 sub-series. 45 Yuma Asami titles in DB (SOE-022 → SOE-944). All 独占 on FANZA. Likely multi-actress or collaborative titles — read covers to confirm |
| `ONSD-` | S1 No.1 Style | (compilations) | S1 compilation/omnibus releases |
| `PDV-` | Alice Japan | (sub-label) | Alice Japan sub-label; 4 Yuma Asami titles in DB |
| `NDV-` | (unknown) | (unknown) | 4 Yuma Asami titles in DB. Confirm studio from cover |
| `KA-` | Alice Japan | (unknown) | 1 Yuma Asami title in DB |
| `MRJJ-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |
| `EBOD-` | E-BODY | (unknown) | Guest appearances |
| `SSPD-` | Attackers | (unknown) | Guest appearances |
| `AAJ-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |
| `AVGL-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |
| `DMSM-` | (unknown) | (unknown) | 1 Yuma Asami title in DB — large seq_num (6863), likely a compilation |
| `MAD-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |
| `PGD-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |
| `TJCA-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |
| `TSDV-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |
| `NDTK-` | (unknown) | (unknown) | 1 Yuma Asami title in DB |

### DMM ID → DB Code Mapping

FANZA product IDs can be decoded to standard label codes using these patterns:

| DMM ID format | DB code format | Example |
|---------------|----------------|---------|
| `dv00563` | `DV-563` | Strip `dv`, remove leading zeros |
| `dv00563ai` | `DV-563` (AI remaster of same title) | Strip `dv`, remove leading zeros, ignore `ai` suffix |
| `oned00292` | `ONED-292` | Strip `oned`, remove leading zeros |
| `soe00022` | `SOE-22` | Strip `soe`, remove leading zeros |

**Important:** Many older DV titles (57 of 73 in our library) do not appear in a FANZA actress page scrape — they have been delisted or are unlisted but still purchasable via direct URL. The codes in the DB are still valid; they just won't appear in a bulk scrape of her actress page.

Extend this table as new label codes are encountered during cover scanning.

---

## 6. Data Source Priority

| Priority | Source | Gives | Does not give |
|----------|--------|-------|---------------|
| 1 | Local DB | Product codes, `first_seen_at` | Japanese titles, profile data |
| 2 | Local cover images | `title.original`, label confirmation, code verification | Release dates, profile data |
| 3 | Japanese Wikipedia | Profile data, Japanese titles, release dates, awards | Product codes |
| 4 | NamuWiki (Korean) | Biographical narrative detail | Structured data |
| 5 | DMM / FANZA (via Puppeteer) | Product codes, Japanese titles, release dates, cover images, cast lists | — |
| 5 | Minnano-AV (via Puppeteer) | Structured actress profiles, filmographies, measurements | Product codes |
| 5 | R18.com (via Puppeteer) | English-facing FANZA catalog — same data, English metadata | — |
| ✗ | JavLibrary / JavDB | Everything — Cloudflare-protected, not bypassable with standard Puppeteer | — |
| ✗ | Memory / prior knowledge | Nothing reliable | Do not use for any factual field |

### Tool Selection for Data Sources

**`mcp__fetch__fetch`** — use for sites that return 403 to simple HTTP clients but do not actively fingerprint browsers:
- `ja.wikipedia.org`, `en.wikipedia.org`, `namu.wiki`

**`mcp__puppeteer__*`** — use for sites that block automated HTTP clients but accept real browser sessions:
- `dmm.co.jp` / `fanza.com` — structured product pages with codes, titles, release dates, cover images
- `minnano-av.com` — actress profile pages with measurements and filmography
- `r18.com` — English-facing FANZA; useful when English metadata is needed

**Not unblockable with standard Puppeteer** — these use Cloudflare JS challenges with browser fingerprinting; the available Puppeteer MCP server does not include stealth plugins:
- `javlibrary.com`, `javdb.com`

### Geo-Restrictions and VPN Exit Node

Site availability depends on the VPN exit country:

| Exit country | DMM/FANZA | R18.com | Minnano-AV |
|--------------|-----------|---------|------------|
| Japan | ✓ accessible | may block | ✓ accessible |
| USA / other | geo-blocked | ✓ accessible | likely blocked |

**With a non-Japanese IP (e.g. USA VPN), use R18.com as the primary Puppeteer target.** It is FANZA's international storefront — same catalog, same product codes, English metadata. Switch to DMM/FANZA only when on a Japanese exit node.

### DMM/FANZA and R18 via Puppeteer

A single actress page on R18 or FANZA lists her full catalog with codes, titles, and release dates in a structured format — directly filling the gaps that the data guide currently marks as "pending cover scan." Product codes on these sites are reliable ground truth.

---

## 7. Status

### Completed

| File | Actress | Studio(s) | Status |
|------|---------|-----------|--------|
| `yuma_asami.yaml` | 麻美ゆま (Yuma Asami) | Alice Japan + S1 No.1 Style | Profile complete from Wikipedia. Filmography is a curated 37-title selection. Codes all null — pending Wikipedia date-match + cover scan pass |

### Raw Cache Files (Yuma Asami)

| File | Source | Scraped | Contents |
|------|--------|---------|----------|
| `yuma_asami_wikipedia_ja_raw.txt` | ja.wikipedia.org | 2026-04-07 | Full article — profile, complete filmography with titles and dates |
| `yuma_asami_dmm_raw.json` | video.dmm.co.jp | 2026-04-07 | 785 entries; 314 with visible titles, 471 独占. Sorted newest-first. |

### DB Inventory (Yuma Asami — actress_id = 99)

Confirmed owned titles by label, as of last sync:

| Label | Count | Seq range | Notes |
|-------|-------|-----------|-------|
| DV | 73 | 563 – 1514 | Alice Japan ビデ倫 series. 16 appear in FANZA raw scrape (AI remasters); 57 are not in scrape (delisted) |
| SOE | 45 | 22 – 944 | S1 sub-series. All 独占 on FANZA. Series identity TBC via cover read |
| ONED | 24 | 292 – 972 | S1 ギリギリモザイク. All 独占 on FANZA |
| ONSD | 3 | 59, 702, 745 | S1 compilations |
| Other | ~15 | — | PDV, NDV, KA, MRJJ, EBOD, SSPD, MAD, PGD, TJCA, TSDV, AAJ, AVGL, DMSM, NDTK |
| **Total** | **~160** | | |

### To Do

_(Add candidates here as they come up)_

---

## 9. Live Scraping Playbook

This section records hard-won operational knowledge from live scraping sessions. **Read this before opening Puppeteer.** Each entry either saved or would have saved significant time if known in advance.

---

### 9.1 — DMM/FANZA via Puppeteer

Tested against `video.dmm.co.jp` during the Yuma Asami scrape (2026-04-07, Japan VPN).

#### Finding the actress page

Do not use `actress.dmm.co.jp` — it redirects to a 404. The live actress catalog is at:

```
https://video.dmm.co.jp/av/list/?actress=<id>
```

To find the actress ID, search by name from the main site. The actress will appear in sidebar results with a link in the form `?actress=15365`. Extract the numeric ID from that URL.

There is no API. All ID resolution must happen through the site UI or by searching and reading the DOM.

#### Age gate

FANZA shows an age verification page on first visit. The "はい" (yes) button is an `<a>` tag, not a `<button>`. It cannot be found by text. Find it by:

```javascript
document.querySelector('a[href*="declared=yes"]')
```

Click it and wait before proceeding.

#### Navigation always times out — this is normal

FANZA uses React SSR. The initial HTML arrives quickly but the page hydrates and renders product cards asynchronously. Every `puppeteer_navigate` call to a FANZA list page will time out at 30 seconds. **This is not an error.** The DOM continues loading after the timeout.

After a navigation call (even if it times out), wait for the cards to appear using a polling evaluate:

```javascript
new Promise(resolve => {
  let attempts = 0;
  const check = () => {
    const count = document.querySelectorAll('[data-e2eid="content-card"]').length;
    if (count > 0 || attempts++ > 30) resolve(count + ' | ' + location.href);
    else setTimeout(check, 500);
  };
  check();
})
```

This polls every 500ms for up to 15 seconds. Cards usually appear within 10 seconds of the navigation timeout resolving.

#### DOM structure for product cards

FANZA uses Tailwind/React with `data-e2eid` attributes as semantic hooks — these are more reliable than CSS class selectors (which change with builds). The stable selectors:

```
[data-e2eid="content-card"]     — one per product
[data-e2eid="title"]            — anchor element inside each card, contains title text and href
```

Product ID is in the `href` query parameter:
```javascript
a.getAttribute('href').match(/id=([^&]+)/)?.[1]
// e.g. "53dv01322", "soe00649", "oned972", "dv00834ai"
```

Always use `a.getAttribute('href')`, not `a.href`. The links are relative paths; `a.href` on a relative URL inside a Puppeteer evaluate context can return unexpected values.

#### Extracting titles and IDs per page

Wrap all evaluate code in an IIFE to avoid "identifier already declared" errors across multiple evaluate calls in the same session:

```javascript
(() => {
  return Array.from(
    document.querySelectorAll('[data-e2eid="content-card"] [data-e2eid="title"]')
  ).map(a => {
    const id = (a.getAttribute('href') || '').match(/id=([^&]+)/)?.[1] || '';
    const span = a.querySelector('span > span:last-child');
    return { id, title: (span ? span.textContent : a.textContent).trim() };
  }).filter(x => x.id);
})()
```

#### Pagination

FANZA paginates at ~100 titles per page. Append `?page=N` to the actress URL to navigate pages. Scrape all pages before saving. Total page count is shown in the pagination UI — read it from the DOM before iterating.

#### The "独占" problem

Approximately 50% of titles on FANZA list pages show the title "独占" (exclusive/hidden) instead of the real Japanese title. This happens for licensed exclusives where FANZA intentionally suppresses the title on list views. The product code (`data-content-id` or the `id=` URL param) is still available.

To resolve "独占" titles, you must fetch each individual product page:

```
https://video.dmm.co.jp/av/detail/?cid=<id>
```

This is a separate pass that can take significant time for large catalogs (~400 fetches for Yuma Asami). Plan it as a distinct step, not inline with the main filmography scrape.

#### Sort order

Only `sort=date` (newest first) works. `sort=date_asc` returns a 404. **Always verify URL parameters before assuming they are valid.** The default sort is newest-first; accept it and document it in the cache file header.

---

### 9.2 — Japanese Wikipedia via mcp__fetch__fetch

Tested against `ja.wikipedia.org` across multiple actresses. No VPN required.

#### Chunking strategy

The fetch tool returns max 15,000 characters per call. A full Wikipedia article for a major actress runs 90,000–100,000 characters. Plan for 6–8 chunks.

```python
# Pattern for iterating chunks:
for start_index in [0, 15000, 30000, 45000, 60000, 75000, 90000]:
    fetch(url, start_index=start_index, max_length=15000)
    # stop when response is clearly tail content (navigation templates)
```

Fetch all chunks before writing anything to disk. The tool will report "Content truncated. Call with start_index of N" when more content remains.

#### What's in each region of the article

| Character range (approx.) | Content |
|--------------------------|---------|
| 0 – 12,000 | Infobox (profile data), biography narrative |
| 12,000 – 35,000 | Filmography tables (per studio, chronological) |
| 35,000 – 50,000 | TV appearances, stage work, lectures, discography |
| 50,000 – 75,000 | Live performances, awards, footnotes begin |
| 75,000+ | References section, navigation templates (low value) |

**The infobox and filmography tables are the highest-value content.** Stop chunking once you are clearly into reference citations (lines starting with `^` and Wayback Machine URLs).

#### URL encoding stage names

Japanese stage names must be URL-encoded. The fastest way is to let the tool handle it — pass the raw kanji and mcp__fetch__fetch will encode it correctly. Alternatively, use the Wikipedia URL directly if you have it.

#### Infobox field names to look for

| Japanese field name | YAML field |
|--------------------|------------|
| 生年月日 | `date_of_birth` |
| 出身地 | `birthplace` |
| 血液型 | `blood_type` |
| 身長 | `height_cm` |
| スリーサイズ | `measurements.bust/waist/hip` |
| ブラサイズ / カップ | `cup` |
| 出演期間 | `active_from` / `active_to` |
| 専属契約 | `primary_studios` |

#### Wikipedia does NOT list product codes

This is a hard fact — Japanese Wikipedia never lists product codes (品番). Filmography tables on Wikipedia give title and date only. Do not use Wikipedia as a code source.

#### When there is no Wikipedia article

Some actresses have no Japanese Wikipedia page. In that case:
1. Skip Phase 2 entirely.
2. Use Minnano-AV (Phase 3) as the primary profile source.
3. Document in `meta.confidence` that Wikipedia was unavailable.

---

### 9.3 — Raw Cache Strategy: What to Save and Why

The goal of caching is: **if you can avoid re-scraping, you should.** Sources change (URLs move, titles get delisted, measurements get updated), so a dated snapshot is more valuable than a re-fetch that may disagree.

#### What makes a good cache file

1. **Complete.** All pages scraped — not just the first. For DMM, all pagination. For Wikipedia, all chunks.
2. **Dated.** The `scraped_date` field tells you whether the cache is fresh enough to trust.
3. **Annotated.** The `note` field records any known gaps (e.g. "~400 titles show '独占' — real titles require individual page fetches").
4. **Raw.** Save what the source gave you, not a cleaned-up interpretation. Cleaning happens downstream during YAML assembly. The cache is the evidence.

#### What raw data to collect per source

| Source | What to save | Why |
|--------|-------------|-----|
| DMM/FANZA | All `{id, title}` pairs per page, actress ID, total count | Title IDs are stable; needed for individual-page fetches of "独占" entries |
| Wikipedia | Full article text, all chunks | Profile data + complete filmography table; no rate-limit risk |
| Minnano-AV | Profile block + filmography table as structured JSON | Cross-check for measurements and studio affiliations |

#### What not to save in cache

- Computed fields (romanizations, translations, grades) — these belong in the YAML, not the cache
- HTML markup — strip to text/JSON before saving
- Pagination metadata — record `total_titles` and `pages_scraped` in the header, not the raw pagination HTML

---

### 9.3b — Local DB as Primary Code Source

**Do Phase 1 before any external scraping.** The DB query takes seconds and often gives you every code you need — eliminating the need to resolve 独占 entries from FANZA at all.

#### What the DB gives you that FANZA cannot

FANZA marks up to 60% of an actress's catalog as 独占 (hidden title). The DB bypasses this entirely — codes were parsed from actual filenames on the filesystem and are ground truth.

**For Yuma Asami**: FANZA showed 471 独占 entries including all 24 ONED and all 45 SOE titles. The DB had all 69 codes already. Zero FANZA individual page fetches were needed for code retrieval.

#### Decoding DB codes from FANZA raw data

When you have a FANZA raw cache and a DB code list, you can cross-reference them to get titles for codes:

```python
# DB code: DV-563
# FANZA ID pattern: dv00563 or dv00563ai (AI remaster)
dmm_id = f"dv{seq_num:05d}"
title = dmm_lookup.get(dmm_id) or dmm_lookup.get(dmm_id + 'ai')
```

For ONED:
```python
# DB code: ONED-292
# FANZA ID pattern: oned00292
dmm_id = f"oned{seq_num:05d}"
```

Note: ONED and SOE entries are universally 独占 on FANZA — the lookup will return `'独占'` not a title. Use Wikipedia date-matching instead (see below).

#### Matching codes to Wikipedia titles by sequence order

When FANZA shows 独占 and you have no other title source, Wikipedia filmography tables list titles in strict chronological order. DB `seq_num` values within a label are also in release order. You can therefore:

1. Extract all titles for a label from the Wikipedia cache in chronological order
2. Sort the DB codes for that label by `seq_num`
3. Pair them positionally — first Wikipedia entry = lowest seq_num, etc.

This works reliably for single-actress series (ONED, DV solo). It breaks down for multi-actress titles where Wikipedia may list them differently or skip them entirely.

#### What the DB does NOT give you

- Release dates (no date column on `titles`)
- Japanese title text
- Studio name (inferred from label prefix using Section 5 table)
- Whether the title is a solo, compilation, or guest appearance

---

### 9.4 — Cross-Source Accuracy Checking

The reason to collect from multiple sources is that **each source has blind spots and errors**. Catching disagreements is the point.

| What to compare | Sources to compare | What disagreement means |
|----------------|-------------------|------------------------|
| Physical stats (height, measurements, cup) | Wikipedia vs Minnano-AV | Common — measurements change over career; Wikipedia is usually the earlier value, Minnano-AV the more recent |
| Release dates | DMM vs Wikipedia | DMM is authoritative; Wikipedia dates can lag or be approximate |
| Title names | DMM vs Wikipedia vs cover | Cover is ground truth; DMM and Wikipedia sometimes use shortened forms |
| Studio affiliations | Wikipedia career section vs DMM catalog | If DMM shows titles outside Wikipedia's stated studio periods, investigate |
| Total title count | DMM total vs Wikipedia filmography | DMM will be higher (includes unlisted/exclusive content Wikipedia omits) |

**Any disagreement should be noted in `meta.confidence`.** It is not a blocker — pick the winner per the conflict resolution rules in Section 3 and document why.

---

## 8. Open Issues

### Yuma Asami — specific

- **SOE series identity unknown**: We own 45 SOE titles (SOE-022 → SOE-944) but don't know what the SOE sub-series is called. Read 2–3 covers to confirm. Once identified, all 45 can be titled from the label name alone.
- **ONED/SOE titles missing**: All 69 ONED+SOE titles have confirmed codes from the DB but no `title.original`. Options: (a) Wikipedia date-matching by seq_num order, (b) individual FANZA product page fetches per `video.dmm.co.jp/av/detail/?cid=oned00292` etc. (Japan VPN required), (c) cover scan reads. Option (b) is 69 fetches — manageable in one session.
- **57 DV titles not in FANZA scrape**: Present in DB (DV-563 → DV-1514 with gaps), absent from the actress page scrape. Likely delisted. Titles and dates can be matched from Wikipedia filmography by seq_num order. Cover reads are the fallback for titles that don't appear in Wikipedia.
- **Minnano-AV not yet scraped**: `yuma_asami_minnano_raw.json` does not exist. Low priority — profile is already Wikipedia-verified. Useful for measurements cross-check only.
- **YAML filmography is a curated 37-title selection**: The full owned catalog is ~160 titles. YAML should be expanded to complete coverage using DB codes + Wikipedia dates/titles.

### General

- **Dates from DB**: The `titles` table has no date column. `first_seen_at` on the `actresses` table is the actress's first-seen scan date — not a title date. Use DB for codes only; get dates from Wikipedia or DMM.
- **Compilation handling**: Compilations currently listed inline with regular titles. May split into a dedicated `compilations:` key for filtering purposes.
- **English translations**: Hand-translated. Some are literal and crude. No quality flag yet.
- **Unknown label identities**: AAJ, AVGL, DMSM, MAD, NDV, NDTK, PDV, PGD, TJCA, TSDV prefixes are present in the DB but their studios are not yet confirmed. Cover reads will resolve these.
- **DMM actress ID registry**: No centralized mapping of actress name → DMM ID yet. IDs discovered ad hoc during scraping. Consider building `reference/dmm_actress_ids.yaml` as IDs are found. Yuma Asami's DMM ID is `15365`.
