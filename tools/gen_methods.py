#!/usr/bin/env python3
"""Generate data/methods.json — the training-method ladders for the goal
engine (WOM-shape {startXp, rate} tiers + requirement overlay).

v1 is a curated seed: the SEED table below encodes the practical ironman
method ladder researched in ENGINE-DESIGN.md Appendix A (OSRS wiki
Ironman_Guide pages, July 2026), with per-entry provenance. Rates are the
wiki's practical numbers, deliberately NOT Wise Old Man's max-efficiency
EHP rates (2-alt tick-perfect assumptions) — WOM's open-source configs
serve as the calibration envelope in tests instead. Levels are written as
levels here and compiled to exact xp thresholds by this script.

Styles: active | semi | afk | daily. `daily` methods are background-lane
content — the cost model never spends active hours on them.

Usage: python3 tools/gen_methods.py
"""
import datetime
import json

OUT = "src/main/resources/data/methods.json"
WIKI = "oldschool.runescape.wiki/w/"


def xp_for_level(level):
    total = 0
    for l in range(1, level):
        total += int(l + 300 * 2 ** (l / 7)) // 4
    return total


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
        ("ensouled_heads", "Ensouled heads (Arceuus)", 1, 90000, "skillb:Magic:16", "active", "Ensouled_head"),
        ("bone_shards", "Blessed bone shards (libation bowl)", 30, 130000, "quest:Children of the Sun", "semi", "Blessed_bone_shards"),
        ("chaos_altar", "Chaos altar dragon bones (wildy)", 30, 250000, None, "active", "Chaos_temple_(hut)"),
    ],
    "Runecraft": [
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
        ("potions", "Potions from farm-run herbs", 26, 60000, None, "semi", "Ironman_Guide/Herblore"),
        ("mixology", "Mastering Mixology", 60, 70000, "quest:Children of the Sun", "active", "Mastering_Mixology"),
    ],
    "Thieving": [
        ("fruit_stalls", "Fruit stalls (Hosidius)", 25, 35000, None, "active", "Ironman_Guide/Thieving"),
        ("blackjacking", "Blackjacking", 45, 150000, "quest:The Feud", "active", "Blackjacking"),
        ("ardy_knights", "Ardougne knights", 55, 80000, None, "semi", "Ironman_Guide/Thieving"),
        ("gem_stalls", "Varlamore gem stalls", 75, 160000, "quest:Children of the Sun", "active", "Ironman_Guide/Thieving"),
    ],
    "Crafting": [
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
        ("iron_ore", "Iron ore (powermine)", 15, 35000, None, "active", "Ironman_Guide/Mining"),
        ("motherlode", "Motherlode Mine", 30, 40000, None, "semi", "Motherlode_Mine"),
        ("calcified", "Calcified rocks (+prayer shards)", 41, 45000, "quest:Perilous Moons", "afk", "Calcified_rocks"),
        ("granite_3t", "3-tick granite", 45, 95000, None, "active", "Ironman_Guide/Mining"),
        ("volcanic_mine", "Volcanic Mine", 70, 80000, "quest:Bone Voyage", "active", "Volcanic_Mine"),
    ],
    "Smithing": [
        ("foundry", "Giants' Foundry", 15, 80000, "skillb:Smithing:15", "active", "Giants'_Foundry"),
        ("foundry_high", "Giants' Foundry (high tiers)", 60, 180000, None, "active", "Giants'_Foundry"),
        ("blast_furnace_gold", "Blast Furnace gold (gauntlets)", 40, 250000, "quest:Family Crest", "active", "Blast_Furnace"),
    ],
    "Fishing": [
        ("fly_fishing", "Fly fishing (powerfish)", 20, 28000, None, "active", "Ironman_Guide/Fishing"),
        ("barbarian", "Barbarian fishing", 58, 68000, "any:skillb:Agility:30&skillb:Strength:30", "active", "Barbarian_Fishing"),
        ("karambwans", "Karambwans (banked cooking)", 65, 30000, "quest:Tai Bwo Wannai Trio", "semi", "Ironman_Guide/Fishing"),
        ("minnows", "Minnows", 82, 48000, None, "semi", "Minnow"),
    ],
    "Cooking": [
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


def main():
    skills = []
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
            }
            if req:
                method["req"] = req
            methods.append(method)
        ladder = {"skill": skill, "methods": methods}
        if BONUSES.get(skill):
            ladder["bonuses"] = BONUSES[skill]
        skills.append(ladder)

    pack = {
        "source": "curated ironman method seed per ENGINE-DESIGN.md Appendix A "
                  "(OSRS wiki Ironman_Guide pages, July 2026); practical rates, "
                  "not WOM max-efficiency EHP",
        "generated": datetime.date.today().isoformat(),
        "skills": skills,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    count = sum(len(l["methods"]) for l in skills)
    print(f"wrote {OUT}: {len(skills)} skills, {count} methods")


if __name__ == "__main__":
    main()
