#!/usr/bin/env python3
"""Harvest EVERY temporary skill boost: the wiki's Temporary_skill_boost
per-skill subpages (the transcluded {{/Agility}}..{{/Woodcutting}} tables:
boost, level increase, visibility, circumstances) plus the invisible-boost
list on the main page. Replaces the pack's known-partial 10 sources."""

import json
import re

import kb

SKILLS = [
    "Agility", "Attack", "Construction", "Cooking", "Crafting", "Defence",
    "Farming", "Firemaking", "Fishing", "Fletching", "Herblore", "Hitpoints",
    "Hunter", "Magic", "Mining", "Prayer", "Ranged", "Runecraft", "Sailing",
    "Slayer", "Smithing", "Strength", "Thieving", "Woodcutting",
]


def boost_name(cell: str) -> str:
    m = re.search(r"\{\{plinkt?\|([^|}]+)", cell)
    if m:
        return m.group(1).strip()
    return kb.strip_markup(cell)


def main():
    conn = kb.db()
    conn.execute("DELETE FROM boosts WHERE src = 'pack:boosts'")  # superseded
    n = 0
    for skill in SKILLS:
        text = kb.page_text(f"Temporary skill boost/{skill}")
        tables = kb.wikitables(text)
        if not tables:
            kb.add_gap(conn, "boosts", skill, "table",
                       "no table parsed on the skill's boost subpage")
            continue
        rows = tables[0]
        header = rows[0]
        for cells in rows[1:]:
            if len(cells) < 3:
                continue
            # header: Boost(colspan 2) | Level increase | Visibility | Other info
            name = boost_name(cells[0])
            if not name:
                continue
            amount = kb.strip_markup(cells[1]) if len(cells) > 1 else ""
            visibility = kb.strip_markup(cells[2]) if len(cells) > 2 else ""
            other = kb.strip_markup(cells[3]) if len(cells) > 3 else ""
            circumstances = visibility + (" — " + other if other and other.lower() not in ("n/a", "") else "")
            flags = None if amount else "amount-unparsed"
            if not amount:
                kb.add_gap(conn, "boosts", f"{name} ({skill})", "amount",
                           "level-increase cell did not parse")
            conn.execute(
                "INSERT OR REPLACE INTO boosts(name,skill,amount,circumstances,reqs,src,flags)"
                " VALUES(?,?,?,?,?,?,?)",
                (name, skill, amount, circumstances[:500], None,
                 "wiki:Temporary skill boost/" + skill, flags))
            n += 1
        print(f"{skill}: {len(rows) - 1} boosts")

    # invisible boosts: the main page's own section table
    main_text = kb.page_text("Temporary skill boost")
    section = main_text.split("===Invisible boost list===", 1)
    if len(section) == 2:
        tables = kb.wikitables(section[1])
        if tables:
            for cells in tables[0][1:]:
                if len(cells) < 3:
                    continue
                name = boost_name(cells[0])
                skills = kb.strip_markup(cells[1])
                detail = kb.strip_markup(cells[2]) if len(cells) > 2 else ""
                conn.execute(
                    "INSERT OR REPLACE INTO boosts(name,skill,amount,circumstances,reqs,src,flags)"
                    " VALUES(?,?,?,?,?,?,?)",
                    (name, skills or "several", detail, "Invisible boost", None,
                     "wiki:Temporary skill boost#Invisible", None))
                n += 1
    else:
        kb.add_gap(conn, "boosts", "(invisible boosts)", "section",
                   "Invisible boost list section not found on the main page")

    kb.set_progress(conn, "boosts", None, "boosts",
                    "wiki:Temporary skill boost (+24 subpages)",
                    f"{n} boost rows across {len(SKILLS)} skills + invisible list")
    conn.commit()
    conn.close()
    print("total boost rows:", n)


if __name__ == "__main__":
    main()
