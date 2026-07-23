#!/usr/bin/env python3
"""Harvest EVERY combat achievement task from the wiki's Bucket API (the
same store the wiki's own CA task tables render from), plus each tier
page's structured Requirements section (Items / Skills / Quests checklists)
— the "gear and skill requirements" attach at TIER level, exactly as the
wiki states them; per-task judgment calls beyond that are flagged."""

import json
import re
import urllib.parse

import kb

TIERS = ["Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"]


def bucket_tasks():
    rows = []
    offset = 0
    while True:
        q = ("bucket('combat_achievement').select('id','name','monster','task',"
             f"'tier','type').offset({offset}).limit(5000).run()")
        data = kb.api({"action": "bucket", "query": q}, f"bucket-ca-{offset}.json")
        batch = data.get("bucket", [])
        rows.extend(batch)
        if len(batch) < 5000:
            break
        offset += 5000
    if len(rows) < 500:
        raise SystemExit(f"suspiciously few CA tasks: {len(rows)}")
    return rows


def tier_requirements(tier: str):
    """The tier page's Requirements checklists as {'Items': [...], ...}."""
    text = kb.page_text(f"Combat Achievements/{tier}")
    section = text.split("==Requirements==", 1)
    if len(section) < 2:
        return {}
    body = section[1].split("\n==", 1)[0]
    out = {}
    current = None
    for line in body.split("\n"):
        line = line.strip()
        m = re.match(r"^;\s*(.+)$", line)
        if m:
            current = kb.strip_markup(m.group(1))
            out[current] = []
        elif line.startswith("*") and current:
            item = kb.strip_markup(line.lstrip("*"))
            if item:
                out[current].append(item)
    return out


def main():
    conn = kb.db()
    tasks = bucket_tasks()
    tier_reqs = {}
    for tier in TIERS:
        tier_reqs[tier.lower()] = tier_requirements(tier)
        print(f"{tier}: reqs sections {list(tier_reqs[tier.lower()].keys())}")
    n = 0
    for t in tasks:
        tier = (t.get("tier") or "").strip()
        reqs = tier_reqs.get(tier.lower())
        conn.execute(
            "INSERT OR REPLACE INTO ca_tasks(name,tier,type,monster,description,reqs,src,flags)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (t.get("name"), tier, t.get("type"),
             kb.strip_markup(t.get("monster") or ""),
             kb.strip_markup(t.get("task") or ""),
             json.dumps({"tier_level": reqs}) if reqs else None,
             "wiki:bucket combat_achievement",
             None if reqs else "tier-reqs-missing"))
        n += 1
    kb.add_gap(conn, "ca", "(per-task reqs)", "granularity",
               "the wiki states gear/skill requirements PER TIER (harvested);"
               " finer per-task gear judgments are community lore — flag any"
               " specific task you want itemised")
    kb.set_progress(conn, "ca-tasks", len(tasks), "ca_tasks",
                    "wiki bucket combat_achievement + tier Requirements sections",
                    f"{n} tasks across {len(TIERS)} tiers; reqs attached at tier level")
    conn.commit()
    conn.close()
    print("CA tasks:", n)


if __name__ == "__main__":
    main()
