#!/usr/bin/env python3
"""Generate data/supplies.json — the Supplies runway catalog: consumables
and resources an ironman stocks, grouped into a handful of categories, with
a curated top-20 "most useful" default per category and a broader searchable
membership.

Source: knowledge/knowledge.db (build it first: python3 tools/knowledge/rebuild.py).
  - Food / Potions: the full `consumables` table membership (kind food/potion),
    so search there is complete.
  - Runes / Ammunition / Prayer / Materials: curated name lists (the closed,
    well-known supply families) plus clean name PATTERNS for the families that
    pattern cleanly (grimy herbs, uncut gems, X bones, ensouled heads, ashes,
    planks, logs, seeds).

The top-20 DEFAULTS per category are curated by ironman usefulness (ordered,
most-useful first); everything else is searchable-only.

Why curated, not "all items": the old module built its list from the trip-diff
consumption log, so a dropped/lost *weapon* (Rune longsword) showed up as a
"supply". A closed, categorised catalog makes that impossible — weapons/armour
appear in neither the consumables table nor the curated resource lists nor the
clean patterns (grimy herbs / uncut gems / bones / ashes / logs / seeds), so
they cannot leak in. (Ammunition IS worn in the ammo slot, so a blanket
"exclude equipment" filter would wrongly drop arrows/bolts — the catalog is
positively sourced instead.)

Fails fast on an unreadable DB or any curated DEFAULT name that does not
resolve to an item id (a typo tripwire); the pack is written only after every
validation passes.
"""

import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "knowledge" / "knowledge.db"
OUT = ROOT / "src" / "main" / "resources" / "data" / "supplies.json"

# game-mode / discontinued name variants (never real supplies to stock)
RESTRICTED = re.compile(
    r"\((?:beta|Last Man Standing|Deadman(?: Mode)?|historical|"
    r"Trailblazer|Shattered Relics|Twisted League|Leagues|keg|"
    r"Player-owned house)\b", re.I)

# ── categories: key, display name, icon item name ────────────────────────
CATEGORIES = [
    ("potions", "Potions", "Super combat potion(4)"),
    ("food", "Food", "Shark"),
    ("runes", "Runes", "Nature rune"),
    ("ammo", "Ammunition", "Rune arrow"),
    ("prayer", "Prayer", "Dragon bones"),
    ("materials", "Materials", "Magic logs"),
]

# ── curated top-20 defaults per category (most-useful first) ─────────────
DEFAULTS = {
    "potions": [
        "Super combat potion(4)", "Ranging potion(4)", "Bastion potion(4)",
        "Super restore(4)", "Prayer potion(4)", "Sanfew serum(4)",
        "Stamina potion(4)", "Super antifire potion(4)", "Extended super antifire(4)",
        "Anti-venom+(4)", "Antidote++(4)", "Divine super combat potion(4)",
        "Divine ranging potion(4)", "Divine bastion potion(4)", "Super attack(4)",
        "Super strength(4)", "Super defence(4)", "Super energy(4)",
        "Magic potion(4)", "Antifire potion(4)",
    ],
    "food": [
        "Anglerfish", "Manta ray", "Dark crab", "Tuna potato", "Sea turtle",
        "Shark", "Cooked karambwan", "Monkfish", "Summer pie", "Swordfish",
        "Wild pie", "Lobster", "Pineapple pizza", "Admiral pie", "Bass",
        "Cake", "Jug of wine", "Purple sweets",
    ],
    "runes": [
        "Nature rune", "Law rune", "Death rune", "Blood rune", "Soul rune",
        "Cosmic rune", "Astral rune", "Chaos rune", "Wrath rune", "Fire rune",
        "Water rune", "Air rune", "Earth rune", "Body rune", "Mind rune",
        "Lava rune", "Mud rune", "Steam rune", "Smoke rune", "Dust rune",
    ],
    "ammo": [
        "Rune arrow", "Amethyst arrow", "Dragon arrow", "Adamant arrow",
        "Broad bolts", "Amethyst broad bolts", "Runite bolts", "Dragon bolts",
        "Diamond bolts (e)", "Ruby dragon bolts (e)", "Diamond dragon bolts (e)",
        "Adamant dart", "Rune dart", "Dragon dart", "Steel cannonball",
        "Granite cannonball", "Mithril arrow", "Adamant bolts", "Rune javelin",
        "Dragon javelin",
    ],
    "prayer": [
        "Dragon bones", "Superior dragon bones", "Wyrm bones", "Dagannoth bones",
        "Lava dragon bones", "Ourg bones", "Babydragon bones", "Big bones",
        "Bones", "Wyvern bones", "Ensouled dragon head", "Ensouled hellhound head",
        "Ensouled demon head", "Ensouled abyssal head", "Ensouled bloodveld head",
        "Fiendish ashes", "Vile ashes", "Malicious ashes", "Abyssal ashes",
        "Infernal ashes",
    ],
    "materials": [
        "Pure essence", "Feather", "Bow string", "Grimy ranarr weed",
        "Grimy snapdragon", "Grimy torstol", "Snape grass", "Red spiders' eggs",
        "Wine of zamorak", "Coal", "Runite bar", "Magic logs", "Yew logs",
        "Mahogany plank", "Oak plank", "Uncut diamond", "Molten glass",
        "Soft clay", "Amylase crystal", "Ranarr seed",
    ],
}

# ── curated extra membership (searchable, beyond the defaults) ────────────
# closed families the wiki does not pattern cleanly; defaults are auto-added.
MEMBERSHIP = {
    "runes": [
        "Sunfire rune", "Mist rune",
    ],
    "ammo": [
        "Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow",
        "Adamant arrow", "Rune arrow", "Amethyst arrow", "Dragon arrow",
        "Bronze bolts", "Iron bolts", "Steel bolts", "Mithril bolts",
        "Adamant bolts", "Runite bolts", "Dragon bolts", "Broad bolts",
        "Amethyst broad bolts", "Opal bolts (e)", "Pearl bolts (e)",
        "Topaz bolts (e)", "Sapphire bolts (e)", "Emerald bolts (e)",
        "Ruby bolts (e)", "Diamond bolts (e)", "Dragonstone bolts (e)",
        "Onyx bolts (e)", "Ruby dragon bolts (e)", "Diamond dragon bolts (e)",
        "Dragonstone dragon bolts (e)", "Onyx dragon bolts (e)", "Bronze dart",
        "Iron dart", "Steel dart", "Mithril dart", "Adamant dart", "Rune dart",
        "Dragon dart", "Bronze javelin", "Iron javelin", "Steel javelin",
        "Mithril javelin", "Adamant javelin", "Rune javelin", "Dragon javelin",
        "Bronze knife", "Iron knife", "Steel knife", "Mithril knife",
        "Adamant knife", "Rune knife", "Dragon knife", "Bronze cannonball",
        "Iron cannonball", "Steel cannonball", "Mithril cannonball",
        "Adamant cannonball", "Granite cannonball", "Bolt rack",
    ],
    # secondaries, bars, hides, essence, feathers — the skilling stockpile
    # that does not pattern cleanly. Grimy herbs / uncut gems / planks / logs
    # / seeds come from PATTERNS below.
    "materials": [
        "Pure essence", "Daeyalt essence", "Blood essence", "Feather",
        "Stripy feather", "Bow string", "Molten glass", "Soft clay", "Coal",
        "Bronze bar", "Iron bar", "Steel bar", "Silver bar", "Gold bar",
        "Mithril bar", "Adamantite bar", "Runite bar", "Green dragonhide",
        "Blue dragonhide", "Red dragonhide", "Black dragonhide",
        "Green dragon leather", "Blue dragon leather", "Red dragon leather",
        "Black dragon leather", "Cured yak-hide", "Snape grass",
        "Red spiders' eggs", "Wine of zamorak", "Potato cactus", "Limpwurt root",
        "Eye of newt", "Unicorn horn dust", "White berries", "Crushed nest",
        "Amylase crystal", "Cactus spine", "Dragon scale dust", "Chocolate dust",
        "Snake weed", "Ashes", "Nail beast nails", "Mort myre fungus",
        "Toad's legs", "Kebbit teeth dust", "Crushed superior dragon bones",
        "Wolfsbane", "Volcanic ash", "Bucket of sand",
        "Compost", "Supercompost", "Ultracompost", "Wool", "Flax",
        "Ball of wool", "Vial of water", "Vial of blood", "Sacred oil",
        # farming seeds (the real stocked set; the '% seed' pattern caught too
        # much quest/unique junk, so these are curated instead)
        "Guam seed", "Marrentill seed", "Tarromin seed", "Harralander seed",
        "Ranarr seed", "Toadflax seed", "Irit seed", "Avantoe seed",
        "Kwuarm seed", "Snapdragon seed", "Cadantine seed", "Lantadyme seed",
        "Dwarf weed seed", "Torstol seed", "Acorn", "Willow seed", "Maple seed",
        "Yew seed", "Magic seed", "Redwood tree seed", "Apple tree seed",
        "Banana tree seed", "Orange tree seed", "Curry tree seed",
        "Pineapple seed", "Papaya tree seed", "Palm tree seed",
        "Dragonfruit tree seed", "Calquat tree seed", "Celastrus seed",
        "Teak seed", "Mahogany seed", "Potato seed", "Onion seed",
        "Cabbage seed", "Sweetcorn seed", "Watermelon seed", "Snape grass seed",
        "Strawberry seed", "Marigold seed", "Limpwurt seed", "Jute seed",
        "Hammerstone seed", "Barley seed", "Grape seed", "Seaweed spore",
        "Mushroom spore", "Belladonna seed", "Hespori seed",
    ],
}

# ── name patterns for clean resource families (searchable membership) ────
# each: (category, WHERE clause on the items table). Equipment ids and
# discontinued/mode variants are filtered out after the query.
PATTERNS = [
    ("prayer",    "name LIKE '% bones' OR name = 'Bones'"),
    ("prayer",    "name LIKE 'Ensouled % head'"),
    ("prayer",    "name LIKE '%ashes'"),
    ("materials", "name LIKE 'Grimy %'"),
    ("materials", "name LIKE 'Uncut %'"),
    ("materials", "name LIKE '% plank' OR name = 'Plank'"),
    ("materials", "name LIKE '% logs' OR name = 'Logs'"),
]

# quest / Sailing-crate / unique tokens the patterns catch that are not
# supplies a player stocks. Applied to pattern results only.
BLOCKLIST = re.compile(
    r"^(Crate of |Alan's |Animals' |Marinated j'|Grimy note$|"
    r"Ground ashes$|Iban's ashes$|Repair plank$)")


def main():
    if not DB.exists():
        sys.exit(f"missing {DB} — run python3 tools/knowledge/rebuild.py first")
    conn = sqlite3.connect(DB)
    cur = conn.cursor()

    def resolve(name):
        """Lowest non-discontinued integer item id for a display name, or None.
        The items table has TEXT affinity on item_id — some rows are beta-mode
        string ids ("beta30922") which must be skipped. canonicalStock() sums
        variants at runtime, so any group member's id gives the right count —
        the lowest is the canonical icon."""
        rows = cur.execute(
            "SELECT item_id FROM items WHERE name=? AND removal_date IS NULL",
            (name,)).fetchall()
        ids = [int(r[0]) for r in rows if str(r[0]).isdigit()]
        return min(ids) if ids else None

    # category -> ordered list of (id, name, default) — defaults first
    items_by_cat = {key: [] for key, _, _ in CATEGORIES}
    seen_ids = set()          # global dedupe (an id belongs to one category)
    misses = []

    def add(cat, name, is_default):
        item_id = resolve(name)
        if item_id is None:
            if is_default:
                misses.append((cat, name))
            return
        if item_id in seen_ids:
            return
        seen_ids.add(item_id)
        items_by_cat[cat].append((item_id, name, is_default))

    # 1. curated defaults (fail-fast on any miss)
    for cat, names in DEFAULTS.items():
        for name in names:
            add(cat, name, True)

    # 2. food / potions: full consumables-table membership
    for kind, cat in (("food", "food"), ("potion", "potions")):
        names = [r[0] for r in cur.execute(
            "SELECT name FROM consumables WHERE kind=? ORDER BY name", (kind,))]
        for name in names:
            if not RESTRICTED.search(name):
                add(cat, name, False)

    # 3. curated extra membership
    for cat, names in MEMBERSHIP.items():
        for name in names:
            add(cat, name, False)

    # 4. clean name patterns (broad searchable membership)
    for cat, where in PATTERNS:
        rows = cur.execute(
            f"SELECT DISTINCT name FROM items WHERE ({where})"
            " AND removal_date IS NULL ORDER BY name").fetchall()
        for (name,) in rows:
            if not RESTRICTED.search(name) and not BLOCKLIST.search(name):
                add(cat, name, False)

    if misses:
        for cat, name in misses:
            print(f"  UNRESOLVED default: {cat} / {name!r}", file=sys.stderr)
        sys.exit(f"{len(misses)} curated default(s) did not resolve — fix the "
                 f"names in DEFAULTS")

    # ── assemble pack ────────────────────────────────────────────────────
    categories = []
    for key, name, icon_name in CATEGORIES:
        icon = resolve(icon_name)
        if icon is None:
            sys.exit(f"category {key} icon {icon_name!r} did not resolve")
        categories.append({"key": key, "name": name, "icon": icon})

    items = []
    for key, _, _ in CATEGORIES:
        for item_id, name, is_default in items_by_cat[key]:
            row = {"id": item_id, "name": name, "category": key}
            if is_default:
                row["default"] = True
            items.append(row)

    # ── sanity asserts (count floors) ────────────────────────────────────
    for key, _, _ in CATEGORIES:
        n = len(items_by_cat[key])
        d = sum(1 for _, _, dflt in items_by_cat[key] if dflt)
        print(f"  {key:10} {n:4} items ({d} default)")
        if d < 10:
            sys.exit(f"category {key} has only {d} defaults — expected ~20")
        if d > 20:
            sys.exit(f"category {key} has {d} defaults — cap is 20")
    if len(items) < 400:
        sys.exit(f"only {len(items)} items total — expected 400+")

    pack = {
        "source": "knowledge.db consumables (food/potion) + curated resource "
                  "tables + clean name patterns; tools/gen_supplies.py",
        "categories": categories,
        "items": items,
    }
    OUT.write_text(json.dumps(pack, indent=1, ensure_ascii=False) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)} — {len(items)} items, "
          f"{len(categories)} categories")


if __name__ == "__main__":
    main()
