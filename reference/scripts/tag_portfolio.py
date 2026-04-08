#!/usr/bin/env python3
"""Tag portfolio entries in an actress YAML file based on title keywords, labels, and codes."""

import yaml
import re
import sys
from datetime import date

# ── Keyword → tag mappings ────────────────────────────────────────────────────
# Checked against Japanese original and English translation.
# Order matters: more specific patterns should come before generic ones.

JP_KEYWORD_TAGS = [
    # Setting
    (r'ソープ', 'soapland'),
    (r'風俗', 'brothel'),
    (r'エステ', 'massage-parlor'),
    (r'温泉|湯けむり', 'hot-spring'),
    (r'お風呂|入浴', 'hot-spring'),
    (r'学校', 'school'),
    (r'ジム', 'gym'),
    (r'スポーツ', 'gym'),
    (r'BEACH|ビーチ|南の島', 'outdoors'),
    (r'派遣します|家に', 'home-visit'),

    # Role / Character
    (r'家庭教師', 'teacher'),
    (r'女教師', 'teacher'),
    (r'先生(?!.*ゆま)', 'teacher'),  # avoid matching "ゆまチン先生" as generic teacher
    (r'保健医', 'nurse'),
    (r'ナース|看護', 'nurse'),
    (r'OLびんびん|OL', 'office-lady'),
    (r'捜査官', 'secret-agent'),
    (r'人妻|若妻', 'married-woman'),
    (r'未亡人', 'widow'),
    (r'ママ(?!す)', 'mother'),  # avoid ママス etc
    (r'(?:お)?姉(?:さん|ちゃん)?(?!.*妹)', 'sister'),
    (r'妹', 'sister'),
    (r'痴女', 'femdom'),
    (r'くノ一|クノイチ|くのいち', 'kunoichi'),
    (r'管理人', 'hostess'),
    (r'バスガイド', 'bus-guide'),
    (r'インストラクター', 'teacher'),
    (r'女将', 'hostess'),
    (r'メイド', 'maid'),
    (r'秘書', 'office-lady'),

    # Theme / Scenario
    (r'コスプレ|コスチューム|\d+コス', 'cosplay'),
    (r'不倫', 'affair'),
    (r'近親相姦', 'taboo-family'),
    (r'義父|継父|二番目の父', 'taboo-family'),
    (r'童貞|筆おろし', 'virgin-play'),
    (r'ナンパ', 'pickup'),
    (r'痴漢', 'groping'),
    (r'レイプ', 'violation'),
    (r'犯され|犯された', 'violation'),
    (r'凌辱', 'violation'),
    (r'監禁|拘束', 'confinement'),
    (r'催眠', 'hypnosis'),
    (r'媚薬', 'aphrodisiac'),
    (r'射精.*管理|管理.*射精', 'cum-control'),

    # Act / Focus
    (r'パイズリ|激パイ', 'paizuri'),
    (r'ディープスロート', 'deepthroat'),
    (r'フェラ|フェラチオ|シャブ', 'blowjob'),
    (r'お掃除フェラ|大掃除フェラ', 'blowjob'),
    (r'潮吹き', 'squirting'),
    (r'乱交', 'orgy'),
    (r'顔射', 'facial'),
    (r'騎乗位', 'cowgirl'),
    (r'ごっくん|精飲', 'swallow'),
    (r'中出し|体内.*射精|射精して', 'creampie'),
    (r'アナル舐め', 'rimming'),
    (r'オイル|ローション', 'oil'),

    # Body / Aesthetic
    (r'水着|競泳', 'swimsuit'),
    (r'ハイレグ|レオタード', 'leotard'),
    (r'ノーブラ', 'braless'),
    (r'カメラ目線', 'eye-contact'),
    (r'おっぱい|ボイン|Hカップ|巨乳|デカパイ|乳(?!話)', 'busty'),
    (r'尻|ヒップ|美尻', 'ass-focus'),

    # Format (from JP title)
    (r'BEST|ベスト|大全集|COLLECTION|コレクション', 'compilation'),
    (r'スペシャル|SPECIAL', 'long-format'),
    (r'制服(?!.*人形)', 'cosplay'),
]

EN_KEYWORD_TAGS = [
    # Setting
    (r'Soapland', 'soapland'),
    (r'Brothel', 'brothel'),
    (r'Massage Parlor|Massage\b', 'massage-parlor'),
    (r'Hot Spring', 'hot-spring'),
    (r'School(?! Trip)', 'school'),
    (r'Gym\b', 'gym'),
    (r'Beach|Island', 'outdoors'),

    # Role / Character
    (r'Teacher|Tutor|Instructor', 'teacher'),
    (r'Nurse', 'nurse'),
    (r'Office Lady', 'office-lady'),
    (r'Secret Agent|Investigator|Undercover', 'secret-agent'),
    (r'Married Woman|Wife|Newlywed', 'married-woman'),
    (r'Widow', 'widow'),
    (r'\bMom\b|Mother', 'mother'),
    (r'Sister', 'sister'),
    (r'Slut(?:ty)?|Femdom|Dominant', 'femdom'),
    (r'Kunoichi|Ninja', 'kunoichi'),
    (r'Bus Guide', 'bus-guide'),
    (r'Hostess|Proprietress', 'hostess'),
    (r'Manager|Building Manager', 'hostess'),

    # Theme / Scenario
    (r'Cosplay|Costume', 'cosplay'),
    (r'Affair|Infidel', 'affair'),
    (r'Incest', 'taboo-family'),
    (r'Stepfather|Step.?father', 'taboo-family'),
    (r'Virgin', 'virgin-play'),
    (r'Pick(?:ed)? Up|Pickup', 'pickup'),
    (r'Grop(?:er|ing)', 'groping'),
    (r'Rape|Violated|Violation', 'violation'),
    (r'Confin|Captiv|Restrain', 'confinement'),
    (r'Hypnoti', 'hypnosis'),
    (r'Aphrodisiac', 'aphrodisiac'),

    # Act / Focus
    (r'Titjob|Paizuri', 'paizuri'),
    (r'Deep Throat', 'deepthroat'),
    (r'Blowjob|Fellatio|Oral|Suck(?:ing)?', 'blowjob'),
    (r'Squirt', 'squirting'),
    (r'Orgy|Gangbang|Group', 'orgy'),
    (r'Facial', 'facial'),
    (r'Cowgirl', 'cowgirl'),
    (r'Swallow', 'swallow'),
    (r'Creampie|Come Inside|Inside (?:Me|Her)', 'creampie'),
    (r'Rimming|Rim Job', 'rimming'),

    # Body / Aesthetic
    (r'Swimsuit|Bikini', 'swimsuit'),
    (r'Leotard|High.?Leg', 'leotard'),
    (r'Braless', 'braless'),
    (r'Eye Contact', 'eye-contact'),
    (r'Bust|Breast|Tit|Boob|H.Cup|Busty', 'busty'),
    (r'\bAss\b|Butt', 'ass-focus'),

    # Format
    (r'\bCompilation\b|\bComplete Collection\b', 'compilation'),
]

# ── Code-based tags ───────────────────────────────────────────────────────────
# Product code prefixes that indicate format
COMPILATION_CODES = {'ONSD', 'OFJE', 'PDV', 'AAJ', 'AVGL'}
REISSUE_CODES = {'PDV', 'NDV', 'KA', 'MRJJ'}

# ── Label-based tags ─────────────────────────────────────────────────────────
LABEL_TAGS = {
    'E-BODY': ['busty'],
    'Attackers': ['violation', 'confinement'],
}

# ── Manual overrides for ambiguous/vague titles ──────────────────────────────
# Titles where the name alone doesn't reveal content
MANUAL_TAGS = {
    'ONED-292': ['debut'],                      # first S1 title
    'DV-563': ['ass-focus'],                     # 女尻 = Female Ass
    'DV-795': ['eye-contact'],                    # THE REAL - intimate documentary style
    'DV-868': ['cum-control'],                   # 我慢 = Endurance - edging/denial
    'DV-615': ['eye-contact'],                   # Self Portrait = POV style
    'DV-718': ['busty'],                           # FUCK & ROLL - aggressive sex showcase
    'DV-939': ['co-star'],                       # amateur actress audition
    'DV-834': ['co-star'],                       # amateur actor audition
    'DV-907': ['virgin-play'],                   # produces a virgin
    'DV-918': ['hypnosis'],                      # 催眠快楽
    'DV-888': ['home-visit'],                     # Together 4 seconds - instant encounter
    'DV-899': ['orgy'],                          # daisy chain
    'DV-1022': ['cosplay'],                      # RUBBERS COSPLAY
    'DV-1061': ['blowjob'],                       # extra thick comparison
    'DV-1069': ['sister', 'taboo-family'],       # nudist older sister
    'DV-1096': ['cum-control'],                  # 10 ejaculations
    'DV-1115': ['cum-control', 'femdom'],         # manages your ejaculations
    'DV-1126': ['groping'],                      # all-groper bus
    'DV-1142': ['gym'],                          # woman at the gym
    'DV-1158': ['teacher', 'braless'],           # braless teacher
    'DV-1166': ['teacher'],                      # English in bed
    'DV-1173': ['fantasy-scenario'],             # got Yuma's body
    'DV-1183': ['school'],                       # precocious classmate
    'DV-1203': ['gravure'],                       # AV idol photo shoot
    'DV-1212': ['outdoors'],                     # nudist woman
    'DV-1221': ['outdoors'],                     # festival girl
    'DV-1230': ['bus-guide'],                    # bus guide school trip
    'DV-1267': ['blowjob', 'rimming'],           # kiss, BJ, ball, BJ, rim
    'DV-1302': ['groping', 'secret-agent'],      # groper bus undercover
    'DV-1312': ['taboo-family', 'violation'],    # molested by stepfather
    'DV-1333': ['fantasy-scenario'],             # body controller
    'DV-1344': ['hot-spring'],                   # hot spring trip
    'DV-1354': ['married-woman'],                 # sex after a fight - couple theme
    'DV-1364': ['femdom'],                       # sexual relief duty - servicing
    'DV-1374': ['massage-parlor', 'squirting'],  # men's squirting massage
    'DV-1384': ['femdom'],                       # 痴女の天才 = slut genius
    'DV-1394': ['teacher'],                      # sex teacher
    'DV-1404': ['office-lady'],                  # pillow sales dept
    'DV-1414': ['cowgirl'],                      # cowgirl only
    'DV-1423': ['fantasy-scenario', 'busty'],    # ghost groping
    'DV-1433': ['pickup'],                       # waiting to be picked up
    'DV-1443': ['long-format'],                  # 4-hour special
    'DV-1453': ['busty'],                        # cleavage teasing
    'DV-1464': ['married-woman', 'soapland'],    # wife sold to soapland
    'DV-1475': ['leotard'],                      # high-leg instructor
    'DV-1485': [],                               # big dick comparison
    'DV-1494': ['violation'],                    # rape madness
    'DV-1504': ['cum-control'],                  # drains every drop
    'DV-1514': ['widow', 'married-woman'],       # widow
    'DV-598': ['cosplay'],                       # costume play doll
    'NDV-0263': [],                              # pure & hardcore
    'NDV-0363': [],                              # practice of perversion
    'NDV-0385': ['busty'],                       # talk about breasts
    'NDV-0397': ['eye-contact'],                 # extreme eye contact
    'KA-2242': ['reissue'],                      # repackage of NDV-0263
    'DMSM-6863': ['kunoichi', 'cosplay'],        # kunoichi legend
    'MRJJ-013': ['reissue'],                     # complete remosaic
    'MAD-034': ['confinement', 'bondage'],        # dark restraint confinement
    'NDTK-252': ['gravure'],                     # gravure/IV
    'TJCA-10004': ['gravure', 'married-woman'],  # beautiful wife IV
    'TSDV-41045': ['gravure', 'reissue'],        # love para reissue
    'PDV-158': ['compilation', 'virgin-play'],   # vs virgins & amateurs
    'SOE-061': ['co-star'],                      # Rio and Yuma
    'SOE-265': ['office-lady'],                  # OL arousal - seduction
    'SOE-287': ['office-lady', 'violation'],     # OL arousal - violation
    'SOE-280': [],                               # body conscious style
    'SOE-301': ['ass-focus'],                    # crushed by beautiful ass
    'SOE-310': ['mother', 'taboo-family'],       # mom who can't stay away
    'SOE-326': ['creampie'],                     # come inside me
    'SOE-371': [],                               # kiss while inside
    'SOE-402': [],                               # suddenly turned on
    'SOE-409': [],                               # horny celebrity
    'SOE-430': ['taboo-family', 'sister'],       # sister's overdeveloped body
    'SOE-446': ['teacher', 'busty'],             # H-cup tutor
    'SOE-463': ['fantasy-scenario'],             # sex anywhere when lamp rings
    'SOE-481': ['hot-spring'],                   # hot spring edition
    'SOE-515': ['secret-agent', 'confinement'],  # female agent captive
    'SOE-531': ['facial'],                       # mega facial festival
    'SOE-579': ['braless', 'hostess'],           # braless building manager
    'SOE-624': ['braless', 'hostess', 'reissue'],# BD repackage
    'SOE-594': ['cosplay'],                      # 20 costumes
    'SOE-609': ['affair', 'married-woman'],      # affair partner
    'SOE-638': ['nurse', 'femdom'],              # slutty school nurse
    'SOE-649': ['eye-contact'],                  # full eye contact
    'SOE-686': [],                               # lewd body
    'SOE-716': ['orgy'],                         # grand orgy
    'SOE-735': ['brothel', 'long-format'],       # brothel 4hr special
    'SOE-753': [],                               # premature ejaculation BF
    'SOE-772': ['brothel'],                      # secret dating club
    'SOE-805': [],                               # drenched in juices
    'SOE-821': ['compilation', 'long-format'],   # 8-hour special
    'SOE-836': ['secret-agent'],                 # female secret agent 2
    'SOE-848': ['squirting'],                    # squirting full course
    'SOE-872': ['brothel'],                      # brothel return visit
    'SOE-893': ['hot-spring', 'hostess'],        # hot spring hostess
    'SOE-904': [],                               # give me your sperm
    'SOE-916': ['violation', 'hypnosis', 'married-woman'],  # hypnotic rape
    'SOE-929': ['aphrodisiac', 'married-woman'], # wife drowning in aphrodisiac
    'SOE-944': ['aphrodisiac', 'married-woman', 'reissue'],  # BD repackage
    'SSPD-086': ['violation', 'married-woman'],  # violated before husband
    'EBOD-238': ['busty'],                       # SSS-BODY
    'PGD-481': [],                               # PREMIUM anniversary
    'ONSD-059': ['compilation'],                 # collection 1
    'ONSD-702': ['compilation', 'violation', 'long-format'],  # 4hr rape special
    'ONSD-745': ['compilation', 'long-format'],  # 8hr special
    'AAJ-030': ['compilation', 'long-format'],   # cross-maker 8hrs
    'PDV-093': ['blowjob', 'long-format'],       # 4hr blowjob reissue
    'PDV-094': ['compilation', 'long-format'],   # all 50 works 16hrs
    'PDV-140': ['soapland', 'long-format'],      # soapland 4hrs
    'AVGL-109': ['compilation', 'co-star', 'long-format'],  # Rio & Yuma 4hrs

    'NDV-0263': ['busty'],                        # pure & hardcore - showcase
    'NDV-0363': ['femdom'],                      # practice of perversion

    # Remaining untagged titles - content inferred from title/context
    'DV-731': ['girlfriend-experience'],         # cheers you on with sex
    'DV-806': ['hardcore'],                      # screaming, convulsing, tears
    'DV-948': ['virgin-play'],                   # younger boy
    'DV-962': ['athletic'],                      # making yuma flexible
    'DV-973': ['cum-control'],                   # premature ejac training camp
    'DV-986': ['girlfriend-experience', 'outdoors'],  # french kiss date
    'DV-1042': ['home-visit'],                   # suddenly yuma appears before you
    'DV-1485': ['blowjob'],                      # big dick taste comparison
    'ONED-787': ['hardcore'],                    # ultimate body fuck
    'ONED-850': ['girlfriend-experience'],       # my girlfriend yuma
    'SOE-080': ['hardcore'],                     # harder thrust harder
    'SOE-166': ['busty'],                        # golden ratio body
    'SOE-280': ['busty'],                        # body conscious style
    'SOE-371': ['creampie', 'girlfriend-experience'],  # kiss while inside
    'SOE-402': ['girlfriend-experience'],        # suddenly gets turned on
    'SOE-409': ['femdom'],                       # horny celebrity with too much lust
    'SOE-686': ['busty'],                        # lewd body
    'SOE-753': ['cum-control'],                  # premature ejaculation boyfriend
    'SOE-805': ['oil'],                          # drenched in juices
    'SOE-904': ['cum-control'],                  # give me your sperm
    'PGD-481': ['co-star', 'long-format'],       # PREMIUM 5th anniversary special

    # S1 ギリギリモザイク series - content from title
    'ONED-333': ['creampie'],                    # thick sticky sex - intimate
    'ONED-352': ['cosplay'],                     # 6 costumes
    'ONED-374': ['outdoors', 'swimsuit'],        # sex on the beach
    'ONED-396': ['busty'],                       # busty nursery
    'ONED-424': ['school'],                      # sex at school
    'ONED-453': ['paizuri'],                     # extreme titjob
    'ONED-478': ['married-woman'],               # newlywed life
    'ONED-500': ['squirting'],                   # non-stop squirting
    'ONED-527': ['girlfriend-experience'],         # yuma all to myself
    'ONED-571': ['soapland'],                    # fantasy soapland
    'ONED-625': ['swimsuit'],                    # bang in swimsuits
    'ONED-651': ['orgy'],                        # orgy 21
    'ONED-675': ['hardcore'],                     # extreme piston - intense action
    'ONED-721': ['hardcore'],                    # infinite climax
    'ONED-747': ['hardcore'],                    # big magnum fuck
    'ONED-765': ['cosplay'],                     # 20 costumes
    'ONED-787': ['hardcore'],                     # ultimate body fuck
    'ONED-812': ['squirting'],                   # squirting hyper
    'ONED-850': ['girlfriend-experience'],        # my girlfriend yuma
    'ONED-869': ['facial'],                      # incredible facial
    'ONED-904': ['squirting'],                   # unstoppable incontinence
    'ONED-922': ['taboo-family'],                # incest
    'ONED-972': ['deepthroat'],                  # deep throat
    'DV-678': ['compilation'],                   # THE BEST
    'DV-704': ['nurse'],                         # let's play doctor
    'DV-652': ['gravure'],                        # obscene model - modeling theme
    'DV-663': ['busty'],                         # breast chat
    'DV-745': ['cowgirl'],                       # reverse cowgirl
    'DV-755': ['busty'],                         # greatest tits
    'DV-772': ['violation'],                     # I like it rough
    'DV-790': ['teacher'],                       # teacher yuma
    'DV-806': ['hardcore'],                       # screaming convulsing
    'DV-879': ['swimsuit'],                      # swimsuit instructor
    'DV-948': ['virgin-play'],                   # younger boy
    'DV-962': ['athletic'],                      # making yuma flexible
    'DV-973': ['cum-control'],                   # premature ejac training
    'DV-986': ['girlfriend-experience', 'outdoors'],  # french kiss date
    'DV-996': ['blowjob'],                       # never-ending cleanup BJ
    'DV-1011': ['home-visit'],                   # sending yuma to your house
    'DV-1042': ['home-visit'],                   # suddenly yuma appears
    'DV-1053': ['paizuri'],                      # never-ending titjob
    'DV-1077': ['soapland'],                     # ultra high-class soapland
    'DV-1247': ['blowjob'],                      # never-ending fellatio
    'DV-1290': ['hot-spring', 'virgin-play'],    # deflowering hot spring
    'SOE-022': ['femdom'],                       # beautiful slut's kisses
    'SOE-059': ['swallow'],                      # first cum drinking
    'SOE-080': ['hardcore'],                      # thrust harder
    'SOE-123': ['violation', 'teacher'],         # violated female teacher
    'SOE-166': ['busty'],                         # golden ratio body
    'SOE-217': ['fantasy-scenario'],             # miracle twin sisters
    'SOE-234': ['nurse', 'squirting'],           # squirting nurse
    'SOE-250': ['blowjob'],                      # suck to last drop
}


def extract_code_prefix(code):
    """Extract the alphabetic prefix from a product code."""
    m = re.match(r'^([A-Z]+)', code)
    return m.group(1) if m else ''


def get_tags_for_entry(entry):
    """Determine tags for a single portfolio entry."""
    tags = set()
    code = entry.get('code', '')
    title_jp = entry.get('title', {}).get('original', '') or ''
    title_en = entry.get('title', {}).get('english', '') or ''
    label = entry.get('label', '') or ''
    notes = entry.get('notes', '') or ''

    # 1. Manual overrides (additive, not exclusive)
    if code in MANUAL_TAGS:
        tags.update(MANUAL_TAGS[code])

    # 2. Keyword matching on Japanese title
    for pattern, tag in JP_KEYWORD_TAGS:
        if re.search(pattern, title_jp):
            tags.add(tag)

    # 3. Keyword matching on English title
    for pattern, tag in EN_KEYWORD_TAGS:
        if re.search(pattern, title_en, re.IGNORECASE):
            tags.add(tag)

    # 4. Code-based tags
    prefix = extract_code_prefix(code)
    if prefix in COMPILATION_CODES:
        tags.add('compilation')
    if prefix in REISSUE_CODES:
        tags.add('reissue')

    # 5. Label-based tags
    for label_key, label_tags in LABEL_TAGS.items():
        if label_key in label:
            tags.update(label_tags)

    # 6. Notes-based tags
    if notes:
        if 'Compilation' in notes:
            tags.add('compilation')
        if 'Co-starring' in notes or 'co-star' in notes.lower() or 'multi-actress' in notes.lower():
            tags.add('co-star')
        if 'BD/' in notes or 'repackage' in notes.lower() or 'Repackage' in notes:
            tags.add('reissue')
        if 'reissue' in label.lower():
            tags.add('reissue')
        if 'Gravure' in notes or 'IV' in notes:
            tags.add('gravure')
        if 'compilation' in label.lower():
            tags.add('compilation')

    # Also check label for reissue/compilation hints
    if 'reissue' in label.lower() or 'リモザイク' in label:
        tags.add('reissue')
    if 'compilation' in label.lower():
        tags.add('compilation')

    # Sort for consistency
    return sorted(tags)


def custom_representer(dumper, data):
    """Use block style for long strings, flow style for short ones."""
    if len(data) > 100:
        return dumper.represent_scalar('tag:yaml.org,2002:str', data, style='"')
    if '\n' in data:
        return dumper.represent_scalar('tag:yaml.org,2002:str', data, style='"')
    return dumper.represent_scalar('tag:yaml.org,2002:str', data)


def date_representer(dumper, data):
    return dumper.represent_scalar('tag:yaml.org,2002:str', data.isoformat())


def none_representer(dumper, data):
    return dumper.represent_scalar('tag:yaml.org,2002:null', 'null')


class CustomDumper(yaml.SafeDumper):
    pass

CustomDumper.add_representer(str, custom_representer)
CustomDumper.add_representer(date, date_representer)
CustomDumper.add_representer(type(None), none_representer)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 tag_portfolio.py <actress.yaml>")
        sys.exit(1)

    filepath = sys.argv[1]

    with open(filepath, 'r') as f:
        data = yaml.safe_load(f)

    portfolio = data.get('portfolio', [])
    stats = {'tagged': 0, 'untagged': 0, 'tag_counts': {}}

    for entry in portfolio:
        tags = get_tags_for_entry(entry)
        entry['tags'] = tags if tags else []

        if tags:
            stats['tagged'] += 1
            for t in tags:
                stats['tag_counts'][t] = stats['tag_counts'].get(t, 0) + 1
        else:
            stats['untagged'] += 1

    # Ensure key order: profile -> portfolio -> meta
    ordered = {}
    for key in ['profile', 'portfolio', 'meta']:
        if key in data:
            ordered[key] = data[key]
    for key in data:
        if key not in ordered:
            ordered[key] = data[key]

    with open(filepath, 'w') as f:
        yaml.dump(ordered, f, Dumper=CustomDumper, allow_unicode=True,
                  default_flow_style=False, sort_keys=False, width=200)

    # Print stats
    print(f"\nTagging complete: {stats['tagged']} tagged, {stats['untagged']} untagged out of {len(portfolio)} titles")
    print(f"\nTag frequency:")
    for tag, count in sorted(stats['tag_counts'].items(), key=lambda x: -x[1]):
        print(f"  {tag}: {count}")

    # List untagged
    untagged = [e['code'] for e in portfolio if not e.get('tags')]
    if untagged:
        print(f"\nUntagged titles ({len(untagged)}):")
        for code in untagged:
            entry = next(e for e in portfolio if e['code'] == code)
            print(f"  {code}: {entry['title'].get('english', '?')}")


if __name__ == '__main__':
    main()
