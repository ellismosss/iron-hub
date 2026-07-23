#!/usr/bin/env python3
"""Harvest EVERY wearable item from the wiki: the 12 equipment-slot
categories, bulk-fetched, with the full Infobox Bonuses param set (all
versions), item ids, members flag, examine and the page's lead sentence as
the effect prose.

Equip requirements are NOT structured on the wiki (they live in prose/tables
per page) — rows keep the gear-progression pack's audited reqs where we have
them and carry a reqs-unverified flag otherwise; obtainment joins arrive
with the drops harvest. Every page that lacks an Infobox Bonuses lands in
the gaps table instead of being guessed at.
"""

import json
import re

import kb

SLOT_CATEGORIES = [
    "Head slot items", "Cape slot items", "Neck slot items",
    "Ammunition slot items", "Weapon slot items", "Two-handed slot items",
    "Body slot items", "Shield slot items", "Legs slot items",
    "Hands slot items", "Feet slot items", "Ring slot items",
]


def lead_sentence(wikitext: str) -> str:
    """First real prose paragraph of a page (the item's own description)."""
    depth = 0
    for raw in wikitext.split("\n"):
        line = raw.strip()
        depth += line.count("{{") - line.count("}}")
        if depth > 0 or not line:
            continue
        if line.startswith(("{", "|", "=", "[[File:", "[[Category:", "<", "#", "*", "__")):
            continue
        text = kb.strip_markup(line)
        if len(text) > 20:
            return text[:400]
    return ""


def item_ids(infobox: dict):
    ids = []
    for key, value in infobox.items():
        if key == "id" or re.fullmatch(r"id\d+", key):
            for tok in re.split(r"[,;]", value):
                tok = tok.strip()
                if tok.isdigit():
                    ids.append(int(tok))
    return ids


def main():
    conn = kb.db()
    seen = set()
    pages = 0
    for category in SLOT_CATEGORIES:
        slot_pages = kb.category_pages(category)
        print(f"{category}: {len(slot_pages)} pages")
        if not slot_pages:
            raise SystemExit(f"empty category — name wrong? {category}")
        for title, text in slot_pages:
            pages += 1
            if title in seen:
                continue  # a 2h weapon is in Weapon + Two-handed; first wins
            seen.add(title)
            bonuses = kb.templates(text, "Infobox Bonuses")
            item_box = (kb.templates(text, "Infobox Item") or [{}])[0]
            if not bonuses:
                kb.add_gap(conn, "equipment", title, "stats",
                           f"page in Category:{category} has no Infobox Bonuses"
                           " — stats unknown")
                stats = None
                slot = category.replace(" slot items", "").lower()
            else:
                stats = json.dumps(bonuses if len(bonuses) > 1 else bonuses[0])
                slot = bonuses[0].get("slot", category.replace(" slot items", "").lower())
            ids = item_ids(item_box)
            members = None
            m = item_box.get("members", "").lower()
            if m.startswith(("yes", "no")):
                members = 1 if m.startswith("yes") else 0
            existing = conn.execute(
                "SELECT equip_reqs, src FROM equipment WHERE name = ?", (title,)).fetchone()
            reqs = existing[0] if existing and existing[0] else None
            made = kb.recipe(text)
            obtain = [{"how": "make", **made}] if made else []
            flags = []
            if stats is None:
                flags.append("stats-missing")
            if reqs is None:
                flags.append("reqs-unverified")
            flags.append("obtain-pending")  # the drops join completes this
            conn.execute(
                "INSERT INTO equipment(name,slot,item_ids,members,stats,equip_reqs,"
                " effects,obtain,examine,src,flags) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
                " ON CONFLICT(name) DO UPDATE SET slot=excluded.slot,"
                " item_ids=excluded.item_ids, members=excluded.members,"
                " stats=excluded.stats, effects=excluded.effects,"
                " obtain=excluded.obtain, examine=excluded.examine,"
                " flags=excluded.flags, src=equipment.src || '+wiki'",
                (title, slot, json.dumps(ids) or None, members, stats, reqs,
                 lead_sentence(text), json.dumps(obtain) if obtain else None,
                 kb.strip_markup(item_box.get("examine", ""))[:200] or None,
                 "wiki:" + category, ",".join(flags)))
        conn.commit()
    kb.set_progress(conn, "equipment", len(seen), "equipment", "wiki slot categories",
                    f"{pages} category rows -> {len(seen)} unique items; stats+ids+prose"
                    " harvested; equip reqs audited for gear-progression items only;"
                    " obtainment joins pending (drops harvest)")
    conn.commit()
    conn.close()
    print(f"done: {len(seen)} unique equipment pages")


if __name__ == "__main__":
    main()
