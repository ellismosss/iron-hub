#!/usr/bin/env python3
"""Generate data/clog.json from the Log Adviser plugin's data tables.

Log Adviser (github.com/SFranciscoSouza/LogAdviser, BSD-2-Clause — see
licenses/log-adviser-LICENSE) ranks every collection-log activity by
Time-To-Next-Slot; its bundled JSON is generated from a maintained
community spreadsheet. We convert its four tables into one Iron Hub pack:

  activities.json           -> activities[] with the IRONMAN completions/hr
                               (this plugin only targets irons), category,
                               and the drop table (from activity_map.json)
  activity_requirements.json-> per-activity requirement-graph strings
                               ("skill:Slayer:85", "quest:Priest in Peril")
                               so gating runs through com.ironhub.requirements
  slots.json                -> every log slot (itemId + name; chat-message
                               and widget-harvest resolution)
  ItemAliases.java          -> body-type-B item ids -> canonical slot ids,
                               plus chat names that never match a slotName

Iron-only conversions baked in:
  - Merchant's paint (32110) is auto-completed for irons (Log Adviser's
    IRON_AUTO_COMPLETED); its activity_map rows are dropped, and any
    activity left without items is dropped entirely.

Every quest token is validated against data/quests.json so a rename in
either source fails the build here, not silently in the client.

Usage:
  python3 tools/gen_clog.py

Fetches the pinned Log Adviser commit from raw.githubusercontent.com,
cached under tools/.cache-clog/ (gitignored).
"""
import datetime
import json
import os
import urllib.request

REPO = "SFranciscoSouza/LogAdviser"
COMMIT = "c76521ea055aeba0ce8eab98e9f33c813b8e1785"  # plugin-hub pinned commit
BASE = f"https://raw.githubusercontent.com/{REPO}/{COMMIT}/src/main/resources/com/logadviser/data/"
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
CACHE = os.path.join(os.path.dirname(__file__), ".cache-clog")
OUT = "src/main/resources/data/clog.json"
QUESTS = "src/main/resources/data/quests.json"

# Slots only an ironman auto-completes as a by-product of other logs
# (Log Adviser's AdviserEngine.IRON_AUTO_COMPLETED).
IRON_AUTO_COMPLETED = {32110}  # Merchant's paint

# Ported from Log Adviser's ItemAliases.java: Body-type-B item id -> the
# canonical Body-type-A id that slots.json carries (Tithe Farm outfit).
ALIASES = {13641: 13640, 13643: 13642, 13645: 13644, 13647: 13646}
# Chat-notification names that never match a slotName verbatim
# (slots.json names the slot "Farmer's shirt/jacket").
CHAT_NAMES = {"farmer's jacket": 13642, "farmer's shirt": 13642}


def fetch(name: str) -> object:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, name)
    if not os.path.exists(cached):
        req = urllib.request.Request(BASE + name, headers={"User-Agent": UA})
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
        with open(cached, "wb") as f:
            f.write(body)
    with open(cached, encoding="utf-8") as f:
        return json.load(f)


def convert_reqs(raw: dict, quest_names: set) -> list:
    reqs = []
    for skill, level in raw.get("skills", {}).items():
        reqs.append(f"skill:{skill.capitalize()}:{level}")
    for quest in raw.get("quests", []):
        if quest.lower() not in quest_names:
            raise SystemExit(f"quest token not in quests.json: {quest!r}")
        reqs.append(f"quest:{quest}")
    return reqs


def main():
    activities = fetch("activities.json")
    activity_map = fetch("activity_map.json")
    slots = fetch("slots.json")
    requirements = fetch("activity_requirements.json")

    quest_names = {q["name"].lower() for q in json.load(open(QUESTS))["quests"]}

    items_by_activity = {}
    for row in activity_map:
        if row["itemId"] in IRON_AUTO_COMPLETED:
            continue
        items_by_activity.setdefault(row["activityIndex"], []).append({
            "itemId": row["itemId"],
            "name": row["itemName"],
            "requiresPrevious": row["requiresPrevious"],
            "exact": row["exact"],
            "independent": row["independent"],
            "attempts": row["dropRateAttempts"],
        })

    out_activities = []
    for a in activities:
        items = items_by_activity.get(a["index"])
        if not items:
            continue  # nothing an iron can chase here
        raw_req = requirements.get(str(a["index"]), {})
        out_activities.append({
            "index": a["index"],
            "name": a["name"],
            "perHour": a["completionsPerHrIron"],
            "extraTimeFirst": a["extraTimeFirst"],
            "category": a["category"],
            "reqs": convert_reqs(raw_req, quest_names),
            "items": items,
        })

    out_slots = [{"itemId": s["itemId"], "name": s["slotName"]} for s in slots]

    slot_ids = {s["itemId"] for s in out_slots}
    for alt, canonical in ALIASES.items():
        assert canonical in slot_ids, f"alias target {canonical} not a slot"
    for name, item_id in CHAT_NAMES.items():
        assert item_id in slot_ids, f"chat name target {item_id} not a slot"
    assert len(out_activities) > 200, f"only {len(out_activities)} activities"
    assert len(out_slots) > 1000, f"only {len(out_slots)} slots"

    pack = {
        "source": f"github.com/{REPO}@{COMMIT[:7]} (BSD-2-Clause), spreadsheet-derived",
        "generated": datetime.date.today().isoformat(),
        "activities": out_activities,
        "slots": out_slots,
        "aliases": [{"alt": k, "canonical": v} for k, v in sorted(ALIASES.items())],
        "chatNames": [{"name": k, "itemId": v} for k, v in sorted(CHAT_NAMES.items())],
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    total_items = sum(len(a["items"]) for a in out_activities)
    print(f"wrote {OUT}: {len(out_activities)} activities, "
          f"{total_items} drop rows, {len(out_slots)} slots")


if __name__ == "__main__":
    main()
