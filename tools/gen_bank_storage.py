#!/usr/bin/env python3
"""Generate data/bank-storage.json — items with a dedicated storage home.

Ported from the Wasted Bank Space hub plugin (mcgeer/WastedBankSpace,
BSD-2, Riley McGee) at the plugin-hub pinned commit: its 21 storage
location enums (Tackle Box, Seed Vault, POH Armour Case / Cape Rack /
Magic Wardrobe / Toy Box, Treasure Chest, ...) are parsed from the
fetched source — every constant is `NAME(ItemID.X[, bis])` against the
LEGACY ItemID class, which is exactly what the item-name index is built
from, so resolution is exact and fail-fast. The `bis` flag marks
best-in-slot gear the player may deliberately keep banked (the
reference filters those out by default; so does Iron Hub).

Usage: python3 tools/gen_bank_storage.py
"""

import json
import os
import re
import urllib.request

COMMIT = "1cb3b285c995fb7f4229a172033fcb6020d445cf"
RAW = ("https://raw.githubusercontent.com/mcgeer/WastedBankSpace/"
       + COMMIT + "/src/main/java/com/wastedbankspace/model/locations/")

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-bank-storage")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "bank-storage.json")

# the 21 location enums registered in the reference's StorageLocations
ENUMS = [
    "ArmourCase", "Bookcase", "CapeRack", "ElnockInquisitor", "FancyDressBox",
    "FlamtaerBag", "ForestryKit", "FossilStorage", "HuntsmansKit",
    "MagicWardrobe", "MasterScrollBook", "MysteriousStranger", "NightmareZone",
    "PetHouse", "SeedVault", "SpiceRack", "SteelKeyRing", "TackleBox",
    "ToolLeprechaun", "ToyBox", "TreasureChest",
]

# ids used raw in the reference (no ItemID constant exists); names from
# the reference's own inline comments
RAW_ID_NAMES = {22818: "Fish chunks"}

# the three enums whose bis flags the reference actually reads
BIS_ENUMS = {"ArmourCase", "CapeRack", "MagicWardrobe"}


def fetch_source(name: str) -> str:
    cached = os.path.join(CACHE, name)
    os.makedirs(CACHE, exist_ok=True)
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return f.read()
    req = urllib.request.Request(RAW + name, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        text = resp.read().decode("utf-8")
    with open(cached, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def item_index() -> dict:
    """EVERY legacy ItemID constant -> id, from the raw dump the item-name
    index is generated from. The generated index deliberately filters
    `.*_\\d+` names, which here would cut BOTH dose constants
    (ABSORPTION_1, id 11737) and id-suffixed duplicates
    (PRESCRIPTION_GOGGLES_29976) — the raw dump has them all."""
    index = {}
    with open(os.path.join(HERE, "itemids.txt")) as f:
        for line in f:
            name, value = line.split()
            index.setdefault(name, int(value))
    return index


def strip_comments(java: str) -> str:
    java = re.sub(r"/\*.*?\*/", "", java, flags=re.S)
    return re.sub(r"//[^\n]*", "", java)


def pretty(constant: str) -> str:
    """Display name from the constant form (gen_clue_steps precedent) —
    offline names so the tab renders headless without an item cache."""
    words = constant.lower().replace("_", " ")
    return words[:1].upper() + words[1:]


def parse_enum(name: str, index: dict):
    java = strip_comments(fetch_source(name + ".java"))
    loc = re.search(r'String location = "([^"]+)"', java)
    if not loc:
        raise SystemExit(f"{name}: no location display string")
    items = []
    seen = set()
    for m in re.finditer(
            r"^\s+[A-Z][A-Z_0-9]*\(\s*(ItemID\.(\w+)|\d+)\s*(?:,\s*(true|false))?\s*\)\s*[,;]",
            java, re.M):
        ref, const, bis = m.groups()
        if const:
            if const not in index:
                raise SystemExit(f"{name}: ItemID.{const} not in the raw item dump")
            item_id = index[const]
        else:
            item_id = int(ref)
        if item_id in seen:
            continue  # a few enums repeat an id under two constant names
        seen.add(item_id)
        if not const and item_id not in RAW_ID_NAMES:
            raise SystemExit(f"{name}: raw id {item_id} has no curated name")
        entry = {"id": item_id,
                 "name": pretty(const) if const else RAW_ID_NAMES[item_id]}
        if bis == "true":
            if name not in BIS_ENUMS:
                raise SystemExit(f"{name}: unexpected bis flag (reference only "
                                 f"reads bis for {sorted(BIS_ENUMS)})")
            entry["bis"] = True
        items.append(entry)
    if not items:
        raise SystemExit(f"{name}: no constants parsed")
    slug = re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()
    return {"id": slug, "name": loc.group(1), "items": items}


def main():
    index = item_index()
    locations = [parse_enum(name, index) for name in ENUMS]

    # ── sanity asserts ───────────────────────────────────────────────────
    assert len(locations) == 21
    total = sum(len(l["items"]) for l in locations)
    assert total >= 2200, total
    by_id = {l["id"]: l for l in locations}
    assert len(by_id["treasure_chest"]["items"]) >= 500
    assert len(by_id["flamtaer_bag"]["items"]) == 3
    bis = sum(1 for l in locations for i in l["items"] if i.get("bis"))
    assert bis >= 10, bis
    for l in locations:
        assert all(i["id"] > 0 for i in l["items"]), l["id"]

    out = {
        "version": 1,
        "source": "mcgeer/WastedBankSpace @ " + COMMIT[:7] + " (BSD-2)",
        "locations": locations,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(f"{len(locations)} locations, {total} items ({bis} bis) -> "
          f"{os.path.relpath(OUT, HERE + '/..')}")


if __name__ == "__main__":
    main()
