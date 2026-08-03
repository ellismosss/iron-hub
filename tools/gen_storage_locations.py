#!/usr/bin/env python3
"""Generate data/storage-locations.json — the storage registry + the big
static item allow-lists that disambiguate co-located storages.

Ported from the "Dude, Where's My Stuff?" hub plugin
(github.com/Thource/dude-wheres-my-stuff, BSD-2, (c) 2022 Thource) at the
pinned commit below. DWMS models every storable place in the game as a
`Storage` whose *StorageType enum carries the metadata, and — for the
storages that SHARE one backing container (the whole POH costume room hangs
off InventoryID.POH_COSTUMES) — a hardcoded allow-list of the item ids that
belong to it. Those allow-lists are the only way to attribute a costume item
to "Fancy dress box" vs "Armour case", so they are exactly the "static table
DWMS hardcodes -> generated data pack" the Iron Hub playbook calls for.

The detection Java references the gameval container/varbit constants directly
(they are on our classpath), so this pack carries NO container ids — only the
registry metadata and the item allow-lists (resolved here, gameval ItemID via
javap, fail-fast). Family by family as the port lands; this pass ships the
PlayerOwnedHouse family (the acceptance-test path).

Usage: python3 tools/gen_storage_locations.py
"""

import glob
import json
import os
import re
import subprocess
import tarfile
import urllib.request

COMMIT = "d032272150b3715b1160e155e0af9564c78997fb"
TARBALL = ("https://github.com/Thource/dude-wheres-my-stuff/archive/"
           + COMMIT + ".tar.gz")
UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-dwms")
SRC_ROOT = os.path.join(
    CACHE, "dude-wheres-my-stuff-" + COMMIT,
    "src", "main", "java", "dev", "thource", "runelite", "dudewheresmystuff")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data",
                   "storage-locations.json")

# per-family label the UI appends in parentheses, e.g. "Fancy dress box (PoH)"
FAMILY_LABELS = {
    "playerownedhouse": "PoH",
    "stash": "STASH",
    "carryable": "Carried",
    "coins": "Coins",
    "world": "World",
    "minigames": "Minigame",
    "sailing": "Boat",
    "death": "Death",
}


def ensure_source():
    """Download + extract the DWMS tarball at the pin (once)."""
    if os.path.isdir(SRC_ROOT):
        return
    os.makedirs(CACHE, exist_ok=True)
    tgz = os.path.join(CACHE, COMMIT + ".tar.gz")
    if not os.path.exists(tgz):
        req = urllib.request.Request(TARBALL, headers={"User-Agent": UA})
        with urllib.request.urlopen(req) as resp, open(tgz, "wb") as f:
            f.write(resp.read())
    with tarfile.open(tgz) as t:
        t.extractall(CACHE)
    if not os.path.isdir(SRC_ROOT):
        raise SystemExit("DWMS source did not extract to " + SRC_ROOT)


def gameval_item_ids():
    """gameval ItemID constant NAME -> id, from the runelite-api jar."""
    jars = [j for j in glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/"
        "runelite-api-*.jar")) if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    dump = subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants",
         "net.runelite.api.gameval.ItemID"],
        capture_output=True, text=True, check=True).stdout
    return {m.group(1): int(m.group(2)) for m in
            re.finditer(r"public static final int (\w+) = (-?\d+);", dump)}


def gameval_inventory_ids():
    """gameval InventoryID constant NAME -> id, from the runelite-api jar."""
    jars = [j for j in glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/"
        "runelite-api-*.jar")) if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    dump = subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants",
         "net.runelite.api.gameval.InventoryID"],
        capture_output=True, text=True, check=True).stdout
    return {m.group(1): int(m.group(2)) for m in
            re.finditer(r"public static final int (\w+) = (-?\d+);", dump)}


def _javap_constants(cls):
    jars = [j for j in glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/"
        "runelite-api-*.jar")) if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    dump = subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", cls],
        capture_output=True, text=True, check=True).stdout
    return {m.group(1): int(m.group(2)) for m in
            re.finditer(r"public static final int (\w+) = (-?\d+);", dump)}


def gameval_varbit_ids():
    """gameval VarbitID + VarPlayerID constant NAME -> id."""
    out = _javap_constants("net.runelite.api.gameval.VarbitID")
    out.update(_javap_constants("net.runelite.api.gameval.VarPlayerID"))
    return out


def gameval_object_ids():
    """gameval ObjectID + ObjectID1 constant NAME -> id, from the runelite-api jar."""
    jars = [j for j in glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/"
        "runelite-api-*.jar")) if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    out = {}
    for cls in ("net.runelite.api.gameval.ObjectID", "net.runelite.api.gameval.ObjectID1"):
        dump = subprocess.run(
            ["javap", "-classpath", sorted(jars)[-1], "-constants", cls],
            capture_output=True, text=True, check=True).stdout
        out.update({m.group(1): int(m.group(2)) for m in
                    re.finditer(r"public static final int (\w+) = (-?\d+);", dump)})
    return out


def parse_cape_hanger(items_by_name, obj_by_name):
    """CapeHanger.java's objectIdItemIdMap: ObjectID.POH_MOUNTED_* -> [cape,
    hood?]. Returns (mounts, clearObjects). The reference clears on raw id
    29166 (the empty hanger, no ObjectID constant)."""
    text = read_enum("playerownedhouse", "CapeHanger.java")
    mounts = []
    for m in re.finditer(
            r"builder\.put\(\s*ObjectID\.(\w+)\s*,\s*new Integer\[\]\{([^}]*)\}\)",
            text):
        obj_const, item_body = m.group(1), m.group(2)
        if obj_const not in obj_by_name:
            raise SystemExit(f"unresolved ObjectID.{obj_const}")
        item_ids = []
        for tok in item_body.split(","):
            tok = tok.strip()
            if not tok:
                continue
            if not tok.startswith("ItemID."):
                raise SystemExit(f"unexpected cape item token: {tok!r}")
            const = tok[len("ItemID."):]
            if const not in items_by_name:
                raise SystemExit(f"unresolved ItemID.{const}")
            item_ids.append(items_by_name[const])
        mounts.append({"object": obj_by_name[obj_const], "items": item_ids})
    if len(mounts) < 60:
        raise SystemExit(f"cape hanger parsed only {len(mounts)} mounts")
    return mounts, [29166]


def read_enum(family, filename):
    with open(os.path.join(SRC_ROOT, family, filename), encoding="utf-8") as f:
        text = f.read()
    # strip block + line comments (none appear inside the string literals we parse)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def split_top_level(body):
    """Split a constructor arg string on top-level commas (ignores commas
    nested inside parens/brackets)."""
    args, depth, cur = [], 0, ""
    for ch in body:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        args.append(cur.strip())
    return args


def enum_constants(text):
    """Yield (constant_name, [top-level arg strings]) for each `NAME(...)`
    enum constant, reading balanced parens so multi-line item lists are
    captured whole. Stops at the field declarations after the last `;`."""
    # trim to the enum body (between the first `{` and the closing `;` that
    # ends the constant list)
    start = text.index("{") + 1
    i, n = start, len(text)
    while i < n:
        m = re.match(r"\s*([A-Z][A-Z0-9_]*)\s*\(", text[i:])
        if not m:
            # not a constant here; if we hit a `;` at top level, constants end
            stripped = text[i:].lstrip()
            if stripped.startswith(";"):
                return
            i += 1
            continue
        name = m.group(1)
        j = i + m.end()  # just after the opening paren
        depth = 1
        while j < n and depth:
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
            j += 1
        body = text[i + m.end():j - 1]
        yield name, split_top_level(body)
        # advance past the trailing comma/semicolon
        i = j
        while i < n and text[i] in ", \n\r\t":
            i += 1
        if i < n and text[i] == ";":
            return


def parse_item_list(arg, items_by_name):
    """Arg is `Arrays.asList(ItemID.X, 12345, ...)` or `null` — resolve to a
    sorted unique list of ids, or None."""
    if arg == "null":
        return None
    inner = re.sub(r"^\s*Arrays\.asList\s*\(", "", arg)
    inner = re.sub(r"\)\s*$", "", inner)
    ids = set()
    for tok in split_top_level(inner):
        tok = tok.strip().rstrip(",")
        if not tok:
            continue
        if tok.startswith("ItemID."):
            const = tok[len("ItemID."):]
            if const not in items_by_name:
                raise SystemExit(f"unresolved ItemID.{const}")
            ids.add(items_by_name[const])
        elif re.fullmatch(r"-?\d+", tok):
            ids.add(int(tok))
        else:
            raise SystemExit(f"unexpected item token: {tok!r}")
    return sorted(ids)


def parse_container(arg):
    """2nd constructor arg — an `InventoryID.NAME` token, or `-1`."""
    arg = arg.strip()
    if arg.startswith("InventoryID."):
        return arg[len("InventoryID."):]
    return None


def sentence_case(name):
    """Match the game's own furniture casing: "Fancy Dress Box" -> "Fancy dress
    box", keeping a parenthesised tier capitalised ("Treasure Chest (Beginner)"
    -> "Treasure chest (Beginner)")."""
    out = name.lower()
    out = out[:1].upper() + out[1:]
    return re.sub(r"\(([a-z])", lambda m: "(" + m.group(1).upper(), out)


def parse_poh(items_by_name, inv_by_name, obj_by_name):
    """PlayerOwnedHouse: NAME("Display", <container|-1>, "configKey", <list|null>)."""
    text = read_enum("playerownedhouse", "PlayerOwnedHouseStorageType.java")
    cape_mounts, cape_clears = parse_cape_hanger(items_by_name, obj_by_name)
    storages = []
    for name, args in enum_constants(text):
        display = sentence_case(args[0].strip().strip('"'))
        container = parse_container(args[1])
        config_key = args[2].strip().strip('"')
        items = parse_item_list(args[3], items_by_name) if len(args) > 3 else None
        entry = {
            "family": "playerownedhouse",
            "key": config_key,
            "name": display,
            "members": True,      # every POH storage is members-only
            "automatic": False,
        }
        if container:
            entry["container"] = container
            cid = inv_by_name.get(container)
            if cid is not None:
                entry["containerId"] = cid
        if items is not None:
            entry["items"] = items
        # the whole costume room shares POH_COSTUMES; attribute by allow-list
        if container == "POH_COSTUMES":
            entry["mode"] = "poh"
        # cape hanger: object-spawn detection of the mounted cape
        if config_key == "capeHanger":
            entry["mode"] = "objectmount"
            entry["mounts"] = cape_mounts
            entry["clearObjects"] = cape_clears
        storages.append(entry)
    return storages


# constructor arg indices per family: (container_idx, configkey_idx). name is
# always arg 0. A None container_idx means the family carries no container arg.
FAMILY_SPEC = {
    "carryable": (1, 3),
    "coins": (2, 4),
    "minigames": (None, 2),
    "sailing": (1, 2),
    "stash": (1, 3),
    "world": (1, 3),
    "death": (1, 3),
}

# storages that are a plain, byte-faithful container read on
# ItemContainerChanged (DWMS's base ItemStorage path) — safe to detect
# generically. bank/inventory/equipment are owned by AccountState and never
# listed here; chat/widget/varbit-scrape storages are not (they land with
# bespoke hooks in later slices).
CONTAINER_MODE = {
    ("carryable", "lootingbag"),
    ("carryable", "seedbox"),
    ("carryable", "huntsmanskit"),
    ("carryable", "forestrykit"),
    ("carryable", "tackleBox"),
    ("carryable", "chuggingBarrel"),
    ("sailing", "boat1"), ("sailing", "boat2"), ("sailing", "boat3"),
    ("sailing", "boat4"), ("sailing", "boat5"),
    ("death", "deathsoffice"),
    ("world", "groupstorage"),  # INV_GROUP_TEMP — GIM shared storage
    ("world", "seedvault"),     # SEED_VAULT
}

# container reads whose container the *StorageType enum leaves at -1 because
# the reference resolves it in a manager override — curated with the real
# gameval InventoryID. Your gravestone (grave/deathbank items) is the big one:
# opening it records what you'd lose on death, the whole point of the plugin.
CURATED_CONTAINERS = {
    ("death", "grave"): "GRAVESTONE",
}

ENUM_FILE = {
    "carryable": "CarryableStorageType.java",
    "coins": "CoinsStorageType.java",
    "minigames": "MinigamesStorageType.java",
    "sailing": "SailingStorageType.java",
    "stash": "StashStorageType.java",
    "world": "WorldStorageType.java",
    "death": "DeathStorageType.java",
}


def parse_family(family, inv_by_name):
    """Registry metadata for a family's *StorageType enum. Emits a container id
    where the storage reads a real InventoryID, and mode='container' for the
    curated plain-container-read set."""
    container_idx, key_idx = FAMILY_SPEC[family]
    text = read_enum(family, ENUM_FILE[family])
    storages = []
    for name, args in enum_constants(text):
        display = args[0].strip().strip('"')
        config_key = args[key_idx].strip().strip('"') if key_idx < len(args) else ""
        if not config_key:
            continue  # e.g. DeathStorageType.DEATH_ITEMS is a preview, not a store
        entry = {"family": family, "key": config_key, "name": display}
        if container_idx is not None and container_idx < len(args):
            container = parse_container(args[container_idx])
            if container:
                entry["container"] = container
                cid = inv_by_name.get(container)
                if cid is not None:
                    entry["containerId"] = cid
        if (family, config_key) in CONTAINER_MODE:
            entry["mode"] = "container"
        storages.append(entry)
    return storages


COINS = 995  # every coins-family storage is a coins balance

# curated tables extracted from the DWMS Storage subclasses (tools/
# dwms-storage-tables.json — every constant copied verbatim from source, so
# the javap resolution below fail-fasts on any drift). This pass wires the
# storages the module can drive generically: static (varbit-per-item, offset
# 0), index (one varbit -> item array), and coin varbit balances. slots
# (rune/bolt/quiver), special (vyre well, leprechaun) and the bespoke
# chat/widget scrapers land with their own hooks in later slices.
def apply_detection_tables(storages, varbit_by_name, items_by_name, inv_by_name):
    with open(os.path.join(HERE, "dwms-storage-tables.json"), encoding="utf-8") as f:
        tables = json.load(f)
    by_id = {(s["family"], s["key"]): s for s in storages}

    def resolve_item(const):
        if const not in items_by_name:
            raise SystemExit(f"unresolved ItemID.{const}")
        return items_by_name[const]

    def resolve_varbit(const):
        if const not in varbit_by_name:
            raise SystemExit(f"unresolved VarbitID/VarPlayerID.{const}")
        return varbit_by_name[const]

    wired = 0

    # curated container reads (the enum leaves the container at -1)
    for (family, key), container in CURATED_CONTAINERS.items():
        storage = by_id.get((family, key))
        if storage is None:
            raise SystemExit(f"curated container has no registry storage: {family}:{key}")
        cid = inv_by_name.get(container)
        if cid is None:
            raise SystemExit(f"unresolved InventoryID.{container}")
        storage["container"] = container
        storage["containerId"] = cid
        storage["mode"] = "container"
        wired += 1

    # Vyre Well is "special" only because blood runes are derived — but they
    # derive from the SAME varbit as the vials (× 200), so it is exactly the
    # varbits mode with two entries. Curated from world/VyreWell.java.
    vyre = by_id.get(("world", "vyrewell"))
    if vyre is not None:
        vyre["mode"] = "varbits"
        vyre["varbitItems"] = [
            {"varbit": resolve_varbit("TOB_LOBBY_WELL_CONTENTS"),
             "itemId": resolve_item("VIAL_BLOOD"), "multiplier": 1},
            {"varbit": resolve_varbit("TOB_LOBBY_WELL_CONTENTS"),
             "itemId": resolve_item("BLOODRUNE"), "multiplier": 200},
        ]
        wired += 1

    # Tool Leprechaun + Elnock Inquisitor: derived per-item formulas (base+extra
    # math, a fairy-secateurs variant, a watering-can index, a bottomless-bucket
    # type, and the impling-net index). Curated from the reference's overrides;
    # every constant resolved fail-fast.
    def csum(item, terms):
        return {"kind": "sum", "itemId": resolve_item(item),
                "terms": [{"varbit": resolve_varbit(v), "mult": m} for v, m in terms]}

    watering_can_ids = ["-1"] + [f"WATERING_CAN_{i}" for i in range(9)] + ["ZEAH_WATERINGCAN"]
    lep = by_id.get(("world", "leprechaun"))
    if lep is not None:
        lep["mode"] = "compute"
        lep["computeItems"] = [
            csum("RAKE", [("FARMING_TOOLS_RAKE", 1), ("FARMING_TOOLS_EXTRARAKES", 2)]),
            csum("DIBBER", [("FARMING_TOOLS_DIBBER", 1), ("FARMING_TOOLS_EXTRADIBBERS", 2)]),
            csum("SPADE", [("FARMING_TOOLS_SPADE", 1), ("FARMING_TOOLS_EXTRASPADES", 2)]),
            {"kind": "variant", "itemId": resolve_item("SECATEURS"),
             "terms": [{"varbit": resolve_varbit("FARMING_TOOLS_SECATEURS"), "mult": 1},
                       {"varbit": resolve_varbit("FARMING_TOOLS_EXTRASECATEURS"), "mult": 2}],
             "variantVarbit": resolve_varbit("FARMING_TOOLS_FAIRYSECATEURS"),
             "variantItemId": resolve_item("FAIRY_ENCHANTED_SECATEURS")},
            {"kind": "index", "indexVarbit": resolve_varbit("FARMING_TOOLS_WATERINGCAN"),
             "indexArray": [-1 if c == "-1" else resolve_item(c) for c in watering_can_ids]},
            csum("GARDENING_TROWEL", [("FARMING_TOOLS_TROWEL", 1), ("FARMING_TOOLS_EXTRATROWELS", 2)]),
            csum("PLANT_CURE", [("FARMING_TOOLS_PLANTCURE", 1)]),
            {"kind": "type", "typeVarbit": resolve_varbit("FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE"),
             "emptyId": resolve_item("BOTTOMLESS_COMPOST_BUCKET"),
             "filledId": resolve_item("BOTTOMLESS_COMPOST_BUCKET_FILLED")},
            csum("BUCKET_EMPTY", [("FARMING_TOOLS_BUCKETS", 1), ("FARMING_TOOLS_EXTRABUCKETS", 32),
                                  ("FARMING_TOOLS_EXTRA2BUCKETS", 256)]),
            csum("BUCKET_COMPOST", [("FARMING_TOOLS_COMPOST", 1), ("FARMING_TOOLS_EXTRACOMPOST", 256)]),
            csum("BUCKET_SUPERCOMPOST", [("FARMING_TOOLS_SUPERCOMPOST", 1),
                                         ("FARMING_TOOLS_EXTRASUPERCOMPOST", 256)]),
            csum("BUCKET_ULTRACOMPOST", [("FARMING_TOOLS_ULTRACOMPOST", 1)]),
        ]
        wired += 1
    eln = by_id.get(("world", "elnock"))
    if eln is not None:
        eln["mode"] = "compute"
        eln["computeItems"] = [
            {"kind": "index", "indexVarbit": resolve_varbit("II_STORED_NET"),
             "indexArray": [-1, resolve_item("HUNTING_BUTTERFLY_NET"),
                            resolve_item("II_MAGIC_BUTTERFLY_NET")]},
            csum("II_IMP_REPELLENT", [("II_STORED_REPELLENT", 1)]),
            csum("II_IMPLING_JAR", [("II_STORED_IMPLING_JARS", 1)]),
        ]
        wired += 1

    for family in ("carryable", "world"):
        for key, spec in tables.get(family, {}).items():
            if key.startswith("_"):
                continue
            key = spec.get("configKey", key)  # the enum's real config key
            storage = by_id.get((family, key))
            if storage is None:
                raise SystemExit(f"detection table has no registry storage: {family}:{key}")
            if spec["shape"] == "static" and spec.get("varbitItemOffset", 0) == 0:
                storage["mode"] = "varbits"
                storage["varbitItems"] = [
                    {"varbit": resolve_varbit(s["varbit"]), "itemId": resolve_item(s["item"])}
                    for s in spec["slots"]]
                wired += 1
            elif spec["shape"] == "index":
                storage["mode"] = "varbitindex"
                storage["indexVarbit"] = resolve_varbit(spec["indexVarbit"])
                storage["indexItems"] = [resolve_item(c) for c in spec["array"]]
                wired += 1
            elif spec["shape"] == "slots":
                storage["mode"] = "slots"
                storage["varp"] = spec.get("typeConstType") == "VarPlayerID"
                storage["slots"] = [
                    {"typeVarbit": resolve_varbit(t), "countVarbit": resolve_varbit(c)}
                    for t, c in zip(spec["type"], spec["count"])]
                tti = spec["typeToItem"]
                if "array" in tti:
                    storage["typeKind"] = "array"
                    storage["typeArray"] = [
                        -1 if c == "-1" else resolve_item(c) for c in spec["array"]]
                elif "getEnum" in tti:
                    storage["typeKind"] = "enum"
                    storage["typeEnum"] = 982  # net.runelite.api.EnumID.RUNEPOUCH_RUNE
                else:
                    storage["typeKind"] = "direct"
                wired += 1

    for key, spec in tables.get("coins", {}).items():
        if key.startswith("_") or spec.get("source") != "varbit":
            continue
        storage = by_id.get(("coins", key))
        if storage is None:
            raise SystemExit(f"coins detection table has no registry storage: coins:{key}")
        storage["mode"] = "varbits"
        storage["varbitItems"] = [{
            "varbit": resolve_varbit(spec["varbit"]),
            "itemId": COINS,
            "multiplier": int(spec.get("multiplier", 1)),
        }]
        wired += 1

    # minigame point balances: one varbit/varp over an icon item, name-overridden
    for key, spec in tables.get("minigames", {}).items():
        if key.startswith("_"):
            continue
        storage = by_id.get(("minigames", key))
        if storage is None:
            raise SystemExit(f"minigames detection table has no registry storage: minigames:{key}")
        storage["mode"] = "varbits"
        storage["varp"] = bool(spec.get("varp", False))
        storage["varbitItems"] = [{
            "varbit": resolve_varbit(spec["varbit"]),
            "itemId": resolve_item(spec["item"]),
            "name": spec["name"],
        }]
        wired += 1
    return wired


def main():
    ensure_source()
    items_by_name = gameval_item_ids()
    inv_by_name = gameval_inventory_ids()
    obj_by_name = gameval_object_ids()
    varbit_by_name = gameval_varbit_ids()

    storages = []
    storages += parse_poh(items_by_name, inv_by_name, obj_by_name)
    for family in FAMILY_SPEC:
        storages += parse_family(family, inv_by_name)
    wired = apply_detection_tables(storages, varbit_by_name, items_by_name, inv_by_name)

    # sanity asserts — the POH costume room, byte-faithful to the source
    keys = {s["key"] for s in storages}
    for required in ("fancyDressBox", "armourCase", "magicWardrobe", "capeRack",
                     "toyBox", "treasureChestMaster", "uncategorised"):
        assert required in keys, f"missing POH storage: {required}"
    fancy = next(s for s in storages if s["key"] == "fancyDressBox")
    assert fancy.get("items"), "fancyDressBox lost its allow-list"
    # every POH_COSTUMES storage except the catch-all carries an allow-list
    for s in storages:
        if s.get("container") == "POH_COSTUMES" and s["key"] != "uncategorised":
            assert s.get("items"), f"{s['key']} missing allow-list"

    # every container-mode storage resolved a real container id
    for s in storages:
        if s.get("mode") == "container":
            assert s.get("containerId"), f"{s['key']} container-mode without a container id"
    # the curated container-mode set is fully present (a rename in the source
    # would silently drop a storage otherwise)
    present = {(s["family"], s["key"]) for s in storages}
    for want in CONTAINER_MODE:
        assert want in present, f"container-mode storage vanished from source: {want}"

    # a globally-unique handle: config keys collide across families ("bank" is
    # both a coins and a world storage), and the persisted snapshot map is
    # keyed by this. Emit id first for readability.
    ordered = []
    seen = set()
    for s in storages:
        s_id = s["family"] + ":" + s["key"]
        assert s_id not in seen, f"duplicate storage id: {s_id}"
        seen.add(s_id)
        ordered.append({"id": s_id, **s})

    pack = {
        "_generated": "tools/gen_storage_locations.py",
        "_source": "dude-wheres-my-stuff @ " + COMMIT,
        "familyLabels": FAMILY_LABELS,
        "storages": ordered,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1)
        f.write("\n")

    from collections import Counter
    modes = Counter(s.get("mode", "—") for s in storages)
    print(f"wrote {os.path.relpath(OUT, HERE)}: {len(storages)} storages; "
          f"modes {dict(modes)}; {wired} wired from detection tables")


if __name__ == "__main__":
    main()
