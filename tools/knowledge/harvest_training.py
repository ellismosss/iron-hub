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


def harvest_wiki(conn):
    total = 0
    for skill in SKILLS:
        title, text = guide_page(skill)
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
        if n == 0:
            kb.add_gap(conn, "training", skill, "methods",
                       f"no method sections with parseable xp/hr on {title}"
                       " — needs manual extraction")
        print(f"{skill}: {n} wiki method sections")
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
