#!/usr/bin/env python3
"""
Add viewer/fan consensus grades to Yuma Asami's portfolio.

Grade scale (school-style):
  SSS  — All-time iconic, career-defining
  SS   — Award-winning, landmark, exceptional commercial performance
  S    — Excellent, highly regarded by fans, notable milestone
  A+   — Very strong, top-tier reception
  A    — Strong, above-average reception
  A-   — Good, solid work above the norm
  B+   — Decent, competent standard fare
  B    — Average for a top actress
  B-   — Below expectations but not bad
  C+   — Formulaic or unremarkable
  C    — Below average
  C-   — Poor

Compilations and reissues get no grade (null).

Grading rationale is based on:
  - CDVJ POS chart rankings (#1 five times in first 6 months: Nov/Dec 2005, Feb/Mar/Apr 2006)
  - DMM #1 actress rankings (2006, 2007)
  - MOODYZ 2006 Best Actress; ONED-292 ranked #2 in product category
  - AV Open 2007 first place (ONED-812 was part of the winning S1 ensemble entry)
  - AV Grand Prix 2009: SOE-061 (Rio & Yuma) won Grand Prix, DVD Sales, Package Design,
    Retailers Award, Best Featured Actress
  - Alice Japan 2012 Best Actress award
  - Amazon/FANZA review consensus where available
  - Fan community discussion, series popularity, cultural significance
  - Industry milestone status (first BD, first 3D, first crossover, etc.)
"""

import yaml
import sys
from pathlib import Path
from collections import OrderedDict

# ── Grade assignments ──────────────────────────────────────────────────────

GRADES = {
    # ═══ SSS — All-time iconic ═══
    'ONED-292': 'SSS',   # S1 debut — #1 POS, MOODYZ #2 product, unprecedented dual-label debut
    'SOE-061':  'SSS',   # Rio & Yuma — AV Grand Prix 2009 winner (5 awards)

    # ═══ SS — Landmark, exceptional ═══
    'DV-563':   'SS',    # Alice Japan debut — #1 POS Dec 2005
    'ONED-812': 'SS',    # Hyper Barely Mosaic — part of AV Open 2007 winning entry
    'SOE-280':  'SS',    # NO.1 BODY CONSCIOUS STYLE — first BD release, iconic body showcase
    'SOE-515':  'SS',    # Secret Agent — launched major S1 franchise, very popular
    'SOE-166':  'SS',    # Golden Ratio Body — iconic body showcase, highly regarded

    # ═══ S — Excellent, highly regarded ═══
    'ONED-333': 'S',     # #1 POS Feb 2006, early peak sales
    'DV-578':   'S',     # #1 POS Mar 2006
    'ONED-352': 'S',     # #1 POS Apr 2006
    'SOE-326':  'S',     # First S1 creampie title — very popular, milestone
    'SOE-371':  'S',     # Kiss Me Even While Inside — top-tier GFE, BD available
    'SSPD-086': 'S',     # Attackers crossover — only non-S1/Alice appearance, notable
    'EBOD-238': 'S',     # SSS-BODY — E-BODY crossover, late-career highlight
    'SOE-836':  'S',     # Secret Agent 2 — sequel to hit franchise
    'SOE-735':  'S',     # 4-hour brothel special — well-received long format
    'SOE-022':  'S',     # First SOE-numbered release — transition from ONED, beautiful slut theme
    'DV-795':   'S',     # THE REAL — regarded as one of her best Alice Japan titles

    # ═══ A+ — Very strong reception ═══
    'ONED-374': 'A+',    # SEX ON THE BEACH — early popular title, fun location shoot
    'ONED-571': 'A+',    # Fantasy Soapland — popular soapland theme, well-executed
    'SOE-059':  'A+',    # First Swallow — milestone first, fan favorite
    'SOE-250':  'A+',    # Let Me Suck to Last Drop — popular blowjob title
    'DV-1077':  'A+',    # Ultra High-Class Soapland — Alice exclusive soapland, well-received
    'SOE-649':  'A+',    # Full Eye Contact O-Face — popular eye contact theme
    'SOE-446':  'A+',    # H-Cup Teacher — popular teacher/busty combo
    'SOE-872':  'A+',    # Brothel No.1 Return Visit — sequel to popular series
    'DV-615':   'A+',    # Self Portrait #6 — notable Alice art-house series
    'SOE-123':  'A+',    # Violated Female Teacher — strong S1 violation entry
    'SOE-481':  'A+',    # Wild Hot Spring Edition — popular hot spring theme

    # ═══ A — Strong, above average ═══
    'ONED-396': 'A',     # My Very Own Busty Nursery — solid early title
    'ONED-424': 'A',     # Let's Have Sex at School — popular school theme
    'ONED-453': 'A',     # Extreme Titjob 3 — popular paizuri series
    'DV-755':   'A',     # Greatest Tits — great body showcase
    'DV-790':   'A',     # Fun Sex Ed — good concept, well-received
    'ONED-850': 'A',     # My Girlfriend Yuma — popular GFE
    'DV-879':   'A',     # Swimsuit Instructor — good reception
    'DV-1011':  'A',     # Sending Yuma to Your House — popular dispatch concept
    'SOE-234':  'A',     # Squirting Nurse — popular nurse/squirt combo
    'DV-1069':  'A',     # Nudist Older Sister — good reception
    'SOE-301':  'A',     # Crushed by Beautiful Ass — good reception
    'SOE-310':  'A',     # My Mom Can't Stay Away — popular taboo
    'SOE-609':  'A',     # My Affair Partner — solid married-woman theme
    'SOE-638':  'A',     # Slutty School Nurse — popular combo
    'DV-1158':  'A',     # Beautiful Braless Teacher — popular braless theme
    'SOE-402':  'A',     # Yuma Gets Turned On — solid GFE
    'SOE-893':  'A',     # Hot Spring Hostess — beautiful late-career title
    'SOE-805':  'A',     # Drenched in Juices — good oil/body showcase
    'DV-888':   'A',     # Together 4 Seconds After Meeting — popular concept
    'SOE-430':  'A',     # Incest: Sister's Overdeveloped Body — popular taboo theme
    'DV-1290':  'A',     # Deflowering Hot Spring — fun concept, good reception

    # ═══ A- — Good, solid above norm ═══
    'ONED-478': 'A-',    # Newlywed Life — decent early title
    'DV-652':   'A-',    # Obscene Model — standard gravure style
    'ONED-500': 'A-',    # Non-Stop Squirting — solid
    'DV-663':   'A-',    # Breast Chat — decent breast showcase
    'ONED-527': 'A-',    # Yuma All to Myself — good GFE
    'ONED-625': 'A-',    # Bang in Swimsuits — decent swimsuit
    'DV-718':   'A-',    # FUCK & ROLL — decent office lady theme
    'ONED-651': 'A-',    # Bang Bang Orgy 21 — decent orgy
    'DV-731':   'A-',    # Cheers You On with Sex — decent GFE
    'DV-868':   'A-',    # Endurance — decent concept
    'DV-918':   'A-',    # Hypnotic Pleasure — decent
    'DV-996':   'A-',    # Never-Ending Cleanup Blowjob — decent BJ title
    'SOE-265':  'A-',    # OL Arousal Seduction — decent co-star
    'SOE-287':  'A-',    # OL Arousal Violation — decent
    'DV-1096':  'A-',    # Until You Come 10 Times — decent concept
    'DV-1115':  'A-',    # Yuma Manages Ejaculations — decent femdom
    'DV-1053':  'A-',    # Never-Ending Titjob — decent paizuri
    'SOE-217':  'A-',    # Miracle Twin Sisters — fun concept
    'SOE-463':  'A-',    # Instant Penetration — decent concept
    'SOE-594':  'A-',    # 20 Costumes! — fun variety
    'DV-1267':  'A-',    # Kiss BJ Ball Licking etc — decent
    'SOE-579':  'A-',    # Braless Building Manager — decent
    'DV-1302':  'A-',    # Groper Bus Undercover — decent combo
    'SOE-904':  'A-',    # Give Me Your Sperm — decent late title
    'SOE-916':  'A-',    # Violated Before Husband — decent drama
    'SOE-929':  'A-',    # Young Wife Aphrodisiac — her last original S1 title
    'DV-1514':  'A-',    # Widow Fucked Husband to Death — her last Alice Japan title

    # ═══ B+ — Decent, competent ═══
    'DV-704':   'B+',    # Let's Play Doctor — standard nurse
    'ONED-675': 'B+',    # Extreme Piston 7 — standard hardcore
    'DV-745':   'B+',    # RODEO FUCK — standard
    'DV-772':   'B+',    # I Like It Rough — standard
    'ONED-721': 'B+',    # Infinite Climax — standard hardcore
    'ONED-747': 'B+',    # Screaming Big Magnum — standard
    'DV-806':   'B+',    # Screaming Convulsing — standard hardcore
    'ONED-787': 'B+',    # Ultimate Body Fuck — standard
    'DV-1384':  'B+',    # Slut Genius — decent femdom
    'ONED-869': 'B+',    # Incredible Facial — standard
    'ONED-904': 'B+',    # Unstoppable Incontinence — standard squirting
    'ONED-922': 'B+',    # Incest — standard taboo
    'ONED-972': 'B+',    # Deep Throat — decent
    'DV-948':   'B+',    # Younger Boy — standard
    'DV-962':   'B+',    # Making Yuma Flexible — decent
    'DV-973':   'B+',    # Premature Ejaculation Camp — decent
    'DV-986':   'B+',    # French Kiss Date — decent GFE
    'SOE-080':  'B+',    # Harder Thrust Harder — standard
    'DV-1022':  'B+',    # RUBBERS COSPLAY — standard cosplay
    'DV-1042':  'B+',    # Suddenly Appears — decent
    'DV-1061':  'B+',    # Extra-Thick Comparison — decent
    'DV-1126':  'B+',    # All-Groper Bus — standard
    'DV-1142':  'B+',    # Woman at the Gym — standard
    'DV-1150':  'B+',    # House into Soapland — decent
    'DV-1166':  'B+',    # English in Bed — fun concept
    'DV-1173':  'B+',    # I Got Yuma's Body — decent fantasy
    'SOE-409':  'B+',    # Horny Celebrity — decent
    'DV-1183':  'B+',    # Precocious Classmate — decent
    'DV-1230':  'B+',    # Rookie Bus Guide — decent
    'DV-1312':  'B+',    # Molested by Stepfather — standard
    'DV-1333':  'B+',    # Female Body Controller — decent fantasy
    'DV-1344':  'B+',    # Seven Shots Two Days — decent
    'DV-1354':  'B+',    # Sex After a Fight — decent
    'DV-1364':  'B+',    # Today's Sexual Relief Duty — decent
    'SOE-686':  'B+',    # A Lewd Body — decent body showcase
    'DV-1374':  'B+',    # Men's Squirting Massage — standard
    'SOE-716':  'B+',    # Bang Bang Grand Orgy — standard
    'DV-1394':  'B+',    # Sex Teacher — standard
    'DV-1404':  'B+',    # After-Five Pillow Sales — decent
    'SOE-753':  'B+',    # Premature Ejaculation BF — decent
    'DV-1414':  'B+',    # Cowgirl Only — standard
    'SOE-772':  'B+',    # VIP Dating Club — decent
    'DV-1423':  'B+',    # If I Were Yuma's Ghost — fun concept
    'DV-1433':  'B+',    # Girl Waiting to Be Picked Up — standard
    'SOE-848':  'B+',    # Squirting Full Course — standard
    'DV-1464':  'B+',    # Married Woman Sold to Soapland — decent
    'DV-1475':  'B+',    # High-Leg Instructor — standard
    'DV-1485':  'B+',    # Big Dick Taste Comparison — standard
    'DV-1494':  'B+',    # Rape Madness — standard
    'DV-1504':  'B+',    # Drains Every Drop — standard
    'ONED-765': 'B+',    # 20 Costumes (2007) — derivative of earlier cosplay entry

    # ═══ B — Average for a top actress ═══
    'DV-834':   'B',     # Amateur Actor Audition — standard audition format
    'DV-939':   'B',     # Amateur Actress Audition — standard audition format
    'DV-907':   'B',     # Produces a Virgin 02 — standard
    'DV-1203':  'B',     # AV Idol Photo Shoot — standard gravure rehash
    'DV-1212':  'B',     # The Nudist Woman — standard outdoor
    'DV-1221':  'B',     # Festival Girl — standard
    'DV-1443':  'B',     # Showing Lewd Entanglements 4hr — Alice Japan padding
    'DV-1453':  'B',     # Cleavage Teasing — late career standard

    # ═══ Compilations / reissues — no grade ═══
    # DV-678:    YUMA ASAMI THE BEST (compilation)
    # ONSD-059:  Collection 1 (compilation)
    # SOE-821:   2012 S1 8-Hour Special (compilation)
    # ONSD-702:  4-Hour Rape Special (compilation)
    # ONSD-745:  2013 S1 8-Hour Special (compilation)
    # PDV-158:   Complete Collection 2 (compilation/reissue)
    # SOE-624:   BD repackage of SOE-579 (reissue)
    # SOE-944:   BD repackage of SOE-929 (reissue)
    # MRJJ-013:  Remosaic (reissue)
    # AAJ-030:   Cross-Maker Best (compilation)
    # PDV-093:   4-Hour blowjob compilation (reissue)
    # PDV-094:   All 50 Works 16 Hours (reissue)
    # AVGL-109:  Rio & Yuma compilation (compilation — award went to SOE-061 original)
    # PDV-140:   Soapland compilation (reissue)
    # NDV-0263:  Pure & Hardcore (reissue)
    # NDV-0363:  Practice of Perversion (reissue)
    # NDV-0385:  Seriously Talk About Breasts (reissue)
    # NDV-0397:  Extreme Eye Contact (reissue)
    # KA-2242:   Pure & Hardcore repackage (reissue)
    # TSDV-41045: Love Para reissue

    # ═══ Special/other — B tier or no grade ═══
    'PGD-481':  'B',     # PREMIUM 5th Anniversary — multi-actress special, minor appearance
    'DV-598':   'B+',    # Costume Play Doll — undated Alice Japan, decent cosplay
    'MAD-034':  'B',     # Dark Restraint Drill Hell — obscure label, niche
    'NDTK-252': None,    # Gravure/IV title — not graded (not AV)
    'TJCA-10004': None,  # Gravure/IV title — not graded (not AV)
    'DMSM-6863': 'B',   # Kunoichi Legend — obscure, niche cosplay
    'DV-899':   'B+',    # Daisy Chain Sex — standard orgy
    'DV-1247':  'A-',    # Never-Ending Fellatio — decent BJ title
    'SOE-531':  'B+',    # Mega Facial Festival — standard facial
}

# Codes that should explicitly get no grade (compilations, reissues, gravure)
NO_GRADE = {
    'DV-678', 'ONSD-059', 'SOE-821', 'ONSD-702', 'ONSD-745', 'PDV-158',
    'SOE-624', 'SOE-944', 'MRJJ-013', 'AAJ-030', 'PDV-093', 'PDV-094',
    'AVGL-109', 'PDV-140', 'NDV-0263', 'NDV-0363', 'NDV-0385', 'NDV-0397',
    'KA-2242', 'TSDV-41045', 'NDTK-252', 'TJCA-10004',
}


def main():
    yaml_path = Path(__file__).resolve().parent.parent / 'actresses' / 'yuma_asami.yaml'

    with open(yaml_path) as f:
        raw = f.read()

    data = yaml.safe_load(raw)

    graded = 0
    skipped = 0
    ungraded_codes = []

    for entry in data.get('portfolio', []):
        code = entry.get('code', '')
        if code in NO_GRADE:
            # Explicitly no grade for compilations/reissues
            skipped += 1
            continue
        grade = GRADES.get(code)
        if grade is not None:
            entry['grade'] = grade
            graded += 1
        elif code not in NO_GRADE:
            ungraded_codes.append(code)

    # Print stats
    print(f"Graded:   {graded}")
    print(f"Skipped:  {skipped} (compilations/reissues/gravure)")
    print(f"Ungraded: {len(ungraded_codes)}")
    if ungraded_codes:
        print(f"  Missing: {', '.join(ungraded_codes)}")

    # Grade distribution
    from collections import Counter
    dist = Counter()
    for entry in data.get('portfolio', []):
        g = entry.get('grade')
        if g:
            dist[g] += 1
    print("\nGrade distribution:")
    for grade in ['SSS', 'SS', 'S', 'A+', 'A', 'A-', 'B+', 'B', 'B-', 'C+', 'C', 'C-']:
        if dist[grade]:
            print(f"  {grade:>3}: {dist[grade]}")

    # Custom YAML dumper that preserves key order and formatting
    class OrderedDumper(yaml.SafeDumper):
        pass

    def _dict_representer(dumper, data):
        return dumper.represent_mapping('tag:yaml.org,2002:map', data.items())

    OrderedDumper.add_representer(dict, _dict_representer)

    # Write back — insert grade after tags (or after last field before next entry)
    # Use line-by-line approach for precise placement
    lines = raw.split('\n')
    output_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        output_lines.append(line)

        # Detect start of a portfolio entry: "- code: XXX"
        if line.strip().startswith('- code:'):
            code = line.strip().split('- code:')[1].strip()

            # Find the end of this entry (next "- code:" or end of portfolio section)
            # We need to find where to insert grade — after tags block
            j = i + 1
            entry_end = j
            tags_end = None
            has_tags = False
            has_notes = False
            notes_line = None

            while j < len(lines):
                next_line = lines[j]
                stripped = next_line.strip()
                # Next entry or new top-level section
                if stripped.startswith('- code:') or (not stripped.startswith('-') and not stripped.startswith(' ') and stripped and ':' in stripped and not stripped.startswith('#')):
                    break
                if stripped == 'tags:':
                    has_tags = True
                if has_tags and stripped.startswith('- ') and tags_end is None:
                    pass  # still in tags
                if has_tags and not stripped.startswith('- ') and not stripped == 'tags:' and stripped:
                    if tags_end is None:
                        tags_end = j
                entry_end = j
                j += 1

            if tags_end is None:
                tags_end = j  # insert at end of entry

            # Now re-scan to find the actual last tag line
            # We'll insert grade right before the tags block
            # Actually, let's insert grade AFTER the last field before tags
            # or after date/notes and before tags

            # Simpler: scan through entry lines, find where tags: starts,
            # and insert grade: before tags:
            grade = GRADES.get(code)
            if grade is not None and code not in NO_GRADE:
                # Find the tags: line
                k = i + 1
                insert_pos = None
                while k < j:
                    if lines[k].strip() == 'tags:':
                        insert_pos = k
                        break
                    k += 1

                if insert_pos is not None:
                    # Insert grade line before tags
                    # But we need to continue processing — let's mark for later
                    pass

        i += 1

    # The line-by-line approach is getting complex. Let's use a simpler strategy:
    # Process the raw YAML and insert "  grade: X" before "  tags:" for each entry

    output_lines = []
    lines = raw.split('\n')
    i = 0
    current_code = None

    while i < len(lines):
        line = lines[i]

        # Track current code
        if line.strip().startswith('- code:'):
            current_code = line.strip().split('- code:')[1].strip()

        # If this is a tags: line and we have a grade for current code, insert grade before it
        if line.strip() == 'tags:' and current_code and current_code in GRADES and current_code not in NO_GRADE:
            grade = GRADES[current_code]
            # Determine indentation (same as tags line)
            indent = line[:len(line) - len(line.lstrip())]
            output_lines.append(f"{indent}grade: {grade}")

        output_lines.append(line)
        i += 1

    with open(yaml_path, 'w') as f:
        f.write('\n'.join(output_lines))

    print(f"\nYAML updated: {yaml_path}")


if __name__ == '__main__':
    main()
