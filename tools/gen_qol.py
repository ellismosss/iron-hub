#!/usr/bin/env python3
"""Generate data/qol.json — the QoL checklist catalog, widened from the
hand-written 9 unlocks to the knowledge-base catalog (Luke's word,
2026-07-21): Category:Storage items + a curated utility list.

Pack semantics (Goals v2 G2): an entry is achieved by OWNING it —
`itemIds` is the ownership proof (variation-aware), `requirements` are
display-only prose steps. Non-item unlocks (fairy ring network, spirit
trees, gnome gliders) cannot prove by ownership and stay knowledge-base
side; they are skipped with a log line. The original 9 entries keep their
hand-written requirement prose verbatim.

Usage: python3 tools/gen_qol.py
"""

import json
import os
import re
import sqlite3
import urllib.parse
import urllib.request

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "knowledge", "knowledge.db")
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-qol")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "qol.json")
ITEM_INDEX = os.path.join(HERE, "..", "src", "main", "resources", "data", "index", "item-names.json")
ITEMIDS_TXT = os.path.join(HERE, "itemids.txt")  # UNFILTERED dump — the index
# drops _\d+-suffixed constants (id-lookalikes), losing ARDOUGNE_CLOAK_1 etc.
WIKI_API = "https://oldschool.runescape.wiki/api.php"

# the original pack's 9 hand-audited entries, kept VERBATIM — ids are
# load-bearing (QolModuleTest, persisted qol:<id> goal seeds) and their
# graph-parseable requirements (skill:/quest:) gate better than prose
LEGACY = [
    {"id": "herb_sack", "name": "Herb sack", "itemIds": [13226],
     "requirements": ["250 Tithe Farm points"]},
    {"id": "seed_box", "name": "Seed box", "itemIds": [13639],
     "requirements": ["250 Tithe Farm points"]},
    {"id": "coal_bag", "name": "Coal bag", "itemIds": [12019],
     "requirements": ["100 golden nuggets (Motherlode Mine)"]},
    {"id": "gem_bag", "name": "Gem bag", "itemIds": [12020],
     "requirements": ["100 golden nuggets (Motherlode Mine)"]},
    {"id": "rune_pouch", "name": "Rune pouch", "itemIds": [12791],
     "requirements": ["750 Slayer reward points"]},
    {"id": "graceful_hood", "name": "Graceful hood", "itemIds": [11850],
     "requirements": ["35 marks of grace"]},
    {"id": "ava_accumulator", "name": "Ava's accumulator", "itemIds": [10499],
     "requirements": ["skill:Ranged:50", "quest:Animal Magnetism"]},
    {"id": "ava_assembler", "name": "Ava's assembler", "itemIds": [22109],
     "requirements": ["quest:Dragon Slayer II"]},
    {"id": "dragon_defender", "name": "Dragon defender", "itemIds": [12954],
     "requirements": ["skill:Attack:60", "skill:Defence:60"]},
]

# utility unlocks beyond storage items (same list the KB catalog uses);
# entries that do not resolve to an item id are skipped with a log line
CURATED = [
    "Graceful hood", "Graceful top", "Graceful legs", "Graceful gloves",
    "Graceful boots", "Graceful cape", "Ava's accumulator", "Ava's assembler",
    "Rogue equipment", "Bottomless compost bucket", "Magic secateurs",
    "Farming cape", "Ardougne cloak 1", "Ardougne cloak 2", "Ardougne cloak 3",
    "Ardougne cloak 4", "Explorer's ring 2", "Explorer's ring 3", "Explorer's ring 4",
    "Karamja gloves 3", "Karamja gloves 4", "Fremennik sea boots 4",
    "Desert amulet 4", "Falador shield 3", "Morytania legs 3", "Varrock armour 3",
    "Wilderness sword 4", "Western banner 4", "Rada's blessing 4",
    "Kandarin headgear 4", "Book of the dead", "Dramen staff",
    "Achievement diary cape", "Crystal saw", "Amy's saw", "Imcando hammer",
    "Bruma torch", "Infernal axe", "Infernal pickaxe", "Infernal harpoon",
    "Crystal harpoon", "Crystal axe", "Crystal pickaxe", "Dragon harpoon",
    "Royal seed pod", "Ectophial", "Camulet",
    "Steel key ring", "Master scroll book", "Rune pouch", "Looting bag",
    # Luke's 2026-07-22 answer key: named variants replace the umbrella
    # names ("Quetzal whistle" x4, "Fur pouch" x3, "Gem containers" x5,
    # "Rogue equipment" = the 5 Rogues' Den pieces)
    "Basic quetzal whistle", "Enhanced quetzal whistle", "Perfected quetzal whistle",
    "Small fur pouch", "Medium fur pouch", "Large fur pouch",
    "Gem pouch", "Gem satchel", "Gem tote", "Gem sack",
    "Small pouch", "Medium pouch", "Large pouch", "Giant pouch", "Colossal pouch",
    "Rogue mask", "Rogue top", "Rogue trousers", "Rogue gloves", "Rogue boots",
    "Plank sack", "Log basket", "Forestry kit", "Herb sack",
]


def fetch(url: str, cache_name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, cache_name)
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return f.read()
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        text = resp.read().decode("utf-8")
    with open(cached, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def api(params: dict, cache_name: str) -> dict:
    params = dict(params, format="json", formatversion="2")
    return json.loads(fetch(WIKI_API + "?" + urllib.parse.urlencode(params), cache_name))


def storage_titles():
    data = api({"action": "query", "list": "categorymembers",
                "cmtitle": "Category:Storage items", "cmnamespace": 0,
                "cmlimit": 500}, "cat-storage.json")
    titles = [m["title"] for m in data["query"]["categorymembers"]]
    assert len(titles) >= 40, f"suspiciously few storage items: {len(titles)}"
    return titles


def recipe_prose(page: str):
    """One display line from the page's {{Recipe}}, or None."""
    data = api({"action": "parse", "page": page, "prop": "wikitext"},
               "page-" + re.sub(r"[^A-Za-z0-9]+", "_", page) + ".json")
    text = data.get("parse", {}).get("wikitext", "")
    m = re.search(r"\{\{Recipe(.*?)\n\}\}", text, re.S)
    if not m:
        return None
    body = m.group(1)
    skills = re.findall(r"\|skill(\d)\s*=\s*([^\n|]+)", body)
    levels = dict(re.findall(r"\|skill(\d)lvl\s*=\s*([^\n|]+)", body))
    parts = [f"{name.strip()} {levels.get(i, '').strip()}".strip()
             for i, name in skills]
    return ("Make: " + ", ".join(p for p in parts if p)) if parts else None


def item_index():
    with open(ITEM_INDEX, encoding="utf-8") as f:
        by_const = json.load(f)
    with open(ITEMIDS_TXT, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2 and parts[1].isdigit():
                by_const.setdefault(parts[0], int(parts[1]))
    collapsed = {}
    for const, item_id in by_const.items():
        collapsed.setdefault(const.replace("_", ""), item_id)
    return by_const, collapsed


def resolve_item(index, name: str):
    by_const, collapsed = index
    base = re.sub(r"^_+|_+$", "", re.sub(r"[^A-Z0-9]+", "_",
                  re.sub(r"['’]", "", name).upper()))
    for c in (base, "_" + base, "BASIC_" + base):
        if c in by_const:
            return by_const[c]
    key = base.replace("_", "")
    if key in collapsed:
        return collapsed[key]
    return None


def benefits():
    """name(lower) → cleaned one-liner from the KB's qol_items.effect —
    what the unlock actually DOES (the hover Luke asked for)."""
    if not os.path.exists(DB):
        raise SystemExit(f"{DB} missing — run: python3 tools/knowledge/rebuild.py")
    conn = sqlite3.connect(DB)
    out = {}
    for name, effect in conn.execute("SELECT name, effect FROM qol_items"):
        if not effect:
            continue
        text = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", effect)
        text = re.sub(r"'{2,}", "", re.sub(r"\{\{[^}]*\}\}", "", text)).strip()
        if len(text) > 300:
            text = text[:297].rstrip() + "..."
        if text:
            out[name.lower()] = text
    conn.close()
    return out


def add_tier_implications(unlocks):
    """Owning a HIGHER tier of a numbered family proves the lower — an
    Ardougne cloak 3 covers everything cloak 2 does (Luke's report: cloak 2
    read 'not obtained' beside an owned cloak 3)."""
    families = {}
    for u in unlocks:
        m = re.fullmatch(r"(.+?) (\d)", u["name"])
        if m:
            families.setdefault(m.group(1), []).append((int(m.group(2)), u))
    added = 0
    for members in families.values():
        members.sort(key=lambda p: p[0])
        for num, u in members:
            for higher_num, higher in members:
                if higher_num > num and higher["itemIds"][0] not in u["itemIds"]:
                    u["itemIds"].append(higher["itemIds"][0])
                    added += 1
    print(f"  tier implications: {added} higher-tier ids added")


def main():
    index = item_index()
    benefit_by_name = benefits()
    unlocks = list(LEGACY)
    seen = {u["name"].lower() for u in LEGACY}
    skipped = []
    for name in storage_titles() + CURATED:
        if name.lower() in seen:
            continue
        seen.add(name.lower())
        if name in ("Gem containers",   # a disambiguation page
                    "Collection bag"):  # BA Collector role tool, not an unlock
            continue
        item_id = resolve_item(index, name)
        if item_id is None:
            skipped.append(name)
            continue
        prose = recipe_prose(name)
        reqs = [prose] if prose else []
        # Luke 2026-07-22: the imbued whistle counts as owning the perfected
        extra = {"Perfected quetzal whistle": [33120]}.get(name, [])
        unlocks.append({
            "id": re.sub(r"^_+|_+$", "", re.sub(r"[^a-z0-9]+", "_", name.lower())),
            "name": name,
            "itemIds": [item_id] + extra,
            "requirements": reqs,
        })
    if skipped:
        print(f"  skipped (no item id — stays knowledge-base side): "
              + ", ".join(skipped))
    add_tier_implications(unlocks)
    n_benefits = 0
    for u in unlocks:
        text = benefit_by_name.get(u["name"].lower())
        if text:
            u["benefit"] = text
            n_benefits += 1
    print(f"  benefits: {n_benefits}/{len(unlocks)} from the KB's effect column")
    assert n_benefits >= 40, f"suspiciously few benefits: {n_benefits}"
    # the reported case stays fixed: cloak 3 (13123) proves cloak 2
    cloak2 = next(u for u in unlocks if u["name"] == "Ardougne cloak 2")
    assert 13123 in cloak2["itemIds"] and 13124 in cloak2["itemIds"], cloak2
    assert len(unlocks) >= 70, f"suspiciously few qol unlocks: {len(unlocks)}"
    ids = [u["id"] for u in unlocks]
    assert len(ids) == len(set(ids)), "duplicate qol ids"
    pack = {
        "$schema": "./schemas/qol.schema.json",
        "version": 2,
        "unlocks": sorted(unlocks, key=lambda u: u["name"]),
    }
    with open(os.path.abspath(OUT), "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(unlocks)} qol unlocks -> {os.path.abspath(OUT)}")


if __name__ == "__main__":
    main()
