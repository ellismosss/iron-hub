#!/usr/bin/env python3
"""Generate data/dailies.json — the repeatable events Iron Hub guides you through.

Two pinned sources, joined:

  1. The OSRS wiki's Repeatable events page (the product truth: which events
     exist, where, what gates them, and how the amount scales with a diary).
     Fetched as raw wikitext and cached; the tier tables below are transcribed
     from it and re-checked against the fetch on every run.

  2. RuneLite's core Daily Task Indicator plugin (the detection truth: which
     varbit says "claimed"). We resolve those varbits by NAME through the
     gameval VarbitID constants in the runelite-api jar, so a rename in a
     client update aborts this generator instead of silently shipping a
     wrong id. See DailyTracker for the semantics (0 == unclaimed).

Deliberately NOT in the pack:

  - NMZ herb boxes — the game blocks ironmen (core literally checks
    IRONMAN == 0), and Iron Hub is an ironman plugin.
  - Explorer's ring alchemy, Falador shield prayer restore, Agility/Fletching/
    Hunter/Magic cape charges, Zulrah + Fight Cave resurrections — per-item
    charges you spend where you already are. A run cannot route to them, so
    listing them as stops would be noise.

  - Teleport tables. Easy Farming gave us verified per-stop teleports for the
    farm runs; no equivalent audited source exists for these sites, and
    guessing rune costs would be inventing data. Each stop carries the wiki's
    own "getting there" wording as a hint and Shortest Path does the routing.

Usage:
  python3 tools/gen_dailies.py

Wikitext cached under tools/.cache-dailies/ (gitignored).
"""
import datetime
import glob
import json
import os
import re
import subprocess
import urllib.request

WIKI = "https://oldschool.runescape.wiki/w/Repeatable_events?action=raw"
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
CACHE = os.path.join(os.path.dirname(__file__), ".cache-dailies")
OUT = "src/main/resources/data/dailies.json"
# The RuneLite tag whose Daily Task Indicator we mirror; keep in lockstep with
# the resolved net.runelite:client version (see tools/gen_timetracking.py).
RUNELITE_TAG = "runelite-parent-1.12.32"

# Every event, in run order. Every stop is a teleport hop, so the order is a
# geographic sweep rather than a walking route: Varrock -> Kandarin (Seers',
# Ardougne, Yanille, Feldip) -> Fremennik -> Morytania -> Kourend -> Lumbridge,
# and finally the Wilderness. Lundail is deliberately LAST — it is the only
# stop that can kill you, so you want the rest of the run's loot banked first.
#
#   varbit  — gameval VarbitID constant name, resolved below (never hardcoded).
#   mode    — see DailiesPack.Detection.
#   reqs    — requirement-graph strings; every one is asserted parseable by
#             DailiesPackTest, and quest tokens are checked here at generation.
#   tiers   — (requirement or None for the base amount, quantity), wiki order.
#   bring   — (label, amount per unit collected).
#   travel  — the wiki's own best "getting there"; a hint, not a routing input.
# Robin converts ANY bone that has a bonemeal — the wiki's Bonemeal table is the
# authoritative list ({{plinkt|...}} per row), and his own page confirms "The
# bones do not need to be of the same type" while excluding Sun-kissed bones
# (absent from the bonemeal table too, so the list already agrees). Names are
# resolved through data/index/item-names.json below; an unresolvable one aborts.
#
# "Bones (Ape Atoll)" is deliberately absent: it is an Ape-Atoll-local variant
# with no entry in the item-name index, and nobody banks it for Robin.
ROBIN_BONES = [
    "Alan's bones", "Babydragon bones", "Bat bones", "Bearded gorilla bones",
    "Big bones", "Bones", "Burnt bones", "Burnt jogre bones", "Curved bone",
    "Dagannoth bones", "Dragon bones", "Drake bones", "Fayrg bones",
    "Frost dragon bones", "Gorilla bones", "Hydra bones", "Jogre bones",
    "Large zombie monkey bones", "Lava dragon bones", "Long bone",
    "Medium ninja monkey bones", "Monkey bones", "Ourg bones", "Raurg bones",
    "Shaikahan bones", "Small ninja monkey bones", "Small zombie monkey bones",
    "Strykewyrm bones", "Superior dragon bones", "Wolf bones", "Wyrm bones",
    "Wyrmling bones", "Wyvern bones", "Zogre bones",
]

DAILIES = [
    {
        "id": "zaff_battlestaves",
        "icon": "BATTLESTAFF",
        "name": "Zaff battlestaves",
        "where": "Zaff, Varrock",
        "reset": "daily",
        "reqs": [],
        "mode": "flag",
        "varbit": "ZAFF_LAST_CLAIMED",
        "tiers": [
            (None, 5),
            ("diary:Varrock:Easy", 15),
            ("diary:Varrock:Medium", 30),
            ("diary:Varrock:Hard", 60),
            ("diary:Varrock:Elite", 120),
        ],
        "bring": [("coins", 7000, None)],  # not checked: you can just buy fewer
        "travel": "Varrock teleport",
        # The wiki is explicit that the base 5 need no diary at all — the tiers
        # only raise the cap. (Core's own notifier gates on the easy diary; we
        # follow the wiki, which is the player-facing truth.)
        "note": "Bought at 7,000 each, noted. If they have not appeared after "
                "the reset, hop worlds or re-log.",
    },
    {
        "id": "flax_bowstring",
        "icon": "BOW_STRING",
        "name": "Flax keeper bowstrings",
        "where": "Flax keeper, Seers' Village",
        "reset": "daily",
        "reqs": ["diary:Kandarin:Easy"],
        "mode": "flag",
        "varbit": "SEERS_FREE_FLAX",
        "tiers": [
            ("diary:Kandarin:Easy", 30),
            ("diary:Kandarin:Medium", 60),
            ("diary:Kandarin:Hard", 120),
            ("diary:Kandarin:Elite", 250),
        ],
        "bring": [("noted flax", 1, ["Flax"])],
        "travel": "Camelot teleport",
        "note": None,
    },
    {
        "id": "cromperty_essence",
        "icon": "BLANKRUNE_HIGH",   # pure essence
        "name": "Cromperty pure essence",
        "where": "Wizard Cromperty, East Ardougne",
        "reset": "daily",
        "reqs": ["diary:Ardougne:Medium"],
        "mode": "flag",
        "varbit": "ARDOUGNE_FREE_ESSENCE",
        "tiers": [
            ("diary:Ardougne:Medium", 100),
            ("diary:Ardougne:Hard", 150),
            ("diary:Ardougne:Elite", 250),
        ],
        "bring": [],
        "travel": "Ardougne teleport",
        "note": None,
    },
    {
        "id": "bert_sand",
        "icon": "BUCKET_SAND",
        "name": "Bert buckets of sand",
        "where": "Bert, Yanille",
        "reset": "daily",
        "reqs": ["quest:The Hand in the Sand"],
        "mode": "flag",
        "varbit": "YANILLE_SAND_CLAIMED",
        "tiers": [(None, 84)],
        "bring": [],
        "travel": "Watchtower teleport, or a Yanille house portal",
        # Elite Ardougne changes the DELIVERY, not the amount — easy to
        # mis-model as a quantity tier like the others. No special case is
        # needed for it: the delivery sets the same claim varbit on login, so
        # the stop reads DONE and the run culls it on its own.
        "note": "With the elite Ardougne Diary (and the option toggled on) Bert "
                "delivers to your bank on login — no trip needed.",
    },
    {
        "id": "rantz_arrows",
        "icon": "OGRE_ARROW",
        "name": "Rantz ogre arrows",
        "where": "Rantz, Feldip Hills",
        "reset": "daily",
        "reqs": ["diary:Western Provinces:Easy"],
        "mode": "flag",
        "varbit": "WESTERN_RANTZ_ARROWS",
        "tiers": [
            ("diary:Western Provinces:Easy", 25),
            ("diary:Western Provinces:Medium", 50),
            ("diary:Western Provinces:Hard", 100),
            ("diary:Western Provinces:Elite", 150),
        ],
        "bring": [],
        "travel": "Fairy ring AKS",
        "note": None,
    },
    {
        "id": "miscellania",
        "icon": "COINS",           # the coffer is the daily act
        "name": "Kingdom of Miscellania",
        "where": "Advisor Ghrim, Miscellania",
        "reset": "daily",
        "reqs": ["quest:Throne of Miscellania"],
        # No claim varbit exists: resources accrue and approval decays daily,
        # with no "collected today" flag to read. Hand-ticked, and the only
        # stop the run cannot advance by itself.
        "mode": "manual",
        "varbit": None,
        "tiers": [],
        "bring": [],
        "travel": "Ring of wealth, or fairy ring CIP",
        "note": "Keep the coffer topped up (500,000, or 750,000 after Royal "
                "Trouble) and approval high; collect from Advisor Ghrim.",
    },
    {
        "id": "robin_bonemeal",
        "icon": "POT_BONEMEAL",
        "name": "Robin bonemeal & slime",
        "where": "Robin, Port Phasmatys",
        "reset": "daily",
        "reqs": ["diary:Morytania:Medium"],
        # Counts what you have taken, capped by your Morytania legs tier.
        "mode": "count",
        "varbit": "MORYTANIA_SLIME_CLAIMED",
        "tiers": [
            ("diary:Morytania:Medium", 13),
            ("diary:Morytania:Hard", 26),
            ("diary:Morytania:Elite", 39),
        ],
        "bring": [("bones", 1, ROBIN_BONES)],
        "travel": "Ectophial",
        "note": "No profit, but it cuts down Prayer training time. Wear the "
                "Morytania legs for your tier.",
    },
    {
        "id": "thirus_dynamite",
        "icon": "LOVAKENGJ_DYNAMITE_POT",
        "name": "Thirus dynamite",
        "where": "Thirus, Lovakengj",
        "reset": "daily",
        "reqs": ["diary:Kourend & Kebos:Medium"],
        "mode": "flag",
        "varbit": "KOUREND_FREE_DYNAMITE",
        "tiers": [
            ("diary:Kourend & Kebos:Medium", 20),
            ("diary:Kourend & Kebos:Hard", 40),
            ("diary:Kourend & Kebos:Elite", 80),
        ],
        "bring": [],
        "travel": "Xeric's talisman to Xeric's Inferno",
        "note": None,
    },
    {
        "id": "tears_of_guthix",
        "icon": "TOG_BOWL",
        "name": "Tears of Guthix",
        "where": "Juna, Chasm of Tears",
        # Not a fixed weekday: 7 days from your last visit, at 00:00 UTC.
        "reset": "rolling7",
        "reqs": ["quest:Tears of Guthix"],
        # No varbit tracks the 7-day cooldown (5099 is the in-minigame
        # ticks-left timer, confirmed against the Improved Tears of Guthix
        # Interface plugin), so this needs two live signals instead:
        #   varbit — the "collecting now" flag, which stamps a visit;
        #   chat   — Juna's opt-in daily reminder, which is the game telling
        #            us outright that the cooldown is up. Wording transcribed
        #            identically by two independent server emulators that
        #            reproduce the real string (GregHib/void's Juna.kt and
        #            Zenyte's TearsOfGuthixServerLaunchSubscriber), and matched
        #            as a substring so colour tags and any trailing punctuation
        #            drift cannot break it.
        "mode": "rolling7",
        "varbit": "TOG_MINIGAME_COLLECTING",
        "chat": "eligible to drink from the Tears of Guthix",
        "tiers": [],
        "bring": [],
        "travel": "Games necklace",
        "note": "Needs 1 quest point or 100,000 XP gained since you last "
                "played. XP goes to your lowest skill. Ask Juna to remind you "
                "daily when you are eligible and Iron Hub reads that message.",
    },
    {
        "id": "lundail_runes",
        "icon": "CHAOSRUNE",       # one of the catalytic runes he hands out
        "name": "Lundail random runes",
        "where": "Lundail, Mage Arena bank (Wilderness)",
        "reset": "daily",
        "reqs": ["diary:Wilderness:Easy"],
        "mode": "flag",
        "varbit": "LUNDAIL_LAST_CLAIMED",
        "tiers": [
            ("diary:Wilderness:Easy", 40),
            ("diary:Wilderness:Medium", 80),
            ("diary:Wilderness:Hard", 120),
            ("diary:Wilderness:Elite", 200),
        ],
        "bring": [],
        "travel": "Edgeville lever, then run west",
        "note": "Pull the lever in the ruin to reach the bank (cut two webs; a "
                "teleblock stops you). The rune type is random.",
        # Off unless you ask for it, and it says why when you do. The shop
        # itself is a safe area, but the wiki is blunt about getting there:
        # "Visiting Lundail requires the player to traverse deep Wilderness".
        "optOut": True,
        "warning": "Getting to Lundail means crossing deep Wilderness, where "
                   "other players can attack you.\n\nTake the Edgeville lever "
                   "to the Deserted Keep and run west. Bring a knife or other "
                   "slash weapon to cut the webs on the way.",
    },
]

# Exact interaction tiles, each transcribed from that page's own {{Map}}
# template — never eyeballed off a map image, because a wrong tile sends
# Shortest Path somewhere wrong.
#
# Two are deliberately NOT the NPC's own tile, and must not be "corrected":
#
#   lundail_runes — Lundail stands in the Mage Arena BANK at (3109,10357),
#     which cannot be walked to at all: the only way in is the lever in the
#     ruin, and Shortest Path ships no transport for it (verified against its
#     transports.tsv — nothing routes into that y-band). Routing to the NPC
#     would simply never solve, so the target is the surface lever and the
#     note tells you to pull it.
#
#   tears_of_guthix — the wiki's {{Map}} carries no plane, but the Chasm of
#     Tears is plane 2, not 0: Shortest Path's own tunnel out of the Lumbridge
#     Swamp Caves runs (3226,9542,0) -> (3219,9532,2), and every transport in
#     that cave is plane 2.
COORDS = {
    "zaff_battlestaves": (3203, 3434, 0),   # Zaff, cross-checked vs the shop page
    "flax_bowstring": (2744, 3443, 0),      # Flax keeper
    "cromperty_essence": (2684, 3325, 0),   # Wizard Cromperty
    "bert_sand": (2551, 3100, 0),           # Bert
    "rantz_arrows": (2630, 2981, 0),        # Rantz
    "miscellania": (2500, 3857, 1),         # Advisor Ghrim — castle first floor
    "robin_bonemeal": (3676, 3494, 0),      # Robin, cross-checked vs The Green Ghost
    "thirus_dynamite": (1517, 3834, 0),     # Thirus
    "tears_of_guthix": (3252, 9517, 2),     # Juna — plane per Shortest Path (see above)
    "lundail_runes": (3090, 3958, 0),       # the Mage Arena bank lever (see above)
}

# Events on the wiki page we deliberately drop, and why (asserted absent below
# so a wiki edit that renames one is caught rather than silently re-added).
EXCLUDED = {
    "herb boxes": "ironman-blocked by the game",
    "explorer's ring": "ring charge, not a destination",
    "falador shield": "shield charge, not a destination",
    "agility cape": "cape charge, not a destination",
    "fletching cape": "cape charge, not a destination",
    "hunter cape": "cape charge, not a destination",
    "magic cape": "cape charge, not a destination",
    "zulrah": "resurrection, not a destination",
    "fight cave": "resurrection, not a destination",
}


def fetch_wikitext() -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, "repeatable_events.wikitext")
    if not os.path.exists(cached):
        req = urllib.request.Request(WIKI, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode("utf-8")
        with open(cached, "w") as f:
            f.write(text)
    return open(cached).read()


def gameval_ids(cls: str, floor: int) -> dict:
    """<CONSTANT NAME> -> id, straight from the client on the classpath."""
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    out = subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", cls],
        capture_output=True, text=True, check=True).stdout
    ids = {}
    for m in re.finditer(r"public static final int (\w+) = (\d+);", out):
        ids[m.group(1)] = int(m.group(2))
    if len(ids) < floor:
        raise SystemExit(f"suspiciously small {cls} dump: {len(ids)}")
    return ids


def index_item_ids() -> dict:
    """Display name -> item id, via the generated item-name index (its keys are
    the client's legacy ItemID constant names)."""
    with open("src/main/resources/data/index/item-names.json") as f:
        return json.load(f)


def resolve_item(name: str, index: dict) -> int:
    key = re.sub(r"[^A-Z0-9]+", "_", name.upper().replace("'", "")).strip("_")
    if key not in index:
        raise SystemExit(f"item not in the name index: {name!r} (looked for {key})")
    return index[key]


def validate_quest_tokens(dailies):
    here = os.path.dirname(__file__)
    names = {ln.strip().lower() for ln in open(os.path.join(here, "questnames.txt")) if ln.strip()}
    for daily in dailies:
        for req in daily["reqs"]:
            if req.startswith(("quest:", "queststarted:")):
                name = req.split(":", 1)[1].rstrip(".").lower()
                if name not in names:
                    raise SystemExit(f"{daily['id']}: quest not in the Quest enum: {name!r}")


def check_wiki_still_lists(wikitext, dailies):
    """Every event we ship must still be findable on the page, and every
    exclusion must still be there to exclude — otherwise the page moved on
    and this generator's transcription is stale."""
    lowered = wikitext.lower()
    # one distinctive wiki-side token per event
    markers = {
        "zaff_battlestaves": "zaff",
        "cromperty_essence": "cromperty",
        "bert_sand": "bert",
        "flax_bowstring": "flax keeper",
        "miscellania": "miscellania",
        "rantz_arrows": "rantz",
        "thirus_dynamite": "thirus",
        "robin_bonemeal": "robin",
        "lundail_runes": "lundail",
        "tears_of_guthix": "tears of guthix",
    }
    for daily in dailies:
        marker = markers[daily["id"]]
        if marker not in lowered:
            raise SystemExit(
                f"{daily['id']}: {marker!r} no longer on the Repeatable events page — "
                "re-read the wiki and update DAILIES")
    for marker, why in EXCLUDED.items():
        if marker not in lowered:
            raise SystemExit(
                f"excluded event {marker!r} ({why}) is no longer on the page — "
                "re-check EXCLUDED against the wiki")


def build(varbit_ids, item_ids, index):
    out = []
    for daily in DAILIES:
        if daily["id"] not in COORDS:
            raise SystemExit(f"{daily['id']}: no verified coordinate — refusing to guess one")
        x, y, plane = COORDS[daily["id"]]
        detection = {"mode": daily["mode"]}
        if daily.get("chat"):
            detection["chat"] = daily["chat"]
        if daily["varbit"] is not None:
            if daily["varbit"] not in varbit_ids:
                raise SystemExit(
                    f"{daily['id']}: unknown gameval VarbitID.{daily['varbit']} — "
                    f"renamed in the client? re-check against {RUNELITE_TAG}")
            detection["varbit"] = varbit_ids[daily["varbit"]]
        elif daily["mode"] not in ("manual",):
            raise SystemExit(f"{daily['id']}: mode {daily['mode']!r} needs a varbit")

        if daily["icon"] not in item_ids:
            raise SystemExit(
                f"{daily['id']}: unknown gameval ItemID.{daily['icon']} — renamed in the client?")
        entry = {
            "id": daily["id"],
            "name": daily["name"],
            "icon": item_ids[daily["icon"]],
            "where": daily["where"],
            "reset": daily["reset"],
            "point": {"x": x, "y": y, "plane": plane},
            "reqs": daily["reqs"],
            "detection": detection,
            "tiers": [{"req": req, "qty": qty} if req else {"qty": qty}
                      for req, qty in daily["tiers"]],
            "bring": [dict({"label": label, "per": per},
                            **({"itemIds": [resolve_item(n, index) for n in items]}
                               if items else {}))
                      for label, per, items in daily["bring"]],
        }
        if daily.get("optOut"):
            entry["optOut"] = True
        if daily.get("warning"):
            entry["warning"] = daily["warning"]
        if daily.get("travel"):
            entry["travel"] = daily["travel"]
        if daily["note"]:
            entry["note"] = daily["note"]
        out.append(entry)
    return out


def main():
    wikitext = fetch_wikitext()
    varbit_ids = gameval_ids("net.runelite.api.gameval.VarbitID", 5000)
    item_ids = gameval_ids("net.runelite.api.gameval.ItemID", 10000)
    validate_quest_tokens(DAILIES)
    check_wiki_still_lists(wikitext, DAILIES)
    dailies = build(varbit_ids, item_ids, index_item_ids())

    ids = [d["id"] for d in dailies]
    assert len(ids) == len(set(ids)), "duplicate daily ids"
    assert len(dailies) == len(DAILIES)
    assert all(d["icon"] > 0 for d in dailies), "every event needs a tile icon"
    gated = {d["id"]: b for d in dailies for b in d["bring"] if "itemIds" in b}
    assert set(gated) == {"flax_bowstring", "robin_bonemeal"}, \
        f"unexpected supply-gated events: {sorted(gated)}"
    assert len(gated["robin_bonemeal"]["itemIds"]) == len(ROBIN_BONES)
    assert len(set(gated["robin_bonemeal"]["itemIds"])) == len(ROBIN_BONES), "duplicate bone ids"
    # Every varbit-backed event must resolve to a real, distinct varbit.
    flags = [d["detection"]["varbit"] for d in dailies if "varbit" in d["detection"]]
    assert len(flags) == len(set(flags)), "two events share a detection varbit"
    assert len(flags) >= 9, f"only {len(flags)} events auto-detect — expected 9+"
    # Tier ladders must be strictly increasing, or "best met tier" is nonsense.
    for d in dailies:
        qtys = [t["qty"] for t in d["tiers"]]
        assert qtys == sorted(qtys) and len(qtys) == len(set(qtys)), \
            f"{d['id']}: tier quantities not strictly increasing: {qtys}"

    pack = {
        "$schema": "./schemas/dailies.schema.json",
        "source": (f"OSRS wiki Repeatable events + RuneLite {RUNELITE_TAG} "
                   "Daily Task Indicator varbits"),
        "generated": datetime.date.today().isoformat(),
        "dailies": dailies,
    }
    with open(OUT, "w") as f:
        json.dump(pack, f, indent=2)
        f.write("\n")
    print(f"wrote {len(dailies)} repeatable events to {OUT} "
          f"({len(flags)} auto-detected, {len(dailies) - len(flags)} manual)")


if __name__ == "__main__":
    main()
