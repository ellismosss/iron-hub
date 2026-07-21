#!/usr/bin/env python3
"""Generate weapon-styles.json: combat-style option names per weapon type.

The combat tab's button labels ("Chop", "Hack", "Spike") and attack types
(Stab/Slash/Crush) are STRING LITERALS in the game's combat_interface_setup
clientscript, switched on the COMBAT_WEAPON_CATEGORY varbit (357) — they
exist nowhere in queryable cache config (the weapon-style structs carry only
the style KIND, param 1407, and the xp split; verified via the cache viewer
2026-07-21). So this pack is parsed from the RuneStar cs2 decompilation at a
pinned commit, and the LIVE client cross-checks each weapon type's style-kind
signature against the cache (enum 3908 walk) before trusting a row — a stale
or reused type id degrades to the honest "Attack style N", never a wrong name.

Also fetches the wiki's five attack-type icons (the ones its own equipment
infoboxes use) into data/icons/osrs/styles/.
"""
import json, os, re, urllib.request

UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
# RuneStar/cs2-scripts, pinned 2026-07-21
CS2_COMMIT = "7da6c1fbab51b7528ff13b15a532a44208bf75d6"
CS2_URL = ("https://raw.githubusercontent.com/RuneStar/cs2-scripts/" + CS2_COMMIT
           + "/scripts/%5Bclientscript%2Ccombat_interface_setup%5D.cs2")

ICONS = {
    "stab": "https://oldschool.runescape.wiki/images/White_dagger.png",
    "slash": "https://oldschool.runescape.wiki/images/White_scimitar.png",
    "crush": "https://oldschool.runescape.wiki/images/White_warhammer.png",
    "ranged": "https://oldschool.runescape.wiki/images/Ranged_icon.png",
    "magic": "https://oldschool.runescape.wiki/images/Magic_icon.png",
}

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-weapon-styles")


def fetch(url, name):
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, name)
    if os.path.exists(cached):
        return open(cached, "rb").read()
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        data = resp.read()
    open(cached, "wb").write(data)
    return data


STYLES = {"Accurate", "Aggressive", "Controlled", "Defensive", "Rapid", "Longrange"}
TYPES = {"Stab", "Slash", "Crush"}

text = fetch(CS2_URL, "combat_interface_setup.cs2").decode("utf-8")

# the style-button chain is the if/else-if run that ends at the
# setbuttons call — earlier %varbit357 ifs (autocast UI) are not it
chain_start = text.index("if (%varbit357 = 1) {")
chain_end = text.index("~combat_interface_setbuttons")
chain = text[chain_start:chain_end]
branches = {}
for m in re.finditer(r"%varbit357 = (\d+)\) \{(.*?)(?=\}\s*else|\}\s*$)", chain, re.DOTALL):
    branches[int(m.group(1))] = m.group(2)
default = re.search(r"\}\s*else\s*\{(?!.*%varbit357)(.*?)\}\s*$", chain, re.DOTALL)
assert default, "unarmed default branch not found"
branches[0] = default.group(1)
# ids 0-28 as of the pinned dump; newer type ids (29+) simply have no pack
# row and the display falls back to "Attack style N" — never a guess
assert sorted(branches) == list(range(29)), sorted(branches)

OPT = re.compile(r"\$op(\d)[^=]*=\s*\"([^\"]+)\",\s*\"([^\"]*)\",")

def parse_branch(body, type_id):
    options = []
    for om in OPT.finditer(body):
        idx, button, tooltip = int(om.group(1)), om.group(2), om.group(3)
        groups = re.findall(r"\(([^)]*)\)", tooltip)
        style = groups[0] if groups and groups[0] in STYLES else None
        atk = next((g for g in groups if g in TYPES), None)
        if atk is None:
            joined = " ".join(groups) + " " + tooltip
            if "Ranged" in joined:
                atk = "Ranged"
            elif "Magic" in joined:
                atk = "Magic"
        # bulwark's "No attacking!" block: no style, no type — both stay null
        options.append({"index": idx, "button": button, "style": style, "type": atk})
    assert options, f"type {type_id}: no options parsed"
    return options


types = {str(t): {"options": parse_branch(b, t)} for t, b in sorted(branches.items())}

# sanity anchors from the script's own text
spear = {o["index"]: o for o in types["15"]["options"]}
assert spear[0]["button"] == "Lunge" and spear[0]["type"] == "Stab" \
    and spear[0]["style"] == "Controlled", spear
axe = {o["index"]: o for o in types["1"]["options"]}
assert axe[1]["button"] == "Hack" and axe[1]["style"] == "Aggressive" and axe[1]["type"] == "Slash"
bow = {o["index"]: o for o in types["3"]["options"]}
assert bow[1]["button"] == "Rapid" and bow[1]["type"] == "Ranged"
unarmed = {o["index"]: o for o in types["0"]["options"]}
assert unarmed[0]["button"] == "Punch" and unarmed[0]["type"] == "Crush"

pack = {
    "$schema": "./schemas/weapon-styles.schema.json",
    "version": 1,
    "source": "RuneStar/cs2-scripts@" + CS2_COMMIT[:9] + " combat_interface_setup",
    "types": types,
}
out = os.path.join(HERE, "../src/main/resources/data/weapon-styles.json")
with open(out, "w") as f:
    json.dump(pack, f, indent=2)
    f.write("\n")

icon_dir = os.path.join(HERE, "../src/main/resources/data/icons/osrs/styles")
os.makedirs(icon_dir, exist_ok=True)
for name, url in ICONS.items():
    data = fetch(url, name + ".png")
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{name}: not a PNG"
    open(os.path.join(icon_dir, name + ".png"), "wb").write(data)

n = sum(len(t["options"]) for t in types.values())
print(f"wrote {len(types)} weapon types, {n} style options, {len(ICONS)} icons")
