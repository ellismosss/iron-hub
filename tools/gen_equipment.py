#!/usr/bin/env python3
"""Generate data/equipment.json — every wearable in the game, for the Gear
library (Luke, 2026-07-24: the module should be a library of every weapon,
armour and cosmetic item, not just an 'optimal' few).

Reads knowledge.db directly (run tools/knowledge/rebuild.py first) — the
`equipment` table is the wiki's 12 slot categories with full Infobox
Bonuses. This pack carries only what no other pack has: the combat stats,
the slot, members, and a value for sorting. Obtainment and equip
requirements already live in item-sources.json (ItemSourcesPack), so the
library reads those by id and never duplicates them.

Excluded: restricted-mode items (LMS / Deadman / beta duplicates of real
gear) and rows the wiki gives no slot (storage items mis-filed under
equipment). Discontinued items (partyhats, holiday cosmetics) STAY — a
gear library is exactly where a player looks them up.

Usage:
  python3 tools/gen_equipment.py
"""
import datetime
import json
import os
import sqlite3

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(HERE, "..", "knowledge", "knowledge.db")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "equipment.json")

# The 12 wearable slots, the order the game's Worn Equipment tab uses.
SLOTS = ["head", "cape", "neck", "ammo", "weapon", "2h", "body", "shield",
         "legs", "hands", "feet", "ring"]

# The combat bonuses, in the order the Equipment Stats interface lists them.
STAT_KEYS = ["astab", "aslash", "acrush", "arange", "amagic",
             "dstab", "dslash", "dcrush", "drange", "dmagic",
             "str", "rstr", "mdmg", "prayer"]


def to_int(value) -> int:
    if value is None:
        return 0
    text = str(value).strip().replace("+", "").replace(",", "").replace("%", "")
    try:
        return int(text)
    except ValueError:
        return 0


def main():
    if not os.path.exists(DB):
        raise SystemExit("knowledge.db missing — run tools/knowledge/rebuild.py first")
    con = sqlite3.connect(DB)

    # GE guide price + high alch, by item id (bucket_exchange).
    ge = {}
    alch = {}
    for row in con.execute("select id, value, high_alch from bucket_exchange"):
        try:
            item_id = int(row[0])
        except (TypeError, ValueError):
            continue
        if to_int(row[1]) > 0:
            ge[item_id] = to_int(row[1])
        if to_int(row[2]) > 0:
            alch[item_id] = to_int(row[2])

    # game-mode-only duplicates the restricted-mode-item flag misses: the
    # Deadman Mode and Bounty Hunter (bh) recolours of real gear.
    restricted_suffixes = ("(Deadman Mode)", "(bh)", "(deadman)")

    items = []
    dropped_slot = 0
    dropped_restricted = 0
    for name, slot, ids_json, members, stats_json, flags in con.execute(
            "select name, slot, item_ids, members, stats, flags from equipment"):
        flag_set = set((flags or "").split(","))
        if "restricted-mode-item" in flag_set or name.endswith(restricted_suffixes):
            dropped_restricted += 1
            continue
        if not slot or slot not in SLOTS:
            dropped_slot += 1
            continue
        try:
            ids = [int(i) for i in json.loads(ids_json)]
        except (TypeError, ValueError, json.JSONDecodeError):
            ids = []
        if not ids:
            continue
        stats = json.loads(stats_json) if stats_json else {}

        entry = {
            "name": name,
            "slot": slot,
            "ids": ids,
            "members": bool(members),
            "stats": [to_int(stats.get(key)) for key in STAT_KEYS],
        }
        speed = to_int(stats.get("speed"))
        if speed > 0:
            entry["speed"] = speed
        # value for sorting: GE guide price where tradeable, else high alch;
        # the first id that carries one wins (versions share a value)
        price = next((ge[i] for i in ids if i in ge), 0)
        if price:
            entry["ge"] = price
        alch_value = next((alch[i] for i in ids if i in alch), 0)
        if alch_value:
            entry["alch"] = alch_value
        items.append(entry)

    items.sort(key=lambda e: (SLOTS.index(e["slot"]), e["name"]))

    # sanity: every slot present, and the counts are in the right ballpark
    by_slot = {}
    for entry in items:
        by_slot[entry["slot"]] = by_slot.get(entry["slot"], 0) + 1
    for slot in SLOTS:
        if by_slot.get(slot, 0) < 30:
            raise SystemExit(f"slot {slot} has only {by_slot.get(slot, 0)} items — "
                             "the equipment table looks wrong")
    if len(items) < 3500:
        raise SystemExit(f"only {len(items)} wearables — expected ~4000+")

    pack = {
        "source": "knowledge.db equipment (wiki Infobox Bonuses) + bucket_exchange values",
        "generated": datetime.date.today().isoformat(),
        "statKeys": STAT_KEYS,
        "items": items,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, separators=(",", ":"))
        f.write("\n")
    priced = sum(1 for e in items if "ge" in e or "alch" in e)
    print(f"wrote {len(items)} wearables ({priced} with a value); "
          f"dropped {dropped_restricted} restricted-mode, {dropped_slot} slotless")
    print("  " + ", ".join(f"{s} {by_slot[s]}" for s in SLOTS))


if __name__ == "__main__":
    main()
