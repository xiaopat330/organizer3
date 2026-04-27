# Misplaced & Misnamed Folders — Cleanup List

**Date:** 2026-04-26  
**Context:** Two batches of merges were done today:
1. 18 name-order flips (e.g. "Mizuno Asahi" → "Asahi Mizuno") — title folders renamed, but wrong volume
2. 21 spelling typo fixes (e.g. "Asusa Misaki" → "Azusa Misaki") — title folders renamed, but actress-level folders not

The `rename_actress_folders` tool only renames title-level folders (e.g. `Azusa Misaki (IPX-001)`).
It does NOT rename the parent actress folder (e.g. `stars/minor/Asusa Misaki/`). Both issues are
listed below for manual cleanup.

---

## Section 1: Actress-Level Folders With Old Name

These `stars/` actress folders still use the pre-merge name. The title folders inside were already
renamed correctly — only the parent folder needs a rename in place (no volume move needed).

### Status (2026-04-26)

19 of 22 folders (all conventional volumes) were routed to `/attention/<canonicalName>/` via the
`move_actress_folder_to_attention` MCP tool. The DB paths were updated at the same time.

**Next step for each routed folder:**
1. On the volume in Finder, find `/attention/<canonicalName>/`
2. Move it to the correct `stars/<tier>/` location (e.g. `/stars/minor/Azusa Misaki/`)
3. Run `sync all` on that volume to update the DB paths

3 folders excluded (non-conventional volumes — handle manually):
- `classic` — `/stars/Azusa Itgaki/` → `/stars/Azusa Itagaki/` (in-place rename)
- `qnap` — `/stars/Erica Sato/` → `/stars/Erika Sato/` ⚠ (see footnote — mixed content)
- `qnap` — `/stars/Sora Amakawa/` → `/stars/Sora Arakawa/` (in-place rename)

| Volume | Current folder | Should be |
|--------|---------------|-----------|
| `a` | `/stars/popular/Akari Niimura` | `/stars/popular/Akari Nimura` |
| `a` | `/stars/minor/Asusa Misaki` | `/stars/minor/Azusa Misaki` |
| `bg` | `/stars/minor/Eria Kitagawa` | `/stars/minor/Erika Kitagawa` |
| `bg` | `/stars/minor/Elisa Kusunoki` | `/stars/minor/Erisa Kusunoki` |
| `classic` | `/stars/Azusa Itgaki` | `/stars/Azusa Itagaki` |
| `k` | `/stars/library/Kuroki Nami` | `/stars/library/Nami Kuroki` |
| `m` | `/stars/library/Mizuno Asahi` | `/stars/library/Asahi Mizuno`\* |
| `m` | `/stars/library/Minami Maeda` | `/stars/library/Minami Maeta` |
| `m` | `/stars/minor/Miyu Shiromine` | `/stars/minor/Miu Shiromine` |
| `m` | `/stars/popular/Miyuki Yokohama` | `/stars/popular/Miyuki Yokoyama` |
| `m` | `/stars/minor/Moe Sakakihara` | `/stars/minor/Moe Sakakibara` |
| `m` | `/stars/minor/Momoka Isumi` | `/stars/minor/Momoka Izumi` |
| `ma` | `/stars/minor/Mariko Sada` | `/stars/minor/Mariko Sata` |
| `ma` | `/stars/minor/Mahina Nagai` | `/stars/minor/Mihina Nagai` |
| `n` | `/stars/minor/Nono Yuiki` | `/stars/minor/Nono Yuki` |
| `qnap` | `/stars/Erica Sato` | `/stars/Erika Sato`\*\* |
| `qnap` | `/stars/Sora Amakawa` | `/stars/Sora Arakawa` |
| `r` | `/stars/minor/Rui Hitzuki` | `/stars/minor/Rui Hizuki` |
| `s` | `/stars/minor/Shizuka Takimoto` | `/stars/minor/Shizuha Takimoto` |
| `tz` | `/stars/minor/Umika Minamizawa` | `/stars/minor/Umika Minamisawa` |
| `tz` | `/stars/library/Yuka Mizono` | `/stars/library/Yuka Mizuno` |
| `tz` | `/stars/popular/Yurina Yoshine` | `/stars/popular/Yuria Yoshine` |

\* `m` is also the wrong volume for Asahi Mizuno (A → `a`) — see Section 2.  
\*\* `qnap/stars/Erica Sato/` also contains `Erika Saito (SEND-116)` (wrong name) and
`Erica Sato - Wild Thing Super Mosaic (banned) (WID-66)` (unresolvable) — needs manual attention.

---

## Section 2: Title Folders on Wrong Volume (From Name-Order Flips)

These title folders are correctly named now, but physically on the wrong volume because they were
filed under the old reversed name. No actress folder move needed for queue paths.

| Actress | Should be on | Currently on | Path |
|---------|-------------|--------------|------|
| **Asahi Mizuno** | `a` | `m` | `/stars/library/Mizuno Asahi/Asahi Mizuno (CJOD-107)` |
| | | `m` | `/stars/library/Mizuno Asahi/Asahi Mizuno (HZGD-009)` |
| | | `m` | `/stars/library/Mizuno Asahi/Asahi Mizuno (WSS-274)` |
| **Asami Nagase** | `a` | `n` | `/archive/Asami Nagase (XVSR-155)` |
| | | `n` | `/queue/Asami Nagase (XVSR-435)` |
| **Ayami Mori** | `a` | `m` | `/queue/Ayami Mori (FSDSS-718)` |
| **Iori Nanase** | `hj` | `n` | `/queue/Nanase Iori (MKMP-315)` |
| **Kyoko Maki** | `k` | `ma` | `/queue/Kyoko Maki (DJJJ-011)` |
| | | `ma` | `/queue/Kyoko Maki (PPPE-263)` |
| **Mai Arisu** | `ma` | `a` | `/queue/Arisu Mai (MTALL-131)` |
| **Mai Tsubasa** | `ma` | `tz` | `/queue/Tsubasa Mai (FSDSS-994)` |
| **Maina Yuri** | `ma` | `tz` | `/queue/Yuri Maina (XVT-005)` |
| **Nami Aino** | `n` | `a` | `/archive/Nami Aino (SKY-283)` |
| | | `a` | `/archive/Nami Aino - JGirls Paradise (X-326)` |
| **Nami Kuroki** | `n` | `k` | `/stars/library/Kuroki Nami/Nami Kuroki (DLDSS-274)` |
| | | `k` | `/stars/library/Kuroki Nami/Nami Kuroki (DLDSS-275)` |
| | | `k` | `/stars/library/Kuroki Nami/Nami Kuroki (DLDSS-276)` |
| | | `k` | `/stars/library/Kuroki Nami/Nami Kuroki (DLDSS-299)` |
| **Nanami Misaki** | `n` | `m` | `/queue/Nanami Misaki (IPX-327)` |
| | | `m` | `/queue/Nanami Misaki (IPX-781)` |
| **Rena Aoi** | `r` | `a` | `/archive/Aoi Rena (KTKP-064)` |
| **Ryo Ayumi** | `r` | `a` | `/queue/Ryo Ayumi (JUR-285)` |
| | | `a` | `/queue/Ryo Ayumi (JUR-496)` |
| **Kokomi Sakura** | `k` | `s` | `/queue/Sakura Kokomi - Perfect Body Beautiful Elder Sister (SOE-372)` |
| **Suzume Mino** | `s` | `m` | `/attention/Mino Suzume (DLDSS-392)` |
| **Wakana Sakura** | `tz` | `s` | `/queue/Sakura Wakana (IPZZ-410)` |

---

## Section 3: Unresolvable Multi-Actress Folders

These folders contain multiple actress names and couldn't be auto-renamed. The old name needs to
be manually replaced in the folder name in place (no volume move needed).

| Volume | Current path | Fix |
|--------|-------------|-----|
| `pool` | `/Akari Niimura, Ichika Matsumoto (DASS-438)` | Rename: `Akari Nimura, ...` |
| `pool` | `/Akari Niimura, Moa Wakatsuki (GVH-680)` | Rename: `Akari Nimura, ...` |
| `pool` | `/Akari Niimura, Sumire Mizukawa, Mizuki Yayoi, Miyu Nanase (MGT-187)` | Rename: `Akari Nimura, ...` |
| `pool` | `/Akari Niimura, Ruisa Totsuki (MVG-096)` | Rename: `Akari Nimura, ...` |
| `pool` | `/Rui Hitzuki, Marina Ikeda (DASS-924)` | Rename: `Rui Hizuki, ...` |
| `pool` | `/Rui Hitzuki, Haruno Ando (MVG-126)` | Rename: `Rui Hizuki, ...` |
| `tz` | `/queue/Yuka Mizono, Saki Oishi (ROE-250)` | Rename: `Yuka Mizuno, ...` |
| `pool` | `/Minami Maeda, ... (DASS-???)` | Rename: `Minami Maeta, ...` |
| `m` | `/queue/Minami Maeda, ... ` | Rename: `Minami Maeta, ...` |
| `pool` | `/Kana Yura, Rina Kago (SQTE-539)` | Rename: `Kana Yura, ...` → `Kana Yura` already correct (name-order only) |
| `pool` | `/Yura Kana, Tsubame Amai (JKSR-621)` | Rename: `Kana Yura, ...` |
| `qnap` | `/stars/Erica Sato/Erika Saito (SEND-116)` | Rename folder + fix "Saito"→"Sato" |
| `qnap` | `/stars/Erica Sato/Erica Sato - Wild Thing Super Mosaic (banned) (WID-66)` | Rename: `Erika Sato - ...` |
| `bg` | (2 multi-actress Erika Kitagawa folders) | Rename: `Eria`→`Erika` |
| `classic_pool` | (1 multi-actress Erika Kitagawa folder) | Rename: `Eria`→`Erika` |
| `qnap` | (4 multi-actress Sora Arakawa folders) | Rename: `Amakawa`→`Arakawa` |

---

## Section 4: Ambiguous Name-Order Pairs (Not Merged — Need Manual Verification)

| Name A | Name B | Counts |
|--------|--------|--------|
| Tsumugi Akari | Akari Tsumugi | 114 vs 31 |
| Mikuni Maisaki | Maisaki Mikuni | 7 vs 8 |
| Minami Kozue | Kozue Minami | 5 vs 8 |
