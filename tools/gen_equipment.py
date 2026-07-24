#!/usr/bin/env python3
"""Generate data/equipment.json — every wearable in the game, for the Gear
library (Luke, 2026-07-24: the module should be a library of every weapon,
armour and cosmetic item, not just an 'optimal' few).

Reads knowledge.db directly (run tools/knowledge/rebuild.py first) — the
`equipment` table is the wiki's 12 slot categories with full Infobox
Bonuses. This pack carries only what no other pack has: the combat stats, the
slot, members, and the high-alch value. Obtainment and equip requirements
already live in item-sources.json (ItemSourcesPack), so the library reads
those by id and never duplicates them.

The MARKET value is NOT baked: the exchange module's `value` attribute is
the item's store value (used to derive high alch), not the live GE price
— for Tumeken's shadow that value is 7M while the item trades at ~750M.
The live price comes from ItemManager.getItemPrice at runtime; only the
high-alch value (a fixed, honest number) is baked, as an offline fallback.

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
import re
import sqlite3
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(HERE, "..", "knowledge", "knowledge.db")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "equipment.json")
CACHE = os.path.join(HERE, ".cache-equipment")
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"

# Wiki categories whose members are NOT part of the standard game — Leagues
# and Deadman rewards (Battlehat, Echo harpoon, Twisted slayer helmet …). An
# item in any of these is marked leagues:true so the library can hide it.
NON_MAIN_CATEGORIES = [
    "Twisted League",
    "Trailblazer League",
    "Shattered Relics League",
    "Trailblazer Reloaded League",
    "Raging Echoes League",
    "Deadman Mode",
    "Deadman Apocalypse",
]


def category_members(category: str) -> set:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, "cat-" + re.sub(r"[^A-Za-z0-9]+", "_", category) + ".json")
    if not os.path.exists(cached):
        members = []
        cont = None
        while True:
            url = ("https://oldschool.runescape.wiki/api.php?action=query&format=json"
                   "&list=categorymembers&cmnamespace=0&cmlimit=500&cmtitle=Category:"
                   + urllib.parse.quote(category))
            if cont:
                url += "&cmcontinue=" + urllib.parse.quote(cont)
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            time.sleep(1)
            with urllib.request.urlopen(req) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            members += [m["title"] for m in data.get("query", {}).get("categorymembers", [])]
            cont = data.get("continue", {}).get("cmcontinue")
            if not cont:
                break
        with open(cached, "w", encoding="utf-8") as f:
            json.dump(members, f)
    with open(cached, encoding="utf-8") as f:
        return set(json.load(f))


def non_main_game_names() -> set:
    names = set()
    for category in NON_MAIN_CATEGORIES:
        names |= category_members(category)
    return names

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

    # high alch by item id (bucket_exchange). The `value` column is the
    # store value, not the market price — deliberately not baked (see the
    # module docstring); the live GE price is an ItemManager runtime read.
    alch = {}
    for row in con.execute("select id, high_alch from bucket_exchange"):
        try:
            item_id = int(row[0])
        except (TypeError, ValueError):
            continue
        if to_int(row[1]) > 0:
            alch[item_id] = to_int(row[1])

    # game-mode-only duplicates the restricted-mode-item flag misses: the
    # Deadman Mode and Bounty Hunter (bh) recolours of real gear.
    restricted_suffixes = ("(Deadman Mode)", "(bh)", "(deadman)")
    non_main = non_main_game_names()

    items = []
    leagues_count = 0
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
        # high-alch value (the first id that carries one — versions share it),
        # the offline fallback when there's no live GE price
        alch_value = next((alch[i] for i in ids if i in alch), 0)
        if alch_value:
            entry["alch"] = alch_value
        if name in non_main:
            entry["leagues"] = True
            leagues_count += 1
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
    priced = sum(1 for e in items if "alch" in e)
    print(f"wrote {len(items)} wearables ({priced} with a value, "
          f"{leagues_count} Leagues/Deadman); "
          f"dropped {dropped_restricted} restricted-mode, {dropped_slot} slotless")
    print("  " + ", ".join(f"{s} {by_slot[s]}" for s in SLOTS))


if __name__ == "__main__":
    main()
