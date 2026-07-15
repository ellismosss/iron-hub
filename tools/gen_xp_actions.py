#!/usr/bin/env python3
"""Generate data/xp-actions.json — per-action xp fingerprints from the OSRS
wiki's skill-calculator Lua data modules (Module:Skill calc/<Skill>).

Each calculator entry carries the exact xp one action awards (one xp drop:
a fletched bow, an agility lap, a cooked karambwan). The planner overlay
matches the player's observed median xp drop against these to name the
method actually in use ("Maple longbow (u)") instead of a bare
"Your method".

Fetched pages cache to tools/.cache-xpactions/ (1s apart, custom
User-Agent, per the wiki's bot etiquette); delete the cache to force a
refresh.

Usage: python3 tools/gen_xp_actions.py
"""
import datetime
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request

OUT = "src/main/resources/data/xp-actions.json"
CACHE = pathlib.Path("tools/.cache-xpactions")
UA = "iron-hub-datagen (info@ellismoss.co.uk)"

# Module:Skill calc/<name> pages (combat skills have no calculators; the
# Helpers module is code, not data)
SKILLS = [
    "Agility", "Construction", "Cooking", "Crafting", "Farming",
    "Firemaking", "Fishing", "Fletching", "Herblore", "Hunter", "Magic",
    "Mining", "Prayer", "Runecraft", "Sailing", "Smithing", "Thieving",
    "Woodcutting",
]


def fetch_module(skill):
    CACHE.mkdir(exist_ok=True)
    cached = CACHE / f"{skill}.lua"
    if cached.exists():
        return cached.read_text()
    url = ("https://oldschool.runescape.wiki/api.php?action=query"
           "&prop=revisions&rvprop=content&rvslots=main&format=json"
           "&titles=" + urllib.parse.quote(f"Module:Skill calc/{skill}"))
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        data = json.load(resp)
    page = next(iter(data["query"]["pages"].values()))
    content = page["revisions"][0]["slots"]["main"]["*"]
    cached.write_text(content)
    time.sleep(1)
    return content


def entry_blocks(lua):
    """Top-level { ... } entries of the returned table, nested blocks kept."""
    start = lua.find("{")  # the outer table
    depth = 0
    block_start = None
    blocks = []
    for i in range(start, len(lua)):
        c = lua[i]
        if c == "{":
            depth += 1
            if depth == 2 and block_start is None:
                block_start = i
        elif c == "}":
            if depth == 2 and block_start is not None:
                blocks.append(lua[block_start:i + 1])
                block_start = None
            depth -= 1
            if depth == 0:
                break
    return blocks


def strip_nested(block):
    """Remove sub-tables (materials etc.) so field regexes see only the
    entry's own fields."""
    out = []
    depth = 0
    for c in block[1:-1]:  # inside the entry's own braces
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        elif depth == 0:
            out.append(c)
    return "".join(out)


FIELD = {
    "name": re.compile(r"\bname\s*=\s*'((?:[^'\\]|\\.)*)'"),
    "name2": re.compile(r'\bname\s*=\s*"((?:[^"\\]|\\.)*)"'),
    "title": re.compile(r"\btitle\s*=\s*'((?:[^'\\]|\\.)*)'"),
    "title2": re.compile(r'\btitle\s*=\s*"((?:[^"\\]|\\.)*)"'),
    "type": re.compile(r"\btype\s*=\s*'((?:[^'\\]|\\.)*)'"),
    "level": re.compile(r"\blevel\s*=\s*(\d+)"),
    "xp": re.compile(r"\bxp\s*=\s*([0-9]+(?:\.[0-9]+)?)\b"),
}


def parse_actions(lua, skill):
    actions = []
    skipped = 0
    for block in entry_blocks(lua):
        flat = strip_nested(block)
        name = _first(flat, "title", "title2") or _first(flat, "name", "name2")
        level = FIELD["level"].search(flat)
        xp = FIELD["xp"].search(flat)
        if not name or not level or not xp or float(xp.group(1)) <= 0:
            skipped += 1
            continue
        actions.append({
            "name": name.replace("\\'", "'"),
            "level": int(level.group(1)),
            "xp": float(xp.group(1)),
            "type": (_first(flat, "type") or "").strip() or None,
        })
    # identical (name, xp) rows collapse to the lowest level
    seen = {}
    for a in actions:
        key = (a["name"], a["xp"])
        if key not in seen or a["level"] < seen[key]["level"]:
            seen[key] = a
    deduped = sorted(seen.values(), key=lambda a: (a["level"], a["name"]))
    print(f"  {skill}: {len(deduped)} actions ({skipped} skipped)")
    return deduped


def _first(flat, *keys):
    for key in keys:
        m = FIELD[key].search(flat)
        if m:
            return m.group(1)
    return None


def main():
    skills = []
    for skill in SKILLS:
        lua = fetch_module(skill)
        actions = parse_actions(lua, skill)
        if not actions:
            raise SystemExit(f"{skill}: no actions parsed — module shape changed?")
        skills.append({"skill": skill, "actions": actions})
    pack = {
        "source": "OSRS wiki Module:Skill calc/<Skill> Lua data",
        "generated": datetime.date.today().isoformat(),
        "skills": skills,
    }
    with open(OUT, "w") as f:
        json.dump(pack, f, indent=1)
        f.write("\n")
    total = sum(len(s["actions"]) for s in skills)
    print(f"wrote {OUT}: {len(skills)} skills, {total} actions")


if __name__ == "__main__":
    main()
