#!/usr/bin/env python3
"""Apply curated-sources.json — Luke's answer key (2026-07-22), every entry
verified against its cited wiki page — onto the knowledge base. Rerunnable:
runs late in the pipeline so a rebuild re-applies it after the harvests.

Covers: the 7 collection-log holdouts, ~25 named materials, pattern classes
(Adventurer's outfits, impling jars, boat paints, flatpacks, seedlings,
Infinity gear, Shayzien tiers), the Lvl-N Enchant spell join (from the
infobox_spell bucket), charged weapons reclassified as not-materials, the
degraded-variants wont-fix, and the QoL source notes."""

import json
import os
import re

import kb


def merge_obtained(conn, table, key_col, key, entry):
    row = conn.execute(f"SELECT obtained FROM {table} WHERE {key_col} = ?",
                       (key,)).fetchone()
    if row is None:
        return False
    obtained = json.loads(row[0]) if row[0] else []
    note = {"how": entry["how"], "detail": entry["detail"],
            "src": "luke+wiki:" + entry["page"]}
    if not any(o.get("detail") == note["detail"] for o in obtained):
        obtained.insert(0, note)
    conn.execute(f"UPDATE {table} SET obtained = ?,"
                 f" flags = CASE WHEN flags='obtain-unknown' THEN 'curated'"
                 f" ELSE flags END WHERE {key_col} = ?",
                 (json.dumps(obtained), key))
    return True


def spell_join(conn, name):
    """Lvl-N Enchant -> its spell row: runes + level from the spell bucket."""
    row = conn.execute(
        'SELECT "uses_material", "json" FROM bucket_infobox_spell'
        ' WHERE page_name = ?', (name,)).fetchone()
    if not row:
        return None
    runes = [m for m in (json.loads(row[0]) if row[0] else []) if m]
    level = ""
    try:
        level = json.loads(row[1] or "{}").get("level") or ""
    except (json.JSONDecodeError, AttributeError):
        pass
    return {"how": "spell",
            "detail": f"Standard spellbook enchantment spell"
                      + (f" — Magic {level}" if level else "")
                      + (f"; runes: {', '.join(runes)}" if runes else "")
                      + "; cast on the matching jewellery",
            "page": "Enchantment_spells"}


def main():
    conn = kb.db()
    cur = json.load(open(os.path.join(kb.HERE, "curated-sources.json"),
                         encoding="utf-8"))

    # ── collection log ────────────────────────────────────────────────
    for item_id, entry in cur["clog"].items():
        sets, vals = [], []
        if "source" in entry:
            sets.append("activity = ?")
            vals.append(entry["source"])
        if "what" in entry:
            sets.append("what_it_does = ?")
            vals.append(entry["what"])
        sets.append("flags = NULL")
        sets.append("src = src || '+luke'")
        conn.execute(f"UPDATE clog_items SET {', '.join(sets)} WHERE item_id = ?",
                     vals + [int(item_id)])
        conn.execute("UPDATE gaps SET status='resolved' WHERE category='clog'"
                     " AND subject LIKE ?", (f"%(id {item_id})",))

    # ── materials: exact names ────────────────────────────────────────
    for name, entry in cur["materials"].items():
        if merge_obtained(conn, "materials", "name", name, entry):
            conn.execute("UPDATE gaps SET status='resolved' WHERE"
                         " category='materials' AND subject = ?", (name,))

    # ── materials: pattern classes over still-open gaps ───────────────
    open_names = [r[0] for r in conn.execute(
        "SELECT subject FROM gaps WHERE category='materials' AND status='open'"
        " AND subject NOT LIKE '(%'").fetchall()]
    for name in open_names:
        for pat in cur["material_patterns"]:
            if not re.search(pat["match"], name, re.I):
                continue
            entry = pat
            if pat["detail"] == "SPELL_JOIN":
                entry = spell_join(conn, name)
                if entry is None:
                    continue
            if merge_obtained(conn, "materials", "name", name, entry):
                conn.execute("UPDATE gaps SET status='resolved' WHERE"
                             " category='materials' AND subject = ?", (name,))
            break

    # ── charged weapons are not materials ─────────────────────────────
    for name, why in cur["not_materials"].items():
        conn.execute("UPDATE materials SET flags='not-a-material',"
                     " obtained = ? WHERE name = ?",
                     (json.dumps([{"how": "note", "detail": why,
                                   "src": "luke"}]), name))
        conn.execute("UPDATE gaps SET status='wont-fix', notes=? WHERE"
                     " category='materials' AND subject = ?", (why, name))

    # ── curated reward rows (prose-only Rewards sections the plink/bullet
    #    parser can't read — Mage Arena II's imbued capes et al) ──────
    for item, source in cur.get("rewards", {}).items():
        conn.execute("INSERT OR IGNORE INTO rewards(item,source,src)"
                     " VALUES(?,?,'luke:curated')", (item, source))

    # ── shop-price corrections (wiki bucket vs page-prose conflicts) ──
    for key, entry in cur.get("shop_prices", {}).items():
        item, shop = key.split("|", 1)
        conn.execute("UPDATE shop_stock SET price = ?, src = src || '+luke'"
                     " WHERE item = ? AND shop = ?",
                     (entry["price"], item, shop))

    # ── class-level wont-fixes ────────────────────────────────────────
    for subject, note in cur["wont_fix"].items():
        conn.execute("UPDATE gaps SET status='wont-fix', notes=? WHERE"
                     " subject = ?", (note, subject))

    # ── QoL source notes ──────────────────────────────────────────────
    for name, note in cur["qol_sources"].items():
        conn.execute("UPDATE qol_items SET sources = COALESCE(sources, ?),"
                     " flags = NULL, src = src || '+luke' WHERE name = ?",
                     (json.dumps([{"how": "note", "detail": note, "src": "luke"}]),
                      name))
        conn.execute("UPDATE gaps SET status='resolved', notes=? WHERE"
                     " category='qol' AND subject = ?", (note, name))

    conn.commit()
    remaining = conn.execute("SELECT category, COUNT(*) FROM gaps WHERE"
                             " status='open' GROUP BY category").fetchall()
    conn.close()
    print("applied; open gaps now:", dict(remaining))


if __name__ == "__main__":
    main()
