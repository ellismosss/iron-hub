#!/usr/bin/env python3
"""Generate data/methods.json — the training-method ladders for the goal
engine (WOM-shape {startXp, rate} tiers + requirement overlay).

Since 2026-07-23 (Luke: "refresh and expand methods.json from the KB. Pull
all methods. Always prefer Wiki-sourced rates over curated rates, until
live player-specific data is harvested") the ladders MERGE two sources:

1. The KB's TABLE-DERIVED wiki tiers (knowledge.db training_methods, the
   v2 header-mapped parse of every skill's training guide + ironman guide
   — run `python3 tools/knowledge/rebuild.py` or
   `tools/knowledge/harvest_training.py` first). Where a wiki tier matches
   a curated method, the WIKI rate wins; unmatched wiki methods join the
   ladder as new entries. Slayer's per-monster rows take their level from
   slayer-tasks.json's task reqs. PROSE-derived KB rows are deliberately
   excluded: their level↔rate pairing is a section summary, not a ladder
   tier — costing on them would invent numbers.
2. The curated SEED below (ENGINE-DESIGN.md Appendix A) survives as the
   requirement/style/consumables overlay, the floor guarantee, and the
   rate of record wherever the wiki tables have no figure.

At runtime, a player's own measured rates (StateView.measuredRate) still
override everything after 1.0 observed hours — the wiki rate is the prior,
never the last word.

The WOM MACHINERY (G4) stands: every rate — curated or wiki — must sit
under WOM's max-efficiency ironman envelope × slack; wiki tiers above it
are dropped with a log line, a curated violation still fails the build.

Styles: active | semi | afk | daily. `daily` methods are background-lane
content — the cost model never spends active hours on them. Wiki-added
methods default to `active` (the guides don't state intensity).

Usage: python3 tools/gen_methods.py

Needs network (the WOM cross-validation). Cached under tools/.cache-methods/.
"""
import datetime
import json
import os
import re
import sqlite3
import urllib.request

OUT = "src/main/resources/data/methods.json"
WIKI = "oldschool.runescape.wiki/w/"

# WOM's open-source ironman EHP config = the max-efficiency envelope. Pinned.
WOM_COMMIT = "1afcf8cb1f2905832149634fec65e318d2521150"
WOM_URL = ("https://raw.githubusercontent.com/wise-old-man/wise-old-man/"
           f"{WOM_COMMIT}/server/src/api/modules/efficiency/configs/ehp/ironman.ehp.ts")
UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-methods")
# a practical rate may sit at most this far above WOM's max-efficiency peak
# before it reads as a data error rather than a modelling difference
ENVELOPE_SLACK = 1.5
# WOM enum name → our skill name where they differ
WOM_SKILL = {"HITPOINTS": "Hitpoints", "RUNECRAFTING": "Runecraft",
             "WOODCUTTING": "Woodcutting", "FIREMAKING": "Firemaking"}


def xp_for_level(level):
    total = 0
    for l in range(1, level):
        total += int(l + 300 * 2 ** (l / 7)) // 4
    return total


# method id -> (xp per action, [(itemId, qty, name)]) for methods whose
# consumables are well-known wiki constants; the planner derives "planks
# needed" from these. Only high-confidence values — no guesses.
CONSUMES = {
    "construction_oak_larders": (480.0, [(8778, 8, "Oak plank")]),
    "construction_teak_benches": (90.0, [(8780, 1, "Teak plank")]),
    "prayer_chaos_altar": (252.0, [(536, 1, "Dragon bones")]),
    "cooking_karambwans_cook": (190.0, [(3142, 1, "Raw karambwan")]),
    "cooking_jugs_of_wine": (200.0, [(1987, 1, "Grapes"), (1937, 1, "Jug of water")]),
    "smithing_blast_furnace_gold": (56.2, [(444, 1, "Gold ore")]),
    "magic_high_alch": (65.0, [(561, 1, "Nature rune")]),
}

# skill -> [(id, name, startLevel, rate, req, style, source_page)]
SEED = {
    "Attack": [
        ("gemstone_crabs", "Gemstone crabs", 1, 25000, "quest:Children of the Sun", "semi", "Gemstone_crab"),
        ("sand_crabs", "Sand crabs", 1, 20000, None, "semi", "Sand_Crab"),
        ("slayer_melee", "Slayer tasks (melee)", 40, 35000, None, "active", "Ironman_Guide/Slayer"),
        ("nmz", "Nightmare Zone", 70, 78000, "qp:100", "afk", "Nightmare_Zone"),
        ("sulphur_naguas", "Sulphur naguas", 70, 130000, "quest:Perilous Moons", "afk", "Sulphur_Nagua"),
    ],
    "Strength": [
        ("gemstone_crabs", "Gemstone crabs", 1, 25000, "quest:Children of the Sun", "semi", "Gemstone_crab"),
        ("sand_crabs", "Sand crabs", 1, 20000, None, "semi", "Sand_Crab"),
        ("slayer_melee", "Slayer tasks (melee)", 40, 35000, None, "active", "Ironman_Guide/Slayer"),
        ("nmz", "Nightmare Zone", 70, 82000, "qp:100", "afk", "Nightmare_Zone"),
        ("sulphur_naguas", "Sulphur naguas", 70, 135000, "quest:Perilous Moons", "afk", "Sulphur_Nagua"),
    ],
    "Defence": [
        ("gemstone_crabs", "Gemstone crabs", 1, 25000, "quest:Children of the Sun", "semi", "Gemstone_crab"),
        ("slayer_melee", "Slayer tasks (defensive)", 40, 30000, None, "active", "Ironman_Guide/Slayer"),
        ("nmz", "Nightmare Zone", 70, 75000, "qp:100", "afk", "Nightmare_Zone"),
    ],
    "Hitpoints": [
        ("via_combat", "Trained via combat (1/3 rate)", 1, 9000, None, "semi", "Hitpoints"),
        ("via_combat_late", "Trained via combat (late rates)", 70, 30000, None, "semi", "Hitpoints"),
    ],
    "Ranged": [
        ("shortbow_crabs", "Shortbow at crabs", 1, 15000, None, "semi", "Ironman_Guide/Ranged"),
        ("dorgeshuun_crabs", "Dorgeshuun crossbow at crabs", 28, 22000, "quest:The Lost Tribe", "semi", "Ironman_Guide/Ranged"),
        ("red_chins", "Red chinchompas (MM1 tunnels)", 55, 90000, "skillb:Hunter:63", "active", "Ironman_Guide/Ranged"),
        ("black_chins", "Black chinchompas", 65, 150000, "skillb:Hunter:73", "active", "Ironman_Guide/Ranged"),
        ("nmz_ranged", "Nightmare Zone (ranged)", 70, 60000, "qp:100", "afk", "Nightmare_Zone"),
    ],
    "Magic": [
        ("crab_autocast", "Autocast at gemstone crabs", 1, 15000, "quest:Children of the Sun", "afk", "Ironman_Guide/Magic"),
        ("high_alch", "High Level Alchemy + misc casts", 55, 65000, None, "semi", "High_Level_Alchemy"),
        ("bursting", "Bursting slayer tasks", 70, 85000, "quest:Desert Treasure I", "active", "Ironman_Guide/Magic"),
    ],
    "Prayer": [
        ("bury_bones", "Buried bones from combat", 1, 30000, None, "active", "Prayer_training"),
        ("ensouled_heads", "Ensouled heads (Arceuus)", 1, 90000, "skillb:Magic:16", "active", "Ensouled_head"),
        ("bone_shards", "Blessed bone shards (libation bowl)", 30, 130000, "quest:Children of the Sun", "semi", "Blessed_bone_shards"),
        ("chaos_altar", "Chaos altar dragon bones (wildy)", 30, 250000, None, "active", "Chaos_temple_(hut)"),
    ],
    "Runecraft": [
        ("low_altars", "Air/earth runes", 1, 8000, None, "active", "Runecraft_training"),
        ("gotr", "Guardians of the Rift", 10, 35000, "quest:Temple of the Eye", "active", "Guardians_of_the_Rift"),
        ("blood_runes", "Blood runes (Arceuus)", 77, 38000, None, "afk", "Blood_rune"),
        ("soul_runes", "Soul runes (Arceuus)", 90, 45000, None, "afk", "Soul_rune"),
    ],
    "Construction": [
        ("mahogany_homes", "Mahogany Homes", 1, 60000, None, "active", "Mahogany_Homes"),
        ("oak_larders", "Oak larders", 33, 150000, None, "active", "Ironman_Guide/Construction"),
        ("teak_benches", "Teak benches + demon butler", 52, 300000, "skillb:Magic:45", "active", "Ironman_Guide/Construction"),
    ],
    "Agility": [
        ("rooftops", "Rooftop courses", 1, 42000, None, "active", "Ironman_Guide/Agility"),
        ("colossal_wyrm", "Colossal Wyrm course", 50, 38000, "quest:Children of the Sun", "semi", "Colossal_Wyrm_Agility_Course"),
        ("sepulchre", "Hallowed Sepulchre", 72, 75000, "quest:Sins of the Father", "active", "Hallowed_Sepulchre"),
        ("prifddinas", "Prifddinas course (+shards)", 75, 62000, "quest:Song of the Elves", "active", "Prifddinas_Agility_Course"),
    ],
    "Herblore": [
        ("attack_potions", "Attack potions", 3, 20000, "quest:Druidic Ritual", "active", "Ironman_Guide/Herblore"),
        ("potions", "Potions from farm-run herbs", 26, 60000, None, "semi", "Ironman_Guide/Herblore"),
        ("mixology", "Mastering Mixology", 60, 70000, "quest:Children of the Sun", "active", "Mastering_Mixology"),
    ],
    "Thieving": [
        ("men_stalls", "Men and cake stalls", 1, 10000, None, "active", "Ironman_Guide/Thieving"),
        ("fruit_stalls", "Fruit stalls (Hosidius)", 25, 35000, None, "active", "Ironman_Guide/Thieving"),
        ("blackjacking", "Blackjacking", 45, 150000, "quest:The Feud", "active", "Blackjacking"),
        ("ardy_knights", "Ardougne knights", 55, 80000, None, "semi", "Ironman_Guide/Thieving"),
        ("gem_stalls", "Varlamore gem stalls", 75, 160000, "quest:Children of the Sun", "active", "Ironman_Guide/Thieving"),
    ],
    "Crafting": [
        ("leather_glass", "Leather and molten glass", 1, 20000, None, "active", "Ironman_Guide/Crafting"),
        ("gem_cutting", "Gem cutting", 34, 40000, None, "afk", "Ironman_Guide/Crafting"),
        ("glassblowing", "Superglass Make + lantern lenses", 46, 75000, "any:skillb:Magic:77&quest:Lunar Diplomacy", "semi", "Ironman_Guide/Crafting"),
        ("battlestaves", "Battlestaves (orb charging)", 54, 50000, "skillb:Magic:66", "active", "Ironman_Guide/Crafting"),
    ],
    "Fletching": [
        ("headless_arrows", "Headless arrows / darts", 1, 50000, None, "afk", "Ironman_Guide/Fletching"),
        ("vale_totems", "Vale Totems (willow+)", 27, 60000, "quest:Children of the Sun", "active", "Vale_Totems"),
        ("vale_totems_yew", "Vale Totems (yew)", 70, 250000, "any:quest:Children of the Sun&skillb:Woodcutting:60", "active", "Vale_Totems"),
        ("vale_totems_magic", "Vale Totems (magic)", 75, 400000, "any:quest:Children of the Sun&skillb:Woodcutting:75", "active", "Vale_Totems"),
    ],
    "Slayer": [
        ("early_tasks", "Turael/Mazchna tasks", 1, 15000, None, "active", "Ironman_Guide/Slayer"),
        ("mid_tasks", "Konar/Nieve tasks", 40, 30000, None, "active", "Ironman_Guide/Slayer"),
        ("high_tasks", "Duradel tasks + bursting", 70, 55000, None, "active", "Ironman_Guide/Slayer"),
        ("elite_tasks", "Block-list optimized tasks", 85, 70000, None, "active", "Ironman_Guide/Slayer"),
    ],
    "Hunter": [
        ("bird_snares", "Bird snares / early traps", 1, 12000, None, "active", "Ironman_Guide/Hunter"),
        ("birdhouses", "Birdhouse runs", 5, 300000, "quest:Bone Voyage", "daily", "Birdhouse_trapping"),
        ("salamanders", "Salamanders", 29, 45000, None, "semi", "Ironman_Guide/Hunter"),
        ("rumours", "Hunter rumours", 46, 60000, "quest:Children of the Sun", "active", "Hunter_Guild"),
        ("red_chins_hunt", "Red chinchompas", 63, 90000, None, "active", "Ironman_Guide/Hunter"),
        ("herbiboar", "Herbiboar", 80, 120000, "any:quest:Bone Voyage&skillb:Herblore:31", "active", "Herbiboar"),
    ],
    "Mining": [
        ("copper_tin", "Copper and tin", 1, 8000, None, "active", "Ironman_Guide/Mining"),
        ("iron_ore", "Iron ore (powermine)", 15, 35000, None, "active", "Ironman_Guide/Mining"),
        ("motherlode", "Motherlode Mine", 30, 40000, None, "semi", "Motherlode_Mine"),
        ("calcified", "Calcified rocks (+prayer shards)", 41, 45000, "quest:Perilous Moons", "afk", "Calcified_rocks"),
        ("granite_3t", "3-tick granite", 45, 95000, None, "active", "Ironman_Guide/Mining"),
        ("volcanic_mine", "Volcanic Mine", 70, 80000, "quest:Bone Voyage", "active", "Volcanic_Mine"),
    ],
    "Smithing": [
        ("bronze_iron", "Bronze/iron smithing", 1, 8000, None, "active", "Ironman_Guide/Smithing"),
        ("foundry", "Giants' Foundry", 15, 80000, "skillb:Smithing:15", "active", "Giants'_Foundry"),
        ("foundry_high", "Giants' Foundry (high tiers)", 60, 180000, None, "active", "Giants'_Foundry"),
        ("blast_furnace_gold", "Blast Furnace gold (gauntlets)", 40, 250000, "quest:Family Crest", "active", "Blast_Furnace"),
    ],
    "Fishing": [
        ("shrimp_sardine", "Shrimp and sardines", 1, 8000, None, "active", "Ironman_Guide/Fishing"),
        ("fly_fishing", "Fly fishing (powerfish)", 20, 28000, None, "active", "Ironman_Guide/Fishing"),
        ("barbarian", "Barbarian fishing", 58, 68000, "any:skillb:Agility:30&skillb:Strength:30", "active", "Barbarian_Fishing"),
        ("karambwans", "Karambwans (banked cooking)", 65, 30000, "quest:Tai Bwo Wannai Trio", "semi", "Ironman_Guide/Fishing"),
        ("minnows", "Minnows", 82, 48000, None, "semi", "Minnow"),
    ],
    "Cooking": [
        ("basic_food", "Basic food", 1, 15000, None, "active", "Ironman_Guide/Cooking"),
        ("trout_salmon", "Trout/salmon", 15, 45000, None, "active", "Ironman_Guide/Cooking"),
        ("karambwans_cook", "Karambwans", 65, 130000, "quest:Tai Bwo Wannai Trio", "active", "Ironman_Guide/Cooking"),
        ("jugs_of_wine", "Jugs of wine", 68, 300000, None, "active", "Ironman_Guide/Cooking"),
    ],
    "Firemaking": [
        ("burn_logs", "Log burning ladder", 1, 40000, None, "active", "Ironman_Guide/Firemaking"),
        ("wintertodt", "Wintertodt", 50, 200000, None, "active", "Wintertodt"),
    ],
    "Woodcutting": [
        ("trees_oaks", "Trees and oaks", 1, 30000, None, "active", "Ironman_Guide/Woodcutting"),
        ("teaks_2t", "Teaks (tick manipulation)", 35, 120000, None, "active", "Ironman_Guide/Woodcutting"),
        ("sulliusceps", "Sulliusceps", 65, 105000, "quest:Bone Voyage", "active", "Sulliuscep"),
        ("redwoods", "Redwoods", 90, 60000, None, "afk", "Redwood_tree"),
    ],
    "Farming": [
        ("allotments", "Allotments and low-level runs", 1, 15000, None, "semi", "Farming_training"),
        ("tithe_farm", "Tithe Farm", 34, 40000, None, "active", "Tithe_Farm"),
        ("herb_runs", "Herb runs", 32, 500000, None, "daily", "Farming_training"),
        ("tree_runs", "Tree/fruit tree runs", 15, 900000, None, "daily", "Farming_training"),
    ],
    "Sailing": [
        ("charting", "Sea charting + couriers", 1, 10000, "quest:Pandemonium", "active", "Sailing_training"),
        ("barracuda_t1", "Barracuda Trials (Tempor Tantrum)", 30, 22000, "quest:Pandemonium", "active", "Sailing_training"),
        ("barracuda_t2", "Barracuda Trials (Jubbly Jive)", 55, 75000, "quest:Pandemonium", "active", "Sailing_training"),
        ("barracuda_t3", "Barracuda Trials (Gwenith Glide)", 72, 140000, "any:quest:Pandemonium&quest:Regicide", "active", "Sailing_training"),
    ],
}

# cross-skill byproduct xp (WOM bonuses semantics), the load-bearing few
BONUSES = {
    "Fishing": [
        {"originSkill": "Fishing", "bonusSkill": "Agility", "startXp": xp_for_level(58), "endXp": 200_000_000, "ratio": 0.088},
        {"originSkill": "Fishing", "bonusSkill": "Strength", "startXp": xp_for_level(58), "endXp": 200_000_000, "ratio": 0.088},
    ],
    "Firemaking": [
        # Wintertodt crates pay broad skilling loot, not xp — no bonus edge
    ],
    "Attack": [
        {"originSkill": "Attack", "bonusSkill": "Hitpoints", "startXp": 0, "endXp": 200_000_000, "ratio": 0.33},
    ],
    "Strength": [
        {"originSkill": "Strength", "bonusSkill": "Hitpoints", "startXp": 0, "endXp": 200_000_000, "ratio": 0.33},
    ],
    "Defence": [
        {"originSkill": "Defence", "bonusSkill": "Hitpoints", "startXp": 0, "endXp": 200_000_000, "ratio": 0.33},
    ],
    "Ranged": [
        {"originSkill": "Ranged", "bonusSkill": "Hitpoints", "startXp": 0, "endXp": 200_000_000, "ratio": 0.33},
    ],
    "Slayer": [
        {"originSkill": "Slayer", "bonusSkill": "Hitpoints", "startXp": 0, "endXp": 200_000_000, "ratio": 1.1},
    ],
}


def wom_envelope():
    """WOM ironman max-efficiency peak rate per skill (the ceiling)."""
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, f"ironman.ehp.{WOM_COMMIT}.ts")
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            ts = f.read()
    else:
        req = urllib.request.Request(WOM_URL, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as resp:
            ts = resp.read().decode("utf-8")
        with open(cached, "w", encoding="utf-8") as f:
            f.write(ts)
    peaks = {}
    for block in re.split(r"skill:\s*Skill\.", ts)[1:]:
        enum = block.split(",", 1)[0].strip()
        rates = [int(r.replace("_", "")) for r in
                 re.findall(r"rate:\s*([\d_]+)", block)]
        if rates:
            peaks[WOM_SKILL.get(enum, enum.capitalize())] = max(rates)
    if len(peaks) < 20:
        raise SystemExit(f"WOM envelope parse yielded only {len(peaks)} skills")
    return peaks


def validate_envelope(skills, peaks):
    """Every curated rate must sit under WOM's max-efficiency ceiling
    (× slack). A violation is a data error (a rate typo), not a choice."""
    violations = []
    for ladder in skills:
        ceiling = peaks.get(ladder["skill"])
        if ceiling is None:
            continue  # WOM doesn't cover this skill (Sailing) — nothing to check
        for method in ladder["methods"]:
            if method["rate"] > ceiling * ENVELOPE_SLACK:
                violations.append(
                    f"{ladder['skill']}/{method['id']}: {method['rate']} > "
                    f"WOM peak {ceiling} × {ENVELOPE_SLACK}")
    if violations:
        raise SystemExit("rates exceed the WOM envelope (likely a typo):\n  "
                         + "\n  ".join(violations))
    checked = sum(1 for l in skills if l["skill"] in peaks)
    print(f"envelope OK: {checked} skills cross-validated vs WOM ironman "
          f"(commit {WOM_COMMIT[:7]}); Sailing has no WOM config, skipped")


DB = os.path.join(HERE, "..", "knowledge", "knowledge.db")
# generic section headings that are not method names
NAME_BLACKLIST = {"other methods", "notes", "summary", "training", "methods",
                  "experience", "quests", "money making", "recommended"}


def norm_name(name):
    return re.sub(r"[^a-z0-9 ]", "", re.sub(r"\([^)]*\)", "", name.lower())).strip()


STOPWORDS = {"of", "the", "and", "at", "in", "from", "with", "a", "an"}


def tokens(name):
    return {w for w in norm_name(name).split() if w not in STOPWORDS}


def names_match(a, b):
    """Containment either way, or equal token sets minus stopwords
    ("Knights of Ardougne" == "Ardougne knights")."""
    na, nb = norm_name(a), norm_name(b)
    if not na or not nb:
        return False
    if na in nb or nb in na:
        return True
    ta, tb = tokens(a), tokens(b)
    return bool(ta) and ta == tb


def name_variants(base):
    v = {base}
    if base.endswith("ies"):
        v.add(base[:-3] + "y")
    if base.endswith("es"):
        v.add(base[:-2])
    if base.endswith("s"):
        v.add(base[:-1])
    v.add(base + "s")
    v.add(base + "es")
    return v


def slayer_levels():
    """Monster name (normalized, singular+plural) → slayer level, from the
    slayer pack's own task stats."""
    with open("src/main/resources/data/slayer-tasks.json", encoding="utf-8") as f:
        pack = json.load(f)
    out = {}
    for task in pack.get("tasks", []):
        level = (task.get("stats") or {}).get("slayerLevel")
        if not level:
            level = 1
        for variant in name_variants(norm_name(task["name"])):
            out.setdefault(variant, level)
    return out


def kb_tiers(peaks):
    """skill → {method name → [(level, rate, source page)]} from the KB's
    table-derived rows, envelope-filtered."""
    if not os.path.exists(DB):
        raise SystemExit(f"{DB} missing — run python3 tools/knowledge/rebuild.py"
                         " (or tools/knowledge/harvest_training.py) first")
    conn = sqlite3.connect(DB)
    rows = conn.execute(
        "SELECT skill, method, level, rate, src FROM training_methods"
        " WHERE flags='table-derived' AND rate >= 1000").fetchall()
    # prose sections pair a rate with their heading's level range — honest
    # only when the range is NARROW ("Levels 25-39: Fruit stalls"); a
    # whole-skill span ("Levels 1-99: Sorceress's Garden") summarizes the
    # END-game rate at the START level and would corrupt the ladder
    rows += conn.execute(
        "SELECT skill, method, level, rate, src FROM training_methods"
        " WHERE flags LIKE 'prose-derived%' AND rate >= 1000"
        " AND level >= 5 AND level_end IS NOT NULL"
        " AND level_end - level <= 30").fetchall()
    conn.close()
    slayer = slayer_levels()
    tiers = {}
    skipped_level = skipped_envelope = 0
    for skill, method, level, rate, src in rows:
        if norm_name(method) in NAME_BLACKLIST or len(method.strip()) < 3:
            continue
        if skill == "Slayer" and not level:
            level = slayer.get(norm_name(method))
            if level is None:
                skipped_level += 1
                continue
        if not level:
            skipped_level += 1
            continue
        ceiling = peaks.get(skill)
        if ceiling is not None and rate > ceiling * ENVELOPE_SLACK:
            skipped_envelope += 1
            continue
        page = src.replace("wiki:", "").replace(" ", "_")
        # case-fold the group key ("Barbarian Fishing" vs "Barbarian
        # fishing" are one method) — first-seen casing is the display name
        skill_methods = tiers.setdefault(skill, {})
        key = next((k for k in skill_methods if k.lower() == method.strip().lower()),
                   method.strip())
        skill_methods.setdefault(key, []).append((int(level), int(rate), page))
    for methods in tiers.values():
        for tier_list in methods.values():
            tier_list.sort()
            # one tier per level (two pages can rate the same level) — keep
            # the first (sorted = lowest rate: never overpromise)
            seen = set()
            tier_list[:] = [t for t in tier_list
                            if t[0] not in seen and not seen.add(t[0])]
    n = sum(len(t) for m in tiers.values() for t in m.values())
    print(f"KB tiers: {n} usable across {len(tiers)} skills"
          f" ({skipped_level} skipped level-less, {skipped_envelope} over the"
          " WOM envelope)")
    return tiers


def slug(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")[:40]


def merge_wiki(skill, methods, wiki_methods, report):
    """Wiki rates override matched curated methods; remaining tiers join
    as new ladder entries (curated req/style propagate to matched groups)."""
    consumed = set()
    for method in methods:
        match = next((w for w in wiki_methods
                      if names_match(w, method["name"])), None)
        if match is None:
            continue
        tier_list = wiki_methods[match]
        cur_level = next((l for l in range(1, 100)
                          if xp_for_level(l) >= method["startXp"]), 1)
        at_or_below = [t for t in tier_list if t[0] <= cur_level]
        level, rate, page = at_or_below[-1] if at_or_below else tier_list[0]
        if rate != method["rate"]:
            report.append(f"  {skill}/{method['name']}: {method['rate']:,}"
                          f" -> {rate:,} (wiki, level {level} tier)")
        method["rate"] = rate
        method["origin"] = "wiki"
        method["source"] = WIKI + page
        consumed.add((match, level))
        # the group's OTHER tiers join the ladder under the curated gate
        for t_level, t_rate, t_page in tier_list:
            if (match, t_level) in consumed or t_level == cur_level:
                continue
            consumed.add((match, t_level))
            extra = {
                "id": f"{skill.lower()}_wiki_{slug(match)}_{t_level}",
                "name": match,
                "startXp": xp_for_level(t_level),
                "rate": t_rate,
                "style": method["style"],
                "source": WIKI + t_page,
                "origin": "wiki",
            }
            if method.get("req"):
                extra["req"] = method["req"]
            methods.append(extra)
    added = 0
    for name, tier_list in wiki_methods.items():
        for level, rate, page in tier_list:
            if (name, level) in consumed:
                continue
            methods.append({
                "id": f"{skill.lower()}_wiki_{slug(name)}_{level}",
                "name": name,
                "startXp": xp_for_level(level),
                "rate": rate,
                "style": "active",
                "source": WIKI + page,
                "origin": "wiki",
            })
            added += 1
    methods.sort(key=lambda m: (m["startXp"], -m["rate"]))
    return added


def main():
    peaks = wom_envelope()
    wiki_tiers = kb_tiers(peaks)
    skills = []
    report = []
    total_added = 0
    for skill, rows in SEED.items():
        methods = []
        for mid, name, level, rate, req, style, page in rows:
            method = {
                "id": f"{skill.lower()}_{mid}",
                "name": name,
                "startXp": xp_for_level(level),
                "rate": rate,
                "style": style,
                "source": WIKI + page,
                "origin": "curated",
            }
            if req:
                method["req"] = req
            consumes = CONSUMES.get(method["id"])
            if consumes:
                method["xpEach"] = consumes[0]
                method["inputs"] = [
                    {"itemId": item, "qty": qty, "name": name}
                    for item, qty, name in consumes[1]
                ]
            methods.append(method)
        added = merge_wiki(skill, methods, wiki_tiers.get(skill, {}), report)
        total_added += added
        ladder = {"skill": skill, "methods": methods}
        if BONUSES.get(skill):
            ladder["bonuses"] = BONUSES[skill]
        skills.append(ladder)

    validate_envelope(skills, peaks)

    pack = {
        "source": "curated ironman seed (ENGINE-DESIGN.md Appendix A) merged "
                  "with the knowledge base's wiki training-guide tables — "
                  "WIKI rates preferred over curated where both speak (Luke, "
                  "2026-07-23); player-measured rates override at runtime. "
                  f"Cross-validated vs WOM ironman EHP envelope "
                  f"(commit {WOM_COMMIT[:7]}).",
        "generated": datetime.date.today().isoformat(),
        "skills": skills,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    count = sum(len(l["methods"]) for l in skills)
    print(f"wrote {OUT}: {len(skills)} skills, {count} methods"
          f" ({total_added} wiki-added)")
    print("curated rates overridden by wiki tables:")
    for line in report:
        print(line)


if __name__ == "__main__":
    main()
