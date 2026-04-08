# Actress Research Procedure

This is the standardized, repeatable procedure for building a complete actress profile and portfolio YAML. It is written for an AI agent (Claude Code) to execute autonomously or semi-autonomously. The Yuma Asami YAML (`reference/actresses/yuma_asami/yuma_asami.yaml`) is the reference implementation.

---

## Prerequisites

- VPN exit node set to Japan (DMM/FANZA is geo-restricted)
- Local DB at `~/.organizer3/organizer.db` synced and up to date
- Cover images synced locally (`sync covers` command) — only needed if cover scanning is required
- `src/main/resources/labels.yaml` available as label source of truth

---

## YAML Schema

This is the canonical schema. All actress YAMLs must conform to this structure. Fields marked `# required` must always be present (use `null` if unknown). Fields marked `# optional` may be omitted entirely.

```yaml
profile:
  name:
    family_name: string        # required — romanized (Hepburn), e.g. "Asami"
    given_name: string         # required — romanized (Hepburn), e.g. "Yuma"
    stage_name: string         # required — kanji/kana, e.g. "麻美ゆま"
    reading: string            # required — hiragana reading, e.g. "あさみ ゆま"
    alternate_names:           # optional — list of other names
    - name: string             # the alternate name
      note: string             # optional — context, e.g. "early gravure name"
  date_of_birth: date          # required — ISO 8601 unquoted, e.g. 1987-03-24
  birthplace: string           # required — "City, Prefecture, Japan"
  blood_type: string           # required — A / B / AB / O
  height_cm: integer           # required — centimeters
  measurements:                # required
    bust: integer              # cm
    waist: integer             # cm
    hip: integer               # cm
  cup: string                  # required — letter A–K
  active_from: date            # required — ISO date of first release
  active_to: date              # required — ISO date of last release or retirement
  retirement_announced: date   # optional — formal retirement date if different from active_to
  biography: string            # required — 3-6 sentences, double-quoted
  primary_studios:             # required — list
  - name: string               # studio/label display name
    company: string            # parent company name
    from: date                 # contract start (ISO date)
    to: date                   # contract end (ISO date)
    role: string               # e.g. "Exclusive contract actress"
  awards:                      # optional — list
  - event: string              # award ceremony name (Japanese)
    year: integer              # year awarded
    category: string           # award category (Japanese with English gloss)
  legacy: string               # optional — 1-3 sentences summary of career significance

portfolio:                     # required — flat array, chronologically sorted
- code: string                 # required — product code from DB, e.g. "DV-563"
  title:                       # required
    original: string           # required — Japanese title (null if unknown)
    english: string            # required — English translation (null if unknown)
  label: string                # required — must match a label value in labels.yaml
  date: date                   # required — ISO release date (null if unknown), single-quoted
  notes: string                # optional — one sentence or null
  grade: string                # optional — SSS/SS/S/A+/A/A-/B+/B/B-/C+/C/C-; omit for compilations/reissues
  tags:                        # required — list from reference/tags.yaml vocabulary
  - string

meta:
  total_owned_titles: integer       # count of titles in local DB for this actress
  titles_with_japanese_title: integer
  titles_with_english_title: integer
  titles_with_release_date: integer
  total_known_releases: integer     # estimated full catalog size (including unowned)
  coverage_note: string             # what's included/excluded
  confidence: string                # data quality notes, flag any uncertain sources
  data_sources:                     # list of URLs and local sources used
  - string
  portfolio_listed: integer         # count of entries in portfolio array
```

### Schema Rules

| Rule | Correct | Wrong |
|------|---------|-------|
| Dates | `1987-03-24` (unquoted) or `'2005-11-07'` (single-quoted in portfolio) | `"1987-03-24"` |
| Unknown values | `null` | `""` or `~` |
| Integers | `158` | `"158"` |
| Strings with colons/special chars | single-quoted: `'Barely Mosaic: Thick Sex'` | unquoted or double-quoted with escaping issues |
| Label values | must match `label:` field in `src/main/resources/labels.yaml` | company name, suffixed labels |
| Tags | from `reference/tags.yaml` vocabulary only | freeform strings |
| Block scalars | never use `>` or `\|` | |
| Portfolio order | chronological by date (nulls at end) | grouped by studio |

---

## Phase 1 — Workspace Setup

Create the actress workspace folder:

```
reference/actresses/{family_name}_{given_name}/
```

All artifacts go here: YAML, raw data caches, scripts.

---

## Phase 2 — Local DB Inventory

Query the local DB to establish the owned title set. These codes are authoritative (filesystem-parsed).

```sql
-- Find actress ID
SELECT id, canonical_name, stage_name FROM actresses
WHERE canonical_name LIKE '%name%' OR stage_name LIKE '%kanji%';

-- Get all owned titles
SELECT code, base_code, label, seq_num FROM titles
WHERE actress_id = <id> ORDER BY label, seq_num;
```

Record the full code list grouped by label prefix. Note the count — this is the minimum portfolio size.

**DB gives:** codes, label prefixes, sequence numbers for ordering.
**DB does NOT give:** Japanese titles, release dates, profile data.

---

## Phase 3 — Wikipedia Profile + Filmography

Fetch the Japanese Wikipedia article:

```
Tool: mcp__fetch__fetch
URL: https://ja.wikipedia.org/wiki/<URL-encoded stage name>
max_length: 15000
```

Paginate with `start_index` if truncated. Cache the raw text to `{name}_wikipedia_ja_raw.txt`.

### Identity Verification (automated heuristics)

Before extracting ANY data, run these checks. All are automated — human pause only on failure.

**Available signals from DB (at this point in the pipeline):**
- `canonical_name` — romanized name (reliable)
- `stage_name` — often null; not dependable yet
- Label distribution — top labels by count (strongest signal)
- Title count — total owned titles
- Note: `first_seen_at` is the sync date, NOT the debut date — do not use for verification

**Check 1 — Studio match (strongest signal):**
1. From Phase 2 DB inventory, take the top label prefixes (those with ≥5 titles)
2. Look up each label prefix in `src/main/resources/labels.yaml` to get the company name
3. Scan the Wikipedia article for those company names (Japanese or English)
4. **PASS** if all top companies appear in Wikipedia. **FAIL** if any top company is missing.

Example: DB has DV(73)→Alice Japan, SOE(45)+ONED(24)→S1. Wikipedia must mention both アリスJAPAN/Alice Japan AND エスワン/S1.

**Check 2 — Title count ballpark:**
1. Count filmography entries in Wikipedia
2. Compare against DB title count
3. **PASS** if Wikipedia count is within 0.3x–3x of DB count. **FAIL** if wildly different.

Example: DB has 165 titles, Wikipedia lists ~210 entries → ratio 1.27 → PASS.

**Check 3 — Name confirmation:**
1. Wikipedia article title should match the stage name searched
2. DMM search result name should match Wikipedia article title (skip if DMM delisted)
3. **PASS** if both match (or DMM unavailable and Wikipedia matches). **FAIL** if either differs.

**Decision:**
- All checks PASS → proceed automatically, no human needed
- Any check FAIL → pause and ask the user to confirm identity before continuing

These heuristics are intentionally conservative and can be adapted as we learn from more actresses. See `reference/actress_data_guide.md` § Known Failure Modes for real conflation examples.

**Never write profile data from memory.** All profile fields must be sourced from Wikipedia or another verified external source.

### Conflicting biographical data

Some actresses have conflicting data across sources or even within the same Wikipedia article. Rules:

- **DOB:** Some actresses used a fake public DOB for privacy. If the real DOB has been publicly confirmed (e.g. Sora Aoi revealed hers in 2021), use the real DOB. Note the public DOB in `alternate_names` or `biography` if relevant.
- **Birthplace:** If two Wikipedia infoboxes on the same page disagree, prefer the AV-specific infobox (the one with measurements/cup). Note the discrepancy in `meta.confidence`.
- **Measurements:** These change over career. Use the most commonly cited set, typically from the AV-era infobox. Note the date qualifier if Wikipedia provides one (e.g. "2008年時点").

### Extract from Wikipedia:
- **Profile fields:** DOB, birthplace, blood type, height, measurements, cup, debut date, retirement
- **Studio affiliations:** contract periods, roles
- **Awards:** event names, years, categories
- **Filmography:** complete title lists with dates, organized by studio section
  - Alice Japan: bullet list format `* title（date）`
  - S1: wiki table format `| date | title | notes |`
  - Other makers: separate table with maker column
  - Note compilation markers: `※総集編`, remakes: `※リメイク`, co-stars: `共演:name`

---

## Phase 4 — DMM/FANZA Scrape

### Step 1 — Resolve DMM Actress ID (automated via Puppeteer)

This step resolves the actress's numeric DMM ID from her stage name. Fully automated.

```
1. Navigate to FANZA top page:
   mcp__puppeteer__puppeteer_navigate → https://www.dmm.co.jp/digital/videoa/

2. Click age gate (はい button):
   mcp__puppeteer__puppeteer_evaluate → find button with text 'はい', click it

3. Fill search box and submit:
   mcp__puppeteer__puppeteer_fill → #naviapi-search-text with stage name (kanji)
   mcp__puppeteer__puppeteer_evaluate → document.querySelector('#frmSearch').submit()

4. Extract actress ID from sidebar filter links:
   mcp__puppeteer__puppeteer_evaluate →
     Parse all <a> tags with href containing 'actress='
     Extract: id (from actress=NNNNN), name, count (from "name(NNN)" text)
     Find exact match where name === search query
     Return the id
```

**Disambiguation rules:**
- **Exact name match** → use that ID (most common case)
- **Multiple partial matches** → cross-reference title count against DB count; the closest match wins
- **No match** → the actress may not be indexed on DMM, or the name may use a different spelling. Try alternate names from Wikipedia.

**Verified examples:**
- Search `麻美ゆま` → sidebar link `麻美ゆま(785)` → `actress=15365` ✓
- Search `蒼井そら` → zero results (all titles delisted 2019) → skip to Phase 5

### DMM Delisting (failure mode)

Some actresses have had all titles removed from FANZA/DMM distribution. This is common for actresses who:
- Formally retired and requested removal (e.g. Sora Aoi, 2019)
- Had legal disputes with studios
- Transitioned to mainstream careers

**Detection:** FANZA search returns zero product results and no actress sidebar links.

**Impact:** Phase 4 is entirely skipped. Wikipedia becomes the sole external data source for titles and dates. Cover scanning becomes critical for resolving titles that Wikipedia doesn't cover (reissues, obscure labels).

**Adaptation:** Proceed directly to Phase 5, relying on Wikipedia + cover scanning. Note in `meta.data_sources` that DMM was unavailable.

**URL patterns (new FANZA):**
- Search results: `https://video.dmm.co.jp/av/list/?key=<name>`
- Actress filtered: `https://video.dmm.co.jp/av/list/?actress=<id>`
- Old-style URLs (`dmm.co.jp/digital/videoa/-/list/=/article=actress/id=<id>/`) redirect to the new format

### Step 2 — Scrape Actress Page

Navigate to the actress's filtered page and extract all titles:

```
mcp__puppeteer__puppeteer_navigate → https://video.dmm.co.jp/av/list/?actress=<id>
```

Paginate through all result pages. Cache results to `{name}_dmm_raw.json`.

### DMM access patterns:
- **Two storefronts:** digital (`/digital/videoa/`) and physical (`/mono/dvd/`). Always try both.
- **独占 (exclusive) titles:** DMM hides the title text. The code is still there.
- **robots.txt blocks `/search/`** — cannot use fetch for searches, must use Puppeteer
- **Content ID format:** `dv00563` → `DV-563`. Strip label prefix, remove leading zeros. Known suffixes to ignore: `ai`, `nkj`, `mtn`
- **Padding varies:** digital uses zero-padded (`dv00563`), mono sometimes unpadded (`onsd059`). Try both on 404.

### Match DMM to DB:
For each DMM entry, convert content ID to standard code and match against the Phase 2 DB list:
- **Matched + visible title:** extract `title.original`
- **Matched + 独占:** code confirmed, title needs Wikipedia or product page
- **No DB match:** title exists on DMM but we don't own it (note for `total_known_releases`)

---

## Phase 5 — Cross-Reference and Resolution

This is where the bulk of data matching happens. Work through titles in priority order.

### 5a. Visible DMM titles → dates from Wikipedia
For titles where DMM gave us the Japanese title, fuzzy-match against Wikipedia filmography to get the release date.

### 5b. 独占 titles → positional matching against Wikipedia
For titles where DMM hid the title behind "独占":
1. Sort DB titles by `seq_num` within each label
2. Match positionally against Wikipedia's chronological table for that studio
3. Filter out compilations (`総集編`) and multi-actress entries before matching
4. This gives both `title.original` and `date` in one step

### 5c. Unmatched DB titles → targeted lookups
For titles in DB but not on DMM actress page (common with reissues, compilations, niche labels):
1. Try DMM product page directly: `/mono/dvd/-/detail/=/cid=<code>/` (try both padded and unpadded)
2. Try digital storefront: `/digital/videoa/-/detail/=/cid=<code>/`
3. Check Wikipedia filmography for compilation entries

### 5d. Cover scanning (before Puppeteer for remaining gaps)
For titles still unresolved after 5a-5c:
1. Read cover images: `data/covers/<LABEL>/<LABEL>-<NNNNN>.jpg` (5-digit zero-padded)
2. Extract `title.original` from front cover (largest Japanese text block)
3. Cover scanning is fast (parallel batch, small JPGs) and works for obscure labels not indexed anywhere
4. **Cover scanning does NOT give dates** — only Puppeteer/DMM product pages have dates

### 5e. Puppeteer product pages (last resort, for dates only)
Individual DMM product page navigations for targeted date lookups when Wikipedia doesn't have the date.

### Resolution priority:

| Field | Priority order |
|-------|---------------|
| `code` | DB (filesystem) > cover spine > DMM |
| `title.original` | Wikipedia > DMM (Wikipedia has uncensored text; DMM censors with ●/○) |
| `date` | DMM product page > Wikipedia > DB `first_seen_at` (last resort) |

---

## Phase 6 — English Translations

Hand-translate all `title.original` to `title.english`. Be accurate, not literal. This is pure text work, no fetches needed. Can be done in batch.

---

## Phase 7 — Label Normalization

Normalize all `label:` values against `src/main/resources/labels.yaml`:

1. Extract unique labels from the YAML
2. For each, grep labels.yaml for the code prefix to find the correct sub-label name
3. Common errors to watch for:
   - **Company name instead of sub-label:** e.g. `PREMIUM` should be `Glamorous` (PGD codes)
   - **Suffixed labels:** e.g. `Alice Japan (reissue)` — drop the suffix; reissue info belongs in `tags:`/`notes:`
   - **Reprint sub-labels missed:** e.g. PDV → `alice pink`, not `Alice Japan`
4. Labels for codes not in labels.yaml → `Unknown`

---

## Phase 8 — Tagging

Apply content tags from the `reference/tags.yaml` vocabulary:

1. Use a tagging script with keyword regex matching (Japanese + English) against title text
2. Use label/code inference for structural tags (compilation, reissue, debut, etc.)
3. Manual overrides for ambiguous titles
4. Every entry must have at least one tag

Reference implementation: `reference/actresses/yuma_asami/tag_portfolio.py`

---

## Phase 9 — Grading

Assign grades based on fan/collector consensus, not personal opinion:

1. Web search for: `{name} FANZA レビュー 評価 人気作品 ランキング`, `{name} 名作 おすすめ 神作`
2. Fetch Wikipedia (ja + en) for chart positions, awards for individual titles, milestone status
3. Calibrate the bell curve for this specific actress's tier
4. Assign grades using the SSS–C- scale
5. Skip compilations/reissues (omit `grade:` field entirely)

Expected distribution for a top actress (~150 titles): SSS: 1-3, SS: 3-7, S: 8-15, A-tier: 40-60, B-tier: 50-70, C-tier: 0-5.

Reference implementation: `reference/actresses/yuma_asami/grade_portfolio.py`

---

## Phase 10 — Verification Checklist

Do not finalize without completing:

- [ ] Profile fields verified against Wikipedia (stage_name, DOB, birthplace, blood_type, height, measurements, cup)
- [ ] Identity confirmed — biography describes the expected career
- [ ] All DB codes present in portfolio (count matches Phase 2)
- [ ] Labels normalized against labels.yaml
- [ ] Tags applied from reference/tags.yaml vocabulary
- [ ] Grades assigned (or consciously skipped for compilations/reissues)
- [ ] English translations present for all titles
- [ ] Portfolio sorted chronologically (null dates at end)
- [ ] Meta section counts are accurate
- [ ] No block scalars (`>` or `|`) in YAML
- [ ] No hallucinated codes — every code sourced from DB or cover spine

---

## Agentic Automation Assessment

### What CAN be automated today (tools available)

| Phase | Tool | Confidence | Notes |
|-------|------|------------|-------|
| 1. Workspace setup | Write | 100% | mkdir + create skeleton YAML |
| 2. DB inventory | mcp__sqlite | 100% | Simple SQL queries |
| 3. Wikipedia fetch | mcp__fetch__fetch | 95% | Pagination needed for long articles; identity verification automated via heuristics (studio match, title count, name match) |
| 4. DMM scrape | mcp__puppeteer__* | 95% | Age gate, pagination, 独占 handling all proven. DMM ID resolution automated via FANZA search + sidebar actress links |
| 5a. DMM→Wikipedia date matching | In-context | 90% | Fuzzy text matching in context window |
| 5b. Positional matching | In-context | 90% | Proven technique, requires filtering compilations |
| 5c. DMM product pages | mcp__puppeteer__* | 70% | Works but slow, 404s common, need padded/unpadded fallback |
| 5d. Cover scanning | Read | 95% | Fast parallel batch, proven for obscure labels |
| 5e. Puppeteer date lookups | mcp__puppeteer__* | 70% | Same as 5c |
| 6. English translations | In-context | 95% | Batch translation, high quality |
| 7. Label normalization | Read + Edit | 100% | Grep labels.yaml, mechanical replacement |
| 8. Tagging | In-context + Write | 90% | Keyword regex engine proven; manual overrides needed for ~10% |
| 9. Grading | WebSearch + mcp__fetch__fetch | 75% | Requires judgment; web sources are spotty for less-famous actresses |
| 10. Verification | In-context | 90% | Checklist is mechanical except identity verification |

### Blocking gaps for full automation

1. ~~**DMM actress ID resolution**~~ — **SOLVED.** Puppeteer search via FANZA form submission + sidebar actress link extraction. Tested 2026-04-07 with 麻美ゆま → `actress=15365` exact match.

2. ~~**Identity verification**~~ — **SOLVED.** Three automated heuristic checks: (1) DB label prefixes → labels.yaml company lookup → Wikipedia studio match, (2) title count ballpark, (3) name match across DB/DMM/Wikipedia. Auto-passes in common case; pauses for human only on mismatch. Intentionally conservative — can be loosened as we gain confidence from more actresses.

3. **Grading for less-famous actresses** — Web sources thin out significantly for mid-tier or lower-tier actresses. Policy: grade what you can from available sources, skip titles with no data, note confidence level in meta. For actresses with very sparse data, grading can be deferred entirely.

### Verdict

**Fully agentic for Phases 1-8. Phase 9 (grading) is semi-agentic.** All data procurement phases can run end-to-end without human input. The identity verification heuristics provide a safety net without requiring a human pause in the common case.

Workflow:
1. Human provides: actress name (kanji + romanized). DMM ID is no longer required.
2. Agent runs Phases 1-8 autonomously, including DMM ID resolution
3. Agent runs identity verification heuristics — pauses only if heuristics fail
4. Agent runs Phase 9 (grading) with web research — can run fully autonomously for well-known actresses, may need human review for less-famous ones
5. Agent runs Phase 10 verification checklist

---

## File Inventory Per Actress

When complete, the workspace folder should contain:

```
reference/actresses/{family}_{given}/
  {family}_{given}.yaml              # final YAML (the deliverable)
  {family}_{given}_wikipedia_ja_raw.txt  # cached Wikipedia article
  {family}_{given}_dmm_raw.json      # cached DMM scrape
  {family}_{given}_minnano_raw.json  # cached Minnano-AV (if used)
  tag_portfolio.py                   # tagging script (actress-specific overrides)
  grade_portfolio.py                 # grading script (actress-specific grades)
```
