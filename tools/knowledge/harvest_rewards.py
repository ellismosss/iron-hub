#!/usr/bin/env python3
"""Harvest REWARD sources — the obtainment class dropsline/storeline can't
see: items granted by quests and achievement diaries. Parses the Rewards
section of every quest page (Category:Quests) and the per-tier Rewards
sections of the 12 diary pages; every {{plink}} there becomes a
rewards(item, source) row, then re-joinable onto equipment/qol."""

import json
import re

import kb

DIARY_PAGES = [
    "Ardougne Diary", "Desert Diary", "Falador Diary", "Fremennik Diary",
    "Kandarin Diary", "Karamja Diary", "Kourend & Kebos Diary",
    "Lumbridge & Draynor Diary", "Morytania Diary", "Varrock Diary",
    "Western Provinces Diary", "Wilderness Diary",
]


def rewards_sections(text):
    """[(section-heading, body)] for every Rewards-ish section."""
    out = []
    sections = re.split(r"\n(==+)([^=\n]+)\1[^\n]*\n", "\n" + text)
    for i in range(1, len(sections) - 2, 3):
        heading = sections[i + 1].strip()
        if "reward" in heading.lower():
            out.append((heading, sections[i + 2]))
    return out


def plinks(body):
    """Item mentions in a Rewards body: {{plink}}s AND bullet-line [[links]]
    (quest {{Quest rewards}} templates use plain links, not plinks — 222
    quest pages yielded ONE line before this)."""
    out = {m.group(1).strip() for m in re.finditer(r"\{\{plink\|([^|}]+)", body)}
    for line in body.split("\n"):
        if not line.lstrip().startswith("*"):
            continue
        for m in re.finditer(r"\[\[([^\]|#]+)(?:[^\]]*)?\]\]", line):
            title = m.group(1).strip()
            # skills/files/generic pages are not items; the join filters to
            # names that exist in the equipment/qol tables anyway
            if not title.startswith(("File:", "Category:")):
                out.add(title)
    return out


def main():
    conn = kb.db()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS rewards(
            item TEXT NOT NULL,
            source TEXT NOT NULL,
            src TEXT,
            flags TEXT,
            PRIMARY KEY(item, source))""")
    conn.execute("DELETE FROM rewards")
    n = 0

    quests = kb.category_pages("Quests")
    print(f"Category:Quests: {len(quests)} pages")
    if len(quests) < 150:
        raise SystemExit("suspiciously few quest pages")
    # miniquests reward real items too (imbued god capes = Mage Arena II) —
    # they live in their own category, missed until 2026-07-23
    miniquests = kb.category_pages("Miniquests")
    print(f"Category:Miniquests: {len(miniquests)} pages")
    if len(miniquests) < 10:
        raise SystemExit("suspiciously few miniquest pages")
    for title, text in list(quests) + list(miniquests):
        for heading, body in rewards_sections(text):
            for item in plinks(body):
                conn.execute("INSERT OR IGNORE INTO rewards(item,source,src)"
                             " VALUES(?,?,?)",
                             (item, f"quest: {title}", "wiki:" + title))
                n += 1

    for page in DIARY_PAGES:
        text = kb.page_text(page)
        for heading, body in rewards_sections(text):
            for item in plinks(body):
                conn.execute("INSERT OR IGNORE INTO rewards(item,source,src)"
                             " VALUES(?,?,?)",
                             (item, f"diary: {page} — {heading}", "wiki:" + page))
                n += 1
    conn.commit()

    # re-join onto equipment + qol rows that still lack obtainment
    fixed = 0
    for table, name_col in (("equipment", "name"), ("qol_items", "name")):
        flag_col = "flags"
        for (name, obtain_json, flags) in conn.execute(
                f"SELECT {name_col}, {'obtain' if table == 'equipment' else 'sources'},"
                f" {flag_col} FROM {table}").fetchall():
            if not flags or ("obtain-unknown" not in flags and "sources-unknown" not in flags):
                continue
            hits = conn.execute(
                "SELECT source FROM rewards WHERE item = ? COLLATE NOCASE",
                (name,)).fetchall()
            if not hits:
                continue
            obtain = json.loads(obtain_json) if obtain_json else []
            obtain.extend({"how": "reward", "from": h[0]} for h in hits)
            new_flags = ",".join(f for f in flags.split(",")
                                 if f not in ("obtain-unknown", "sources-unknown")) or None
            col = "obtain" if table == "equipment" else "sources"
            conn.execute(f"UPDATE {table} SET {col} = ?, flags = ? WHERE {name_col} = ?",
                         (json.dumps(obtain), new_flags, name))
            if table == "qol_items":
                conn.execute("UPDATE gaps SET status='resolved' WHERE category='qol'"
                             " AND subject=? AND field='sources'", (name,))
            fixed += 1
    conn.commit()
    remaining = conn.execute(
        "SELECT COUNT(*) FROM equipment WHERE flags LIKE '%obtain-unknown%'").fetchone()[0]
    kb.set_progress(conn, "reward-sources", None, "rewards",
                    "wiki quest pages + diary Rewards sections",
                    f"{n} reward lines; {fixed} previously-unknown rows sourced;"
                    f" {remaining} equipment rows still obtain-unknown")
    conn.close()
    print(f"reward lines: {n}; rows fixed: {fixed}; equipment still unknown: {remaining}")


if __name__ == "__main__":
    main()
