# Cover Tagging Instructions

Instructions for a Haiku session that infers tags from cover images and merges them into actress YAML files.

---

## Session Setup

At the start of each session, the user will tell you:
- Which actress to work on (e.g. "tag Yuma Asami's covers")
- The actress YAML path (e.g. `reference/actresses/yuma_asami/yuma_asami.yaml`)

Read the actress YAML first to extract the full portfolio (list of `code` entries). Then read `reference/tags.yaml` to load the tag vocabulary.

---

## Cover File Location

Covers are stored at:
```
/Users/pyoung/workspace/organizer3/data/covers/{LABEL}/{CODE}.jpg
```

The label prefix is the alphabetic portion of the code. Examples:
- `ABP-712` → `/data/covers/ABP/ABP-00712.jpg`
- `ONED-292` → `/data/covers/ONED/ONED-00292.jpg`

Note: filenames are zero-padded to 5 digits after the hyphen. If a file is not found at the zero-padded path, try without padding (e.g. `ABP-712.jpg`).

---

## Batch Workflow

Process covers in batches of **10 at a time**:

1. Take the next 10 codes from the portfolio that have no tags or incomplete tags
2. Read each cover image using the Read tool
3. Analyze all 10 covers and output a tag map (see format below)
4. Ask the user to confirm or adjust before writing
5. Merge confirmed tags into the YAML
6. Move to the next batch

Do not process more than 10 covers before pausing to write and confirm.

---

## Tag Inference Rules

Use only tags from `reference/tags.yaml` unless you are confident a new tag is warranted (see New Tags section).

**High-confidence signals (use freely):**
- Costume/uniform visible on cover → role + setting tags (`nurse` + `hospital`, `maid`, `office-lady` + `office`, etc.)
- Swimwear visible → `swimsuit`
- Leotard/high-leg → `leotard`
- Multiple actresses on cover → `co-star`
- "BEST" / "COLLECTION" / "コレクション" in title text → `compilation`
- "再" / "リパッケージ" in title or notes → `reissue`
- Runtime 4+ hours noted in title → `long-format`
- Debut noted in title or notes field → `debut`
- Breast/bust emphasis in framing or title → `busty`
- Ass/posterior emphasis → `ass-focus`
- Oil/lotion visible or in title → `oil`
- Eye contact with camera, close face framing → `eye-contact`

**Text-dependent signals (read title kanji):**
- 中出し → `creampie`
- パイズリ → `paizuri`
- 顔射 → `facial`
- 潮吹き → `squirting`
- ごっくん / 精飲 → `swallow`
- 痴女 → `femdom`
- 痴漢 → `groping`
- 凌辱 / レイプ → `violation`
- 監禁 / 拘束 → `confinement`
- 催眠 → `hypnosis`
- 媚薬 → `aphrodisiac`
- ナンパ → `pickup`
- 童貞 → `virgin-play`
- 人妻 / 若妻 → `married-woman`
- ソープ → `soapland`
- エステ → `massage-parlor`
- 温泉 → `hot-spring`

**Do not guess** tags that are not visually or textually supported. Omit rather than speculate.

---

## Output Format

After analyzing a batch, output a YAML block in this format:

```yaml
# Batch 1 — ONED-292 through DV-999
- code: ONED-292
  tags_to_add: [debut]
- code: DV-563
  tags_to_add: [ass-focus, eye-contact]
- code: DV-578
  tags_to_add: [soapland, femdom]
```

Only list tags that are **new** (not already present in the YAML for that entry). If a cover yields no new confident tags, output `tags_to_add: []` so the user knows it was processed.

---

## Merging into the YAML

After user confirmation, merge tags into the actress YAML:
- Append new tags to the existing `tags:` list for each code
- Do not remove or reorder existing tags
- Do not add duplicate tags
- If a portfolio entry has `tags: null` or no tags key, initialize it as an empty list first

---

## New Tags

If a cover strongly suggests a concept not in `tags.yaml` (e.g. `lesbian`, `pov`, `pantyhose`, `bondage`, `toys`, `gangbang`, `lingerie`, `interview`), flag it separately:

```yaml
# Suggested new tags (not yet in tags.yaml)
- code: ABP-100
  suggested_new_tags: [lesbian]
  rationale: Two actresses shown in intimate pose, no male presence on cover
```

Do not add suggested tags to the YAML. Collect them for review — the user will decide whether to add them to `tags.yaml` first.

---

## Session End

At the end of each session (or when the user says to stop), output a summary:
- How many covers were processed
- How many had tags added
- How many yielded no confident tags
- Any suggested new tags collected during the session

---

## Notes

- If a cover file is missing, skip it and note the missing code in your output
- The actress YAML is the source of truth; do not modify profile, grade, title, label, or date fields
- Tags are the only field you are authorized to add to
