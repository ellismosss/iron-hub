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


# bullet lead-ins that still leave the item as the subject
LEAD_IN = re.compile(
    r"^(?:you\s+(?:will\s+)?(?:receive|get|are\s+given|obtain|keep)\s+|"
    r"receive\s+|gain\s+|access\s+to\s+your\s+own\s+)?"
    r"(?:the|a|an|one|two|three|four|five|\d[\d,]*)?\s*", re.I)


def plinks(body):
    """Reward ITEMS in a Rewards body.

    {{plink}}/{{plinkt}} templates are explicit item links and always count.
    Plain [[links]] only count when the item is the BULLET'S SUBJECT — quest
    {{Quest rewards}} bodies use plain links, but they also *mention* items
    the quest does not give ("Jaltevas teleport option on the [[Pharaoh's
    sceptre]]", "the ability to upgrade the [[Xyz]]", "access to [[Place]]"),
    and counting those made the sceptre a Beneath Cursed Sands reward
    (Luke, 2026-07-23). Requiring the link to open the bullet — after an
    optional article, quantity or "you receive" lead-in — keeps the real
    rewards ("A [[Keris partisan]].", "The [[circlet of water]], ...") and
    drops the mentions."""
    out = {m.group(1).strip() for m in re.finditer(r"\{\{plinkt?\|([^|}]+)", body)}
    for line in body.split("\n"):
        if not line.lstrip().startswith("*"):
            continue
        # strip the bullet marker and any leading templates ({{SCP|...}},
        # {{Coins|...}}) so a quantity template can't hide the subject
        text = line.lstrip().lstrip("*").strip()
        text = re.sub(r"^(?:\{\{[^{}]*\}\}\s*)+", "", text).strip()
        text = re.sub(r"^'{2,}|^\[\[File:[^\]]*\]\]\s*", "", text).strip()
        m = re.match(r"\[\[([^\]|#]+)(?:\|[^\]]*)?\]\]",
                     LEAD_IN.sub("", text, count=1).strip())
        if not m:
            continue
        title = m.group(1).strip()
        # skills/files/generic pages are not items; the join filters to
        # names that exist in the equipment/qol tables anyway
        if not title.startswith(("File:", "Category:")):
            out.add(capitalize_title(title))
    return out


def capitalize_title(title):
    """Wiki page titles are case-insensitive in the FIRST character, so
    "[[circlet of water]]" and "Circlet of water" are one page — but a
    lowercase row never joins to the items table by name (the circlet's
    quest reward was silently lost, 2026-07-23)."""
    return title[:1].upper() + title[1:] if title else title


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

    # ── the subject rule stays honest in BOTH directions ──────────────
    def has(item, source):
        return conn.execute("SELECT 1 FROM rewards WHERE item=? AND source=?",
                            (item, source)).fetchone() is not None

    for item, source in (("Keris partisan", "quest: Beneath Cursed Sands"),
                         ("Circlet of water", "quest: Beneath Cursed Sands"),
                         ("Ardougne cloak 1", "diary: Ardougne Diary — Rewards")):
        if not has(item, source):
            raise SystemExit(f"reward parse lost a real reward: {item} <- {source}")
    # mentions, not rewards: the sceptre only gains a teleport OPTION, and
    # RFD grants the ABILITY TO BUY the gloves (they are a shop item)
    for item, source in (("Pharaoh's sceptre", "quest: Beneath Cursed Sands"),
                         ("Barrows gloves", "quest: Recipe for Disaster")):
        if has(item, source):
            raise SystemExit(f"reward parse still counts a MENTION: {item} <- {source}")
    # the subject rule is strict by design (2,422 -> ~700 lines): it keeps
    # items the quest GIVES and drops the ones it merely mentions
    if n < 500:
        raise SystemExit(f"suspiciously few reward lines after filtering: {n}")

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
