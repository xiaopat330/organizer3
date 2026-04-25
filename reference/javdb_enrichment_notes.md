# javdb Enrichment — Exploration Notes

## What we tried

POC goal: use javdb to look up an actress's kanji stage name without AI, without a headless browser, without a VPN.

Approach:
- Find a solo title for the actress (one where only she is attributed)
- Search javdb by product code → get title slug
- Fetch the title detail page → extract the female actress from the Actor(s) panel
- Compare the returned kanji name against what's in our DB

Result: confirmed working. `DV-1221` → `麻美ゆま`, exact match against Yuma Asami's stored `stage_name`.

---

## Title detail page — confirmed fields (verified against DV-948)

URL: `GET /v/{slug}`, reached via search `GET /search?q={CODE}&f=all`

| Field | javdb value (DV-948 example) | In our DB? |
|---|---|---|
| Product code | DV-948 | ✓ |
| Japanese title | 年下の男の子 麻美ゆま | ✓ title_original |
| Release date | 2008-09-12 | ✓ release_date |
| Duration | 180 minute(s) | ✗ not stored |
| Maker (studio) | アリスJAPAN | indirectly via label→company |
| Publisher (label) | アリスJAPAN | ✓ label = DV |
| Rating | 4.5 / 5, by 29 users | ✗ not stored |
| Tags | 3p/4p, Solowork, Cunnilingus, Big Tits, Cowgirl | we have our own tag taxonomy |
| Actress name | 麻美ゆま | ✓ name; ✗ slug not stored |
| Actress javdb slug | ex3z | ✗ not stored |
| Cover image URL | `https://c0.jdbstatic.com/covers/de/deD0v.jpg` | we have local covers |
| Sample thumbnails | ~13 thumb URLs on jdbstatic CDN | ✗ not stored |

**HTML structure of the title:**
```html
<h2 class="title is-4">
  <strong>DV-948 </strong>
  <strong class="current-title">年下の男の子 麻美ゆま </strong>
</h2>
```
Cover image is `<img class="video-cover" src="https://c0.jdbstatic.com/covers/{xx}/{slug}.jpg">`.
Sample thumbnails are `<img src="https://c0.jdbstatic.com/thumbs/{xx}/{slug}.jpg">` — typically ~10–13 per title.

---

## HTTP access details

- Base URL: `https://javdb.com`
- Search: `GET /search?q={CODE}&f=all`
- Title detail: `GET /v/{slug}`
- Required headers: browser User-Agent, `Cookie: age_check_done=1; locale=en`
- No auth, no VPN, no headless browser needed
- Plain `java.net.http.HttpClient` works

---

## HTML structure (real, verified)

Search results: first `<a href="/v/{slug}">` link is the top result.

Title detail info panel — actress row:
```html
<div class="panel-block">
  <strong>Actor(s):</strong>
  &nbsp;<span class="value">
    <a href="/actors/OpzD">水咲ローラ</a><strong class="symbol female">♀</strong>&nbsp;
    <a href="/actors/x7wn">田淵正浩</a><strong class="symbol male">♂</strong>&nbsp;
  </span>
</div>
```
Gender is on the `<strong class="symbol female|male">` immediately after each actress link.

---

## Solo title strategy

To unambiguously identify an actress via a title:
- Filter her titles to ones where she is the only attributed actress
- In our DB: `titles.actress_id = X` and no rows in `title_actresses` for that title_id, OR `title_actresses` has exactly one row
- Pick any matching title — one is enough

---

## Actress profile page — confirmed fields (verified against Yuma Asami, slug: ex3z)

URL: `GET /actors/{slug}`

**What's there:**
- **All name variants** — comma-separated in `<span class="actor-section-name">`. For Yuma: `麻美由真, 麻美ゆま`. These can include alternate kanji spellings not in our aliases table.
- **Avatar image** — inline CSS background-image URL, e.g. `https://c0.jdbstatic.com/avatars/ex/ex3z.jpg`
- **Total title count** — e.g. "870 movie(s)" in `<span class="section-meta">`
- **Twitter link** — `href="https://twitter.com/{handle}"` if listed
- **Instagram link** — `href="https://instagram.com/{handle}"` if listed
- **Content tags with counts** — e.g. Big Tits (469), Best/Omnibus (428), Cowgirl (229), Solowork (50). English labels. Reflects her full javdb-indexed filmography.

**What's NOT there:**
- No birth date
- No height / measurements / cup size
- No career dates / retirement info

javdb is a title catalog — physical profile data lives on DMM/Wikipedia, not here.

---

## Multi-title consistency check (5 titles, 5 actresses)

Tested: XV-1208 (Nana Ogura), ONED-404 (Sora Aoi), ONED-495 (Yua Aida), SSNI-172 (An Tsujimoto), ONED-539 (Azusa Isshiki).

**Fields present on all 5:** release date, duration, maker, publisher, rating, actress name + slug, cover URL, thumbnails (~15–18 per title). HTML structure was identical across all — no missing panels, no layout variation.

**Series field:** present on some titles, absent on others (empty string when not set). Not currently stored in our DB at all — potential new field.

**Surprises / data quality signals:**

- **ONED-539 actress mismatch** — our DB attributed this to Azusa Isshiki, but javdb says 蒼井そら (Sora Aoi). Root cause: the title had a misnamed copy in the queue (`/queue/Azusa Isshiki - Demosaiced (ONED-539)`) which the sync picked up as an attribution. The two qnap locations were correctly under Sora Aoi. **Fixed** — `titles.actress_id` updated to Sora Aoi and Azusa Isshiki removed from `title_actresses`. This shows javdb cross-referencing could be a data quality signal for catching misattributions.
- **SSNI-172 date discrepancy** — javdb: `2018-04-06`, our DB: `2018-04-14`. Minor difference, probably announcement date vs. street date from different sources.
- **Maker vs. Publisher** — sometimes the same (ONED, SSNI → both "S1 NO.1 STYLE"), sometimes different (XV-1208: Maker = マックスエー in Japanese, Publisher = MAX-A in English). Publisher seems to be the label-facing name.

---

## What else javdb could give us (not yet explored)

- **Release date** — available on the title page, could fill `release_date` gaps
- **Tags** — English-label tags per title (e.g. "Solowork", "Big Tits") — could map to our tag taxonomy
- **Label / maker** — could cross-check against our labels table
- **Direct actress page lookup** — once we have a slug stored, we can skip the title search entirely and go straight to `/actors/{slug}`

---

## Open questions / things to explore next

- How well does the product code search work for less common labels (e.g. NDV, KA)? Those aren't indexed on DMM or Wikipedia but may be on javdb.
- Rate limiting behavior — how aggressive is javdb's anti-bot? At what req/sec does it start 429ing?
- Is the `locale=en` cookie the right default, or should we use `locale=ja` to get Japanese label names in tags?
- The name variants field (e.g. `麻美由真, 麻美ゆま`) could be a source for alias enrichment — worth checking how consistently javdb populates this across actresses.
