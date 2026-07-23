#!/usr/bin/env python3
"""Harvest training methods per skill:

1. The plugin's curated methods.json imports as src=pack rows (these are
   the rates the planner actually uses — Luke-gated, never auto-changed).
2. Every skill's wiki training guide ("<Skill> training", following the
   redirect to the P2P guide) is section-parsed: each method heading with
   any parseable "N xp/h" figures in its body, its {{SCP}} level mentions
   as reqs, and the section's plink items as inputs. Wiki rows are ALL
   flagged prose-derived — xp/hr from prose is an estimate to review, not
   a curated rate.

The two live side by side per skill so the pack's gaps are visible: a
wiki method with no pack counterpart is a candidate the planner lacks.
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
LEVELS_HEADING = re.compile(r"^levels?\s+\d", re.I)


def import_pack(conn):
    d = kb.pack("methods")
    n = 0
    for entry in d["skills"]:
        skill = entry["skill"]
        for m in entry["methods"]:
            reqs = [m["req"]] if m.get("req") else []
            conn.execute(
                "INSERT OR REPLACE INTO training_methods(skill,method,xp_hr,reqs,"
                "inputs,outputs,notes,src,flags) VALUES(?,?,?,?,?,?,?,?,?)",
                (skill, m["name"], str(m.get("rate")),
                 json.dumps(reqs) if reqs else None, None, None,
                 f"from xp {m.get('startXp', 0)}; style {m.get('style', '?')}",
                 "pack:methods", None))
            n += 1
    print(f"pack methods: {n}")
    return n


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
    """Rate TABLES (Luke's table-parse pass): wikitables whose header names
    an xp/hour column — each row a method (first linked cell) with the rate
    from that column. Herblore/Fletching keep their rates this way."""
    n = 0
    for table in kb.wikitables(text):
        if not table or len(table) < 2:
            continue
        header = [kb.strip_markup(c).lower() for c in table[0]]
        rate_idx = next((i for i, h in enumerate(header)
                         if re.search(r"(xp|exp(erience)?).{0,12}(hour|hr|/h)", h)
                         or re.search(r"(hour|hr)\b.{0,12}(xp|exp)", h)), None)
        if rate_idx is None:
            continue
        for cells in table[1:]:
            if len(cells) <= rate_idx:
                continue
            name = None
            m = re.search(r"\{\{plinkt?\|([^|}]+)", cells[0]) \
                or re.search(r"\[\[([^\]|#]+)", cells[0])
            if m:
                name = m.group(1).strip()
            if not name:
                name = kb.strip_markup(cells[0])[:60]
            rate = kb.strip_markup(cells[rate_idx])
            if not name or not re.search(r"\d", rate):
                continue
            conn.execute(
                "INSERT OR REPLACE INTO training_methods(skill,method,xp_hr,reqs,"
                "inputs,outputs,notes,src,flags) VALUES(?,?,?,?,?,?,?,?,?)",
                (skill, name, rate[:60], None, None, None,
                 f"table-derived from {title}", "wiki:" + title,
                 "table-derived,rates-need-review"))
            n += 1
    return n


def harvest_wiki(conn):
    total = 0
    conn.execute("UPDATE gaps SET status='resolved' WHERE category='training'"
                 " AND status='open'")  # retire-then-rejudge (stale skill gaps
                 # survived earlier parser widenings)
    for skill in SKILLS:
        title, text = guide_page(skill)
        # Defence's own page is prose ("train via Melee/Ranged/Magic");
        # Hitpoints' is a disambiguation — both train through the combat
        # guides (Luke, 2026-07-22)
        if skill in ("Defence", "Hitpoints"):
            conn.execute(
                "INSERT OR REPLACE INTO training_methods(skill,method,xp_hr,reqs,"
                "inputs,outputs,notes,src,flags) VALUES(?,?,?,?,?,?,?,?,?)",
                (skill, "Combat training (shared)", None, None, None, None,
                 "Trains through any combat method — see the Attack/Strength"
                 " (melee), Ranged and Magic ladders"
                 + ("; Hitpoints accrues passively from all combat damage"
                    if skill == "Hitpoints" else ""),
                 "wiki:Pay-to-play Melee training", None))
            title, text = "Pay-to-play Melee training", \
                kb.page_text("Pay-to-play Melee training")
        if not text:
            kb.add_gap(conn, "training", skill, "guide-page",
                       f"no '{skill} training' wiki page resolved")
            continue
        # sections: == / === headings; a method section = heading + body
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
            # a rate-less section still counts as a METHOD when its heading
            # reads like one ("Levels 70-99: Slayer") — the combat guides
            # rarely state xp/hr in prose, but the method list is the point
            is_method = bool(rates) or LEVELS_HEADING.match(heading)
            if not is_method:
                continue
            levels = sorted({f"{m.group(1)} {m.group(2)}" for m in re.finditer(
                r"\{\{SCP\|([A-Za-z]+)\|(\d+)", body)})[:6]
            items = sorted({m.group(1) for m in re.finditer(
                r"\{\{plink\|([^|}]+)", body)})[:10]
            conn.execute(
                "INSERT OR REPLACE INTO training_methods(skill,method,xp_hr,reqs,"
                "inputs,outputs,notes,src,flags) VALUES(?,?,?,?,?,?,?,?,?)",
                (skill, heading, "; ".join(rates) or None,
                 json.dumps(levels) if levels else None,
                 json.dumps(items) if items else None, None,
                 f"prose-derived from {title}", "wiki:" + title,
                 "prose-derived,rates-need-review" if rates
                 else "prose-derived,rate-not-stated"))
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


def main():
    conn = kb.db()
    pack_n = import_pack(conn)
    wiki_n = harvest_wiki(conn)
    kb.set_progress(conn, "training-methods", None, "training_methods",
                    "pack:methods + wiki training guides",
                    f"{pack_n} curated pack methods (the planner's rates) +"
                    f" {wiki_n} prose-derived wiki sections flagged for review")
    conn.commit()
    conn.close()
    print("training methods total:", pack_n + wiki_n)


if __name__ == "__main__":
    main()
