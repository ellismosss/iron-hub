#!/usr/bin/env python3
"""Generate data/diaries.json — the achievement diary task pack.

Two verified sources are joined:

1. Per-task completion flags (varplayer bit, or varbit for Karamja) extracted
   from Quest Helper's achievement diary classes at the plugin-hub-pinned
   commit. QH is the production-proven authority on which bit tracks which
   task; Jagex's own gameval names corroborate the Karamja varbits.
2. Task text, requirements and rewards parsed from the 12 OSRS wiki diary
   pages (task tables carry data-diary-name/tier attributes; task numbering
   mirrors the in-game journal order).

The join is positional — QH flags sorted by (varp, bit) line up with wiki
task numbers — except four tiers where content was appended or reordered
after launch; those carry explicit field-name overrides below, each pair
hand-verified (see DOMAIN-NOTES.md "Achievement diary task tracking").

Usage:
  python3 tools/gen_diaries.py <quest-helper checkout> <runelite-api.jar> [wiki_cache_dir]

Without wiki_cache_dir the 12 pages are fetched live (descriptive
User-Agent, 1s apart, per the wiki's bot etiquette). With it, each page is
read from "<dir>/<Page Name>.json" (action=parse&prop=wikitext output).
"""
import datetime
import html as htmllib
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
OUT = "src/main/resources/data/diaries.json"
TIERS = ["Easy", "Medium", "Hard", "Elite"]

# wiki page -> region name used by DiariesModule
PAGES = {
    "Ardougne Diary": "Ardougne",
    "Desert Diary": "Desert",
    "Falador Diary": "Falador",
    "Fremennik Diary": "Fremennik",
    "Kandarin Diary": "Kandarin",
    "Karamja Diary": "Karamja",
    "Kourend & Kebos Diary": "Kourend & Kebos",
    "Lumbridge & Draynor Diary": "Lumbridge & Draynor",
    "Morytania Diary": "Morytania",
    "Varrock Diary": "Varrock",
    "Western Provinces Diary": "Western Provinces",
    "Wilderness Diary": "Wilderness",
}
# region -> QH package directory name
QH_DIRS = {
    "Ardougne": "ardougne", "Desert": "desert", "Falador": "falador",
    "Fremennik": "fremennik", "Kandarin": "kandarin", "Karamja": "karamja",
    "Kourend & Kebos": "kourend", "Lumbridge & Draynor": "lumbridgeanddraynor",
    "Morytania": "morytania", "Varrock": "varrock",
    "Western Provinces": "westernprovinces", "Wilderness": "wilderness",
}
QH_CLASS = {
    "Ardougne": "Ardougne", "Desert": "Desert", "Falador": "Falador",
    "Fremennik": "Fremennik", "Kandarin": "Kandarin", "Karamja": "Karamja",
    "Kourend & Kebos": "Kourend", "Lumbridge & Draynor": "Lumbridge",
    "Morytania": "Morytania", "Varrock": "Varrock",
    "Western Provinces": "Western", "Wilderness": "Wilderness",
}

# Tiers where the in-game journal order (= wiki numbering) does not follow
# ascending flag order: wiki task number -> QH field name. Tasks appended or
# renumbered after launch; every pair hand-verified against task semantics
# (Karamja additionally against Jagex's ATJUN_MED_* gameval names).
OVERRIDES = {
    ("Ardougne", "Elite"): {3: "PickHero", 4: "RuneCrossbow"},
    ("Kourend & Kebos", "Medium"): {
        1: "FairyRing", 2: "KillLizardman", 3: "TravelWithMemoirs",
        4: "MineSulphur", 5: "EnterFarmingGuild", 6: "SwitchSpellbooks",
        7: "RepairCrane", 8: "DeliverIntelligence", 9: "CatchBluegill",
        10: "UseBoulderShortcut", 11: "SubdueWintertodt",
        12: "CatchChinchompa", 13: "ChopMahoganyTree",
    },
    ("Kourend & Kebos", "Hard"): {5: "PlantLogavano", 6: "KillZombie"},
    ("Karamja", "Medium"): {
        7: "TraveledToKhazard", 18: "CharteredFromShipyard", 19: "MinedRedRopaz",
    },
}


def gameval(jar, cls):
    out = subprocess.run(
        ["javap", "-constants", "-cp", jar, "net.runelite.api.gameval." + cls],
        capture_output=True, text=True, check=True).stdout
    return {m.group(1): int(m.group(2))
            for m in re.finditer(r"public static final int (\w+) = (\d+);", out)}


def quest_names(jar):
    """Quest display names from the Quest enum's constant pool — only links
    matching one become quest: leaves (dot-tolerant, lowercased)."""
    out = subprocess.run(
        ["javap", "-v", "-cp", jar, "net.runelite.api.Quest"],
        capture_output=True, text=True, check=True).stdout
    names = set()
    for m in re.finditer(r"= String\s+#\d+\s+// (.+)$", out, re.MULTILINE):
        names.add(m.group(1).strip().rstrip(".").lower())
    if len(names) < 100:
        raise SystemExit(f"only {len(names)} quest names from {jar} - parsing regressed?")
    return names


# ── Quest Helper flag extraction ─────────────────────────────────────────

def qh_flags(qh_root, varps, varbits):
    base = pathlib.Path(qh_root) / "src/main/java/com/questhelper/helpers/achievementdiaries"
    flags = {}
    for region, pkg in QH_DIRS.items():
        for tier in TIERS:
            src = (base / pkg / (QH_CLASS[region] + tier + ".java")).read_text()
            found = []
            for m in re.finditer(
                r"not(\w+) = new VarplayerRequirement\(VarPlayerID\."
                r"(\w*ACHIEVEMENT_DIARY\w*|ATJUN_TASKS_\d), false, (\d+)\)", src):
                found.append({"field": m.group(1),
                              "varp": varps[m.group(2)], "bit": int(m.group(3))})
            for m in re.finditer(
                r"not(\w+) = new VarbitRequirement\(VarbitID\.(ATJUN_\w+), (\d+)"
                r"(, Operation\.LESS_EQUAL)?\)", src):
                threshold = int(m.group(3)) + 1 if m.group(4) else 1
                found.append({"field": m.group(1),
                              "varbit": varbits[m.group(2)], "min": threshold})
            keys = [(f.get("varp", 0), f.get("bit", f.get("varbit"))) for f in found]
            if len(set(keys)) != len(keys):
                raise SystemExit(f"duplicate flag in QH {region} {tier}")
            found.sort(key=lambda f: (f.get("varp", 1 << 30), f.get("bit", 0), f.get("varbit", 0)))
            flags[(region, tier)] = found
    return flags


# ── wiki parsing ─────────────────────────────────────────────────────────

def fetch_page(page):
    url = ("https://oldschool.runescape.wiki/api.php?action=parse&page="
           + urllib.parse.quote(page) + "&prop=wikitext&format=json&formatversion=2")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def clean(s):
    s = re.sub(r"<ref[^>]*/>", "", s)
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.DOTALL)
    s = re.sub(r"\{\{sic\}\}", "", s)
    s = re.sub(r"\{\{[Bb]oostable\|[^}]*\}\}", "", s)
    s = re.sub(r"\{\{RuneReq\|([^}]*)\}\}",
               lambda m: ", ".join(f"{v} {k}" for k, v in
                                   (p.split("=") for p in m.group(1).split("|") if "=" in p)),
               s)
    s = re.sub(r"\{\{Fairycode\|([^}]*)\}\}", lambda m: m.group(1).upper(), s)
    s = re.sub(r"\{\{FloorNumber\|uk=0\}\}", "ground floor", s)
    # SCP skill icons inline in prose -> "lvl Skill"; icon-only SCPs drop
    s = re.sub(r"\{\{SCP\|([A-Za-z ]+)\|(\d+)[^}]*\}\}", r"\2 \1", s)
    s = re.sub(r"\{\{SCP\|[^}]*\}\}", "", s)
    s = re.sub(r"\{\{efn\|[^{}]*\}\}", "", s)
    s = re.sub(r"\{\{NA\|?[^{}]*\}\}", "None", s)
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]+)\]\]", r"\1", s)
    s = s.replace("'''", "").replace("''", "")
    s = htmllib.unescape(s)
    return re.sub(r"\s+", " ", s).strip()


SCP_SKILL = re.compile(r"\{\{SCP\|([A-Za-z ]+)\|(\d+)[^}]*\}\}")


def skill_req(raw, text):
    """One requirement string for a skill-requirement line, or None.
    Multiple skills on one line are all required (single-path any: composite);
    "X or Y[, and Z...]" makes the first two skills alternative paths. The
    wiki's per-line {{Boostable|n}} marks turn skillb: into skill:."""
    scps = [(m.group(1), int(m.group(2))) for m in SCP_SKILL.finditer(raw)
            if m.group(1) not in ("Quest", "Combat")]
    if not scps or "recommended" in text.lower():
        return None
    prefix = "skill" if re.search(r"\{\{[Bb]oostable\|no?\}\}", raw) else "skillb"
    leaves = [f"{prefix}:{s}:{l}" for s, l in scps]
    if len(leaves) == 1:
        return leaves[0]
    if re.search(r"\}\}\s*,?\s*or\s+\{\{SCP\|", raw):
        common = leaves[2:]
        return ("any:" + "&".join([leaves[0]] + common)
                + "|" + "&".join([leaves[1]] + common))
    return "any:" + "&".join(leaves)  # single path = all required


def parse_reqs(cell, quests):
    """Requirement bullets -> [{text, req?}] — req only when it parses into
    the requirement graph (skill leaves/quest: leaves); the rest is
    display-only. Quest leaves come from wiki links on "Completion of ..."
    lines validated against the Quest enum; the line is truncated at
    started/partial/either/or wording so partial-completion phrasing never
    gates a task."""
    reqs = []
    for line in cell.split("\n"):
        line = line.strip()
        if not line.startswith("*"):
            continue
        raw = line.lstrip("* ").strip()
        text = clean(raw)
        if not text or text == "None":
            continue
        entry = {"text": text}
        skill = skill_req(raw, text)
        if skill:
            entry["req"] = skill
            reqs.append(entry)
            continue
        if text.startswith("Completion of"):
            gated = re.split(r"(?i)\bstarted\b|\bpartial\b|\beither\b|\bor\b", raw)[0]
            leaves = []
            for target in re.findall(r"\[\[([^|\]]+)(?:\|[^\]]*)?\]\]", gated):
                if target.strip().rstrip(".").lower() in quests:
                    leaf = "quest:" + target.strip()
                    if leaf not in leaves:
                        leaves.append(leaf)
            if len(leaves) == 1:
                entry["req"] = leaves[0]
                reqs.append(entry)
                continue
            if leaves:  # several quests on one line -> one leaf each
                reqs.append(entry)
                for leaf in leaves:
                    reqs.append({"text": leaf.split(":", 1)[1], "req": leaf})
                continue
        reqs.append(entry)
    return reqs


def parse_page(data, quests):
    w = data["parse"]["wikitext"]
    out = {}
    for tm in re.finditer(
        r'data-diary-name="[^"]+" data-diary-tier="([^"]+)"\s*\n(.*?)\n\|\}', w, re.DOTALL):
        tier, body = tm.group(1), tm.group(2)
        tasks = []
        for row in re.split(r"\n\|-\s*\n", body)[1:]:
            cells = [c for c in re.split(r"\n\|(?!\|)", "\n" + row) if c.strip()]
            if len(cells) < 2:
                continue
            first, *notelines = cells[0].strip().split("\n")
            nm = re.match(r"(\d+)\.\s*(.*)", clean(first))
            if not nm:
                continue
            tasks.append({"n": int(nm.group(1)), "task": nm.group(2),
                          "note": clean(" ".join(notelines)),
                          "reqs": parse_reqs(cells[1], quests)})
        out.setdefault(tier, {})["tasks"] = tasks
    for tier in TIERS:
        sm = re.search(r"==\s*" + tier + r"\s*==(.*?)(?=\n==[^=]|\Z)", w, re.DOTALL)
        rm = sm and re.search(r"===\s*Rewards\s*===[ \t]*\n(.*)", sm.group(1), re.DOTALL)
        rewards = []
        if rm:
            for line in rm.group(1).split("\n"):
                line = line.strip()
                if line.startswith("*") and not line.startswith("**"):
                    rewards.append(clean(line.lstrip("* ")).rstrip(":"))
                elif line.startswith("==") or line.startswith("{|"):
                    break
        out.setdefault(tier, {})["rewards"] = rewards
    return out


# ── join + validation ────────────────────────────────────────────────────

def field_tokens(field):
    return [t.lower() for t in re.findall(r"[A-Z][a-z]+|[A-Z]{2,}|[a-z]+", field) if len(t) > 2]


def join(region, tier, flags, tasks):
    """Positional join with overrides; returns tasks with flags attached."""
    override = OVERRIDES.get((region, tier), {})
    by_field = {f["field"]: f for f in flags}
    for name in override.values():
        if name not in by_field:
            raise SystemExit(f"override field {name} missing in QH {region} {tier}")
    overridden = set(override.values())
    rest = iter([f for f in flags if f["field"] not in overridden])
    joined = []
    for task in tasks:
        flag = by_field[override[task["n"]]] if task["n"] in override else next(rest)
        entry = {"task": task["task"]}
        if task["note"]:
            entry["note"] = task["note"]
        for key in ("varp", "bit", "varbit", "min"):
            if key in flag:
                entry[key] = flag[key]
        entry["reqs"] = task["reqs"]
        joined.append((flag["field"], entry))
    return joined


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    qh_root, jar = sys.argv[1], sys.argv[2]
    cache = pathlib.Path(sys.argv[3]) if len(sys.argv) > 3 else None

    quests = quest_names(jar)
    varps = gameval(jar, "VarPlayerID")
    varbits = gameval(jar, "VarbitID")
    flags = qh_flags(qh_root, varps, varbits)

    regions = []
    warnings = 0
    for page, region in PAGES.items():
        if cache:
            data = json.load(open(cache / (page + ".json"), encoding="utf-8"))
        else:
            data = fetch_page(page)
            time.sleep(1)
        parsed = parse_page(data, quests)
        tiers = []
        for tier in TIERS:
            tasks = parsed[tier]["tasks"]
            tier_flags = flags[(region, tier)]
            if len(tasks) != len(tier_flags):
                raise SystemExit(
                    f"{region} {tier}: wiki has {len(tasks)} tasks, QH has {len(tier_flags)}")
            joined = []
            for field, entry in join(region, tier, tier_flags, tasks):
                text = (entry["task"] + " " + entry.get("note", "")).lower()
                toks = field_tokens(field)
                hits = sum(1 for t in toks
                           if t in text or (t.endswith("s") and t[:-1] in text) or (t + "s") in text)
                if toks and hits == 0:
                    print(f"warn: no token overlap {region} {tier} "
                          f"field={field} task={entry['task'][:60]}")
                    warnings += 1
                joined.append(entry)
            tiers.append({"tier": tier, "tasks": joined,
                          "rewards": parsed[tier]["rewards"]})
        regions.append({"name": region, "tiers": tiers})

    total = sum(len(t["tasks"]) for r in regions for t in r["tiers"])
    if total < 490:
        raise SystemExit(f"only {total} tasks - parsing regressed?")
    pack = {
        "source": "oldschool.runescape.wiki diary pages + "
                  "github.com/Zoinkwiz/quest-helper task flag mappings",
        "generated": datetime.date.today().isoformat(),
        "regions": regions,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {OUT}: {total} tasks across {len(regions)} regions, {warnings} warnings")


if __name__ == "__main__":
    main()
