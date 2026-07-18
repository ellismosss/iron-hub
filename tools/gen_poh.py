#!/usr/bin/env python3
"""Generate data/poh.json — the useful player-owned-house progression.

The space/tier curation follows the OSRS wiki's
Guide:POH_Progression_&_Layout_Guide (the community's "what is worth
building" list); every tier's hard data comes from its own furniture
page's {{Infobox Construction}}: Construction level, room, the BUILT
furniture object ids (all decoration/state variants — the in-house
detection signal) and the inventory item id used as the tile icon.
Quest/item gates per tier are curated (famous, few) and validated by the
requirement graph's pack test. The guide page is fetched and each tier
name soft-checked against it so curation drift gets flagged at
generation time.

Levels emit as skillb:Construction:<n> — POH building is the textbook
boostable action gate (crystal saw + tea/stew).

Usage: python3 tools/gen_poh.py
"""

import json
import os
import re
import urllib.parse
import urllib.request

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-poh")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "poh.json")
API = "https://oldschool.runescape.wiki/api.php"

GUIDE_PAGE = "Guide:POH Progression & Layout Guide"

# space id, display name, [(tier page, [extra graph-string gates]), ...]
# — tiers in build-progression order; the first unbuilt tier is "next".
SPACES = [
    ("pool", "Pool", [
        ("Restoration pool", []),
        ("Revitalisation pool", []),
        ("Rejuvenation pool", []),
        ("Fancy rejuvenation pool", []),
        ("Ornate rejuvenation pool", []),
    ]),
    ("jewellery_box", "Jewellery box", [
        ("Basic jewellery box", []),
        ("Fancy jewellery box", []),
        ("Ornate jewellery box", []),
    ]),
    ("altar", "Chapel altar", [
        ("Oak altar", []),
        ("Teak altar", []),
        ("Cloth-covered altar", []),
        ("Mahogany altar", []),
        ("Limestone altar", []),
        ("Marble altar", []),
        ("Gilded altar", []),
    ]),
    ("spellbook_altar", "Spellbook altar", [
        ("Ancient altar", []),
        ("Lunar altar", ["quest:Lunar Diplomacy"]),
        ("Dark altar (Construction)", []),
        ("Occult altar", ["quest:Lunar Diplomacy"]),
    ]),
    ("portal_nexus", "Portal nexus", [
        ("Marble portal nexus", []),
        ("Gilded portal nexus", []),
        ("Crystalline portal nexus", []),
    ]),
    ("teleport_space", "Fairy ring & spirit tree", [
        ("Spirit tree (Construction)", ["quest:Tree Gnome Village"]),
        ("Fairy ring (Construction)", ["queststarted:Fairytale II - Cure a Queen"]),
        ("Spirit tree & fairy ring", ["quest:Tree Gnome Village",
                                      "queststarted:Fairytale II - Cure a Queen"]),
    ]),
    ("glory", "Mounted glory", [
        ("Amulet of glory (mounted)", ["item:1704:1:Amulet of glory"]),
    ]),
    ("mythical_cape", "Mythical cape", [
        ("Mythical cape (mounted)", ["item:22114:1:Mythical cape"]),
    ]),
    ("xerics_talisman", "Xeric's talisman", [
        ("Mounted xeric's talisman", ["item:13393:1:Xeric's talisman"]),
    ]),
    ("digsite_pendant", "Digsite pendant", [
        ("Mounted digsite pendant", ["item:11194:1:Digsite pendant"]),
    ]),
    ("cape_hanger", "Cape hanger", [
        ("Cape hanger", []),
    ]),
    ("lectern", "Lectern", [
        ("Oak lectern", []),
        ("Eagle lectern", []),
        ("Demon lectern", []),
        ("Teak eagle lectern", []),
        ("Teak demon lectern", []),
        ("Mahogany eagle lectern", []),
        ("Mahogany demon lectern", []),
        ("Marble lectern", []),
    ]),
    ("armour_stand", "Armour stand", [
        ("Armour stand", []),
    ]),
]

# curated page-title fixes discovered at generation time
PAGE_FIXES = {}

# tiers whose furniture pages carry no id param — built-object ids curated
# as gameval ObjectID constant NAMES, resolved via javap (fail-fast). The
# chapel altar triplets are the three god variants per tier; pool RECOVERY/
# REGENERATION are the game's names for fancy/ornate rejuvenation.
OBJECT_FIXES = {
    "pool:restoration_pool": ["POH_POOL_RESTORATION"],
    "pool:revitalisation_pool": ["POH_POOL_REVITALISATION"],
    "pool:rejuvenation_pool": ["POH_POOL_REJUVENATION"],
    "pool:fancy_rejuvenation_pool": ["POH_POOL_RECOVERY"],
    "pool:ornate_rejuvenation_pool": ["POH_POOL_REGENERATION"],
    "altar:oak_altar": ["POH_ALTAR_SARADOMIN_1", "POH_ALTAR_ZAMORAK_1", "POH_ALTAR_GUTHIX_1"],
    "altar:teak_altar": ["POH_ALTAR_SARADOMIN_2", "POH_ALTAR_ZAMORAK_2", "POH_ALTAR_GUTHIX_2"],
    "altar:cloth_covered_altar": ["POH_ALTAR_SARADOMIN_3", "POH_ALTAR_ZAMORAK_3", "POH_ALTAR_GUTHIX_3"],
    "altar:mahogany_altar": ["POH_ALTAR_SARADOMIN_4", "POH_ALTAR_ZAMORAK_4", "POH_ALTAR_GUTHIX_4"],
    "altar:limestone_altar": ["POH_ALTAR_SARADOMIN_5", "POH_ALTAR_ZAMORAK_5", "POH_ALTAR_GUTHIX_5"],
    "altar:marble_altar": ["POH_ALTAR_SARADOMIN_6", "POH_ALTAR_ZAMORAK_6", "POH_ALTAR_GUTHIX_6"],
    "altar:gilded_altar": ["POH_ALTAR_SARADOMIN_7", "POH_ALTAR_ZAMORAK_7", "POH_ALTAR_GUTHIX_7"],
    "teleport_space:spirit_tree_construction": ["POH_SPIRIT_TREE"],
    "teleport_space:spirit_tree_fairy_ring": ["POH_SPIRIT_RING"],
    "portal_nexus:crystalline_portal_nexus": ["POH_NEXUS_PORTAL_3"],
}


def gameval_object_ids():
    import glob
    import subprocess
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    out = {}
    for cls in ("net.runelite.api.gameval.ObjectID", "net.runelite.api.gameval.ObjectID1"):
        dump = subprocess.run(["javap", "-classpath", sorted(jars)[-1], "-constants", cls],
                              capture_output=True, text=True, check=True).stdout
        out.update({m.group(1): int(m.group(2)) for m in
                    re.finditer(r"public static final int (\w+) = (-?\d+);", dump)})
    return out


def fetch(page: str):
    os.makedirs(CACHE, exist_ok=True)
    slug = re.sub(r"[^A-Za-z0-9]+", "_", page)
    cached = os.path.join(CACHE, slug + ".json")
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return json.load(f)
    url = API + "?" + urllib.parse.urlencode(
        {"action": "parse", "page": page, "prop": "wikitext",
         "redirects": "1", "format": "json", "formatversion": "2"})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        data = json.load(resp)
    with open(cached, "w", encoding="utf-8") as f:
        json.dump(data, f)
    return data


def infobox(page: str):
    data = fetch(PAGE_FIXES.get(page, page))
    if "parse" not in data:
        raise SystemExit(f"missing furniture page: {page}")
    text = data["parse"]["wikitext"]
    box = {}
    for key in ("level", "room", "id", "itemid", "furniturename"):
        m = re.search(r"^\|\s*" + key + r"\s*=\s*(.+)$", text, re.M)
        if m:
            box[key] = m.group(1).strip()
    if "level" not in box:
        raise SystemExit(f"no construction level on {page}")
    return box


def main():
    guide = fetch(GUIDE_PAGE)
    guide_text = guide.get("parse", {}).get("wikitext", "")
    if len(guide_text) < 5000:
        raise SystemExit("guide page fetch looks wrong")

    object_constants = gameval_object_ids()
    spaces_out = []
    missing_from_guide = []
    for space_id, name, tiers in SPACES:
        tiers_out = []
        for page, gates in tiers:
            box = infobox(page)
            level_match = re.search(r"\d+", box["level"])
            if not level_match:
                raise SystemExit(f"unparseable level on {page}: {box['level']!r}")
            level = int(level_match.group())
            object_ids = []
            for raw in re.split(r"[,;]", box.get("id", "")):
                raw = raw.strip()
                if raw.isdigit():
                    object_ids.append(int(raw))
            tier = {
                "id": space_id + ":" + re.sub(r"[^a-z0-9]+", "_",
                                              page.lower()).strip("_"),
                "name": re.sub(r"^Mounted ", "", re.sub(r"\s*\((mounted|Construction|built)\)$", "", page)),
                "page": PAGE_FIXES.get(page, page),
                "level": level,
                "reqs": [f"skillb:Construction:{level}"] + gates,
                "objectIds": object_ids,
            }
            if not object_ids and tier["id"] in OBJECT_FIXES:
                for constant in OBJECT_FIXES[tier["id"]]:
                    if constant not in object_constants:
                        raise SystemExit(f"unresolved ObjectID.{constant}")
                    object_ids.append(object_constants[constant])
                tier["objectIds"] = object_ids
            if box.get("itemid"):
                m = re.search(r"\d+", box["itemid"])
                if m:
                    tier["icon"] = int(m.group())
            tiers_out.append(tier)
            base = tier["name"].split(" (")[0]
            if base.lower() not in guide_text.lower():
                missing_from_guide.append(page)
        # tile icon: the top tier's item, else the first with one
        icon = None
        for tier in reversed(tiers_out):
            if "icon" in tier:
                icon = tier["icon"]
                break
        space = {"id": space_id, "name": name, "tiers": tiers_out}
        room = infobox(tiers[0][0]).get("room", "")
        room = re.sub(r"\[\[([^|\]]*\|)?([^\]]*)\]\]", r"\2", room)
        if room:
            space["room"] = room
        if icon is not None:
            space["icon"] = icon
        spaces_out.append(space)

    if missing_from_guide:
        print("  note: not named in the guide page (check curation): "
              + ", ".join(missing_from_guide))

    no_ids = [t["id"] for s in spaces_out for t in s["tiers"] if not t["objectIds"]]
    if no_ids:
        print(f"  note: {len(no_ids)} tiers have no object ids (manual-mark only): "
              + ", ".join(no_ids))

    pack = {
        "version": 1,
        "sources": {
            "wiki": "oldschool.runescape.wiki per-furniture Infobox Construction; "
                    "curation per Guide:POH_Progression_&_Layout_Guide",
        },
        "spaces": spaces_out,
    }
    with open(os.path.abspath(OUT), "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    n_tiers = sum(len(s["tiers"]) for s in spaces_out)
    n_ids = sum(len(t["objectIds"]) for s in spaces_out for t in s["tiers"])
    print(f"wrote {os.path.abspath(OUT)}: {len(spaces_out)} spaces, "
          f"{n_tiers} tiers, {n_ids} object ids")
    assert len(spaces_out) >= 12 and n_tiers >= 30


if __name__ == "__main__":
    main()
