#!/usr/bin/env python3
"""Generate data/boosts.json — every temporary skill boost, from the wiki's
Temporary_skill_boost/<Skill> subpages (the same tables the knowledge base
harvested; Luke's word 2026-07-21 replaced the 10-source hand-curated pack).

Semantics preserved from the curated pack:
- one entry per (source, boost amount); skills merged
- `gate` = the source's own obtainment as a requirement-graph string. Ten
  curated entries keep their hand-audited gates (quest/unlock/any:
  alternatives); every harvested row gates on OWNING the source item
  (item:<id> — variation-aware, so any potion dose counts). Rows that do
  not resolve to an item (area auras, special attacks, percent formulas)
  are SKIPPED with a log line — an ungated boost would over-promise; they
  remain visible in knowledge/html/boosts.html.
- visible boosts take the MAX of a range ("1-5" -> 5, planning headroom);
  percent-of-level cells are level-dependent and skip.
- invisible boosts only from item sources (crystal saw grammar); area
  boosts (fishing guild) are location-gated and skip.

Usage: python3 tools/gen_boosts.py
"""

import json
import os
import re
import urllib.parse
import urllib.request

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-boosts")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "boosts.json")
ITEM_INDEX = os.path.join(HERE, "..", "src", "main", "resources", "data", "index", "item-names.json")
WIKI_API = "https://oldschool.runescape.wiki/api.php"

SKILLS = [
    "Agility", "Attack", "Construction", "Cooking", "Crafting", "Defence",
    "Farming", "Firemaking", "Fishing", "Fletching", "Herblore", "Hitpoints",
    "Hunter", "Magic", "Mining", "Prayer", "Ranged", "Runecraft", "Sailing",
    "Slayer", "Smithing", "Strength", "Thieving", "Woodcutting",
]

# the original pack's hand-audited entries — better gates than item
# ownership (quest/unlock/alternative paths); wiki rows for the same
# sources dedupe against these by base name
CURATED = [
    {"name": "Spicy stew (+5)", "skills": ["Construction", "Crafting", "Smithing", "Fletching",
     "Runecraft", "Cooking", "Firemaking", "Magic", "Ranged", "Attack", "Strength", "Defence",
     "Agility", "Hunter", "Slayer", "Thieving", "Farming", "Fishing", "Mining", "Woodcutting",
     "Herblore"], "boost": 5, "gate": "quest:Recipe for Disaster - Evil Dave"},
    {"name": "Crystal saw (+3)", "skills": ["Construction"], "boost": 3,
     "invisible": True, "gate": "item:9625"},
    {"name": "POH tea (+3)", "skills": ["Construction"], "boost": 3,
     "gate": "unlock:gearmark_teak_shelves_2"},
    {"name": "Mushroom pie (+4)", "skills": ["Crafting"], "boost": 4,
     "gate": "any:item:21690|skillb:Cooking:60"},
    {"name": "Dragon fruit pie (+4)", "skills": ["Fletching"], "boost": 4,
     "gate": "any:item:22795|skillb:Cooking:73"},
    {"name": "Admiral pie (+5)", "skills": ["Fishing"], "boost": 5,
     "gate": "any:item:7198|skillb:Cooking:70"},
    {"name": "Fish pie (+3)", "skills": ["Fishing"], "boost": 3,
     "gate": "any:item:7188|skillb:Cooking:47"},
    {"name": "Agility summer pie (+5)", "skills": ["Agility"], "boost": 5,
     "gate": "any:item:7218|skillb:Cooking:95"},
    {"name": "Stranger plant (+4)", "skills": ["Farming"], "boost": 4,
     "gate": "skillb:Farming:76"},
    {"name": "Garden pie (+3)", "skills": ["Farming"], "boost": 3,
     "gate": "any:item:7178|skillb:Cooking:34"},
]

CURATED_BASES = {"spicy stew", "crystal saw", "cup of tea (clay)", "cup of tea (porcelain)",
                 "cup of tea (trimmed)", "mushroom pie", "dragon fruit pie", "admiral pie",
                 "fish pie", "summer pie", "stranger plant", "garden pie"}


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


def page_text(page: str) -> str:
    url = WIKI_API + "?" + urllib.parse.urlencode(
        {"action": "parse", "page": page, "prop": "wikitext",
         "format": "json", "formatversion": "2"})
    data = json.loads(fetch(url, "wiki-" + re.sub(r"[^A-Za-z0-9]+", "_", page) + ".json"))
    if "parse" not in data:
        raise SystemExit(f"wiki page missing: {page}")
    return data["parse"]["wikitext"]


def item_index():
    with open(ITEM_INDEX, encoding="utf-8") as f:
        by_const = json.load(f)
    collapsed = {}
    for const, item_id in by_const.items():
        collapsed.setdefault(const.replace("_", ""), item_id)
    return by_const, collapsed


def normalize(name: str) -> str:
    return re.sub(r"^_+|_+$", "",
                  re.sub(r"[^A-Z0-9]+", "_",
                         re.sub(r"['’]", "", name).upper()))


def resolve_item(index, name: str):
    """Display name -> item id. Fallback ladder (the gen_gear lessons):
    potion/mix dose suffixes (SARADOMIN_BREW4, AGILITY_MIX2), a leading
    underscore for digit-led constants (_3RD_AGE_AXE), collapsed keys for
    parenthetical variants (AXEMANS_FOLLYM), and a stripped trailing
    parenthetical ("Black warlock (item)")."""
    by_const, collapsed = index
    candidates = []
    for base_name in (name, re.sub(r"\s*\([^)]*\)\s*$", "", name)):
        base = normalize(base_name)
        if not base:
            continue
        for c in (base, base + "4", base + "_4", base + "3", base + "_3",
                  base + "2", base + "_2", base + "1", base + "_1"):
            candidates.append(c)
            candidates.append("_" + c)
    for c in candidates:
        if c in by_const:
            return by_const[c]
    for c in candidates:
        key = c.replace("_", "")
        if key in collapsed:
            return collapsed[key]
    return None


def parse_rows(text: str):
    """[(name, amount_cell, visibility, info)] from the subpage's table."""
    m = re.search(r"\{\|.*?\n(.*?)\n\|\}", text, re.S)
    if not m:
        return []
    rows = []
    for chunk in re.split(r"\n\|-[^\n]*", "\n" + m.group(1)):
        cells = []
        for line in chunk.split("\n"):
            if line.startswith("|") and not line.startswith(("|+", "|}")):
                cells.extend(re.split(r"\|\|", line[1:]))
            elif cells and line.strip():
                cells[-1] += "\n" + line
        if len(cells) >= 3 and "{{plink" in cells[0]:
            name = re.search(r"\{\{plinkt?\|([^|}]+)", cells[0])
            if name:
                rows.append((name.group(1).strip(), cells[1].strip(),
                             cells[2].strip(), cells[3].strip() if len(cells) > 3 else ""))
    return rows


def main():
    index = item_index()
    by_key = {}     # (base name, amount) -> entry
    skipped = {}
    for skill in SKILLS:
        for name, amount_cell, visibility, _info in parse_rows(
                page_text(f"Temporary skill boost/{skill}")):
            base = name.lower()
            if base in CURATED_BASES:
                continue
            if "%" in amount_cell:
                skipped.setdefault("percent-of-level", []).append(name)
                continue
            ints = [int(x) for x in re.findall(r"\d+", amount_cell)]
            if not ints:
                skipped.setdefault("no-amount", []).append(name)
                continue
            boost = max(ints)
            if boost < 1 or boost > 30:
                skipped.setdefault("odd-amount", []).append(f"{name} ({amount_cell})")
                continue
            invisible = visibility.lower().startswith("invisible")
            item_id = resolve_item(index, name)
            if item_id is None:
                skipped.setdefault("no-item-gate", []).append(name)
                continue
            key = (name, boost, invisible)
            entry = by_key.setdefault(key, {
                "name": f"{name} (+{boost})",
                "skills": [],
                "boost": boost,
                "gate": f"item:{item_id}",
            })
            if invisible:
                entry["invisible"] = True
            if skill not in entry["skills"]:
                entry["skills"].append(skill)
    boosts = CURATED + sorted(by_key.values(), key=lambda b: b["name"])
    for kind, names in sorted(skipped.items()):
        print(f"  skipped {kind}: {len(names)} — {', '.join(sorted(set(names))[:8])}"
              + (" …" if len(set(names)) > 8 else ""))
    assert len(boosts) >= 150, f"suspiciously few boosts: {len(boosts)}"
    covered = {s for b in boosts for s in b["skills"]}
    assert covered.issuperset(set(SKILLS) - {"Hitpoints"}), sorted(set(SKILLS) - covered)
    pack = {
        "$schema": "./schemas/boosts.schema.json",
        "version": 2,
        "boosts": boosts,
    }
    with open(os.path.abspath(OUT), "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(boosts)} boost sources ({len(CURATED)} curated + "
          f"{len(by_key)} wiki-derived) -> {os.path.abspath(OUT)}")


if __name__ == "__main__":
    main()
