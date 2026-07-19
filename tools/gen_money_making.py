#!/usr/bin/env python3
"""Generate data/money-making.json — the OSRS wiki Money making guide, for the
Bank-section "Money making" module.

Two sources, joined by subpage:
  1. the main guide page rendered (action=parse, prop=text) — the aggregated
     table gives each method's name, hourly PROFIT (a GE-price snapshot),
     category, intensity, and members flag; the recurring table gives
     profit-per-run + time + recurrence instead of gp/hr;
  2. each method subpage's {{Mmgtable}} template (wikitext) — the structured
     Skill / Quest / Item / Other requirements and the Input consumables.

Requirements are classified hard-vs-recommended per Luke's rules (category
aware): quests + access-gating "Completion of [[X]]" are HARD; skilling/
processing/collecting skill levels are HARD gates; combat skill levels are
RECOMMENDATIONS (you can fight under-levelled); recommended gear is soft. The
module layers account-type logic on top (iron processing must OWN the inputs;
non-iron needs the gp). Item names resolve to ids via the GE mapping.

Profit is a snapshot — regenerate to refresh. Cache under tools/.cache-mmg/.
"""
import json
import html
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

UA = "iron-hub-mmg-gen/1.0 (+github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
WIKI = "https://oldschool.runescape.wiki/api.php"
MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping"
CACHE = Path(__file__).parent / ".cache-mmg"
ROOT = Path(__file__).parent.parent
OUT = ROOT / "src/main/resources/data/money-making.json"

# combat skills are recommendations (you can fight under-levelled); a level in
# any OTHER skill is a hard gate (you can't craft nature runes below 44 RC)
COMBAT_SKILLS = {"Attack", "Strength", "Defence", "Ranged", "Magic", "Hitpoints",
                 "Prayer", "Slayer", "Combat", "Combat level"}
VALID_SKILLS = {"Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic",
                "Runecraft", "Construction", "Hitpoints", "Agility", "Herblore",
                "Thieving", "Crafting", "Fletching", "Slayer", "Hunter", "Mining",
                "Smithing", "Fishing", "Cooking", "Firemaking", "Woodcutting", "Farming"}
CATEGORIES = ["Collecting", "Combat", "Processing", "Skilling", "Recurring", "Other"]


def http(url, cache_key):
    CACHE.mkdir(exist_ok=True)
    cf = CACHE / cache_key
    if cf.exists():
        return cf.read_text(encoding="utf-8")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(5):
        try:
            data = urllib.request.urlopen(req, timeout=90).read().decode("utf-8")
            cf.write_text(data, encoding="utf-8")
            return data
        except Exception:  # noqa: BLE001
            if attempt == 4:
                raise
            time.sleep(2 ** attempt)


def ge_mapping():
    by_name = {}
    for it in json.loads(http(MAPPING_URL, "mapping.json")):
        by_name.setdefault(it["name"], it["id"])
    return by_name


def strip_html(s):
    return re.sub(r"<[^>]+>", "", html.unescape(s)).strip()


def cells_of(row):
    return re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", row, re.S)


def parse_int(s):
    m = re.search(r"-?[0-9][0-9,]*", s or "")
    return int(m.group(0).replace(",", "")) if m else None


def main_tables():
    """subpage title -> summary dict from the main guide page's tables."""
    text = json.loads(http(WIKI + "?action=parse&page=Money_making_guide&prop=text&format=json",
                           "main.json"))["parse"]["text"]["*"]
    out = {}
    for tbl in re.findall(r"<table[^>]*sortable[^>]*>(.*?)</table>", text, re.S):
        rows = re.findall(r"<tr>(.*?)</tr>", tbl, re.S)
        header = [strip_html(c).lower() for c in cells_of(rows[0])] if rows else []
        recurring = "recurrence" in " ".join(header) or "effective" in " ".join(header)
        for row in rows[1:]:
            cells = cells_of(row)
            if not cells:
                continue
            link = re.search(r'href="/w/(Money_making_guide/[^"?#]+)"', cells[0])
            if not link:
                continue
            title = urllib.parse.unquote(link.group(1)).replace("_", " ")
            name = strip_html(cells[0])
            members = "Member" in cells[-1] or "member" in cells[-1].lower()
            entry = {"name": name, "members": members, "recurring": recurring}
            if recurring:
                # columns: Method, Profit, Time, Effective profit, Recurrence time
                entry["profit"] = parse_int(strip_html(cells[1]) if len(cells) > 1 else "")
                entry["time"] = strip_html(cells[2]) if len(cells) > 2 else ""
                entry["category"] = "Recurring"
                entry["intensity"] = ""
            else:
                # columns: Method, Hourly profit, Skills, Category, Intensity, Members
                entry["profit"] = parse_int(strip_html(cells[1]) if len(cells) > 1 else "")
                cat = strip_html(cells[3]).split("/")[0].strip() if len(cells) > 3 else ""
                entry["category"] = cat if cat in CATEGORIES else "Other"
                entry["intensity"] = strip_html(cells[4]) if len(cells) > 4 else ""
            out[title] = entry
    return out


def fetch_wikitext(titles):
    out = {}
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        url = (WIKI + "?action=query&prop=revisions&rvprop=content&rvslots=main&format=json"
               "&titles=" + urllib.parse.quote("|".join(batch)))
        d = json.loads(http(url, f"wt_{i:05d}.json"))
        for pg in d.get("query", {}).get("pages", {}).values():
            if "revisions" in pg:
                out[pg["title"]] = pg["revisions"][0]["slots"]["main"]["*"]
        time.sleep(0.05)
    return out


def template_body(wikitext, name):
    start = wikitext.find("{{" + name)
    if start < 0:
        return None
    depth = 0
    j = start
    while j < len(wikitext):
        if wikitext[j:j + 2] == "{{":
            depth += 1
            j += 2
        elif wikitext[j:j + 2] == "}}":
            depth -= 1
            j += 2
            if depth == 0:
                return wikitext[start:j]
        else:
            j += 1
    return wikitext[start:]


def split_params(body):
    inner = body[2:-2]
    parts, depth_c, depth_b, cur = [], 0, 0, []
    for ch in inner:
        if ch == "{":
            depth_c += 1
        elif ch == "}":
            depth_c = max(0, depth_c - 1)
        elif ch == "[":
            depth_b += 1
        elif ch == "]":
            depth_b = max(0, depth_b - 1)
        if ch == "|" and depth_c == 0 and depth_b == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    params = {}
    for p in parts[1:]:
        if "=" in p:
            k, v = p.split("=", 1)
            params[k.strip()] = v.strip()
    return params


def wiki_names(s):
    """[[a|b]] / [[a]] link targets in a string."""
    return [m.group(1).split("|")[0].strip() for m in re.finditer(r"\[\[([^\]]+)\]\]", s or "")]


def clean_item(raw):
    s = re.sub(r"\{\{[^}]*\}\}", "", raw)
    s = re.sub(r"\[\[([^\]|]*\|)?([^\]]*)\]\]", r"\2", s)
    s = re.sub(r"<[^>]+>", "", s)
    return s.strip()


def quest_names():
    d = json.loads((ROOT / "src/main/resources/data/quests.json").read_text())
    return {q["name"] for q in d["quests"]}


def parse_skills(field):
    """{{SCP|Skill|lvl}} → [(Skill, level, soft)]. The listed level is the hard
    FLOOR; a skill is soft only when its bullet line says "optional" or tags the
    level itself "recommended" WITHOUT a separate higher "(NN recommended)" —
    e.g. "{{SCP|Runecraft|44}} (91 recommended)" keeps 44 hard, 91 is advice."""
    out = []
    for line in re.split(r"\n|<br\s*/?>", field or ""):
        low = line.lower()
        higher_rec = re.search(r"\(\s*\d+\+?[^)]*recommend", low)  # a higher advised level
        for m in re.finditer(r"\{\{SCP\|([^|}]+)\|?([^|}]*)", line):
            skill = m.group(1).strip()
            lvl = parse_int(m.group(2))
            if lvl is None:
                continue
            soft = "optional" in low or ("recommend" in low and not higher_rec)
            out.append((skill, lvl, soft))
    return out


def build(method_id, title, summary, wt, mapping, quests):
    body = template_body(wt or "", "Mmgtable")
    p = split_params(body) if body else {}
    category = summary["category"]

    reqs, recommends = [], []
    # ── skills ──────────────────────────────────────────────────────────
    for skill, lvl, soft in parse_skills(p.get("Skill", "")):
        if skill in ("Combat", "Combat level"):
            recommends.append(f"combat:{lvl}")     # combat level is always soft
            continue
        if skill == "Quest":                       # {{SCP|Quest|30}} = 30 quest points
            (recommends if soft else reqs).append(f"qp:{lvl}")
            continue
        if skill not in VALID_SKILLS:
            continue                                # a non-skill SCP (Sailing?, typo) — skip
        leaf = f"skill:{skill}:{lvl}"
        # combat skills are recommendations (you can fight under-levelled), an
        # explicit optional/recommended tag is soft; every other skill level is
        # a hard gate (44 Runecraft, 85 Crafting …)
        if skill in COMBAT_SKILLS or soft:
            recommends.append(leaf)
        else:
            reqs.append(leaf)
    # ── quests (Quest field + access-gating "Completion of [[X]]") — only
    #    links that are REAL quests; "for the Ava's assembler" etc. are not ──
    quest_text = (p.get("Quest", "") + "\n" + p.get("Other", ""))
    for line in quest_text.split("\n"):
        optional = " for " in line.lower() or " to use" in line.lower() or " to access" in line.lower()
        for q in wiki_names(line):
            if q in quests:
                (recommends if optional else reqs).append(f"quest:{q}")
    # ── inputs (supplies / materials consumed) ─────────────────────────
    inputs = []
    for n in range(1, 40):
        key = f"Input{n}"
        if key not in p or not p[key].strip():
            continue
        name = clean_item(p[key])
        if not name:
            continue
        num = p.get(f"{key}num", "1")
        inputs.append({
            "name": name,
            "itemId": mapping.get(name, 0),
            "qty": num,
            "perHour": p.get(f"{key}isph", "").lower().startswith("y"),
        })
    # a representative icon: first resolvable input, else the profit item
    icon = next((i["itemId"] for i in inputs if i["itemId"]), 0)

    method = {
        "id": method_id,
        "name": summary["name"],
        "category": category,
        "intensity": summary.get("intensity", ""),
        "members": summary["members"],
        "recurring": summary["recurring"],
        "profit": summary.get("profit"),
        "wiki": title.replace(" ", "_"),
        "icon": icon,
        "reqs": dedupe(reqs),
        "recommends": dedupe(recommends),
        "inputs": inputs,
        "skillsText": strip_wiki(p.get("Skill", "")),
        "itemsText": strip_wiki(p.get("Item", "")),
        "otherText": strip_wiki(p.get("Other", "")),
    }
    if summary["recurring"]:
        method["time"] = summary.get("time", "")
    return method


def dedupe(seq):
    seen, out = set(), []
    for x in seq:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out


def strip_wiki(s):
    s = re.sub(r"\{\{SCP\|([^|}]+)\|?([^|}]*)[^}]*\}\}", r"\1 \2", s or "")
    s = re.sub(r"\{\{[^}]*\}\}", "", s)
    s = re.sub(r"\[\[([^\]|]*\|)?([^\]]*)\]\]", r"\2", s)
    s = re.sub(r"<[^>]+>", " ", s)
    s = re.sub(r"[*#]", "", s)
    return re.sub(r"\s+", " ", s).strip()


def slug(title):
    base = title.replace("Money making guide/", "")
    return re.sub(r"[^a-z0-9]+", "-", base.lower()).strip("-")


def main():
    print("Fetching main guide table…", file=sys.stderr)
    summaries = main_tables()
    print(f"  {len(summaries)} methods in the guide tables", file=sys.stderr)
    mapping = ge_mapping()
    quests = quest_names()
    print(f"  {len(mapping)} mapped items, {len(quests)} quests", file=sys.stderr)
    print("Fetching method subpages…", file=sys.stderr)
    wt = fetch_wikitext(list(summaries.keys()))

    methods, no_body = [], 0
    for title, summary in summaries.items():
        body_present = template_body(wt.get(title, ""), "Mmgtable") is not None
        if not body_present:
            no_body += 1
        methods.append(build(slug(title), title, summary, wt.get(title, ""), mapping, quests))
    methods.sort(key=lambda m: (m["profit"] is None, -(m["profit"] or 0)))

    out = {
        "$schema": "./schemas/money-making.schema.json",
        "source": "OSRS wiki Money making guide (main table + per-method {{Mmgtable}})",
        "generated": time.strftime("%Y-%m-%d", time.gmtime()),
        "gePricesAsOf": time.strftime("%Y-%m-%d", time.gmtime()),
        "version": 1,
        "methods": methods,
    }
    OUT.write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")

    from collections import Counter
    cats = Counter(m["category"] for m in methods)
    with_reqs = sum(1 for m in methods if m["reqs"])
    print(f"{len(methods)} methods → {OUT}", file=sys.stderr)
    print(f"  categories: {dict(cats)}", file=sys.stderr)
    print(f"  {with_reqs} have hard reqs, {no_body} had no Mmgtable body", file=sys.stderr)
    assert len(methods) > 400, f"too few methods: {len(methods)}"
    assert cats["Combat"] > 20 and cats["Skilling"] > 20, "category extraction looks off"


if __name__ == "__main__":
    main()
