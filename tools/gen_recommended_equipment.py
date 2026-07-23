#!/usr/bin/env python3
"""Generate data/recommended-equipment.json — the wiki's own
{{Recommended equipment}} tables for every activity, from the
recommended_equipment bucket (Luke's word, 2026-07-21: the offline
replacement for the old per-page wiki-strategy fetch path).

Each activity carries its slot -> ranked item lists (the wiki's own
best-first ordering, the same tables WikiStrategy used to parse live) and
the style tab where the page splits by combat style. Entries resolve to
item ids via the item-name index + the unfiltered itemids.txt dump;
set-names/notes that are not items keep name-only rows (honest).

Usage: python3 tools/gen_recommended_equipment.py
"""

import json
import os
import re
import urllib.parse
import urllib.request

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-recequip")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data",
                   "recommended-equipment.json")
ITEM_INDEX = os.path.join(HERE, "..", "src", "main", "resources", "data",
                          "index", "item-names.json")
ITEMIDS_TXT = os.path.join(HERE, "itemids.txt")
WIKI_API = "https://oldschool.runescape.wiki/api.php"

SLOTS = ["head", "cape", "neck", "ammo", "weapon", "shield", "body",
         "legs", "hands", "feet", "ring", "special"]


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


def bucket_rows():
    rows, offset = [], 0
    while True:
        q = ("bucket('recommended_equipment').select('page_name','json')"
             f".offset({offset}).limit(5000).run()")
        url = WIKI_API + "?" + urllib.parse.urlencode(
            {"action": "bucket", "format": "json", "query": q})
        data = json.loads(fetch(url, f"bucket-{offset}.json"))
        batch = data.get("bucket", [])
        rows.extend(batch)
        if not batch:
            break
        offset += len(batch)
    if len(rows) < 400:
        raise SystemExit(f"suspiciously few recommended-equipment rows: {len(rows)}")
    return rows


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
    for c in (base, "_" + base, base + "4", base + "_4"):
        if c in by_const:
            return by_const[c]
    key = base.replace("_", "")
    if key in collapsed:
        return collapsed[key]
    return None


def entry_name(raw: str):
    """One ranked cell -> the item/page name (link target beats label)."""
    m = re.search(r"link=([^|\]}]+)", raw)
    if m:
        return m.group(1).strip()
    m = re.search(r"\[\[([^\]|#]+)", raw)
    if m:
        return m.group(1).strip()
    text = re.sub(r"<[^>]+>", "", raw)
    text = re.sub(r"\{\{[^}]*\}\}", "", text)
    return re.sub(r"\s+", " ", text).strip() or None


def main():
    index = item_index()
    activities = []
    resolved = unresolved = 0
    for row in bucket_rows():
        try:
            data = json.loads(row.get("json") or "{}")
        except json.JSONDecodeError:
            continue
        table = data.get("Recommended Equipment") or {}
        slots = {}
        for slot, entries in table.items():
            slot_key = slot.strip().lower()
            if not isinstance(entries, list):
                continue
            ranked = []
            seen = set()
            for raw in entries:
                name = entry_name(str(raw))
                if not name or name.lower() in seen:
                    continue
                seen.add(name.lower())
                item_id = resolve_item(index, name)
                out = {"name": name}
                if item_id is not None:
                    out["itemId"] = item_id
                    resolved += 1
                else:
                    unresolved += 1
                ranked.append(out)
            if ranked:
                slots[slot_key] = ranked
        if not slots:
            continue
        activity = {"page": row["page_name"], "slots": slots}
        style = data.get("style")
        if style:
            activity["style"] = str(style)
        activities.append(activity)
    if len(activities) < 400:
        raise SystemExit(f"suspiciously few activities: {len(activities)}")
    pack = {
        "$schema": "./schemas/recommended-equipment.schema.json",
        "version": 1,
        "source": "oldschool.runescape.wiki recommended_equipment bucket",
        "activities": sorted(activities, key=lambda a: (a["page"], a.get("style", ""))),
    }
    with open(os.path.abspath(OUT), "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(activities)} activities ({resolved} entries id-resolved,"
          f" {unresolved} name-only) -> {os.path.abspath(OUT)}")


if __name__ == "__main__":
    main()
