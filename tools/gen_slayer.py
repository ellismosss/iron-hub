#!/usr/bin/env python3
"""Generate data/slayer-tasks.json — the Iron Hub slayer suite pack.

Sources (all pinned):
1. RuneLite core Task.java @ RL_TAG — canonical task names, NPC target-name
   lists (the highlight matcher core itself uses), item sprite ids and the
   finisher-item thresholds (rock hammer / bag of salt / fungicide spray).
   BSD-2 (runelite). https://github.com/runelite/runelite
2. OSRS wiki per-master assignment tables (Turael..Krystilia) — weights,
   amount ranges, extended ranges, unlock requirements parsed into
   requirement-graph strings.
3. OSRS wiki Bucket API (infobox_monster) — bundled monster stats for task
   monsters (defences, weakness, attack style/speed, slayer level/xp).
   Fetched at BUILD time so the client never phones home.
4. Slayer Simplified @ SS_COMMIT — per-task location lists + coordinates +
   access requirements + required/suggested items. BSD-2 (Lee / Slayer
   Simplified contributors). https://github.com/Wulfic/slayer-simplified
5. Turael Skipping @ TS_COMMIT — Turael/Aya task registry: kill areas,
   teleports, notes, shortest-path targets. BSD-2 (BrastaSauce).
   https://github.com/BrastaSauce/turael-skipping
6. OSRS wiki Slayer Rewards page — point unlocks/extends, joined to gameval
   VarbitID constants (javap over the runelite-api jar) so live unlock
   state is varbit-readable.

Requirement strings use the graph's parse() form; the pack test asserts
every emitted req parses non-manual. Fail-fast on any unjoined name.
"""

import glob
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

RL_TAG = "runelite-parent-1.12.33"
SS_COMMIT = "0d12863952ecbb268b850ec271e1f4b2a63fef9f"
TS_COMMIT = "2a389f044b5c76c995e57c31f9939db9338de262"

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-slayer", "gen")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "slayer-tasks.json")
ITEM_INDEX = os.path.join(HERE, "..", "src", "main", "resources", "data", "index", "item-names.json")
QUESTS_PACK = os.path.join(HERE, "..", "src", "main", "resources", "data", "quests.json")

WIKI_API = "https://oldschool.runescape.wiki/api.php"


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


def wiki_text(page: str) -> str:
    url = WIKI_API + "?" + urllib.parse.urlencode(
        {"action": "parse", "page": page, "prop": "wikitext",
         "format": "json", "formatversion": "2"})
    slug = re.sub(r"[^A-Za-z0-9]+", "_", page)
    data = json.loads(fetch(url, f"wiki-{slug}.json"))
    if "parse" not in data:
        raise SystemExit(f"wiki page missing: {page}")
    return data["parse"]["wikitext"]


def wiki_text_optional(page: str):
    url = WIKI_API + "?" + urllib.parse.urlencode(
        {"action": "parse", "page": page, "prop": "wikitext",
         "format": "json", "formatversion": "2"})
    slug = re.sub(r"[^A-Za-z0-9]+", "_", page)
    try:
        data = json.loads(fetch(url, f"wiki-{slug}.json"))
    except Exception:
        return None
    return data.get("parse", {}).get("wikitext")


def javap(class_name: str) -> str:
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    return subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", class_name],
        capture_output=True, text=True, check=True).stdout


def constants_of(class_name: str) -> dict:
    out = javap(class_name)
    return {m.group(1): int(m.group(2)) for m in
            re.finditer(r"public static final int (\w+) = (-?\d+);", out)}


# ── source 1: core Task.java ─────────────────────────────────────────────

TASK_ENTRY = re.compile(
    r'^\t([A-Z_0-9]+)\("([^"]+)",\s*ItemID\.(\w+)'
    r'(?:,\s*(\d+),\s*ItemID\.(\w+))?'
    r'((?:,\s*"[^"]*")*)\)[,;]$', re.M)


def core_tasks(item_ids: dict) -> dict:
    """Canonical task catalog: lower name -> entry."""
    src = fetch(
        f"https://raw.githubusercontent.com/runelite/runelite/{RL_TAG}/"
        "runelite-client/src/main/java/net/runelite/client/plugins/slayer/Task.java",
        f"core-Task-{RL_TAG}.java")
    tasks = {}
    for m in TASK_ENTRY.finditer(src):
        name = m.group(2)
        icon_const = m.group(3)
        if icon_const not in item_ids:
            raise SystemExit(f"unresolved ItemID.{icon_const} for task {name}")
        entry = {
            "name": name,
            "icon": item_ids[icon_const],
            "targets": re.findall(r'"([^"]*)"', m.group(6) or ""),
        }
        if m.group(4):
            finisher_const = m.group(5)
            if finisher_const not in item_ids:
                raise SystemExit(f"unresolved ItemID.{finisher_const} for task {name}")
            entry["finisher"] = {"itemId": item_ids[finisher_const],
                                 "threshold": int(m.group(4))}
        tasks[name.lower()] = entry
    if len(tasks) < 120:
        raise SystemExit(f"suspiciously few core tasks: {len(tasks)}")
    return tasks


def build_join(core: dict):
    """Return resolve(name) -> canonical lower key or None (plural-tolerant)."""
    keys = set(core)

    def resolve(raw: str):
        n = raw.strip().lower()
        n = n.replace("’", "'")
        for cand in (n,
                     n + "s",
                     n + "es",
                     n[:-1] + "ies" if n.endswith("y") else None,
                     n[:-3] + "men" if n.endswith("man") else None,
                     n[:-1] if n.endswith("s") and not n.endswith(("ss", "us")) else None,
                     n[:-1] + "ves" if n.endswith("f") else None,
                     n[:-2] + "ves" if n.endswith("fe") else None,
                     n.replace(" ", "") + "s"):
            if cand and cand in keys:
                return cand
        return None

    return resolve


# slayer-simplified per-variant task files that fold into one core task —
# their locations/bring merge in, their reqs stay per-variant and are dropped
SS_VARIANT_OF = {
    "adamant dragon": "metal dragons",
    "bronze dragon": "metal dragons",
    "iron dragon": "metal dragons",
    "mithril dragon": "metal dragons",
    "rune dragon": "metal dragons",
    "steel dragon": "metal dragons",
    "zygomite": "mutated zygomites",
}


# curated wiki-name → core-name joins where plural rules can't bridge
WIKI_ALIASES = {
    "tzhaar": "tzhaar",
    "fleshcrawlers": "fleshcrawlers",
    "flesh crawlers": "fleshcrawlers",
    "mutated zygomites": "mutated zygomites",
    "zygomites": "mutated zygomites",
    "cave kraken": "cave kraken",
    "dagannoths": "dagannoth",
    "fossil island wyverns": "fossil island wyverns",
    "custodian stalkers": "custodian stalkers",
    "warped creatures": "warped creatures",
    "spiritual creatures": "spiritual creatures",
    "sulphurous creatures": None,  # Konar-era row with no core task yet
}


# ── source 2: wiki per-master tables ─────────────────────────────────────

MASTERS = [
    # name, wiki page, focusId, (x,y,plane), base points, reqs, wilderness, dialog aliases
    ("Turael", "Turael", 1, (2931, 3536, 0), 0, None, False, ["Aya"]),
    ("Spria", "Spria", 9, (3104, 3257, 0), 0, ["quest:A Porcine of Interest"], False, []),
    ("Mazchna", "Mazchna", 2, (3510, 3507, 0), 6, ["combat:20"], False, ["Achtryn"]),
    ("Vannaka", "Vannaka", 3, (3145, 9914, 0), 8, ["combat:40"], False, []),
    ("Chaeldar", "Chaeldar", 4, (2445, 4431, 0), 10,
     ["combat:70", "quest:Lost City"], False, []),
    ("Konar quo Maten", "Konar quo Maten", 8, (1308, 3786, 0), 18, ["combat:75"], False, []),
    ("Nieve", "Nieve", 6, (2432, 3424, 0), 12, ["combat:85"], False, ["Steve"]),
    ("Duradel", "Duradel", 5, (2869, 2982, 0), 15,
     ["quest:Shilo Village", "any:combat:100&skill:Slayer:50|skill:Slayer:99"],
     False, ["Kuradel"]),
    ("Krystilia", "Krystilia", 7, (3109, 3514, 0), 25, None, True, []),
]

SCP = re.compile(r"\{\{SCP\|([A-Za-z ]+)\|(\d+)[^}]*\}\}")
REF = re.compile(r"<ref[^>/]*>.*?</ref>|<ref[^>]*/>", re.S)
LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]+)\]\]")
WEIGHT_TMPL = re.compile(r"\{\{\+=\|weight\|(\d+)[^}]*\}\}")
SORT_ATTR = re.compile(r'data-sort-value="[^"]*"\s*\|')
ABILITY = re.compile(r"[Uu]nlock(?:ed|ing)? the '*\[*'*([A-Za-z !&'\-]+?)'*\]*'* (?:ability|reward)")


def slug_unlock(name: str) -> str:
    return "slayerreward_" + re.sub(r"[^a-z0-9]+", "", name.strip().lower())


def parse_req_cell(cell: str, quest_names, master: str, monster: str):
    """Wiki unlock-requirement cell -> list of requirement-graph strings
    (allOf semantics, one leaf or any: composite per entry), or None."""
    text = REF.sub("", cell)
    text = SORT_ATTR.sub("", text).strip()
    if not text or "{{NA" in text or text.lower() in ("none", "n/a", "-"):
        return None
    leaves = []
    for skill, lvl in SCP.findall(text):
        skill = skill.strip()
        if skill.lower() == "combat":
            leaves.append(f"combat:{lvl}")
        else:
            leaves.append(f"skill:{skill}:{lvl}")
    text = SCP.sub("", text)
    ab = ABILITY.search(text)
    if ab:
        leaves.append(f"unlock:{slug_unlock(ab.group(1))}")
        text = ABILITY.sub("", text)
    # quest links; "X or Y" becomes an any: pair
    quest_links = [l.strip() for l in LINK.findall(text)
                   if l.strip() in quest_names]
    partial = "partial completion" in text.lower()
    if quest_links:
        form = "queststarted" if partial else "quest"
        if len(quest_links) >= 2 and re.search(r"\]\]\s+or\s+\[\[", text):
            leaves.append("any:" + "|".join(f"{form}:{q}" for q in quest_links))
        else:
            for q in quest_links:
                leaves.append(f"{form}:{q}")
    if not leaves:
        residue = LINK.sub(r"\1", text)
        residue = re.sub(r"[^A-Za-z]+", " ", residue).strip()
        if residue and len(residue) > 3:
            raise SystemExit(
                f"unparsed requirement cell for {master}/{monster}: {cell!r}")
        return None
    return leaves


CELL_ATTR = re.compile(r'^\s*(?:[a-z-]+="[^"]*"\s*)+\|(?!\|)')


def clean_cell(cell: str) -> str:
    cell = REF.sub("", cell)
    cell = SORT_ATTR.sub("", cell)
    cell = CELL_ATTR.sub("", cell)
    cell = LINK.sub(r"\1", cell)
    cell = re.sub(r"\{\{[^}]*\}\}", "", cell)
    cell = re.sub(r"<[^>]+>", "", cell)
    return cell.strip()


def parse_wikitable(text: str, want_header: str = None):
    """First wikitable in text (or the first whose headers mention
    want_header) -> (headers, rows of raw cell strings)."""
    body = None
    for m in re.finditer(r"\{\|.*?\n(.*?)\n\|\}", text, re.S):
        if want_header is None or re.search(r"^!.*" + re.escape(want_header),
                                            m.group(1), re.M | re.I):
            body = m.group(1)
            break
    if body is None:
        return None, []
    chunks = re.split(r"\n\|-[^\n]*", "\n" + body)
    headers = []
    rows = []
    for chunk in chunks:
        lines = [l for l in chunk.split("\n") if l.strip()]
        if not lines:
            continue
        if any(l.startswith("!") for l in lines):
            if headers:
                continue  # a later !-row is a footer (e.g. total task weight)
            for l in lines:
                if l.startswith("!"):
                    for h in re.split(r"!!", l[1:]):
                        headers.append(clean_cell(re.sub(r"^[^|!]*\|(?![|\[])", "", h)).lower())
            continue
        cells = []
        current = None
        for l in lines:
            if l.startswith("|"):
                for part in re.split(r"\|\|", l[1:]):
                    cells.append(part)
            elif cells:
                cells[-1] += "\n" + l
        if cells:
            rows.append(cells)
    return headers, rows


def master_table(page: str):
    """(headers, rows) for a master's assignment table."""
    sub = wiki_text_optional(page.replace(" ", "_") + "/Slayer_assignments")
    if sub and "{|" in sub:
        return parse_wikitable(sub, want_header="Monster")
    text = wiki_text(page)
    idx = re.search(r"^==+ *(Tasks|Assignments) *==+$", text, re.M)
    if not idx:
        raise SystemExit(f"no Tasks section on {page}")
    return parse_wikitable(text[idx.end():], want_header="Monster")


def col(headers, *names):
    for n in names:
        for i, h in enumerate(headers):
            if n in h:
                return i
    return None


def parse_master_rows(master, page, resolve, quest_names):
    headers, rows = master_table(page)
    if not headers:
        raise SystemExit(f"no table headers for {page}")
    ci_monster = col(headers, "monster")
    ci_amount = col(headers, "amount", "quantity")
    ci_ext = col(headers, "extended")
    ci_req = col(headers, "unlock requirement", "requirement")
    ci_weight = col(headers, "weight")
    ci_combat = col(headers, "combat level")
    ci_loc = col(headers, "location")
    if ci_monster is None or ci_weight is None:
        raise SystemExit(f"missing monster/weight columns for {page}: {headers}")
    out = []
    for cells in rows:
        if len(cells) <= ci_weight:
            # rowspan continuation rows (Krystilia's per-boss sub-rows) have
            # fewer cells than the header — the group row carries the entry
            continue
        raw_name = clean_cell(cells[ci_monster])
        if not raw_name:
            continue
        key = resolve(raw_name)
        if key is None and raw_name.lower() in WIKI_ALIASES:
            key = WIKI_ALIASES[raw_name.lower()]
        if key is None and "boss" in raw_name.lower():
            key = "bosses"
        if key is None:
            raise SystemExit(f"unjoined wiki task name on {page}: {raw_name!r}")
        if key is None:
            continue
        wm = WEIGHT_TMPL.search(cells[ci_weight])
        weight = int(wm.group(1)) if wm else None
        if weight is None:
            plain = clean_cell(cells[ci_weight])
            if plain.isdigit():
                weight = int(plain)
        if weight is None:
            raise SystemExit(f"no weight for {page}/{raw_name}: {cells[ci_weight]!r}")
        reqs = None
        if ci_req is not None and len(cells) > ci_req:
            reqs = parse_req_cell(cells[ci_req], quest_names, master, raw_name)
        if ci_combat is not None and len(cells) > ci_combat:
            combat = clean_cell(cells[ci_combat])
            m = re.search(r"\d+", combat)
            if m and int(m.group()) > 3:
                reqs = (reqs or []) + [f"combat:{m.group()}"]
        row = {"task": key, "weight": weight}
        if ci_amount is not None and len(cells) > ci_amount:
            amount = clean_cell(cells[ci_amount])
            if re.search(r"\d", amount):
                row["amount"] = amount
        if ci_ext is not None and len(cells) > ci_ext:
            ext = clean_cell(cells[ci_ext])
            if re.search(r"\d", ext):
                row["extended"] = ext
        if reqs:
            row["reqs"] = reqs
        if ci_loc is not None and len(cells) > ci_loc:
            locs = [clean_cell(x) for x in re.split(r"<br\s*/?>|\n\*", cells[ci_loc])]
            locs = [l for l in (re.sub(r"^[\*: ]+", "", l) for l in locs) if l]
            if locs:
                row["locations"] = locs
        out.append(row)
    if len(out) < 15:
        raise SystemExit(f"suspiciously few rows for {page}: {len(out)}")
    return out


# ── source 3: Bucket monster stats ───────────────────────────────────────

BUCKET_FIELDS = [
    "name", "combat_level", "hitpoints", "max_hit", "attack_style",
    "attack_speed", "stab_defence_bonus", "slash_defence_bonus",
    "crush_defence_bonus", "magic_defence_bonus", "range_defence_bonus",
    "elemental_weakness", "elemental_weakness_percent", "slayer_level",
    "slayer_experience", "slayer_category", "attribute", "cannon_immune",
    "poisonous",
]


def bucket_rows():
    rows = []
    offset = 0
    while True:
        q = ("bucket('infobox_monster').select(" +
             ",".join(f"'{f}'" for f in BUCKET_FIELDS) +
             f").where('slayer_category','!=','').offset({offset}).limit(5000).run()")
        url = WIKI_API + "?" + urllib.parse.urlencode(
            {"action": "bucket", "format": "json", "query": q})
        data = json.loads(fetch(url, f"bucket-{offset}.json"))
        batch = data.get("bucket", [])
        rows.extend(batch)
        if len(batch) < 5000:
            break
        offset += 5000
    if len(rows) < 300:
        raise SystemExit(f"suspiciously few bucket monsters: {len(rows)}")
    return rows


def stats_from_row(r):
    def num(k):
        v = r.get(k)
        return v if isinstance(v, (int, float)) else None

    out = {
        "level": num("combat_level"),
        "hp": num("hitpoints"),
        "attackSpeed": num("attack_speed"),
        "defStab": num("stab_defence_bonus"),
        "defSlash": num("slash_defence_bonus"),
        "defCrush": num("crush_defence_bonus"),
        "defMagic": num("magic_defence_bonus"),
        "defRanged": num("range_defence_bonus"),
        "weaknessPercent": num("elemental_weakness_percent"),
        "slayerLevel": num("slayer_level"),
        "slayerXp": num("slayer_experience"),
    }
    if r.get("elemental_weakness"):
        out["weakness"] = r["elemental_weakness"]
    styles = r.get("attack_style")
    if isinstance(styles, list) and styles:
        out["attackStyle"] = ", ".join(s for s in styles if s)[:60]
    mh = r.get("max_hit")
    if isinstance(mh, list) and mh:
        m = re.search(r"\d+", str(mh[0]))
        if m:
            out["maxHit"] = int(m.group())
    attrs = r.get("attribute")
    if isinstance(attrs, list) and attrs:
        out["attributes"] = [a for a in attrs if a]
    if str(r.get("cannon_immune", "")).lower().startswith("yes"):
        out["cannonImmune"] = True
    if str(r.get("poisonous", "")).lower().startswith("yes"):
        out["poisonous"] = True
    return {k: v for k, v in out.items() if v is not None}


# ── source 4: Slayer Simplified data ─────────────────────────────────────

SS_BASE = ("https://raw.githubusercontent.com/Wulfic/slayer-simplified/"
           f"{SS_COMMIT}/src/main/resources/data/")


def ss_json(path: str):
    return json.loads(fetch(SS_BASE + path, "ss-" + path.replace("/", "-")))


def normalize_item(name: str):
    return re.sub(r"^_|_$", "",
                  re.sub(r"[^A-Z0-9]+", "_",
                         re.sub(r"['’]", "", name).upper()))


def quest_display_names():
    with open(QUESTS_PACK, encoding="utf-8") as f:
        pack = json.load(f)
    quests = pack["quests"] if isinstance(pack, dict) and "quests" in pack else pack
    names = set()
    for q in quests:
        names.add(q["name"] if isinstance(q, dict) else q)
    if len(names) < 150:
        raise SystemExit(f"suspiciously few quests: {len(names)}")
    return names


def quest_enum_to_name(enum: str, quest_names):
    """RuneLite Quest enum constant -> display name, validated vs quests.json."""
    norm = re.sub(r"[^a-z0-9]+", "", enum.lower())
    for name in quest_names:
        if re.sub(r"[^a-z0-9]+", "", name.lower()) == norm:
            return name
    raise SystemExit(f"quest enum does not match quests.json: {enum}")


def req_json_to_list(req: dict, quest_names):
    """slayer-simplified requirements.json entry -> list of graph strings."""
    leaves = []
    for skill, lvl in (req.get("skills") or {}).items():
        leaves.append(f"skill:{skill.capitalize()}:{lvl}")
    for q in (req.get("quests") or []):
        leaves.append(f"quest:{quest_enum_to_name(q, quest_names)}")
    qany = req.get("questsAny") or []
    if qany:
        leaves.append("any:" + "|".join(
            f"quest:{quest_enum_to_name(q, quest_names)}" for q in qany))
    return leaves or None


# Locations the reference ships WITHOUT coordinates (16 task rows lacked a
# Route button) — curated from each location's own wiki page {{Map}}, using
# the pack's existing SURFACE-ENTRANCE convention for underground spots.
# "Wildnerness" is the reference's own typo, kept so its row still joins.
CURATED_COORDS = {
    "wilderness slayer cave": (3260, 3664, 0),      # Wilderness_Slayer_Cave southern entrance
    "wilderness slayer dungeon": (3260, 3664, 0),   # the same cave's older name
    "wildnerness slayer cave": (3260, 3664, 0),     # reference typo of the above
    "north of venenatis": (3319, 3798, 0),          # Venenatis page maplink
    "stronghold of security (level 2)": (3081, 3420, 0),  # Entrance_(Stronghold_of_Security)
    "enchanted valley": (3040, 4512, 0),            # Enchanted_Valley {{Map}} (fairy ring BKQ)
    "meiyerditch mine": (2389, 4627, 0),            # Meiyerditch_mine {{Map}}
}


def ss_locations_and_items(resolve, quest_names, item_ids_by_norm):
    index = ss_json("tasks/_index.json")
    coords = ss_json("location_coordinates.json")
    reqs = ss_json("requirements.json")

    coord_lookup = {}
    for name, c in coords.items():
        coord_lookup[name.lower()] = (name, c)
        for alias in c.get("aliases") or []:
            coord_lookup[alias.lower()] = (name, c)
    for lname, (x, y, plane) in CURATED_COORDS.items():
        coord_lookup.setdefault(lname, (lname, {"x": x, "y": y, "plane": plane}))

    loc_reqs = {}
    for name, r in (reqs.get("locations") or {}).items():
        s = req_json_to_list(r, quest_names)
        if s:
            loc_reqs[name.lower()] = s
    monster_reqs = {}
    for name, r in (reqs.get("monsters") or {}).items():
        s = req_json_to_list(r, quest_names)
        if s:
            monster_reqs[name.lower()] = s

    per_task = {}
    for entry in index:
        if entry["key"].startswith("a_debug"):
            continue
        task = ss_json("tasks/" + entry["file"])
        key = resolve(task["name"])
        merged_variant = False
        if key is None:
            folded = SS_VARIANT_OF.get(task["name"].lower())
            if folded:
                key = folded
                merged_variant = True
            else:
                print(f"  note: slayer-simplified task not in core catalog, skipped: {task['name']}")
                continue
        locations = []
        seen = set()
        for names in (task.get("variantLocations") or {}).values():
            for loc_name in names:
                lname = loc_name.strip()
                if lname.lower() in seen:
                    continue
                seen.add(lname.lower())
                loc = {"name": lname}
                hit = coord_lookup.get(lname.lower())
                if hit:
                    c = hit[1]
                    loc.update({"x": c["x"], "y": c["y"], "plane": c.get("plane", 0)})
                lreq = loc_reqs.get(lname.lower())
                if lreq:
                    loc["reqs"] = lreq
                locations.append(loc)
        bring = []

        def add_items(names, required):
            for raw in names or []:
                display = re.sub(r"\s*--.*$", "", raw).strip()
                if not display or display.lower() == "none":
                    continue
                item = {"name": display, "required": required}
                iid = item_ids_by_norm.get(normalize_item(display))
                if iid is not None:
                    item["id"] = iid
                bring.append(item)

        add_items(task.get("itemsRequired"), True)
        add_items(task.get("itemsSuggested"), False)
        if key in per_task:
            existing = per_task[key]
            have = {l["name"].lower() for l in existing["locations"]}
            existing["locations"].extend(
                l for l in locations if l["name"].lower() not in have)
            have_items = {b["name"].lower() for b in existing["bring"]}
            existing["bring"].extend(
                b for b in bring if b["name"].lower() not in have_items)
        else:
            per_task[key] = {
                "locations": locations,
                "bring": bring,
                # a folded variant's req is its own (e.g. adamant dragons need
                # DS2) — never the whole category's gate
                "reqs": None if merged_variant else monster_reqs.get(task["name"].lower()),
            }
    if len(per_task) < 90:
        raise SystemExit(f"suspiciously few slayer-simplified joins: {len(per_task)}")
    return per_task


# ── source 5: Turael Skipping registry ───────────────────────────────────

TS_URL = ("https://raw.githubusercontent.com/BrastaSauce/turael-skipping/"
          f"{TS_COMMIT}/src/main/java/com/brastasauce/turaelskipping/"
          "SlayerTaskRegistry.java")

WP = re.compile(r"new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)")


def parse_turael(resolve):
    src = fetch(TS_URL, f"ts-SlayerTaskRegistry-{TS_COMMIT[:7]}.java")
    entries = {}
    # split on Map.entry("key", boundaries; the goblin factory duplicates collapse
    parts = re.split(r'Map\.entry\("([a-z ]+)",', src)[1:]
    for key, body in zip(parts[0::2], parts[1::2]):
        m = re.search(r'new SlayerTask\("([^"]+)"', body)
        if not m:
            # the goblin entries call a factory — reconstructed below from
            # the factory body, never from this empty call site
            continue
        display = m.group(1)
        # world map points: the first List.of(new WorldPoint...) group
        wps = WP.findall(body.split("new NpcLocation")[0])
        locations = []
        for lm in re.finditer(
                r'new NpcLocation\("([^"]+)",\s*List\.of\((.*?)\),\s*new String\[\]\{([^}]*)\}\)',
                body, re.S):
            areas = []
            corners = WP.findall(lm.group(2))
            for a, b in zip(corners[0::2], corners[1::2]):
                x1, y1, p = map(int, a)
                x2, y2, _ = map(int, b)
                areas.append([min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), p])
            teleports = re.findall(r'"([^"]*)"', lm.group(3))
            locations.append({"name": lm.group(1), "teleports": teleports,
                              "areas": areas})
        info = None
        im = re.search(r'\),\s*"([^"]+)"(?:,\s*new WorldPoint|\))', body)
        if im:
            info = im.group(1)
        # explicit shortest-path point = last WorldPoint after the info arg,
        # else the LAST world map location (upstream convention)
        sp = None
        tail = body[body.rfind(")"):] if False else body
        em = re.search(r'"\s*,\s*new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)\s*\)\s*\)\s*,?\s*$',
                       body.strip(), re.S)
        if em:
            sp = [int(em.group(1)), int(em.group(2)), int(em.group(3))]
        elif wps:
            sp = [int(v) for v in wps[-1]]
        core_key = resolve(display)
        if core_key is None:
            raise SystemExit(f"unjoined turael task: {display}")
        if core_key in entries:
            continue  # goblin double-key
        entry = {"point": sp, "locations": locations}
        if info:
            entry["note"] = info
        entries[core_key] = entry
    # the goblin factory entries don't match the inline SlayerTask regex —
    # reconstruct from the factory body
    if resolve("Goblins") not in entries:
        fm = re.search(r"createGoblinTask\(String name\)\s*\{(.*?)\n\s*\}", src, re.S)
        if not fm:
            raise SystemExit("goblin factory not found in turael registry")
        body = fm.group(1)
        wps = WP.findall(body.split("new NpcLocation")[0])
        locations = []
        for lm in re.finditer(
                r'new NpcLocation\("([^"]+)",\s*List\.of\((.*?)\),\s*new String\[\]\{([^}]*)\}\)',
                body, re.S):
            corners = WP.findall(lm.group(2))
            areas = []
            for a, b in zip(corners[0::2], corners[1::2]):
                x1, y1, p = map(int, a)
                x2, y2, _ = map(int, b)
                areas.append([min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), p])
            locations.append({"name": lm.group(1),
                              "teleports": re.findall(r'"([^"]*)"', lm.group(3)),
                              "areas": areas})
        sp = [int(v) for v in wps[-1]] if wps else None
        goblin = {"point": sp, "locations": locations}
        gm = re.search(r'\),\s*"([^"]+)"\);', body + ");")
        if gm:
            goblin["note"] = gm.group(1)
        entries[resolve("Goblins")] = goblin
    if len(entries) != 24:
        raise SystemExit(f"expected 24 turael tasks, got {len(entries)}")
    return entries


# ── source 6: Slayer Rewards unlocks ─────────────────────────────────────

# wiki reward name -> gameval VarbitID constant (verified reflectively below
# and again by the pack test). Fail-fast on any table row not listed here.
UNLOCK_VARBITS = {
    "gargoyle smasher": "SLAYER_AUTOKILL_GARGOYLES",
    "slug salter": "SLAYER_AUTOKILL_ROCKSLUGS",
    "reptile freezer": "SLAYER_AUTOKILL_DESERTLIZARDS",
    "shroom sprayer": "SLAYER_AUTOKILL_ZYGOMITES",
    "broader fletching": "SLAYER_AMMO_UNLOCKED",
    "malevolent masquerade": "SLAYER_HELM_UNLOCKED",
    "ring bling": "SLAYER_RING_UNLOCKED",
    "seeing red": "SLAYER_UNLOCK_REDDRAGONS",
    "i hope you mith me": "SLAYER_UNLOCK_MITHRILDRAGONS",
    "watch the birdie": "SLAYER_UNLOCK_AVIANSIES",
    "hot stuff": "SLAYER_UNLOCK_TZHAAR",
    "reptile got ripped": "SLAYER_UNLOCK_LIZARDMEN",
    "like a boss": "SLAYER_UNLOCK_BOSSES",
    "bigger and badder": "SLAYER_UNLOCK_SUPERIORMOBS",
    "duly noted": "SLAYER_UNLOCK_NOTEDMITHRILBARS",
    "stop the wyvern": "SLAYER_UNLOCK_FOSSILWYVERNBLOCK",
    "double trouble": "SLAYER_UNLOCK_GROTESQUEKILLS",
    "basilocked": "SLAYER_UNLOCK_BASILISK",
    "actual vampyre slayer": "SLAYER_UNLOCK_VAMPYRES",
    "warped reality": "SLAYER_UNLOCK_WARPED_CREATURES",
    "task storage": "SLAYER_UNLOCK_STORAGE",
    "lured in": "SLAYER_UNLOCK_AQUANITES",
    "wings spread": "SLAYER_UNLOCK_GRYPHONS",
    "chance of heavy frost": "SLAYER_WEIGHTED_LONGER_FROST_DRAGONS",
    "i wildy more slayer": "SLAYER_UNLOCK_WILDY_EXTRATASKS",
    "revenenenenenants": "SLAYER_LONGER_REVENANTS",
    "need more darkness": "SLAYER_LONGER_DARKBEASTS",
    "ankou very much": "SLAYER_LONGER_ANKOU",
    "suq-a-nother one": "SLAYER_LONGER_SUQAH",
    "fire & darkness": "SLAYER_LONGER_BLACKDRAGONS",
    "pedal to the metals": "SLAYER_LONGER_METALDRAGONS",
    "i really mith you": "SLAYER_LONGER_MITHRILDRAGONS",
    "i see dragons": "SLAYER_UNLOCK_LONGER_FROST_DRAGONS",
    "gryphon and on": "SLAYER_UNLOCK_LONGER_GRYPHON",
    "augment my abbies": "SLAYER_LONGER_ABYSSALDEMONS",
    "it's dark in here": "SLAYER_LONGER_BLACKDEMONS",
    "greater challenge": "SLAYER_LONGER_GREATERDEMONS",
    "bleed me dry": "SLAYER_LONGER_BLOODVELD",
    "smell ya later": "SLAYER_LONGER_ABERRANTSPECTRES",
    "birds of a feather": "SLAYER_LONGER_AVIANSIES",
    "horrorific": "SLAYER_LONGER_CAVEHORRORS",
    "to dust you shall return": "SLAYER_LONGER_DUSTDEVILS",
    "wyver-nother one": "SLAYER_LONGER_SKELETALWYVERNS",
    "wyver-nother two": "SLAYER_LONGER_FOSSILWYVERNS",
    "basilonger": "SLAYER_LONGER_BASILISK",
    "get smashed": "SLAYER_LONGER_GARGOYLES",
    "nechs please": "SLAYER_LONGER_NECHRYAEL",
    "krack on": "SLAYER_LONGER_CAVEKRAKEN",
    "spiritual fervour": "SLAYER_LONGER_SPIRITUALGWD",
    "more at stake": "SLAYER_LONGER_VAMPYRES",
    "can of wyrms": "SLAYER_LONGER_WYRMS",
    "get scabaright on it": "SLAYER_LONGER_SCABARITES",
    "un-restraining order": "SLAYER_LONGER_CUSTODIANS",
    "more eyes than sense": "SLAYER_LONGER_ARAXYTES",
    "let's stay all aquanite": "SLAYER_LONGER_AQUANITES",
}


ICONS_OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "icons", "slayer")


def fetch_icon(file_name: str, out_name: str) -> str:
    """Download a wiki [[File:...]] image (cached, PNG-checked) into the
    bundled slayer icon directory; returns the bundled file name."""
    safe = file_name.strip().replace(" ", "_")
    cached = os.path.join(CACHE, "icon-" + re.sub(r"[^A-Za-z0-9._-]+", "_", safe))
    if not os.path.exists(cached):
        url = ("https://oldschool.runescape.wiki/w/Special:FilePath/"
               + urllib.parse.quote(safe))
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        data = None
        for attempt in range(5):
            import time
            time.sleep(1 if attempt == 0 else 15 * attempt)  # polite pace + 429 backoff
            try:
                with urllib.request.urlopen(req) as resp:
                    data = resp.read()
                break
            except urllib.error.HTTPError as e:
                if e.code != 429 or attempt == 4:
                    raise
        if not data.startswith(b"\x89PNG"):
            raise SystemExit(f"icon is not a PNG: {file_name}")
        os.makedirs(CACHE, exist_ok=True)
        with open(cached, "wb") as f:
            f.write(data)
    os.makedirs(ICONS_OUT, exist_ok=True)
    with open(cached, "rb") as src, open(os.path.join(ICONS_OUT, out_name), "wb") as dst:
        dst.write(src.read())
    return out_name


def parse_unlocks(varbit_ids):
    text = wiki_text("Slayer Rewards")
    unlocks = []
    unmapped = []
    section = None
    for chunk in re.split(r"(^==+[^=\n]+==+$)", text, flags=re.M):
        h = re.match(r"^==+ *([^=\n]+?) *==+$", chunk.strip())
        if h:
            section = h.group(1).strip()
            continue
        if section is None or "{|" not in chunk:
            continue
        if section.lower() not in ("unlock", "unlocks", "extend", "extends"):
            continue
        headers, rows = parse_wikitable(chunk)
        ci_name = col(headers, "unlock", "extend", "name", "reward")
        ci_pts = col(headers, "point", "cost")
        ci_desc = col(headers, "notes", "description", "effect")
        if ci_name is None or ci_pts is None:
            raise SystemExit(f"unlock table headers unrecognized: {headers}")
        for cells in rows:
            if len(cells) <= max(ci_name, ci_pts):
                continue
            name = clean_cell(cells[ci_name])
            if not name:
                continue
            pts_txt = clean_cell(cells[ci_pts])
            pm = re.search(r"\d+", pts_txt.replace(",", ""))
            if not pm:
                continue
            norm = re.sub(r"[^a-z0-9]+", "", name.lower())
            const = {re.sub(r"[^a-z0-9]+", "", k): v
                     for k, v in UNLOCK_VARBITS.items()}.get(norm)
            if const is None:
                unmapped.append(name)
                continue
            if const not in varbit_ids:
                raise SystemExit(f"varbit constant does not resolve: {const}")
            unlock = {
                "key": slug_unlock(name),
                "name": name,
                "points": int(pm.group()),
                "varbit": const,
                "category": "extend" if section.lower().startswith("extend") else "unlock",
            }
            if ci_desc is not None and len(cells) > ci_desc:
                desc = clean_cell(cells[ci_desc])
                if desc:
                    unlock["desc"] = re.sub(r"\s+", " ", desc)[:200]
            # the table's unnamed first column is the wiki's own icon per
            # reward ([[File:Gargoyle icon.png]]) — bundle it (Luke, 2026-07-21)
            if ci_name != 0:
                im = re.search(r"\[\[File:([^|\]]+)", cells[0])
                if im:
                    unlock["icon"] = fetch_icon(
                        im.group(1), unlock["key"].replace("slayerreward_", "") + ".png")
            unlocks.append(unlock)
    if unmapped:
        raise SystemExit("rewards not in UNLOCK_VARBITS map: " + "; ".join(unmapped))
    if len(unlocks) < 30:
        raise SystemExit(f"suspiciously few unlocks: {len(unlocks)}")
    with_icons = sum(1 for u in unlocks if "icon" in u)
    if with_icons < len(unlocks) - 5:
        raise SystemExit(f"suspiciously few unlock icons: {with_icons}/{len(unlocks)}")
    return unlocks


# ── main ─────────────────────────────────────────────────────────────────

def main():
    item_ids = constants_of("net.runelite.api.gameval.ItemID")
    varbit_ids = constants_of("net.runelite.api.gameval.VarbitID")
    with open(ITEM_INDEX, encoding="utf-8") as f:
        item_ids_by_norm = json.load(f)
    quest_names = quest_display_names()

    print("parsing core Task.java ...")
    core = core_tasks(item_ids)
    resolve = build_join(core)

    print("fetching Bucket monster stats ...")
    stats_by_name = {}
    for row in bucket_rows():
        name = (row.get("name") or "").lower()
        if name and name not in stats_by_name:
            stats_by_name[name] = stats_from_row(row)

    print("fetching slayer-simplified data ...")
    ss = ss_locations_and_items(resolve, quest_names, item_ids_by_norm)

    print("parsing turael-skipping registry ...")
    turael = parse_turael(resolve)

    print("parsing wiki master tables ...")
    masters_out = []
    rows_by_task = {}
    for (name, page, focus, (x, y, plane), points, reqs, wilderness, aliases) in MASTERS:
        rows = parse_master_rows(name, page, resolve, quest_names)
        print(f"  {name}: {len(rows)} rows")
        master = {
            "name": name, "focusId": focus, "x": x, "y": y, "plane": plane,
            "points": points, "wilderness": wilderness,
        }
        if reqs:
            master["reqs"] = reqs
        if aliases:
            master["aliases"] = aliases
        master["tasks"] = rows
        masters_out.append(master)
        for row in rows:
            rows_by_task.setdefault(row["task"], []).append(name)

    print("parsing unlocks ...")
    unlocks = parse_unlocks(varbit_ids)

    tasks_out = []
    assigned = set(rows_by_task)
    for key in sorted(set(core) | assigned | set(turael)):
        if key == "bosses":
            entry = {"name": "Bosses", "targets": [], "icon": 0}
        else:
            base = core.get(key)
            if base is None:
                raise SystemExit(f"assigned task missing from core catalog: {key}")
            entry = dict(base)
        stats = stats_by_name.get(key) or stats_by_name.get(
            key[:-1] if key.endswith("s") and not key.endswith(("ss", "us")) else key)
        reqs = []
        if stats:
            entry["stats"] = stats
            if stats.get("slayerLevel", 0) > 1:
                reqs.append(f"skill:Slayer:{stats['slayerLevel']}")
        extra = ss.get(key)
        if extra:
            if extra.get("locations"):
                entry["locations"] = extra["locations"]
            if extra.get("bring"):
                entry["bring"] = extra["bring"]
            if extra.get("reqs"):
                reqs.extend(extra["reqs"])
        if reqs:
            entry["reqs"] = reqs
        if key in turael:
            entry["turael"] = turael[key]
        tasks_out.append(entry)

    pack = {
        "version": 1,
        "sources": {
            "runelite": RL_TAG,
            "slayerSimplified": SS_COMMIT,
            "turaelSkipping": TS_COMMIT,
            "wiki": "oldschool.runescape.wiki (masters, Slayer Rewards, Bucket infobox_monster)",
        },
        "tasks": tasks_out,
        "masters": masters_out,
        "unlocks": unlocks,
    }

    with open(os.path.abspath(OUT), "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")

    n_locs = sum(len(t.get("locations", [])) for t in tasks_out)
    n_stats = sum(1 for t in tasks_out if "stats" in t)
    print(f"wrote {os.path.abspath(OUT)}")
    print(f"  tasks {len(tasks_out)} (stats {n_stats}, locations {n_locs}), "
          f"masters {len(masters_out)}, unlocks {len(unlocks)}, turael {len(turael)}")
    assert len(tasks_out) >= 120, "task floor"
    assert len(masters_out) == 9, "master count"
    assert len(unlocks) >= 30, "unlock floor"
    # every location must route: a coordless one silently loses its Route
    # button (Luke's Wilderness Slayer Dungeon report) — curate, never drop
    coordless = [(t["name"], l["name"]) for t in tasks_out
                 for l in t.get("locations", []) if "x" not in l]
    assert not coordless, f"locations without coordinates: {coordless}"


if __name__ == "__main__":
    main()
