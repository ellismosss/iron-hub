#!/usr/bin/env python3
"""Generate data/farm-runs.json from Easy Farming's location tables.

Easy Farming (github.com/Speaax/Farming-Helper, BSD-2 — see
licenses/easy-farming-LICENSE) encodes each farm-run stop as a Java
LocationData class: the patch tile, and every teleport option with its
in-game description and item requirements. We extract that data into one
pack so Iron Hub's run planner can rank teleports by what the player
actually owns instead of forcing a per-location config choice.

What the pack does NOT carry (improved on Easy Farming):
  - patch types per location — resolved live from the vendored
    FarmingWorld by the stop's region, so Weiss never claims a flower
    patch it doesn't have;
  - their interface/widget ids and highlight metadata — Iron Hub guides
    through its run overlay + Shortest Path, not click highlighting.

Teleports whose items come from a config-dependent supplier in their
code (house teleport, fairy ring) carry "supplier": "house"/"fairyRing"
and an empty item list — availability for those is access-based, not
item-based.

Item ids resolve through the gameval ItemID constants (javap over the
runelite-api jar in the local Gradle cache); an unknown constant aborts.

Usage:
  python3 tools/gen_farm_runs.py

Sources fetched from the pinned hub commit, cached under
tools/.cache-farmruns/ (gitignored).
"""
import datetime
import glob
import json
import os
import re
import subprocess
import urllib.request

COMMIT = "dc52159eec4f8e0d43237a596c19939b8cd6ff04"  # plugin-hub pinned commit
BASE = (f"https://raw.githubusercontent.com/Speaax/Farming-Helper/{COMMIT}/"
        "src/main/java/com/easyfarming/locations/")
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
CACHE = os.path.join(os.path.dirname(__file__), ".cache-farmruns")
OUT = "src/main/resources/data/farm-runs.json"

# (category, subdir, class, route order) — LocationCatalog.rebuild() order.
LOCATIONS = [
    ("herb", "", "FarmingGuildLocationData"),
    ("herb", "", "ArdougneLocationData"),
    ("herb", "", "CatherbyLocationData"),
    ("herb", "", "FaladorLocationData"),
    ("herb", "", "HarmonyLocationData"),
    ("herb", "", "KourendLocationData"),
    ("herb", "", "MorytaniaLocationData"),
    ("herb", "", "TrollStrongholdLocationData"),
    ("herb", "", "WeissLocationData"),
    ("herb", "", "CivitasLocationData"),
    ("tree", "tree/", "FarmingGuildTreeLocationData"),
    ("tree", "tree/", "FaladorTreeLocationData"),
    ("tree", "tree/", "TaverleyTreeLocationData"),
    ("tree", "tree/", "LumbridgeTreeLocationData"),
    ("tree", "tree/", "VarrockTreeLocationData"),
    ("tree", "tree/", "GnomeStrongholdTreeLocationData"),
    # Easy Farming calls the Varlamore tree patch "Nemus Retreat"; the game
    # (and Luke's route) call it Auburnvale — same patch, region 5427. Rename
    # so the id is tree/auburnvale, keeping Easy Farming's richer teleport list.
    ("tree", "tree/", "NemusRetreatTreeLocationData"),
    ("fruit", "fruittree/", "FarmingGuildFruitTreeLocationData"),
    ("fruit", "fruittree/", "BrimhavenFruitTreeLocationData"),
    ("fruit", "fruittree/", "CatherbyFruitTreeLocationData"),
    ("fruit", "fruittree/", "LletyaFruitTreeLocationData"),
    ("fruit", "fruittree/", "GnomeStrongholdFruitTreeLocationData"),
    ("fruit", "fruittree/", "TreeGnomeVillageFruitTreeLocationData"),
    ("fruit", "fruittree/", "KastoriFruitTreeLocationData"),
    ("hops", "hops/", "LumbridgeHopsLocationData"),
    ("hops", "hops/", "SeersVillageHopsLocationData"),
    ("hops", "hops/", "YanilleHopsLocationData"),
    ("hops", "hops/", "EntranaHopsLocationData"),
    ("hops", "hops/", "AldarinHopsLocationData"),
]

# Per-location unlock requirements (requirement-graph strings) — Easy Farming
# does NOT encode these, so they are curated from the wiki (Herb patch /
# Tree patch pages). Quest tokens validated against the RuneLite Quest enum
# (tools/questnames.txt); a locked location is dropped from a run at runtime.
# Display-name overrides for parsed Easy Farming locations (id derives from
# the name). Nemus Retreat is the game's Auburnvale tree patch.
NAME_OVERRIDE = {
    "NemusRetreatTreeLocationData": "Auburnvale",
}

LOCATION_REQS = {
    "herb/farming-guild": ["skill:Farming:65"],
    "herb/harmony-island": ["diary:Morytania:Elite"],      # patch needs Elite Morytania diary
    "herb/troll-stronghold": ["quest:My Arm's Big Adventure"],
    "herb/weiss": ["quest:Making Friends with My Arm"],
    "herb/morytania": ["queststarted:Priest in Peril"],    # Morytania access
    "herb/civitas-illa-fortis": ["quest:Children of the Sun"],  # Varlamore access
    "tree/farming-guild": ["skill:Farming:65"],
    "tree/auburnvale": ["quest:Children of the Sun"],
    "fruit/farming-guild": ["skill:Farming:85"],           # high tier
    "fruit/lletya": ["queststarted:Mourning's End Part I"],
    "fruit/kastori": ["quest:Children of the Sun"],
    "hops/aldarin": ["quest:Children of the Sun"],
}

# Saplings plantable in each patch category — the plant-pot form the player
# carries on a run (client ItemID constants, resolved to ids below). The run
# culler checks ownership of these to drop stops you can't plant. Herb/hops
# plant seeds directly and aren't listed here, so their stops are never
# sapling-culled (assume-yes when we can't check).
SAPLINGS = {
    "tree": ["PLANTPOT_OAK_SAPLING", "PLANTPOT_WILLOW_SAPLING",
             "PLANTPOT_MAPLE_SAPLING", "PLANTPOT_YEW_SAPLING",
             "PLANTPOT_MAGIC_TREE_SAPLING"],
    "fruit": ["PLANTPOT_APPLE_SAPLING", "PLANTPOT_BANANA_SAPLING",
              "PLANTPOT_ORANGE_SAPLING", "PLANTPOT_CURRY_SAPLING",
              "PLANTPOT_PINEAPPLE_SAPLING", "PLANTPOT_PAPAYA_SAPLING",
              "PLANTPOT_PALM_SAPLING", "PLANTPOT_DRAGONFRUIT_SAPLING"],
    "calquat": ["PLANTPOT_CALQUAT_SAPLING"],
    "celastrus": ["PLANTPOT_CELASTRUS_TREE_SAPLING"],
    "hardwood": ["PLANTPOT_TEAK_SAPLING", "PLANTPOT_MAHOGANY_SAPLING"],
}

# Curated additions — calquat / celastrus / specific Varlamore patches the
# Combo tree run needs, which Easy Farming's tables don't carry. Tiles are
# region-accurate: each sits in the vendored FarmingWorld's PRIMARY region for
# that patch (FarmingWorld.java), so live patch state resolves. Teleports
# either reuse a sibling location's parsed list ("copy") or are given inline.
# Great Conch is the wiki's third calquat site (Luke's "Summer Shore"); it is
# an instanced region, so its live state may not always resolve (assume-yes).
VARLAMORE_TELEPORTS = [
    {"id": "Quetzal_whistle", "category": "ITEM",
     "description": "Quetzal whistle (basic/enhanced/perfected).",
     "supplier": None, "items": [("HG_QUETZALWHISTLE_BASIC", 1)]},
    {"id": "Pendant_of_Ates", "category": "ITEM", "description": "Pendant of Ates.",
     "supplier": None, "items": [("PENDANT_OF_ATES", 1)]},
    {"id": "None", "category": "NONE",
     "description": "No teleport - travel there on your own.",
     "supplier": None, "items": []},
]
CURATED = [
    {"id": "celastrus/farming-guild", "category": "celastrus", "name": "Farming Guild",
     "point": (1245, 3752, 0), "reqs": ["skill:Farming:85"], "copy": "tree/farming-guild"},
    {"id": "calquat/tai-bwo-wannai", "category": "calquat", "name": "Tai Bwo Wannai",
     "point": (2796, 3101, 0), "reqs": ["skill:Farming:72"],
     "teleports": [
         {"id": "Fairy_Ring", "category": "FAIRY_RING",
          "description": "Fairy ring code CKR, beside the patch.",
          "supplier": "fairyRing", "items": []},
         {"id": "None", "category": "NONE",
          "description": "No teleport - travel there on your own.",
          "supplier": None, "items": []},
     ]},
    {"id": "calquat/kastori", "category": "calquat", "name": "Kastori",
     "point": (1352, 3054, 0), "reqs": ["quest:Children of the Sun", "skill:Farming:72"],
     "teleports": VARLAMORE_TELEPORTS},
    {"id": "calquat/summer-shore", "category": "calquat", "name": "The Great Conch",
     "point": (3160, 2400, 0), "reqs": ["quest:Children of the Sun", "skill:Farming:72"],
     "teleports": VARLAMORE_TELEPORTS},
    # Hardwood tree run (Fossil Island / Locus Oasis / Anglers' Retreat).
    {"id": "hardwood/fossil-island", "category": "hardwood", "name": "Fossil Island",
     "point": (3702, 3810, 0), "reqs": ["quest:Bone Voyage"],
     "teleports": [
         {"id": "Digsite_pendant", "category": "ITEM",
          "description": "Digsite pendant to Fossil Island.",
          "supplier": None, "items": [("NECKLACE_OF_DIGSITE_5", 1)]},
         {"id": "Fairy_Ring", "category": "FAIRY_RING", "description": "Fairy ring, then run.",
          "supplier": "fairyRing", "items": []},
         {"id": "None", "category": "NONE",
          "description": "No teleport - travel there on your own.",
          "supplier": None, "items": []},
     ]},
    {"id": "hardwood/locus-oasis", "category": "hardwood", "name": "Locus Oasis",
     "point": (1690, 2970, 0), "reqs": ["quest:Children of the Sun"],
     "teleports": [
         {"id": "Quetzal_whistle", "category": "ITEM",
          "description": "Quetzal whistle to the Hunter Guild, then run.",
          "supplier": None, "items": [("HG_QUETZALWHISTLE_BASIC", 1)]},
         {"id": "Fairy_Ring", "category": "FAIRY_RING", "description": "Fairy ring ajp, then run.",
          "supplier": "fairyRing", "items": []},
         {"id": "None", "category": "NONE",
          "description": "No teleport - travel there on your own.",
          "supplier": None, "items": []},
     ]},
    {"id": "hardwood/anglers-retreat", "category": "hardwood", "name": "Anglers' Retreat",
     "point": (2465, 2710, 0),
     "reqs": ["quest:The Ribbiting Tale of a Lily Pad Labour Dispute",
              "skill:Construction:50", "skill:Sailing:51"],
     "teleports": [
         {"id": "None", "category": "NONE",
          "description": "Travel via Corsair Cove and use the rowboat.",
          "supplier": None, "items": []},
     ]},
    # Bush patches for the Hop and bush run (hops patches already exist).
    {"id": "bush/champions-guild", "category": "bush", "name": "Champions' Guild",
     "point": (3182, 3357, 0), "reqs": [],
     "teleports": [
         {"id": "Chronicle", "category": "ITEM",
          "description": "Chronicle teleport to the Champions' Guild.",
          "supplier": None, "items": [("CHRONICLE", 1)]},
         {"id": "Combat_bracelet", "category": "ITEM",
          "description": "Combat bracelet to the Champions' Guild.",
          "supplier": None, "items": [("JEWL_BRACELET_OF_COMBAT", 1)]},
         {"id": "None", "category": "NONE",
          "description": "No teleport - travel there on your own.",
          "supplier": None, "items": []},
     ]},
    {"id": "bush/ardougne-monastery", "category": "bush", "name": "Ardougne Monastery",
     "point": (2612, 3226, 0), "reqs": [], "copy": "herb/ardougne"},
    {"id": "bush/etceteria", "category": "bush", "name": "Etceteria",
     "point": (2612, 3872, 0), "reqs": ["quest:The Fremennik Trials"],
     "teleports": [
         {"id": "Ring_of_Wealth", "category": "ITEM",
          "description": "Ring of wealth to Miscellania, run north-east.",
          "supplier": None, "items": [("RING_OF_WEALTH", 1)]},
         {"id": "Fairy_Ring", "category": "FAIRY_RING",
          "description": "Fairy ring cip, then run east.",
          "supplier": "fairyRing", "items": []},
         {"id": "None", "category": "NONE",
          "description": "No teleport - travel there on your own.",
          "supplier": None, "items": []},
     ]},
    {"id": "bush/farming-guild", "category": "bush", "name": "Farming Guild",
     "point": (1232, 3736, 0), "reqs": ["skill:Farming:45"], "copy": "tree/farming-guild"},
    {"id": "bush/rimmington", "category": "bush", "name": "Rimmington",
     "point": (2938, 3212, 0), "reqs": [],
     "teleports": [
         {"id": "Portal_Nexus", "category": "PORTAL_NEXUS",
          "description": "House portal redirected to Rimmington.",
          "supplier": "house", "items": []},
         {"id": "Rimmington_Tele_Tab", "category": "ITEM",
          "description": "Rimmington teleport tablet.",
          "supplier": None, "items": [("NZONE_TELETAB_RIMMINGTON", 1)]},
         {"id": "None", "category": "NONE",
          "description": "No teleport - travel there on your own.",
          "supplier": None, "items": []},
     ]},
]

# Named curated routes (ordered location ids) — the "one plan" runs that cross
# categories in a specific order, which the auto-grouping templates can't
# express. The run culler trims these to what's unlocked / ready / plantable.
ROUTES = {
    # Wiki "Herb run" order (Farming_runs#Herb run): Falador, Port Phasmatys,
    # Ardougne, Catherby, Hosidius, Farming Guild, Ortus/Civitas, Troll
    # Stronghold, Weiss, Harmony Island.
    "Herb run": [
        "herb/falador", "herb/morytania", "herb/ardougne", "herb/catherby",
        "herb/kourend", "herb/farming-guild", "herb/civitas-illa-fortis",
        "herb/troll-stronghold", "herb/weiss", "herb/harmony-island",
    ],
    # Wiki "Tree run" order: Lumbridge, Varrock Palace, Falador Park, Taverley,
    # Gnome Stronghold, Farming Guild, Auburn Valley.
    "Tree run": [
        "tree/lumbridge", "tree/varrock", "tree/falador", "tree/taverley",
        "tree/gnome-stronghold", "tree/farming-guild", "tree/auburnvale",
    ],
    # Wiki "Fruit tree run" order (fruit + calquat): Gnome Stronghold, Tree
    # Gnome Village, Catherby, Farming Guild, Lletya, Brimhaven, Tai Bwo Wannai
    # calquat, Kastori calquat+fruit, The Summer Shore calquat.
    "Fruit tree run": [
        "fruit/gnome-stronghold", "fruit/tree-gnome-village", "fruit/catherby",
        "fruit/farming-guild", "fruit/lletya", "fruit/brimhaven",
        "calquat/tai-bwo-wannai", "calquat/kastori", "fruit/kastori",
        "calquat/summer-shore",
    ],
    # Luke's optimal Combo tree run (regular + fruit + calquat + celastrus) =
    # the wiki's combined tree run. "Start at GE" is deferred to the
    # banking-steps spec, so this begins at the first patch.
    "Combo tree run": [
        "fruit/gnome-stronghold", "tree/gnome-stronghold", "fruit/tree-gnome-village",
        "tree/farming-guild", "fruit/farming-guild", "celastrus/farming-guild",
        "tree/lumbridge", "tree/varrock", "tree/falador", "tree/taverley",
        "fruit/catherby", "fruit/brimhaven", "calquat/tai-bwo-wannai",
        "fruit/lletya", "fruit/kastori", "calquat/kastori", "tree/auburnvale",
        "calquat/summer-shore",
    ],
    # Wiki "Hardwood tree run": Fossil Island, Locus Oasis, Anglers' Retreat.
    "Hardwood tree run": [
        "hardwood/fossil-island", "hardwood/locus-oasis", "hardwood/anglers-retreat",
    ],
    # Wiki "Hop and bush run" — interleaved hop and bush patches in route order.
    "Hop and bush run": [
        "bush/champions-guild", "hops/lumbridge", "bush/ardougne-monastery",
        "hops/seers-village", "hops/aldarin", "bush/etceteria", "hops/yanille",
        "bush/farming-guild", "bush/rimmington", "hops/entrana",
    ],
}

POINT = re.compile(r"PATCH_POINT = new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)")
NAME = re.compile(r'new Location\(\s*[^;]*?,\s*"([^"]+)",\s*(?:true|false)', re.DOTALL)
TELEPORT = re.compile(
    r'new Teleport\(\s*"([^"]+)",\s*Teleport\.Category\.(\w+),\s*"([^"]+)",', re.DOTALL)
NONE_TELEPORT = re.compile(r'Teleport\.none\(')
ITEM = re.compile(r"ItemRequirement\(ItemID\.(\w+),\s*(\d+)\)")


def gameval_item_ids() -> dict:
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    out = subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", "net.runelite.api.gameval.ItemID"],
        capture_output=True, text=True, check=True).stdout
    ids = {}
    for m in re.finditer(r"public static final int (\w+) = (\d+);", out):
        ids[m.group(1)] = int(m.group(2))
    if len(ids) < 10000:
        raise SystemExit(f"suspiciously small gameval ItemID dump: {len(ids)}")
    return ids


def fetch(subdir: str, name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, name + ".java")
    if not os.path.exists(cached):
        req = urllib.request.Request(BASE + subdir + name + ".java", headers={"User-Agent": UA})
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
        with open(cached, "w", encoding="utf-8") as f:
            f.write(body)
    with open(cached, encoding="utf-8") as f:
        return f.read()


def teleport_blocks(source: str):
    """Each `new Teleport(...)` call body, brace/paren balanced."""
    for m in TELEPORT.finditer(source):
        depth = 1
        i = source.index("(", m.start()) + 1
        while depth > 0:
            if source[i] == "(":
                depth += 1
            elif source[i] == ")":
                depth -= 1
            i += 1
        yield m.group(1), m.group(2), m.group(3), source[m.start():i]


def parse_location(category: str, cls: str, source: str, item_ids: dict) -> dict:
    point = POINT.search(source)
    name = NAME.search(source)
    if not point or not name:
        raise SystemExit(f"could not parse patch point/name from {cls}")

    teleports = []
    for enum_option, tele_category, description, block in teleport_blocks(source):
        supplier = None
        if "houseTeleportSupplier.get()" in block:
            supplier = "house"
        elif "fairyRingSupplier.get()" in block:
            supplier = "fairyRing"
        items = []
        for item in ITEM.finditer(block):
            if item.group(1) not in item_ids:
                raise SystemExit(f"unknown gameval ItemID.{item.group(1)} in {cls}")
            items.append({"itemId": item_ids[item.group(1)], "qty": int(item.group(2))})
        teleports.append({
            "id": enum_option,
            "category": tele_category,
            "description": description,
            "supplier": supplier,
            "items": items,
        })
    if NONE_TELEPORT.search(source):
        teleports.append({
            "id": "None",
            "category": "NONE",
            "description": "No teleport - travel there on your own.",
            "supplier": None,
            "items": [],
        })
    if not teleports:
        raise SystemExit(f"no teleports parsed from {cls}")

    display_name = NAME_OVERRIDE.get(cls, name.group(1))
    loc_id = category + "/" + re.sub(r"[^a-z0-9]+", "-", display_name.lower()).strip("-")
    return {
        "id": loc_id,
        "category": category,
        "name": display_name,
        "point": {
            "x": int(point.group(1)),
            "y": int(point.group(2)),
            "plane": int(point.group(3)),
        },
        "reqs": LOCATION_REQS.get(loc_id, []),
        "teleports": teleports,
    }


def validate_quest_tokens(locations):
    here = os.path.dirname(__file__)
    quest_names = {ln.strip().lower() for ln in open(os.path.join(here, "questnames.txt"))
                   if ln.strip()}
    for loc in locations:
        for req in loc["reqs"]:
            if req.startswith(("quest:", "queststarted:")):
                name = req.split(":", 1)[1].rstrip(".").lower()
                if name not in quest_names:
                    raise SystemExit(f"{loc['id']}: quest not in the Quest enum: {name!r}")


def curated_teleports(spec: dict, by_id: dict, item_ids: dict):
    """Resolve a curated location's teleports: reuse a sibling's parsed list
    ("copy"), or build from inline (constant-name, qty) item tuples."""
    if "copy" in spec:
        src = by_id.get(spec["copy"])
        if src is None:
            raise SystemExit(f"{spec['id']}: copy source {spec['copy']!r} not found")
        return [dict(t) for t in src["teleports"]]
    teleports = []
    for t in spec["teleports"]:
        items = []
        for name, qty in t["items"]:
            if name not in item_ids:
                raise SystemExit(f"unknown gameval ItemID.{name} in {spec['id']}")
            items.append({"itemId": item_ids[name], "qty": qty})
        teleports.append({**t, "items": items})
    return teleports


def main():
    item_ids = gameval_item_ids()
    locations = []
    for category, subdir, cls in LOCATIONS:
        locations.append(parse_location(category, cls, fetch(subdir, cls), item_ids))

    by_id = {loc["id"]: loc for loc in locations}
    for spec in CURATED:
        x, y, plane = spec["point"]
        locations.append({
            "id": spec["id"],
            "category": spec["category"],
            "name": spec["name"],
            "point": {"x": x, "y": y, "plane": plane},
            "reqs": spec["reqs"],
            "teleports": curated_teleports(spec, by_id, item_ids),
        })

    ids = [loc["id"] for loc in locations]
    assert len(ids) == len(set(ids)), "duplicate location ids"
    assert len(locations) == len(LOCATIONS) + len(CURATED)
    validate_quest_tokens(locations)
    unknown = set(LOCATION_REQS) - set(ids)
    assert not unknown, f"LOCATION_REQS has ids not in the pack: {unknown}"
    for name, route in ROUTES.items():
        missing = [i for i in route if i not in set(ids)]
        assert not missing, f"route {name!r} references unknown ids: {missing}"
    total_teleports = sum(len(l["teleports"]) for l in locations)
    assert total_teleports > 100, f"only {total_teleports} teleports parsed"

    saplings = {}
    for category, names in SAPLINGS.items():
        ids = []
        for n in names:
            if n not in item_ids:
                raise SystemExit(f"unknown gameval ItemID.{n} in SAPLINGS[{category}]")
            ids.append(item_ids[n])
        saplings[category] = ids

    pack = {
        "source": f"github.com/Speaax/Farming-Helper@{COMMIT[:7]} (BSD-2-Clause) "
                  "+ curated calquat/celastrus/Varlamore patches and routes",
        "generated": datetime.date.today().isoformat(),
        "locations": locations,
        "saplings": saplings,
        "routes": ROUTES,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {OUT}: {len(locations)} locations ({len(CURATED)} curated), "
          f"{total_teleports} teleport options, {len(ROUTES)} routes, "
          f"{sum(len(v) for v in saplings.values())} saplings")


if __name__ == "__main__":
    main()
