#!/usr/bin/env python3
"""Join the drops table onto equipment, consumables and the collection log:
every row gains its actual sources (who drops it / which chest, at what
rate). Items with a recipe keep their make entry first. Rows that end up
with NO obtainment at all are flagged and land in gaps — never guessed."""

import json
import re

import kb

# game-mode/beta/discontinued variants that are NOT obtainable in the main
# game — an empty obtainment on these is expected, not a data hole
RESTRICTED = re.compile(
    r"\((?:beta|Last Man Standing|Deadman(?: Mode)?|historical|"
    r"Trailblazer|Shattered Relics|Twisted League|Leagues)\b|^Dni\d|^Team-\d",
    re.I)


def sources_for(conn, name):
    rows = conn.execute(
        "SELECT source, rarity, quantity, drop_type FROM drops WHERE item = ?"
        " ORDER BY CASE WHEN rarity LIKE '1/%' THEN 0 ELSE 1 END, rarity"
        " LIMIT 25", (name,)).fetchall()
    out = []
    for source, rarity, quantity, drop_type in rows:
        out.append({"how": drop_type or "drop", "from": source,
                    "rate": rarity, "qty": quantity})
    for shop, price, currency in conn.execute(
            "SELECT shop, price, currency FROM shop_stock WHERE item = ?"
            " COLLATE NOCASE LIMIT 10", (name,)).fetchall():
        out.append({"how": "shop", "from": shop,
                    "price": (price or "") + (" " + currency if currency else "")})
    return out


def discontinued_names(conn):
    """Items the wiki marks REMOVED from the game (infobox_item
    removal_date) — an empty obtainment on these is expected."""
    return {(r[0] or "").lower() for r in conn.execute(
        "SELECT DISTINCT name FROM items"
        " WHERE removal_date IS NOT NULL AND removal_date != ''")}


def equipment(conn):
    filled = 0
    empty = 0
    removed = discontinued_names(conn)
    for (name, obtain_json, flags, effects) in conn.execute(
            "SELECT name, obtain, flags, effects FROM equipment").fetchall():
        obtain = json.loads(obtain_json) if obtain_json else []
        obtain = [o for o in obtain if o.get("how") == "make"]
        drops = sources_for(conn, name)
        obtain.extend(drops)
        # strip BOTH obtainment flags before re-judging — a rerun after a
        # new source harvest must clear a stale obtain-unknown (722 shop
        # items kept the flag from the pre-shops run)
        new_flags = [f for f in (flags or "").split(",")
                     if f and f not in ("obtain-pending", "obtain-unknown",
                                        "restricted-mode-item", "discontinued",
                                        "obtain-prose-only")]
        if not obtain:
            if RESTRICTED.search(name):
                new_flags.append("restricted-mode-item")
            elif name.lower() in removed:
                # the wiki's own removal_date: holiday/discontinued items —
                # unobtainable is the answer, not a hole (the reward-source
                # completion pass, Luke 2026-07-22)
                new_flags.append("discontinued")
            elif effects:
                # last resort: the page's own lead sentence IS the wiki's
                # statement of how it's obtained — honest prose, flagged
                obtain = [{"how": "prose", "detail": effects}]
                new_flags.append("obtain-prose-only")
                filled += 1
            else:
                new_flags.append("obtain-unknown")
                empty += 1
        else:
            filled += 1
        conn.execute("UPDATE equipment SET obtain = ?, flags = ? WHERE name = ?",
                     (json.dumps(obtain) if obtain else None,
                      ",".join(new_flags) or None, name))
    print(f"equipment: {filled} with obtainment, {empty} without (flagged)")
    kb.set_progress(conn, "equipment", None, "equipment",
                    "wiki slot categories + recipes + dropsline bucket",
                    f"{filled} items with obtainment (make/drops), {empty} with none"
                    " — shops/quest-rewards/minigame-shops not yet modelled")


def consumables(conn):
    for (name, obtain_json, flags) in conn.execute(
            "SELECT name, obtain, flags FROM consumables").fetchall():
        obtain = json.loads(obtain_json) if obtain_json else []
        obtain = [o for o in obtain if o.get("how") != "drop"]
        obtain.extend(sources_for(conn, name))
        new_flags = flags
        if obtain and flags and "obtain-unknown" in flags:
            new_flags = ",".join(f for f in flags.split(",") if f != "obtain-unknown") or None
            conn.execute("UPDATE gaps SET status='resolved' WHERE category='consumables'"
                         " AND subject=? AND field='obtain'", (name,))
        conn.execute("UPDATE consumables SET obtain = ?, flags = ? WHERE name = ?",
                     (json.dumps(obtain) if obtain else None, new_flags, name))
    print("consumables: drops joined")


def clog(conn):
    filled = 0
    still = 0
    for (item_id, name, activity, rate, flags) in conn.execute(
            "SELECT item_id, name, activity, drop_rate, flags FROM clog_items").fetchall():
        drops = sources_for(conn, name)
        if not drops:
            if not activity:
                still += 1
            continue
        wiki_activity = "; ".join(sorted({d["from"] for d in drops if d["from"]})[:6])
        wiki_rate = "; ".join(f"{d['rate']} from {d['from']}" for d in drops[:6]
                              if d.get("rate"))
        new_flags = [f for f in (flags or "").split(",") if f and f != "source-missing"]
        conn.execute(
            "UPDATE clog_items SET activity = COALESCE(activity, ?),"
            " drop_rate = COALESCE(drop_rate, ?), flags = ? WHERE item_id = ?",
            (wiki_activity or None, wiki_rate or None,
             ",".join(new_flags) or None, item_id))
        filled += 1
    for (item_id, name) in conn.execute(
            "SELECT item_id, name FROM clog_items WHERE activity IS NULL").fetchall():
        kb.add_gap(conn, "clog", f"{name} (id {item_id})", "source",
                   "no clog-pack activity and no wiki drop line matched the name")
    print(f"clog: {filled} slots joined to wiki drops, {still} still sourceless")
    kb.set_progress(conn, "clog-items", None, "clog_items",
                    "pack:clog + wiki dropsline",
                    "slots joined to wiki drop lines by name; what-it-does joins"
                    " to equipment/consumable effects pending")


def merge_seeds(conn):
    """Fold the gear-progression seed rows onto their wiki rows CASE-
    INSENSITIVELY (pack: "Bow of Faerdhinen", wiki: "Bow of faerdhinen" —
    the shadow rows hid the audited reqs). Seeds with no wiki page keep a
    flag instead of pretending to be equipment pages."""
    moved = 0
    for (name, reqs) in conn.execute(
            "SELECT name, equip_reqs FROM equipment"
            " WHERE src='pack:gear-progression'").fetchall():
        hit = conn.execute(
            "SELECT name, flags FROM equipment WHERE name = ? COLLATE NOCASE"
            " AND src != 'pack:gear-progression'", (name,)).fetchone()
        if hit:
            flags = [f for f in (hit[1] or "").split(",")
                     if f and f != "reqs-unverified"]
            conn.execute(
                "UPDATE equipment SET equip_reqs = ?, flags = ?,"
                " src = src || '+pack:gear-progression' WHERE name = ?",
                (reqs, ",".join(flags) or None, hit[0]))
            conn.execute("DELETE FROM equipment WHERE name = ?"
                         " AND src='pack:gear-progression'", (name,))
            moved += 1
        else:
            conn.execute("UPDATE equipment SET flags='no-wiki-slot-page'"
                         " WHERE name = ? AND src='pack:gear-progression'", (name,))
    print(f"seed merge: {moved} audited req sets onto wiki rows")


def main():
    conn = kb.db()
    merge_seeds(conn)
    equipment(conn)
    consumables(conn)
    clog(conn)
    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
