#!/usr/bin/env python3
"""
Grade assignment script for Sora Aoi portfolio.
Inserts grade: field before tags: in the YAML file.

Grade rationale:
- SSS: ONED-003 (S1 debut, sold 100K copies — industry record, career-defining moment)
- SS: DV-172 (AV debut), SOE-046 (犯された蒼井そら — iconic violation drama),
       ONED-314 (妄想的特殊浴場 — signature soapland title)
- S: ONED-025 (淫語 — early S1 standout), ONED-065 (いいなり玩具 — fan favorite concept),
     ONED-269 (cosplay special), SOE-285 (20 costumes — sequel hit),
     HIG-001 (AV OPEN 2007 1st place), SOE-454 (NTR drama — popular genre entry),
     ONED-602 (Hyper Barely Mosaic — premium release)
- A+: Notable S1 titles with strong concepts
- A: Solid S1 mid-career titles
- A-: Good standard S1 releases
- B+: Standard Samantha/early Alice Japan fare, later S1 releases
- B: Average early career titles
- No grade: compilations
"""

import sys

GRADES = {
    # === SSS ===
    'ONED-003': 'SSS',   # S1 debut — 100K copies sold, industry record

    # === SS ===
    'DV-172': 'SS',       # AV debut "Happy Go Lucky!" — career-defining
    'SOE-046': 'SS',      # 犯された蒼井そら — iconic violation drama
    'ONED-314': 'SS',     # 妄想的特殊浴場 — signature soapland title, S1 Best Actress year

    # === S ===
    'ONED-025': 'S',      # 淫語 — early S1 hit, distinctive concept
    'ONED-065': 'S',      # いいなり玩具 — popular submissive concept
    'ONED-269': 'S',      # コスプレ special — fan favorite
    'ONED-602': 'S',      # ハイパーギリギリモザイク — premium release
    'HIG-001': 'S',       # AV OPEN 2007 1st place (multi-actress)
    'SOE-285': 'S',       # 20 costumes — sequel to cosplay hit
    'SOE-454': 'S',       # 夫の目の前で犯された若妻 — NTR drama, popular
    'RKI-015': 'S',       # ピンクのカーテン — one-off Rookie, movie tie-in

    # === A+ ===
    'ONED-014': 'A+',     # 女教師×女子校生 — co-star title, strong concept
    'ONED-104': 'A+',     # ネットリ濃厚セックス — signature series entry
    'ONED-136': 'A+',     # おっぱいスペシャル — bust showcase for G-cup
    'ONED-293': 'A+',     # エッチな隣人 — popular neighbor concept
    'ONED-335': 'A+',     # 24時間セックス — cohabitation fantasy
    'ONED-881': 'A+',     # おっきいチンポでバコバコ — hardcore standout
    'SOE-088': 'A+',      # ものすごい顔射 — Hyper series, intense
    'SOE-132': 'A+',      # 無限絶頂 — intense orgasm series
    'SOE-586': 'A+',      # 犯された人妻女教師 — combined popular themes
    'SOE-616': 'A+',      # 極美映像 — high-production value showcase

    # === A ===
    'DV-369': 'A',        # cosmic girl — 2nd Alice Japan title
    'ONED-036': 'A',      # 僕だけの蒼井そら — girlfriend experience
    'ONED-047': 'A',      # 接吻スペシャル — kissing specialty
    'ONED-168': 'A',      # 癒してあげる — healing concept
    'ONED-238': 'A',      # じっくり見せて — eye contact, intimate
    'ONED-356': 'A',      # 新米ナース — nurse cosplay
    'ONED-404': 'A',      # 激パイズリ 2 — paizuri sequel
    'ONED-433': 'A',      # 隣の若奥様 — young wife
    'ONED-539': 'A',      # バコバコ乱交15 — orgy series
    'ONED-730': 'A',      # S1 2nd anniversary special
    'ONED-927': 'A',      # MV改 — limited edition
    'ONED-944': 'A',      # 壮絶アクメ — last ONED code
    'SOE-019': 'A',       # 僕だけのアイドル — first SOE code
    'SOE-316': 'A',       # 濃密セックス — intense sex
    'SOE-339': 'A',       # 巨乳女教師 — busty teacher
    'SOE-370': 'A',       # 一撃顔射 — facial specialty
    'SOE-490': 'A',       # M調教 — femdom
    'SOE-523': 'A',       # シャブらせて — blowjob specialty
    'SOE-556': 'A',       # 轟沈アクメ — intense orgasm

    # === A- ===
    'ONED-201': 'A-',     # はげましセックス — standard ギリモザ
    'SOE-395': 'A-',      # 近親相姦 — taboo theme
    'SOE-422': 'A-',      # LOTION HELL — niche concept

    # === B+ ===
    'XS-2268': 'B+',      # あおぞら — Samantha debut
    'XS-2271': 'B+',      # Virgin Sky — early Samantha
    'XS-2278': 'B+',      # facial — early title
    'XS-2283': 'B+',      # 50/50 — early title
    'XS-2286': 'B+',      # SOLA-GRAPH — early Samantha
    'XS-2292': 'B+',      # 不法侵乳 14 — series entry
    'XS-2298': 'B+',      # 妹の秘密 — sister concept
    'XS-2303': 'B+',      # スプラッシュ — swimsuit
    'XS-2311': 'B+',      # Max Cafe — variety
    'XS-2320': 'B+',      # 口内感染 — standard fare
    'XS-2331': 'B+',      # 監禁ボディドール — confinement
    'XS-2338': 'B+',      # Self Produce — self-directed
    'XS-2349': 'B+',      # TABOO — taboo theme
    'XS-2358': 'B+',      # Lollipop — variety
    'XV-139': 'B+',       # 魔女の棲み家 IV — Karen series entry
}

# Compilations and reissues — no grade
NO_GRADE = {
    'ONSD-024',   # S1 compilation
    'ONSD-441',   # S1 8-hour compilation
    'ONSD-539',   # S1 12-hour compilation
    'ONSD-588',   # S1 rape compilation
    'ONSD-783',   # S1 8-hour compilation
    # KA reissues
    'KA-2080', 'KA-2094', 'KA-2101', 'KA-2109', 'KA-2115',
    'KA-2122', 'KA-2138', 'KA-2151', 'KA-2170', 'KA-2181',
    # Unknown labels
    'KR-9189', 'KR-9211',
    'SFBV-001', 'SFBV-009',
}


def main():
    yaml_path = 'reference/actresses/sora_aoi/sora_aoi.yaml'
    with open(yaml_path, 'r') as f:
        lines = f.readlines()

    output_lines = []
    current_code = None
    ungraded = []
    grade_counts = {}

    for line in lines:
        stripped = line.strip()

        if stripped.startswith('- code:'):
            current_code = stripped.split(':', 1)[1].strip()

        if stripped == 'tags:' or stripped.startswith('tags:'):
            if current_code and current_code in GRADES and current_code not in NO_GRADE:
                grade = GRADES[current_code]
                indent = line[:len(line) - len(line.lstrip())]
                output_lines.append(f"{indent}grade: {grade}\n")
                grade_counts[grade] = grade_counts.get(grade, 0) + 1
            elif current_code and current_code not in NO_GRADE and current_code not in GRADES:
                ungraded.append(current_code)

        output_lines.append(line)

    with open(yaml_path, 'w') as f:
        f.writelines(output_lines)

    print(f"Graded {sum(grade_counts.values())} titles")
    print(f"Distribution: {dict(sorted(grade_counts.items()))}")
    print(f"No grade (compilations/reissues/unknown): {len(NO_GRADE)}")
    if ungraded:
        print(f"WARNING: {len(ungraded)} codes not in GRADES dict: {ungraded}")


if __name__ == '__main__':
    main()
