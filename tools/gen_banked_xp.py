#!/usr/bin/env python3
"""Generate data/banked-xp.json from the Banked Experience plugin's enums.

Banked Experience (github.com/TheStonedTurtle/banked-experience,
BSD-2-Clause — see licenses/banked-experience-LICENSE) encodes every
bankable XP item as two Java enums:

  ExperienceItem.java -> bankable item (gameval ItemID constant + Skill;
                         a few potion items carry one id per dose with
                         byDose=true, worth xp * dose-count each)
  Activity.java       -> item -> action: display name, level requirement
                         and xp per action (skill comes from the item)

We flatten them into the pack's existing shape: one entry per
(item, activity) pair — {skill, method, itemId, name, xpEach, level}.
BankedXp.compute() picks the best method per (skill, item), so listing
every activity is safe. Byte-faithful conversions:

  - byDose items emit one entry per dose id at xp * (index + 1), the
    exact multiplier BankedCalculator.recreateBankedItemMap applies;
  - xp expressions ("150 * 0.5" for shields) are evaluated as written.

Principled skips (logged at generation, never silent):
  - zero-xp activities (Nardah herb cleaning, blessed-bone-shard
    conversions) — they contribute nothing to a banked-XP total and the
    schema demands xpEach > 0.

Item ids resolve through the gameval ItemID constants (javap over the
runelite-api jar in the local Gradle cache); skills are validated
against net.runelite.api.Skill the same way. An unknown constant aborts.
Display names come from data/index/item-names.json (legacy-constant
name for the same numeric id), falling back to the upstream enum name.

Usage:
  python3 tools/gen_banked_xp.py

Sources fetched from the pinned commit, cached under
tools/.cache-banked-xp/ (gitignored).
"""
import datetime
import glob
import json
import os
import re
import subprocess
import urllib.request

REPO = "TheStonedTurtle/banked-experience"
COMMIT = "788fbbf2d2d8b70f70ec3c569f57d3c58f0c0415"  # plugin-hub pinned commit
BASE = (f"https://raw.githubusercontent.com/{REPO}/{COMMIT}"
        "/src/main/java/thestonedturtle/bankedexperience/data/")
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
CACHE = os.path.join(os.path.dirname(__file__), ".cache-banked-xp")
OUT = "src/main/resources/data/banked-xp.json"
NAME_INDEX = "src/main/resources/data/index/item-names.json"

# ExperienceItem enum-name prefixes that only disambiguate the consuming
# skill (F_YEW_LOGS = yew logs for Fletching); stripped for display names.
SKILL_PREFIX = re.compile(r"^(F|FM|H)_")

ITEM_FORM_A = re.compile(r'^ItemID\.(\w+),\s*Skill\.(\w+)(?:,\s*"[^"]*")?$')
ITEM_FORM_B = re.compile(r"^Skill\.(\w+),\s*(true|false),\s*((?:ItemID\.\w+,?\s*)+)$")
ACTIVITY = re.compile(
    r'([A-Za-z0-9_]+)\(\s*ItemID\.\w+,\s*"([^"]*)",\s*(\d+),\s*'
    r"([0-9.]+(?:\s*\*\s*[0-9.]+)?),\s*(?:(?:true|false),\s*)?"
    r"ExperienceItem\.([A-Za-z0-9_]+),", re.S)


def fetch(name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, name + ".java")
    if not os.path.exists(cached):
        req = urllib.request.Request(BASE + name + ".java", headers={"User-Agent": UA})
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
        with open(cached, "w", encoding="utf-8") as f:
            f.write(body)
    with open(cached, encoding="utf-8") as f:
        return f.read()


def enum_body(source: str, enum_name: str) -> str:
    """The constants section: enum declaration up to the lone ';' line."""
    start = source.index(f"public enum {enum_name}")
    body = source[start:]
    return re.split(r"^\t;\s*$", body, maxsplit=1, flags=re.M)[0]


def javap(class_name: str) -> str:
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    return subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", class_name],
        capture_output=True, text=True, check=True).stdout


def gameval_item_ids() -> dict:
    out = javap("net.runelite.api.gameval.ItemID")
    ids = {m.group(1): int(m.group(2))
           for m in re.finditer(r"public static final int (\w+) = (\d+);", out)}
    if len(ids) < 10000:
        raise SystemExit(f"suspiciously small gameval ItemID dump: {len(ids)}")
    return ids


def skill_enum_names() -> set:
    out = javap("net.runelite.api.Skill")
    names = set(re.findall(r"Skill (\w+);", out))
    if len(names) < 20:
        raise SystemExit(f"suspiciously small Skill enum dump: {names}")
    return names


def parse_items(source: str) -> dict:
    """enum name -> (skill, [gameval constants], byDose)."""
    body = enum_body(source, "ExperienceItem")
    items = {}
    lines = re.findall(r"^\t([A-Za-z0-9_]+)\((.*)\),?\s*$", body, re.M)
    for name, args in lines:
        m = ITEM_FORM_A.match(args)
        if m:
            items[name] = (m.group(2), [m.group(1)], False)
            continue
        m = ITEM_FORM_B.match(args)
        if m:
            ids = re.findall(r"ItemID\.(\w+)", m.group(3))
            items[name] = (m.group(1), ids, m.group(2) == "true")
            continue
        raise SystemExit(f"unparsed ExperienceItem {name}({args})")
    declared = len(re.findall(r"^\t[A-Za-z0-9_]+\(", body, re.M))
    assert len(items) == declared, f"parsed {len(items)} of {declared} items"
    return items


def parse_activities(source: str) -> list:
    """[(name, display, level, xp, experience-item enum name)] in file order."""
    body = enum_body(source, "Activity")
    body = re.sub(r"//[^\n]*", "", body)  # inline comments split some constants
    acts = []
    for m in ACTIVITY.finditer(body):
        xp = m.group(4)
        if "*" in xp:
            a, b = xp.split("*")
            value = float(a) * float(b)
        else:
            value = float(xp)
        acts.append((m.group(1), m.group(2), int(m.group(3)), value, m.group(5)))
    declared = len(re.findall(r"^\t[A-Za-z0-9_]+\(ItemID\.", body, re.M))
    assert len(acts) == declared, f"parsed {len(acts)} of {declared} activities"
    return acts


def display_name(enum_name: str, item_id: int, legacy_by_id: dict) -> str:
    legacy = legacy_by_id.get(item_id)
    if legacy is None:
        legacy = re.sub(r"_\d+$", "", SKILL_PREFIX.sub("", enum_name))
    return legacy.replace("_", " ").strip().capitalize()


def main():
    items = parse_items(fetch("ExperienceItem"))
    activities = parse_activities(fetch("Activity"))
    gameval = gameval_item_ids()
    skills = skill_enum_names()
    legacy_by_id = {}
    for name, item_id in json.load(open(NAME_INDEX)).items():
        legacy_by_id.setdefault(item_id, name)

    entries = []
    skipped_zero = []
    for act_name, display, level, xp, item_name in activities:
        if xp <= 0:
            skipped_zero.append(act_name)
            continue
        if item_name not in items:
            raise SystemExit(f"activity {act_name} references unknown item {item_name}")
        skill, id_constants, by_dose = items[item_name]
        if skill not in skills:
            raise SystemExit(f"unknown Skill.{skill} on {item_name}")
        for i, constant in enumerate(id_constants):
            if constant not in gameval:
                raise SystemExit(f"unknown gameval ItemID.{constant} in {item_name}")
            item_id = gameval[constant]
            entry = {
                "skill": skill.capitalize(),
                "method": display,
                "itemId": item_id,
                "name": display_name(item_name, item_id, legacy_by_id),
                "xpEach": round(xp * (i + 1 if by_dose else 1), 2),
            }
            if level > 1:
                entry["level"] = level
            entries.append(entry)

    # Cross-checks against the upstream source (wiki-verified values).
    anchors = {("Firemaking", 1515, 202.5), ("Prayer", 536, 72.0)}
    have = {(e["skill"], e["itemId"], e["xpEach"]) for e in entries}
    for anchor in anchors:
        assert anchor in have, f"anchor entry missing: {anchor}"
    assert len(entries) >= 600, f"only {len(entries)} entries"
    assert len({e["skill"] for e in entries}) >= 10, "too few skills"

    pack = {
        "$schema": "./schemas/banked-xp.schema.json",
        "source": f"github.com/{REPO}@{COMMIT[:7]} (BSD-2-Clause)",
        "generated": datetime.date.today().isoformat(),
        "version": 2,
        "entries": entries,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    per_skill = {}
    for e in entries:
        per_skill[e["skill"]] = per_skill.get(e["skill"], 0) + 1
    print(f"wrote {OUT}: {len(entries)} entries from {len(activities)} activities; "
          f"skipped {len(skipped_zero)} zero-xp activities "
          f"({', '.join(skipped_zero[:4])}…)")
    for skill in sorted(per_skill):
        print(f"  {skill}: {per_skill[skill]}")


if __name__ == "__main__":
    main()
