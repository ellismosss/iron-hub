#!/usr/bin/env python3
"""Generate data/banked-xp.json from the Banked Experience plugin's enums.

Banked Experience (github.com/TheStonedTurtle/banked-experience,
BSD-2-Clause — see licenses/banked-experience-LICENSE) encodes every
bankable XP item in Java enums:

  ExperienceItem.java -> bankable item (gameval ItemID constant + Skill;
                         a few potion items carry one id per dose with
                         byDose=true, worth xp * dose-count each)
  Activity.java       -> item -> action: display name, level requirement,
                         xp per action (skill comes from the item), the
                         Secondaries it consumes and the ItemStack output
  Secondaries.java    -> secondary items an action consumes
  modifiers/          -> per-skill xp modifiers (altars, outfits)

We flatten them into the pack: one entry per (item, activity) pair —
{skill, method, activity, itemId, name, xpEach, level, secondaries,
outputId, outputQty} — plus a top-level modifiers array. `activity` is
the upstream enum constant; modifiers' appliesTo/ignores lists join on
it (display names collide — two activities are both named "Bury").
BankedXp.compute() picks the best method per (skill, item), so listing
every activity is safe. Byte-faithful conversions:

  - byDose items emit one entry per dose id at xp * (index + 1), the
    exact multiplier BankedCalculator.recreateBankedItemMap applies;
  - xp/qty expressions ("150 * 0.5", "1.0 / 3") are evaluated as written;
  - the Wildy Altar modifier is emitted as its effective per-banked-bone
    multiplier 7.0 (= 350% xp / 50% consumption chance — exactly
    Activity.getXpRate's math for it); the name keeps upstream's wording.

Principled skips (logged at generation, never silent):
  - zero-xp activities (Nardah herb cleaning, blessed-bone-shard
    conversions) — they contribute nothing to a banked-XP total and the
    schema demands xpEach > 0;
  - Secondaries backed by a custom handler (Crushable = raw-OR-crushed
    alternative ids, ByDose = per-dose potions, Degrime = cast math) —
    not representable as plain {itemId, qty}; those entries carry no
    secondaries list;
  - the Zealot's robes modifier — consumption-only (1.25% save per worn
    piece, non-additive stacking, no xp effect); no declarative shape.

The modifier table is hand-declared below but every row is asserted
against the upstream Modifiers.java source text, and the activity sets
(BONES/ASHES/SALVAGE/...) are parsed from it — a drifted upstream fails
the build here. Skilling outfits are emitted at their full-set bonus
(x1.025); per-piece bonuses (0.4/0.8/0.6/0.2%) are a UI concern.

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
NUMBER = r"[0-9.]+(?:\s*[*/]\s*[0-9.]+)?"
ACTIVITY = re.compile(
    r'([A-Za-z0-9_]+)\(\s*ItemID\.\w+,\s*"([^"]*)",\s*(\d+),\s*'
    rf"({NUMBER}),\s*(?:(?:true|false),\s*)?"
    r"ExperienceItem\.([A-Za-z0-9_]+),\s*(?:Secondaries\.([A-Za-z0-9_]+)|null),\s*"
    rf"(?:new ItemStack\(ItemID\.(\w+),\s*({NUMBER})\)|null)\)", re.S)
ITEM_STACK = re.compile(rf"new ItemStack\(ItemID\.(\w+),\s*({NUMBER})\)")

# The upstream modifiers (Modifiers.createModifiers), each row verified
# below against the source text at the pinned commit; activity sets are
# parsed from the same file. (skill, name, value, includedSet, ignored,
# sourceText). Values are xp multipliers on a banked item.
MODIFIERS = [
    ("PRAYER", "Demonic Offering (300% xp)", 3.0, "ASHES", [],
     'new StaticModifier(Skill.PRAYER, "Demonic Offering (300% xp)", 3, ASHES, null'),
    ("PRAYER", "Sinister Offering (300% xp)", 3.0, "BONES", [],
     'new StaticModifier(Skill.PRAYER, "Sinister Offering (300% xp)", 3, BONES, null'),
    ("PRAYER", "Lit Gilded Altar (350% xp)", 3.5, "BONES", [],
     'new StaticModifier(Skill.PRAYER, "Lit Gilded Altar (350% xp)", 3.5f, BONES, null'),
    ("PRAYER", "Ectofuntus (400% xp)", 4.0, "BONES", ["STRYKEWYRM_BONES_BURY"],
     'new StaticModifier(Skill.PRAYER, "Ectofuntus (400% xp)", 4, BONES, '
     'Set.of(Activity.STRYKEWYRM_BONES_BURY)'),
    # ponytail: 3.5x xp / 0.5 consumption chance = 7x per banked bone —
    # Activity.getXpRate's own math; split into {xp, save} fields if the
    # UI ever models consumption separately.
    ("PRAYER", "Wildy Altar (350% xp & 50% Save)", 7.0, "BONES", [],
     'new ConsumptionModifier(Skill.PRAYER, "Wildy Altar (350% xp & 50% Save)", 0.5f, BONES, null'),
    ("FARMING", "Farmer's Outfit", 1.025, None, [],
     'new SkillingOutfit(Skill.FARMING, "Farmer\'s Outfit", null, null'),
    ("CONSTRUCTION", "Carpenter's Outfit", 1.025, None,
     ["LONG_BONE", "CURVED_BONE"],
     'new SkillingOutfit(Skill.CONSTRUCTION, "Carpenter\'s Outfit", null, CONSTRUCTION_BONES'),
    ("FIREMAKING", "Pyromancer Outfit", 1.025, None, [],
     'new SkillingOutfit(Skill.FIREMAKING, "Pyromancer Outfit"'),
    ("SAILING", "Horizon's Lure (102.5% xp)", 1.025, "SALVAGE", [],
     'new StaticModifier(Skill.SAILING, "Horizon\'s Lure (102.5% xp)", 1.025f, SALVAGE, null'),
]
SKIPPED_MODIFIERS = ["Zealot's robes"]  # consumption-only, per-piece save, no xp effect


def fetch(name: str, subdir: str = "") -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, name + ".java")
    if not os.path.exists(cached):
        req = urllib.request.Request(BASE + subdir + name + ".java", headers={"User-Agent": UA})
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


def eval_number(expr: str) -> float:
    """A literal, or the single * or / expression upstream writes."""
    for op in "*/":
        if op in expr:
            a, b = expr.split(op)
            return float(a) * float(b) if op == "*" else float(a) / float(b)
    return float(expr)


def num(value: float):
    """Whole numbers as ints, fractions rounded to 4 dp (1.0/3 -> 0.3333)."""
    value = round(value, 4)
    return int(value) if value == int(value) else value


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
    """[(name, display, level, xp, item, secondariesName, outputConst, outputQty)]."""
    body = enum_body(source, "Activity")
    body = re.sub(r"//[^\n]*", "", body)  # inline comments split some constants
    acts = []
    for m in ACTIVITY.finditer(body):
        out_qty = eval_number(m.group(8)) if m.group(7) else None
        acts.append((m.group(1), m.group(2), int(m.group(3)), eval_number(m.group(4)),
                     m.group(5), m.group(6), m.group(7), out_qty))
    declared = len(re.findall(r"^\t[A-Za-z0-9_]+\(ItemID\.", body, re.M))
    assert len(acts) == declared, f"parsed {len(acts)} of {declared} activities"
    return acts


def parse_secondaries(source: str) -> tuple:
    """(enum name -> [(gameval const, qty)], [custom-handler names skipped])."""
    body = enum_body(source, "Secondaries")
    stacks, custom = {}, []
    lines = re.findall(r"^\t([A-Za-z0-9_]+)\((new .*)\),?\s*$", body, re.M)
    for name, args in lines:
        if re.search(r"new (Crushable|ByDose|Degrime)\(", args):
            custom.append(name)
            continue
        parsed = [(m.group(1), eval_number(m.group(2))) for m in ITEM_STACK.finditer(args)]
        if not parsed:
            raise SystemExit(f"unparsed Secondaries {name}({args})")
        stacks[name] = parsed
    declared = len(re.findall(r"^\t[A-Za-z0-9_]+\(", body, re.M))
    assert len(stacks) + len(custom) == declared, \
        f"parsed {len(stacks)}+{len(custom)} of {declared} secondaries"
    return stacks, custom


def parse_modifier_sets(source: str) -> dict:
    """BONES/ASHES/SALVAGE/CONSTRUCTION_BONES -> [activity enum names]."""
    sets = {}
    for m in re.finditer(
            r"(?:Set|Collection)<Activity> (\w+) = ImmutableSet\.of\((.*?)\);",
            source, re.S):
        sets[m.group(1)] = re.findall(r"Activity\.([A-Za-z0-9_]+)", m.group(2))
    for required in ("BONES", "ASHES", "SALVAGE", "CONSTRUCTION_BONES"):
        assert sets.get(required), f"activity set {required} not parsed"
    return sets


def build_modifiers(modifiers_source: str, activity_names: set) -> list:
    """activity_names: enum names of emitted (xp > 0) activities."""
    sets = parse_modifier_sets(modifiers_source)
    result = []
    for skill, name, value, included_set, ignored, source_text in MODIFIERS:
        assert source_text in modifiers_source, f"modifier drifted upstream: {name}"
        modifier = {
            "skill": skill.capitalize(),
            "name": name,
            "type": "multiplier",
            "value": value,
        }
        if included_set is None:
            modifier["appliesToAll"] = True
            if ignored:
                modifier["ignores"] = ignored
        else:
            applies = [a for a in sets[included_set] if a not in ignored]
            for activity in applies:
                assert activity in activity_names, \
                    f"{name} applies to unknown/zero-xp activity {activity}"
            modifier["appliesTo"] = applies
        result.append(modifier)
    return result


def display_name(enum_name: str, item_id: int, legacy_by_id: dict) -> str:
    legacy = legacy_by_id.get(item_id)
    if legacy is None:
        legacy = re.sub(r"_\d+$", "", SKILL_PREFIX.sub("", enum_name))
    return legacy.replace("_", " ").strip().capitalize()


def main():
    items = parse_items(fetch("ExperienceItem"))
    activities = parse_activities(fetch("Activity"))
    secondaries, custom_secondaries = parse_secondaries(fetch("Secondaries"))
    modifiers_source = fetch("Modifiers", subdir="modifiers/")
    gameval = gameval_item_ids()
    skills = skill_enum_names()
    legacy_by_id = {}
    for name, item_id in json.load(open(NAME_INDEX)).items():
        legacy_by_id.setdefault(item_id, name)

    def resolve(constant: str, context: str) -> int:
        if constant not in gameval:
            raise SystemExit(f"unknown gameval ItemID.{constant} in {context}")
        return gameval[constant]

    entries = []
    skipped_zero = []
    for act_name, display, level, xp, item_name, sec_name, out_const, out_qty in activities:
        if xp <= 0:
            skipped_zero.append(act_name)
            continue
        if item_name not in items:
            raise SystemExit(f"activity {act_name} references unknown item {item_name}")
        skill, id_constants, by_dose = items[item_name]
        if skill not in skills:
            raise SystemExit(f"unknown Skill.{skill} on {item_name}")
        if sec_name is not None and sec_name not in secondaries and sec_name not in custom_secondaries:
            raise SystemExit(f"activity {act_name} references unknown Secondaries.{sec_name}")
        for i, constant in enumerate(id_constants):
            item_id = resolve(constant, item_name)
            entry = {
                "skill": skill.capitalize(),
                "method": display,
                "activity": act_name,
                "itemId": item_id,
                "name": display_name(item_name, item_id, legacy_by_id),
                "xpEach": round(xp * (i + 1 if by_dose else 1), 2),
            }
            if level > 1:
                entry["level"] = level
            if sec_name in secondaries:
                entry["secondaries"] = [
                    {"itemId": resolve(c, sec_name), "qty": num(q)}
                    for c, q in secondaries[sec_name]]
            if out_const is not None:
                entry["outputId"] = resolve(out_const, act_name)
                entry["outputQty"] = num(out_qty)
            entries.append(entry)

    modifiers = build_modifiers(modifiers_source, {e["activity"] for e in entries})

    # Cross-checks against the upstream source (wiki-verified values).
    anchors = {("Firemaking", 1515, 202.5), ("Prayer", 536, 72.0)}
    have = {(e["skill"], e["itemId"], e["xpEach"]) for e in entries}
    for anchor in anchors:
        assert anchor in have, f"anchor entry missing: {anchor}"
    assert len(entries) >= 600, f"only {len(entries)} entries"
    assert len({e["skill"] for e in entries}) >= 10, "too few skills"
    assert sum(1 for e in entries if "secondaries" in e) >= 100, "too few secondaries"
    assert sum(1 for e in entries if "outputId" in e) >= 400, "too few outputs"
    assert len(modifiers) >= 8, f"only {len(modifiers)} modifiers"

    pack = {
        "$schema": "./schemas/banked-xp.schema.json",
        "source": f"github.com/{REPO}@{COMMIT[:7]} (BSD-2-Clause)",
        "generated": datetime.date.today().isoformat(),
        "version": 3,
        "entries": entries,
        "modifiers": modifiers,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    per_skill = {}
    for e in entries:
        per_skill[e["skill"]] = per_skill.get(e["skill"], 0) + 1
    print(f"wrote {OUT}: {len(entries)} entries from {len(activities)} activities, "
          f"{len(modifiers)} modifiers; skipped {len(skipped_zero)} zero-xp activities "
          f"({', '.join(skipped_zero[:4])}…), {len(custom_secondaries)} custom-handler "
          f"secondaries ({', '.join(custom_secondaries[:4])}…), "
          f"modifiers: {', '.join(SKIPPED_MODIFIERS)}")
    for skill in sorted(per_skill):
        print(f"  {skill}: {per_skill[skill]}")


if __name__ == "__main__":
    main()
