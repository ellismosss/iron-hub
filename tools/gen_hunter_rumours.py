#!/usr/bin/env python3
"""Generate data/hunter-rumours.json from the Hunter Rumours plugin.

Source: github.com/geel9/runelite-hunter-rumours @ HR_COMMIT (BSD-2,
Joshua Coffey) — the Hunter, Rumour, Creature, Trap and RumourLocation
enums parsed byte-faithful: rumour targets, trap types with pity
thresholds (bare and full-outfit), per-creature possible Hunter xp drops
(the catch-counting signal), assigning tiers, and hunting locations with
fairy ring codes (deduped to one representative point per area).

Legacy NpcID/ItemID constants resolve via javap over the runelite-api
jar. Hunter levels emit as skillb:Hunter:<n> (catching is boostable).
Fail-fast on any unresolved constant.
"""

import glob
import json
import os
import re
import subprocess
import urllib.request

HR_COMMIT = "4fcf4e895b2b6bb0eea8e9c18d2a737af2396180"
BASE = ("https://raw.githubusercontent.com/geel9/runelite-hunter-rumours/"
        f"{HR_COMMIT}/src/main/java/com/geel/hunterrumours/")

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-hunter", "gen")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "hunter-rumours.json")

# guild hunter outfit pieces (worn checks adjust the pity rate) and the
# quetzal whistle tiers (the "check your whistle" sync affordance) — from
# the plugin's PlayerChanged handler and the farm pack's teleport table
OUTFIT_CONSTANTS = ["GUILD_HUNTER_HEADWEAR", "GUILD_HUNTER_TOP",
                    "GUILD_HUNTER_LEGS", "GUILD_HUNTER_BOOTS"]

HUNTER_NPC_NAMES = {
    "MASTER_WOLF": "Guild Hunter Wolf",
    "EXPERT_TECO": "Guild Hunter Teco",
    "EXPERT_ACO": "Guild Hunter Aco",
    "ADEPT_CERVUS": "Guild Hunter Cervus",
    "ADEPT_ORNUS": "Guild Hunter Ornus",
    "NOVICE_GILMAN": "Guild Hunter Gilman",
}


def fetch(name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, name.replace("/", "-"))
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return f.read()
    req = urllib.request.Request(BASE + name, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        text = resp.read().decode("utf-8")
    with open(cached, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def constants_of(class_name: str) -> dict:
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    out = subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", class_name],
        capture_output=True, text=True, check=True).stdout
    return {m.group(1): int(m.group(2)) for m in
            re.finditer(r"public static final int (\w+) = (-?\d+);", out)}


def resolve(constant: str, table: dict, context: str) -> int:
    if constant not in table:
        raise SystemExit(f"unresolved constant {constant} ({context})")
    return table[constant]


def main():
    item_ids = constants_of("net.runelite.api.ItemID")
    npc_ids = constants_of("net.runelite.api.NpcID")

    # ── Trap.java: name, trap item, pity thresholds ──────────────────
    trap_src = fetch("enums/Trap.java")
    traps = {}
    for m in re.finditer(
            r'^\s*([A-Z_0-9]+)\("([^"]+)", ItemID\.(\w+), (\d+), (\d+)\)',
            trap_src, re.M):
        traps[m.group(1)] = {
            "name": m.group(2),
            "itemId": resolve(m.group(3), item_ids, "trap " + m.group(1)),
            "pity": int(m.group(4)),
            "pityWithOutfit": int(m.group(5)),
        }
    if len(traps) < 9:
        raise SystemExit(f"suspiciously few traps: {len(traps)}")

    # ── Creature.java: npc, sprite item, trap, level, xp drops ───────
    creature_src = fetch("enums/Creature.java")
    creatures = {}
    for m in re.finditer(
            r'^\s*([A-Z_0-9]+)\((NpcID\.\w+|0),\s*ItemID\.(\w+),\s*(\w+),\s*(\d+),\s*'
            r'(?:new int\[\]\{([^}]*)\}|(IntStream[^)]*\)\.toArray\(\)))\)',
            creature_src, re.M):
        name = m.group(1)
        if name == "NONE":
            continue
        npc = 0 if m.group(2) == "0" else resolve(m.group(2)[len("NpcID."):],
                                                  npc_ids, "creature " + name)
        if m.group(6) is not None:
            xp_drops = [int(x) for x in re.findall(r"\d+", m.group(6))]
        else:
            rng = re.search(r"rangeClosed\((\d+),\s*(\d+)\)", m.group(7))
            xp_drops = []  # herbiboar's 1950..2461 — carried as a range
            xp_range = [int(rng.group(1)), int(rng.group(2))]
        creature = {
            "npcId": npc,
            "itemId": resolve(m.group(3), item_ids, "creature " + name),
            "trap": m.group(4),
            "level": int(m.group(5)),
        }
        if m.group(6) is not None:
            creature["xpDrops"] = xp_drops
        else:
            creature["xpRange"] = xp_range
        if creature["trap"] not in traps:
            raise SystemExit(f"creature {name} uses unknown trap {creature['trap']}")
        creatures[name] = creature
    if len(creatures) < 25:
        raise SystemExit(f"suspiciously few creatures: {len(creatures)}")

    # ── Rumour.java: display name, rare-piece item, assigning tiers ──
    rumour_src = fetch("enums/Rumour.java")
    rumours = []
    for m in re.finditer(
            r'^\s*([A-Z_0-9]+)\(Creature\.(\w+),\s*"([^"]+)",\s*ItemID\.(\w+),\s*'
            r'(true|false),\s*(true|false),\s*(true|false),\s*(true|false)\)',
            rumour_src, re.M):
        name = m.group(1)
        if name == "NONE":
            continue
        creature = creatures.get(m.group(2))
        if creature is None:
            raise SystemExit(f"rumour {name} targets unknown creature {m.group(2)}")
        trap = traps[creature["trap"]]
        rumour = {
            "id": name.lower(),
            "name": m.group(3),
            "creature": m.group(2),
            "npcId": creature["npcId"],
            "icon": creature["itemId"],
            "pieceItemId": resolve(m.group(4), item_ids, "rumour " + name),
            "trap": trap["name"],
            "trapItemId": trap["itemId"],
            "pity": trap["pity"],
            "pityWithOutfit": trap["pityWithOutfit"],
            "level": creature["level"],
            "reqs": [f"skillb:Hunter:{creature['level']}"],
            "tiers": [tier for tier, flag in
                      zip(["novice", "adept", "expert", "master"],
                          m.groups()[4:8]) if flag == "true"],
        }
        if "xpDrops" in creature:
            rumour["xpDrops"] = creature["xpDrops"]
        else:
            rumour["xpRange"] = creature["xpRange"]
        rumours.append(rumour)
    if len(rumours) < 28:
        raise SystemExit(f"suspiciously few rumours: {len(rumours)}")

    # ── RumourLocation.java: one representative point per area ───────
    location_src = fetch("enums/RumourLocation.java")
    locations = []
    seen = set()
    spot_counts = {}
    for m in re.finditer(
            r'^\s*[A-Z_0-9]+\((\w+),\s*"([^"]+)",\s*"([A-Z]*)",\s*'
            r'new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)\)',
            location_src, re.M):
        creature, area = m.group(1), m.group(2)
        key = creature + "|" + area
        spot_counts[key] = spot_counts.get(key, 0) + 1
        if key in seen:
            continue
        seen.add(key)
        location = {
            "creature": creature,
            "name": area,
            "x": int(m.group(4)),
            "y": int(m.group(5)),
            "plane": int(m.group(6)),
        }
        if m.group(3):
            location["fairyRing"] = m.group(3)
        locations.append(location)
    for location in locations:
        location["spots"] = spot_counts[location["creature"] + "|" + location["name"]]
    if len(locations) < 40:
        raise SystemExit(f"suspiciously few location areas: {len(locations)}")
    located = {loc["creature"] for loc in locations}
    for name in creatures:
        if name not in located:
            print(f"  note: no locations for {name}")

    # ── hunters + outfit ─────────────────────────────────────────────
    hunter_src = fetch("enums/Hunter.java")
    hunters = []
    for m in re.finditer(r'^\s*([A-Z_0-9]+)\((\d+), "([^"]+)", HunterTier\.(\w+)\)',
                         hunter_src, re.M):
        if m.group(1) == "NONE":
            continue
        hunters.append({
            "id": m.group(1).lower(),
            "npcId": int(m.group(2)),
            "name": m.group(3),
            "npcName": HUNTER_NPC_NAMES[m.group(1)],
            "tier": m.group(4).lower(),
        })
    if len(hunters) != 6:
        raise SystemExit(f"expected 6 hunters, got {len(hunters)}")

    outfit = [resolve(c, item_ids, "outfit") for c in OUTFIT_CONSTANTS]

    pack = {
        "version": 1,
        "sources": {"hunterRumours": HR_COMMIT},
        "hunters": hunters,
        "rumours": rumours,
        "locations": locations,
        "outfit": outfit,
    }
    with open(os.path.abspath(OUT), "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {os.path.abspath(OUT)}: {len(hunters)} hunters, "
          f"{len(rumours)} rumours, {len(locations)} location areas")


if __name__ == "__main__":
    main()
