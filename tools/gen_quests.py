#!/usr/bin/env python3
"""Generate data/quests.json — the quest action pack for the goal engine.

Sources, all OSRS wiki (fetched with a descriptive User-Agent):
  - Optimal_quest_guide/Ironman: the community route order (route prior)
    and per-quest xp rewards ({{Optimal quest|skill=xp}} templates).
  - Each quest page's {{Quest details}} infobox: official length rating,
    direct prerequisite quests (the ** nesting level — deeper levels are
    the transitive closure the engine's expander recomputes itself), and
    skill requirements as SCP templates with {{Boostable|no}} marks.

Quest identity comes from the RuneLite Quest enum (extracted from the
runelite-api jar), so every entry the client can detect carries its enum
name; wiki-only entries (miniquests absent from the enum) keep name-only
identity and compile to manual steps.

Length words map to minutes via a curated calibration table (documented
in the pack header; the wiki does not publish minutes).

Usage:
  python3 tools/gen_quests.py <runelite-api.jar> [cache_dir]

With cache_dir, API responses are read from/written to it (re-runs are
offline). Fetches are 1s apart per wiki bot etiquette.
"""
import datetime
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
OUT = "src/main/resources/data/quests.json"
API = "https://oldschool.runescape.wiki/api.php"

# official length rating -> minutes (curated calibration, see docstring)
LENGTH_MINUTES = {
    "very short": 10, "short": 25, "medium": 45, "long": 90, "very long": 180,
}

# OQG rowid -> RuneLite Quest enum display name, where they differ
# (RFD subquests use page-path rowids)
ENUM_FIXES = {
    "Recipe for Disaster/Another Cook's Quest": "Recipe for Disaster - Another Cook's Quest",
    "Recipe for Disaster/Freeing the Mountain Dwarf": "Recipe for Disaster - Mountain Dwarf",
    "Recipe for Disaster/Freeing the Goblin generals": "Recipe for Disaster - Wartface & Bentnoze",
    "Recipe for Disaster/Freeing Pirate Pete": "Recipe for Disaster - Pirate Pete",
    "Recipe for Disaster/Freeing the Lumbridge Guide": "Recipe for Disaster - Lumbridge Guide",
    "Recipe for Disaster/Freeing Evil Dave": "Recipe for Disaster - Evil Dave",
    "Recipe for Disaster/Freeing Skrach Uglogwee": "Recipe for Disaster - Skrach Uglogwee",
    "Recipe for Disaster/Freeing Sir Amik Varze": "Recipe for Disaster - Sir Amik Varze",
    "Recipe for Disaster/Freeing King Awowogei": "Recipe for Disaster - King Awowogei",
    "Recipe for Disaster#Defeating the Culinaromancer": "Recipe for Disaster - Culinaromancer",
}
# rowid -> fetchable page title, where the rowid itself is not one
PAGE_FIXES = {
    "Recipe for Disaster#Defeating the Culinaromancer": "Recipe for Disaster/Defeating the Culinaromancer",
}


def fetch(params, cache, key):
    if cache:
        cached = cache / (key + ".json")
        if cached.exists():
            return json.load(open(cached, encoding="utf-8"))
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json", "formatversion": "2"})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        data = json.load(resp)
    time.sleep(1)
    if cache:
        cache.mkdir(parents=True, exist_ok=True)
        json.dump(data, open(cache / (key + ".json"), "w", encoding="utf-8"))
    return data


def quest_enum_names(jar):
    out = subprocess.run(["javap", "-v", "-cp", jar, "net.runelite.api.Quest"],
                         capture_output=True, text=True, check=True).stdout
    names = [m.group(1).strip().replace("\\'", "'")
             for m in re.finditer(r"= String\s+#\d+\s+// (.+)$", out, re.MULTILINE)]
    if len(names) < 100:
        raise SystemExit("Quest enum extraction regressed")
    return names


def parse_oqg(wikitext):
    """Route order + xp rewards per quest row."""
    order, rewards, qp_points = [], {}, {}
    for m in re.finditer(r'\|- data-rowid="([^"\n]+)"?(.*?)(?=\n\|- |\Z)', wikitext, re.DOTALL):
        name, row = m.group(1).strip(), m.group(2)
        # non-quest route rows (partial completions, activities) are not actions
        if name.lower().startswith(("partial", "museum", "train ")):
            continue
        if name not in rewards:
            order.append(name)
            qpm = re.search(r"\{\{Optimal quest/qp\|(\d+)\}\}", row)
            qp_points[name] = int(qpm.group(1)) if qpm else 0
            xp = {}
            for tm in re.finditer(r"\{\{Optimal quest\|([^}]*)\}\}", row):
                for part in tm.group(1).split("|"):
                    if "=" in part:
                        skill, value = part.split("=", 1)
                        value = value.strip().replace(",", "")
                        if value.replace(".", "").isdigit():
                            xp[skill.strip().capitalize()] = xp.get(skill.strip().capitalize(), 0) + int(float(value))
            rewards[name] = xp
    return order, rewards, qp_points


def parse_infobox(content):
    """length minutes, direct prereq quests, started-quests, skill req strings."""
    result = {"minutes": 0, "questReqs": [], "startedReqs": [], "skillReqs": []}
    lm = re.search(r"\|\s*length\s*=\s*([A-Za-z ]+)", content)
    if lm:
        result["minutes"] = LENGTH_MINUTES.get(lm.group(1).strip().lower(), 0)
    rm = re.search(r"\|\s*requirements\s*=(.*?)(?=\n\|[a-z]+\s*=|\Z)", content, re.DOTALL)
    if not rm:
        return result
    block = rm.group(1)
    for line in block.split("\n"):
        line = line.strip()
        # direct prerequisites: exactly two stars under "Completion of ..."
        qm = re.match(r"\*\*\s*\[\[([^|\]]+)(?:\|[^\]]*)?\]\]\s*$", line)
        if qm:
            result["questReqs"].append(qm.group(1).strip())
            continue
        # single-star "Completion of [[X]]" (quests with one prerequisite)
        sm = re.match(r"\*\s*Completion of \[\[([^|\]]+)(?:\|[^\]]*)?\]\]", line)
        if sm:
            result["questReqs"].append(sm.group(1).strip())
            continue
        bm = re.match(r"\*\s*\[\[([^|\]]+)(?:\|[^\]]*)?\]\]\s*$", line)
        if bm and not bm.group(1).startswith(("File:", "Category:")):
            result["questReqs"].append(bm.group(1).strip())
            continue
        qpm = re.match(r"\*+\s*\{\{SCP\|Quest\|(\d+)[^}]*\}\}", line)
        if qpm:
            result["skillReqs"].append("qp:" + qpm.group(1))
            continue
        stm = re.match(r"\*\s*Started \[\[([^|\]]+)(?:\|[^\]]*)?\]\]", line)
        if stm:
            result["startedReqs"].append(stm.group(1).strip())
            continue
        skm = re.match(r"\*+\s*\{\{SCP\|([A-Za-z ]+)\|(\d+)[^}]*\}\}(.*)", line)
        if skm and skm.group(1) not in ("Quest", "Combat"):
            boostable = not re.search(r"\{\{[Bb]oostable\|no?\}\}", skm.group(3))
            prefix = "skillb" if boostable else "skill"
            result["skillReqs"].append(f"{prefix}:{skm.group(1)}:{int(skm.group(2))}")
    return result


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    jar = sys.argv[1]
    cache = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else None

    enum_names = quest_enum_names(jar)
    oqg = fetch({"action": "parse", "page": "Optimal_quest_guide/Ironman", "prop": "wikitext"},
                cache, "oqg-ironman")["parse"]["wikitext"]
    order, rewards, qp_points = parse_oqg(oqg)

    # identity set: the OQG route rows (covers every quest + miniquest);
    # the enum constant pool carries non-quest string literals, so it only
    # serves as the id join, never as a fetch source
    names = list(dict.fromkeys(order))
    pages = {n: PAGE_FIXES.get(n, n) for n in names}

    infoboxes = {}
    titles = list(dict.fromkeys(pages.values()))
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        data = fetch({"action": "query", "prop": "revisions", "rvprop": "content",
                      "rvslots": "main", "redirects": "1", "titles": "|".join(batch)},
                     cache, f"quests-batch-{i}")
        normalized = {}
        for n in data["query"].get("normalized", []) + data["query"].get("redirects", []):
            normalized[n["from"]] = n["to"]
        resolved = {t: normalized.get(t, t) for t in batch}
        by_title = {p["title"]: p for p in data["query"]["pages"]}
        for t in batch:
            page = by_title.get(resolved.get(t, t))
            if page and "revisions" in page:
                infoboxes[t] = parse_infobox(page["revisions"][0]["slots"]["main"]["content"])

    enum_by_name = {n.lower().rstrip("."): n for n in enum_names}
    def to_enum(name):
        wanted = ENUM_FIXES.get(name, name)
        return enum_by_name.get(wanted.lower().rstrip("."))
    order_index = {n: i for i, n in enumerate(order)}
    quests = []
    for name in names:
        info = infoboxes.get(pages[name], {"minutes": 0, "questReqs": [], "startedReqs": [], "skillReqs": []})
        reqs = list(info["skillReqs"])
        for q in info["questReqs"]:
            reqs.append("quest:" + ENUM_FIXES.get(q, q))
        for q in info["startedReqs"]:
            reqs.append("queststarted:" + ENUM_FIXES.get(q, q))
        enum = to_enum(name)
        if enum is None and info["minutes"] == 0:
            continue # non-quest route row (diary step, museum, balloon...)
        quests.append({
            "name": ENUM_FIXES.get(name, name),
            "enumName": enum,
            "minutes": info["minutes"],
            "qp": qp_points.get(name, 0),
            "order": order_index.get(name, -1),
            "reqs": reqs,
            "xp": rewards.get(name, {}),
        })

    resolved_count = sum(1 for q in quests if q["enumName"])
    with_reqs = sum(1 for q in quests if q["reqs"])
    if resolved_count < 140 or with_reqs < 100:
        raise SystemExit(f"suspiciously sparse: {resolved_count} enum-resolved, {with_reqs} with reqs")
    pack = {
        "source": "oldschool.runescape.wiki Optimal_quest_guide/Ironman + per-quest infoboxes; "
                  "length->minutes calibration curated (VS10/S25/M45/L90/VL180)",
        "generated": datetime.date.today().isoformat(),
        "quests": quests,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {OUT}: {len(quests)} quests ({resolved_count} enum-resolved, "
          f"{with_reqs} with reqs, {len(order)} on the route)")


if __name__ == "__main__":
    main()
