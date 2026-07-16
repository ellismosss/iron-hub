#!/usr/bin/env python3
"""Generate data/skill-unlocks.json — what each Farming / Herblore level
unlocks, from the OSRS wiki's own "<Skill>/Level up table" pages (the tables
the game's level-up interface mirrors).

Source: https://oldschool.runescape.wiki/w/Farming/Level_up_table and
        https://oldschool.runescape.wiki/w/Herblore/Level_up_table
        (?action=raw wikitext, {{Level up table}} template: one
        |membersN = / |freeN = parameter per level, "* unlock" bullets).

Only these two skills ship for now — the farm-runs sidebar says what the
next Farming / Herblore level is worth. Add a skill to SKILLS when another
module needs one.

Usage:
  python3 tools/gen_skill_unlocks.py

Wikitext cached under tools/.cache-skill-unlocks/ (gitignored).
"""
import datetime
import json
import os
import re
import urllib.request

UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
CACHE = os.path.join(os.path.dirname(__file__), ".cache-skill-unlocks")
OUT = "src/main/resources/data/skill-unlocks.json"
SKILLS = ["Farming", "Herblore"]


def fetch(skill: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, skill.lower() + ".wikitext")
    if not os.path.exists(cached):
        url = f"https://oldschool.runescape.wiki/w/{skill}/Level_up_table?action=raw"
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode("utf-8")
        with open(cached, "w", encoding="utf-8") as f:
            f.write(text)
    with open(cached, encoding="utf-8") as f:
        return f.read()


PLINK = re.compile(r"\{\{plinkp?\|([^}]*)\}\}")
SCLINK = re.compile(r"\{\{SCP\|[^}]*\}\}\s*")
LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]")
TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")


def plink_text(args: str) -> str:
    """{{plink|Maple Tree (Farming)|pic=...|txt=maple tree}} -> its display
    text: the txt= parameter if present, else the page name minus any
    disambiguation parentheses."""
    parts = args.split("|")
    txt = next((p[4:] for p in parts if p.startswith("txt=")), None)
    if txt is None:
        txt = parts[0]
        txt = re.sub(r"\s*\([^)]*\)$", "", txt)
    return txt


def clean(line: str) -> str:
    line = PLINK.sub(lambda m: plink_text(m.group(1)), line)
    line = SCLINK.sub("", line)
    line = LINK.sub(r"\1", line)
    for _ in range(3):  # nested leftovers ({{sic}}, {{*}}, refs)
        line = TEMPLATE.sub("", line)
    line = re.sub(r"<[^>]+>", "", line)  # html refs/br
    line = line.replace("'''", "").replace("''", "")
    return re.sub(r"\s+", " ", line).strip()


def parse(skill: str, wikitext: str) -> dict:
    """level -> [unlock, ...] from the |membersN= / |freeN= parameters."""
    unlocks = {}
    for m in re.finditer(r"\|\s*(?:members|free)(\d+)\s*=\s*\n((?:\*[^\n]*\n?)*)", wikitext):
        level = int(m.group(1))
        assert 1 <= level <= 99, f"{skill}: level {level} out of range"
        lines = [clean(l.lstrip("* ")) for l in m.group(2).splitlines() if l.startswith("*")]
        lines = [l for l in lines if l]
        if lines:
            unlocks.setdefault(level, []).extend(lines)
    # sanity: a real level-up table is dense, and nothing half-parsed remains
    assert len(unlocks) >= 40, f"{skill}: only {len(unlocks)} levels parsed"
    for level, lines in unlocks.items():
        for line in lines:
            assert "{{" not in line and "[[" not in line and "|" not in line, \
                f"{skill} {level}: unparsed markup in {line!r}"
    return {str(level): unlocks[level] for level in sorted(unlocks)}


def main():
    skills = {skill: parse(skill, fetch(skill)) for skill in SKILLS}
    assert any("oak" in u.lower() for u in skills["Farming"].get("15", [])), \
        "Farming 15 should unlock oak trees — parse drifted"
    assert any("rayer potion" in u for u in skills["Herblore"].get("38", [])), \
        "Herblore 38 should unlock prayer potions — parse drifted"
    pack = {
        "source": "oldschool.runescape.wiki /Level_up_table pages (CC BY-NC-SA 3.0)",
        "generated": datetime.date.today().isoformat(),
        "skills": skills,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    total = sum(len(v) for s in skills.values() for v in s.values())
    print(f"wrote {OUT}: {', '.join(f'{s} {len(v)} levels' for s, v in skills.items())}, "
          f"{total} unlock lines")


if __name__ == "__main__":
    main()
