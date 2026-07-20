#!/usr/bin/env python3
"""Generate data/boat-upgrades.json — the Sailing boat-upgrade catalog.

Ported from the Boat Upgrades hub plugin (IEarnSolo/boat-upgrades, BSD-2)
at the plugin-hub pinned commit: UpgradeData.java is the upgrade table
(part, boat type, tier, Sailing/Construction levels, materials),
SchematicUtils.java the 10 late-game schematic gates (LOST_SCHEMATIC_*
varbits), FacilityService.java the facility-detection object-id sets —
all parsed from the fetched source, never retyped. Material names resolve
to item ids via the generated item-name index; facility object ids and
schematic varbits resolve via javap over the gameval classes (fail-fast
on any unresolved constant).

Levels emit as skillb: — shipwright building is a boostable action gate
(the reference itself reads boosted levels). Schematic gates emit as
unlock:schematic_<slug>, mirrored from the LOST_SCHEMATIC_* varbits by
the module.

Per-part benefit copy is curated from each part's own wiki page intro
and soft-checked against the fetched page text (drift fails the run);
every per-tier wiki page name (the reference's own linking transform) is
verified to exist.

Usage: python3 tools/gen_boat_upgrades.py
"""

import glob
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request

COMMIT = "0c0978b8c4634a4d215450f6b71054c01571247a"
RAW = ("https://raw.githubusercontent.com/IEarnSolo/boat-upgrades/"
       + COMMIT + "/src/main/java/com/boatupgrades/")

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-boat-upgrades")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "boat-upgrades.json")
API = "https://oldschool.runescape.wiki/api.php"

# part key (verbatim from UpgradeData) -> display name, kind, wiki page for
# the benefit soft-check, curated benefit line, a keyword that must appear
# on the fetched page (proves the copy still matches the wiki)
PART_META = {
    "Base": ("Base", "core", "Hull",
             "Hitpoints, defences and speed of the raft.", "hitpoints"),
    "Hull": ("Hull", "core", "Hull",
             "Hitpoints, defences and speed of the boat.", "hitpoints"),
    "Helm": ("Helm", "core", "Rune helm",
             "Navigation; higher tiers resist stronger rapids.", "rapids"),
    "Sails": ("Mast & sails", "core", "Sails",
              "Speed; trim every 30 seconds for a boost and Sailing xp.", "trimmed"),
    "Keel": ("Keel", "core", "Keel",
             "Armour and hitpoints; adamant+ opens crystal-flecked waters.", "armour"),
    "Cargo Hold": ("Cargo hold", "facility", "Cargo hold",
                   "Stores cargo and salvage while sailing.", "storage"),
    "Salvaging Hook": ("Salvaging hook", "facility", "Salvaging hook",
                       "Salvage shipwrecks while sailing for xp and salvage.", "shipwreck"),
    "Cannon": ("Cannon", "facility", "Cannon (facility)",
               "Boat combat; fires cannonballs up to its own tier.", "cannonball"),
    "Teleport Focus": ("Teleport focus", "facility", "Teleport focus (facility)",
                       "Lets Summon Boat teleport the boat to your dock or mooring.",
                       "teleport"),
    "Wind Device": ("Wind catcher", "facility", "Wind catcher",
                    "Stores wind motes; release for a speed boost at sea.", "wind motes"),
    "Trawling Net": ("Trawling net", "facility", "Rope trawling net",
                     "Used for deep-sea trawling.", "trawling"),
    "Chum Station": ("Chum station", "facility", "Chum station",
                     "Baits shoals while deep-sea trawling.", "shoals"),
    "Fathom Device": ("Fathom stone", "facility", "Fathom stone",
                      "Locates shoals while deep-sea trawling.", "shoals"),
    "Range": ("Range", "facility", "Range (facility)",
              "Cook food at sea.", "cook"),
    "Keg": ("Keg", "facility", "Keg (facility)",
            "Stores ales rewarded from sea charting.", "ales"),
    "Anchor": ("Anchor", "facility", "Anchor (facility)",
               "Holds the boat in place at sea.", "in place"),
    "Inoculation Station": ("Inoculation station", "facility", "Inoculation station",
                            "Safely traverse the Shrouded Ocean's fetid waters.", "fetid"),
    "Salvaging Station": ("Salvaging station", "facility", "Salvaging station",
                          "Sort salvage on board.", "salvage"),
    "Crystal Extractor": ("Crystal extractor", "facility", "Crystal extractor",
                          "Generates wind motes for the wind/gale catcher.", "wind motes"),
    "Eternal Brazier": ("Eternal brazier", "facility", "Eternal brazier",
                        "Safely traverse the icy Northern Ocean.", "icy"),
}

# The reference's own wiki-link disambiguation (BoatUpgradesPanel.makeClickable)
FACILITY_PAGE_SUFFIX = {"Teleport focus", "Greater teleport focus", "Anchor",
                        "Range", "Keg"}


def fetch_source(name: str) -> str:
    cached = os.path.join(CACHE, name.replace("/", "_"))
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


def wiki_api(params: dict) -> dict:
    os.makedirs(CACHE, exist_ok=True)
    qs = urllib.parse.urlencode({**params, "format": "json"})
    cached = os.path.join(CACHE, "api_" + re.sub(r"[^A-Za-z0-9]+", "_", qs)[:180] + ".json")
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return json.load(f)
    req = urllib.request.Request(API + "?" + qs, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        data = json.load(resp)
    with open(cached, "w", encoding="utf-8") as f:
        json.dump(data, f)
    return data


def javap_constants(class_name: str) -> dict:
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    dump = subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", class_name],
        capture_output=True, text=True, check=True).stdout
    return {m.group(1): int(m.group(2)) for m in
            re.finditer(r"public static final int (\w+) = (-?\d+);", dump)}


def strip_comments(java: str) -> str:
    java = re.sub(r"/\*.*?\*/", "", java, flags=re.S)
    return re.sub(r"//[^\n]*", "", java)


# ── UpgradeData.java: the catalog ────────────────────────────────────────

def parse_catalog(java: str):
    java = strip_comments(java)
    rows = []
    pattern = re.compile(
        r'OPTIONS\.add\(new UpgradeOption\(\s*"([^"]+)"\s*,\s*(-?\d+)\s*,\s*(\d+)'
        r'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*"([^"]+)"\s*,\s*Arrays\.asList\((.*?)\)\)\)',
        re.S)
    for m in pattern.finditer(java):
        part, boat_type, tier, sailing, construction, name, mats_src = m.groups()
        # no trailing \) — the row terminator ))) eats the last material's paren
        materials = [{"name": mm.group(1), "qty": int(mm.group(2))}
                     for mm in re.finditer(r'new Material\(\s*"([^"]+)"\s*,\s*(\d+)',
                                           mats_src)]
        if not materials:
            raise SystemExit(f"row {name!r}: no materials parsed")
        rows.append({
            "part": part, "boatType": int(boat_type), "tier": int(tier),
            "sailing": int(sailing), "construction": int(construction),
            "name": name, "materials": materials,
        })
    excluded = re.search(r"RAFT_EXCLUDED_FACILITIES\s*=\s*Set\.of\((.*?)\);", java, re.S)
    raft_excluded = set(re.findall(r'"([^"]+)"', excluded.group(1)))
    return rows, raft_excluded


# ── SchematicUtils.java: the 10 late-game gates ──────────────────────────

def parse_schematics(java: str, varbits: dict):
    out = {}
    for m in re.finditer(
            r'new SchematicEntry\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*VarbitID\.(\w+)\s*\)',
            java):
        schematic_name, upgrade_name, constant = m.groups()
        if constant not in varbits:
            raise SystemExit(f"unknown gameval VarbitID.{constant}")
        slug = re.sub(r"[^a-z0-9]+", "_", schematic_name.lower()
                      .removesuffix(" schematic")).strip("_")
        out[upgrade_name] = {"slug": slug, "name": schematic_name,
                             "varbit": varbits[constant]}
    if len(out) != 10:
        raise SystemExit(f"expected 10 schematics, parsed {len(out)}")
    return out


# ── FacilityService.java: facility object-id sets + set -> (part, tier) ──

def parse_detection(java: str, object_ids: dict):
    java = strip_comments(java)
    sets = {}
    for m in re.finditer(
            r"private static final Set<Integer>\s+(\w+)\s*=\s*Set\.of\((.*?)\);",
            java, re.S):
        set_name, body = m.groups()
        ids = []
        for cm in re.finditer(r"ObjectID\.(\w+)", body):
            const = cm.group(1)
            if const not in object_ids:
                raise SystemExit(f"unknown gameval ObjectID.{const}")
            ids.append(object_ids[const])
        sets[set_name] = sorted(ids)

    # set name -> (part, tier), from processSceneGameObject + the IdToTier fns
    mapping = {}
    scan = re.search(r"private void processSceneGameObject.*?\n    \}", java, re.S).group(0)
    for m in re.finditer(
            r"if \(((?:\w+\.contains\(id\)(?:\s*\|\|\s*)?)+)\)\s*\{\s*"
            r'updateHighest\("([^"]+)",\s*(\w+\(id\)|\d+)\);', scan):
        guards = re.findall(r"(\w+)\.contains", m.group(1))
        part, tier_expr = m.group(2), m.group(3).strip()
        if tier_expr.isdigit():
            for g in guards:
                mapping[g] = (part, int(tier_expr))
        else:
            fn = re.search(
                r"private int " + tier_expr.split("(")[0]
                + r"\(int id\)\s*\{(.*?)\n    \}", java, re.S).group(1)
            for fm in re.finditer(r"if \((\w+)\.contains\(id\)\) return (\d+);", fn):
                mapping[fm.group(1)] = (part, int(fm.group(2)))

    # UPSTREAM BUG (deliberate deviation): the reference's fathomIdToTier maps
    # FATHOM_PEARL->0 and FATHOM_STONE->1, inverted vs its own catalog (Fathom
    # stone = tier 0 @ Sailing 70, pearl = tier 1 @ 91). Detecting a built
    # stone as tier 1 would hide the pearl upgrade — swap them back.
    assert mapping["FATHOM_STONE"] == ("Fathom Device", 1), mapping["FATHOM_STONE"]
    mapping["FATHOM_STONE"] = ("Fathom Device", 0)
    mapping["FATHOM_PEARL"] = ("Fathom Device", 1)

    detection = []
    for set_name, (part, tier) in sorted(mapping.items(), key=lambda e: (e[1][0], e[1][1])):
        detection.append({"part": part, "tier": tier, "objectIds": sets[set_name]})
    # merge same part+tier (a tier's raft/skiff/sloop variants live in one set
    # already, but keep the shape one-entry-per-part-tier regardless)
    merged = {}
    for d in detection:
        merged.setdefault((d["part"], d["tier"]), []).extend(d["objectIds"])
    return [{"part": p, "tier": t, "objectIds": sorted(set(ids))}
            for (p, t), ids in sorted(merged.items())]


# ── item-name resolution (gen_dailies convention + potion-dose parens) ───

def index_item_ids() -> dict:
    with open(os.path.join(HERE, "..", "src", "main", "resources", "data",
                           "index", "item-names.json")) as f:
        return json.load(f)


def resolve_item(name: str, index: dict) -> int:
    # "Relicym's balm(4)" -> RELICYMS_BALM4 (dose suffixes fuse in constants)
    fused = re.sub(r"\((\d)\)$", r"\1", name)
    key = re.sub(r"[^A-Z0-9]+", "_", fused.upper().replace("'", "")).strip("_")
    if key not in index:
        raise SystemExit(f"item not in the name index: {name!r} (looked for {key})")
    return index[key]


# ── wiki page names + existence check ────────────────────────────────────

def row_page(name: str) -> str:
    page = name + (" (facility)" if name in FACILITY_PAGE_SUFFIX else "")
    return page.replace("&", "and")


def verify_pages(pages):
    pages = sorted(set(pages))
    for i in range(0, len(pages), 50):
        batch = pages[i:i + 50]
        data = wiki_api({"action": "query", "titles": "|".join(batch),
                         "redirects": 1})
        for pid, pg in data["query"]["pages"].items():
            if int(pid) < 0:
                raise SystemExit(f"wiki page missing: {pg.get('title')!r}")


def soft_check_benefits():
    for part, (_, _, page, benefit, keyword) in PART_META.items():
        data = wiki_api({"action": "query", "prop": "extracts", "exintro": 1,
                         "explaintext": 1, "titles": page, "redirects": 1})
        pg = next(iter(data["query"]["pages"].values()))
        extract = (pg.get("extract") or "").lower()
        if not extract:
            raise SystemExit(f"{part}: benefit page {page!r} has no extract")
        if keyword.lower() not in extract:
            raise SystemExit(
                f"{part}: keyword {keyword!r} no longer on {page!r} — "
                f"re-read the page and update the benefit copy")


def main():
    rows, raft_excluded = parse_catalog(fetch_source("UpgradeData.java"))
    varbits = javap_constants("net.runelite.api.gameval.VarbitID")
    schematics = parse_schematics(fetch_source("utils/SchematicUtils.java"), varbits)
    object_ids = javap_constants("net.runelite.api.gameval.ObjectID")
    object_ids.update(javap_constants("net.runelite.api.gameval.ObjectID1"))
    detection = parse_detection(fetch_source("FacilityService.java"), object_ids)
    index = index_item_ids()

    upgrades = []
    for r in rows:
        reqs = []
        if r["sailing"] > 1:
            reqs.append(f"skillb:Sailing:{r['sailing']}")
        if r["construction"] > 1:
            reqs.append(f"skillb:Construction:{r['construction']}")
        schematic = schematics.get(r["name"])
        if schematic:
            reqs.append("unlock:schematic_" + schematic["slug"])
        materials = [{"name": m["name"], "itemId": resolve_item(m["name"], index),
                      "qty": m["qty"]} for m in r["materials"]]
        bt = "all" if r["boatType"] == -1 else "b" + str(r["boatType"])
        upgrades.append({
            "id": re.sub(r"[^a-z0-9]+", "_", r["part"].lower()).strip("_")
                  + "_" + bt + "_t" + str(r["tier"]),
            "part": r["part"], "boatType": r["boatType"], "tier": r["tier"],
            "name": r["name"], "page": row_page(r["name"]),
            "sailing": r["sailing"], "construction": r["construction"],
            "reqs": reqs,
            "schematic": schematic["name"] if schematic else None,
            "materials": materials,
        })

    parts = []
    seen = []
    for r in rows:
        if r["part"] not in seen:
            seen.append(r["part"])
    if set(seen) != set(PART_META):
        raise SystemExit(f"part drift: {sorted(set(seen) ^ set(PART_META))}")
    for key in seen:
        name, kind, _, benefit, _ = PART_META[key]
        parts.append({"key": key, "name": name, "kind": kind,
                      "benefit": benefit,
                      "raftExcluded": key in raft_excluded})

    verify_pages([u["page"] for u in upgrades])
    soft_check_benefits()

    # ── sanity asserts ───────────────────────────────────────────────────
    assert len(upgrades) >= 90, len(upgrades)
    assert len(parts) == 20, len(parts)
    assert sum(1 for u in upgrades if u["schematic"]) >= 10
    core = {"Base", "Hull", "Helm", "Sails", "Keel"}
    for p in parts:
        if p["kind"] == "facility":
            assert any(d["part"] == p["key"] for d in detection), p["key"]
        else:
            assert p["key"] in core, p["key"]
    # the reference's noted quirk: Linen trawling net construction differs
    # between skiffs and sloops
    linen = {u["boatType"]: u["construction"] for u in upgrades
             if u["name"] == "Linen trawling net"}
    assert linen[1] != linen[2], linen
    for u in upgrades:
        for m in u["materials"]:
            assert m["itemId"] > 0, m

    out = {
        "version": 1,
        "source": "IEarnSolo/boat-upgrades @ " + COMMIT[:7] + " (BSD-2)",
        "parts": parts,
        "upgrades": upgrades,
        "detection": {
            "facilities": detection,
            "schematics": sorted(
                ({"slug": s["slug"], "name": s["name"], "upgrade": upg,
                  "varbit": s["varbit"]} for upg, s in schematics.items()),
                key=lambda s: s["slug"]),
        },
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(f"{len(upgrades)} upgrades, {len(parts)} parts, "
          f"{len(detection)} detection entries, "
          f"{len(schematics)} schematics -> {os.path.relpath(OUT, HERE + '/..')}")


if __name__ == "__main__":
    main()
