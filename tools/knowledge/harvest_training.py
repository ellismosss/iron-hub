#!/usr/bin/env python3
"""Harvest training methods per skill:

1. The plugin's curated methods.json imports as src=pack rows.
2. Every skill's wiki training guide (+ Ironman Guide/<Skill>) is parsed
   SECTION-AWARE: each wikitable is read under its enclosing heading, with
   HEADER-DRIVEN column mapping — a `level(s)` column, an xp/hour column,
   and a name column (course/potion/monster/...) when the table has one.
   Tables WITHOUT a name column (the common `level | xp/hour` progression
   shape) describe the SECTION's method at successive levels, so rows
   inherit the section heading as the method name. This is the v2 parser —
   v1 took the first cell as the name, which put level ranges ("1–20/30")
   in the method column and lost the actual names (Luke's methods-regen
   directive, 2026-07-23, exposed it).
3. Prose method sections (the combat guides state no xp/hr) keep their
   heading + any inline rates, flagged prose-derived.

Rates keep the wiki's own string verbatim in xp_hr AND a parsed integer in
rate (ranges → midpoint, k/m suffixes expanded, labeled variants take the
first figure). gen_methods.py consumes the parsed rows — wiki rates are
PREFERRED over the curated seed since Luke's 2026-07-23 call.
"""

import json
import re

import kb

SKILLS = [
    "Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic",
    "Runecraft", "Construction", "Hitpoints", "Agility", "Herblore",
    "Thieving", "Crafting", "Fletching", "Slayer", "Hunter", "Mining",
    "Smithing", "Fishing", "Cooking", "Firemaking", "Woodcutting",
    "Farming", "Sailing",
]

XP_HR = re.compile(r"([\d,]+(?:\.\d+)?\s*[km]?)\s*(?:–|-|to)?\s*([\d,]+(?:\.\d+)?\s*[km]?)?\s*"
                   r"(?:\[\[)?(?:xp|exp|experience)(?:\]\])?(?:\s*(?:per|/|an)\s*h(?:ou)?r)",
                   re.I)
# "Levels 30 to 50: Crabs" — the combat guides' method-section headings
LEVELS_HEADING = re.compile(r"^levels?\s+(\d+)", re.I)
# headers that name the thing being trained on
NAME_HEADERS = re.compile(
    r"^(course|potion|method|monster|creature|item|log|logs|fish|rune|runes|"
    r"activity|task|ore|rock|tree|trees|food|plank|bones?|glass|gem|spell|"
    r"herb|seed|crop|trap|lap|obstacle|route|boss|npc|location|result)s?\b", re.I)
RATE_HEADER = re.compile(r"(xp|exp(erience)?).{0,14}(hour|hr|/ ?h)|(hour|hr)\b.{0,12}(xp|exp)", re.I)
LEVEL_HEADER = re.compile(r"^levels?\b", re.I)


def clean_cell(cell):
    """Drop wikitable cell attributes ('colspan=2 |Potion' → 'Potion')."""
    m = re.match(r"^[^|\[\]{}]*\|(?!\|)", cell)
    if m:
        cell = cell[m.end():]
    return cell.strip()


def parse_rate(raw):
    """Wiki rate string → one integer xp/hr, or None. Ranges take the
    midpoint; k/m suffixes expand; labeled variants take the first figure."""
    s = kb.strip_markup(raw or "")
    m = re.search(r"([\d,]+(?:\.\d+)?)\s*([km])?"
                  r"(?:\s*(?:[–—−\-]|to)\s*([\d,]+(?:\.\d+)?)\s*([km])?)?", s, re.I)
    if not m:
        return None

    def num(value, suffix):
        x = float(value.replace(",", ""))
        suffix = (suffix or "").lower()
        return x * (1000 if suffix == "k" else 1_000_000 if suffix == "m" else 1)

    lo = num(m.group(1), m.group(2))
    hi = num(m.group(3), m.group(4)) if m.group(3) else lo
    if hi < lo:  # "70-80/90" style level junk that leaked — not a rate
        hi = lo
    return int(round((lo + hi) / 2))


def parse_level(raw):
    """First integer in a level(s) cell ('1–20/30' → 1), or None."""
    m = re.search(r"\d+", kb.strip_markup(raw or ""))
    return int(m.group(0)) if m else None


def heading_name(heading):
    """'Levels 30 to 50: Crabs' → 'Crabs'; the level prefix runs to the
    LAST colon ('Levels 45–55, 65–84/91/99: Blackjacking' — commas and
    slashes ride inside it); plain headings pass through."""
    m = re.match(r"^levels?\s+\d[^:]*:\s*(.*)$", heading, re.I)
    if m and m.group(1).strip():
        return m.group(1).strip()
    return heading.strip()


def sectioned_tables(text):
    """Yield (section_heading, table) for every wikitable, where the
    heading is the nearest heading above the table (page title context
    is the caller's fallback)."""
    pieces = re.split(r"\n(==+)\s*([^=\n]+?)\s*\1[^\n]*\n", "\n" + text)
    # pieces: [pre, marker, heading, body, marker, heading, body, ...]
    sections = [("", pieces[0])]
    for i in range(1, len(pieces) - 2, 3):
        sections.append((kb.strip_markup(pieces[i + 1]).strip(), pieces[i + 2]))
    for heading, body in sections:
        for table in kb.wikitables(body):
            yield heading, table


def store(conn, skill, method, level, xp_hr, rate, reqs, inputs, notes, src, flags,
          level_end=None):
    conn.execute(
        "INSERT OR REPLACE INTO training_methods(skill,method,level,level_end,"
        "xp_hr,rate,reqs,inputs,outputs,notes,src,flags)"
        " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        (skill, method, level or 0, level_end, xp_hr, rate, reqs, inputs, None,
         notes, src, flags))


def import_pack(conn):
    d = kb.pack("methods")
    levels = {kb_xp(l): l for l in range(1, 100)}
    n = 0
    for entry in d["skills"]:
        skill = entry["skill"]
        for m in entry["methods"]:
            reqs = [m["req"]] if m.get("req") else []
            start = m.get("startXp", 0)
            level = levels.get(start) or next(
                (l for l in range(99, 0, -1) if kb_xp(l) <= start), 1)
            store(conn, skill, m["name"], level, str(m.get("rate")),
                  int(m.get("rate") or 0) or None,
                  json.dumps(reqs) if reqs else None, None,
                  f"style {m.get('style', '?')}", "pack:methods", None)
            n += 1
    print(f"pack methods: {n}")
    return n


def kb_xp(level):
    total = 0
    for l in range(1, level):
        total += int(l + 300 * 2 ** (l / 7)) // 4
    return total


def guide_page(skill):
    data = kb.api({"action": "query", "titles": f"{skill} training",
                   "redirects": 1}, f"trainredir-{skill}.json")
    pages = data.get("query", {}).get("pages", [])
    title = pages[0]["title"] if pages and "missing" not in pages[0] else None
    if not title:
        return None, None
    return title, kb.page_text(title)


def ironman_guide(skill):
    """The wiki's Ironman Guide/<Skill> page, or None (Luke, 2026-07-22:
    also read the ironman guides for the gap skills)."""
    try:
        return f"Ironman Guide/{skill}", kb.page_text(f"Ironman Guide/{skill}")
    except SystemExit:
        return None, None


def table_methods(conn, skill, title, text):
    """The v2 table pass: header-driven columns under section context."""
    n = 0
    for heading, table in sectioned_tables(text):
        if len(table) < 2:
            continue
        header = [kb.strip_markup(clean_cell(c)).lower() for c in table[0]]
        rate_cols = [(i, h) for i, h in enumerate(header) if RATE_HEADER.search(h)]
        if not rate_cols:
            continue
        # prefer the plainest xp/h column (shortest qualifier), ties → first
        rate_idx = min(rate_cols, key=lambda ih: (len(ih[1]), ih[0]))[0]
        level_idx = next((i for i, h in enumerate(header) if LEVEL_HEADER.match(h)), None)
        name_idx = next((i for i, h in enumerate(header)
                         if NAME_HEADERS.match(h) and i != rate_idx), None)
        if level_idx is None and name_idx is None:
            continue  # no way to anchor rows — not a training ladder table
        section = heading_name(heading) if heading else None
        for cells in table[1:]:
            cells = [clean_cell(c) for c in cells]
            if len(cells) <= rate_idx:
                continue
            rate_raw = kb.strip_markup(cells[rate_idx]).strip()
            rate = parse_rate(rate_raw)
            if not rate:
                continue
            level = parse_level(cells[level_idx]) \
                if level_idx is not None and len(cells) > level_idx else None
            name = None
            if name_idx is not None and len(cells) > name_idx:
                m = re.search(r"\{\{plinkt?\|([^|}]+)", cells[name_idx]) \
                    or re.search(r"\[\[([^\]|#]+)", cells[name_idx])
                name = (m.group(1) if m else kb.strip_markup(cells[name_idx])).strip()[:60]
            if not name or re.fullmatch(r"[\d\s\-–/+.]*", name):
                name = section  # `level | xp/hour` shape — the section IS the method
            if not name or re.fullmatch(r"[\d\s\-–/+.]*", name) or len(name) < 3:
                continue
            store(conn, skill, name, level, rate_raw[:80], rate, None, None,
                  f"table-derived from {title}"
                  + (f" § {heading}" if heading else ""),
                  "wiki:" + title, "table-derived")
            n += 1
    return n


def harvest_wiki(conn):
    total = 0
    conn.execute("UPDATE gaps SET status='resolved' WHERE category='training'"
                 " AND status='open'")  # retire-then-rejudge
    for skill in SKILLS:
        title, text = guide_page(skill)
        # Defence's own page is prose ("train via Melee/Ranged/Magic");
        # Hitpoints' is a disambiguation — both train through the combat
        # guides (Luke, 2026-07-22)
        if skill in ("Defence", "Hitpoints"):
            store(conn, skill, "Combat training (shared)", None, None, None,
                  None, None,
                  "Trains through any combat method — see the Attack/Strength"
                  " (melee), Ranged and Magic ladders"
                  + ("; Hitpoints accrues passively from all combat damage"
                     if skill == "Hitpoints" else ""),
                  "wiki:Pay-to-play Melee training", None)
            title, text = "Pay-to-play Melee training", \
                kb.page_text("Pay-to-play Melee training")
        if not text:
            kb.add_gap(conn, "training", skill, "guide-page",
                       f"no '{skill} training' wiki page resolved")
            continue
        # prose method sections (headings + any inline rates)
        sections = re.split(r"\n(==+)([^=\n]+)\1[^\n]*\n", "\n" + text)
        n = 0
        for i in range(1, len(sections) - 2, 3):
            heading = kb.strip_markup(sections[i + 1]).strip()
            body = sections[i + 2]
            if not heading or heading.lower() in (
                    "contents", "see also", "references", "trivia",
                    "useful equipment", "equipment", "quests", "temporary boosts"):
                continue
            rates = ["{}{}".format(m.group(1).strip(),
                                   "-" + m.group(2).strip() if m.group(2) else "")
                     for m in XP_HR.finditer(body)][:4]
            level_m = LEVELS_HEADING.match(heading)
            # the heading's level RANGE ("Levels 25-39: Fruit stalls") — the
            # range END lets the generator judge whether the section's rate
            # honestly pairs with its start level (narrow = yes, "1-99" = no)
            level_end = None
            if level_m:
                prefix = heading.split(":", 1)[0]
                nums = [int(n) for n in re.findall(r"\d+", prefix)]
                if len(nums) > 1:
                    level_end = max(nums)
            is_method = bool(rates) or level_m
            if not is_method:
                continue
            levels = sorted({f"{m.group(1)} {m.group(2)}" for m in re.finditer(
                r"\{\{SCP\|([A-Za-z]+)\|(\d+)", body)})[:6]
            items = sorted({m.group(1) for m in re.finditer(
                r"\{\{plink\|([^|}]+)", body)})[:10]
            joined = "; ".join(rates) or None
            store(conn, skill, heading_name(heading),
                  int(level_m.group(1)) if level_m else None,
                  joined, parse_rate(rates[0]) if rates else None,
                  json.dumps(levels) if levels else None,
                  json.dumps(items) if items else None,
                  f"prose-derived from {title}", "wiki:" + title,
                  "prose-derived,rates-need-review" if rates
                  else "prose-derived,rate-not-stated",
                  level_end=level_end)
            n += 1
            total += 1
        extra = table_methods(conn, skill, title, text)
        im_title, im_text = ironman_guide(skill)
        if im_text:
            extra += table_methods(conn, skill, im_title, im_text)
        n += extra
        total += extra
        if n == 0:
            kb.add_gap(conn, "training", skill, "methods",
                       f"no method sections or rate tables parsed on {title}"
                       " — needs manual extraction")
        print(f"{skill}: {n} wiki method rows")
    return total


def migrate(conn):
    """The v2 schema added level/rate columns and a per-tier PK — an old
    table is dropped whole (this harvester repopulates everything)."""
    cols = {r[1] for r in conn.execute("PRAGMA table_info(training_methods)")}
    if "rate" not in cols or "level_end" not in cols:
        conn.execute("DROP TABLE training_methods")
        conn.executescript(kb.SCHEMA)
        print("training_methods migrated to the v2 (level/rate) schema")


def main():
    conn = kb.db()
    migrate(conn)
    conn.execute("DELETE FROM training_methods")  # full repopulate, no stale rows
    pack_n = import_pack(conn)
    wiki_n = harvest_wiki(conn)
    kb.set_progress(conn, "training-methods", None, "training_methods",
                    "pack:methods + wiki training guides (v2 table parser)",
                    f"{pack_n} curated pack methods + {wiki_n} wiki rows"
                    " (header-mapped tables w/ parsed level+rate; prose"
                    " sections flagged)")
    conn.commit()
    conn.close()
    print("training methods total:", pack_n + wiki_n)


if __name__ == "__main__":
    main()
