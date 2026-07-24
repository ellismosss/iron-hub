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


def main():
    ensure_source()
    items_by_name = gameval_item_ids()
    inv_by_name = gameval_inventory_ids()
    obj_by_name = gameval_object_ids()

    storages = []
    storages += parse_poh(items_by_name, inv_by_name, obj_by_name)
    for family in FAMILY_SPEC:
        storages += parse_family(family, inv_by_name)

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

    poh = sum(1 for s in storages if s["family"] == "playerownedhouse")
    allow = sum(1 for s in storages if s.get("items"))
    cont = sum(1 for s in storages if s.get("mode") == "container")
    print(f"wrote {os.path.relpath(OUT, HERE)}: {len(storages)} storages "
          f"({poh} POH, {allow} allow-lists, {cont} container-mode)")


if __name__ == "__main__":
    main()
