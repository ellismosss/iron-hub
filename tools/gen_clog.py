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
import hashlib
import json
import os
import re
import urllib.parse
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


WIKI_API = "https://oldschool.runescape.wiki/api.php"


def bucket_query(query: str) -> list:
    """Run a wiki Bucket API query, cached by the query itself (never a bare
    batch index — the stale-cache trap in DOMAIN-NOTES 'Generator hygiene')."""
    os.makedirs(CACHE, exist_ok=True)
    key = hashlib.sha1(query.encode()).hexdigest()[:16]
    cached = os.path.join(CACHE, f"bucket-{key}.json")
    if not os.path.exists(cached):
        url = WIKI_API + "?" + urllib.parse.urlencode(
            {"action": "bucket", "format": "json", "query": query})
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
        with open(cached, "wb") as f:
            f.write(body)
    with open(cached, encoding="utf-8") as f:
        data = json.load(f)
    if "bucket" not in data:
        raise SystemExit(f"bucket query failed: {data.get('error', data)!r}")
    return data["bucket"]


def fetch_wiki_clog_rates() -> dict:
    """item_id -> [(source, rate)] from the wiki's collection_log_source
    bucket — the same store the wiki's own clog pages render from."""
    rows, offset = [], 0
    while True:
        batch = bucket_query(
            "bucket('collection_log_source')"
            ".select('item_id','item_name','sources','rates','kinds')"
            f".offset({offset}).limit(5000).run()")
        if not batch:
            break
        rows.extend(batch)
        offset += len(batch)
    by_id = {}
    for row in rows:
        item_id = row.get("item_id")
        if item_id is None:
            continue
        pairs = list(zip(row.get("sources") or [], row.get("rates") or []))
        by_id.setdefault(item_id, []).extend(pairs)
    return by_id


def parse_rate(rate: str):
    """'1/50' -> 50.0, 'Always' -> 1.0, else None.

    'N × 1/M' is deliberately NOT parsed: on some pages it means N rolls
    (effective M/N), on others it annotates quantity (effective M) — the
    Corporeal Beast's '10 × 1/174,762.67' reads either way, so any row
    wearing it is adjudicated by hand, never auto-compared."""
    s = (rate or "").strip().replace(",", "").replace("~", "").lower()
    if s in ("always", "1/1"):
        return 1.0
    m = re.fullmatch(r"1/(\d+(?:\.\d+)?)", s)
    if m:
        return float(m.group(1))
    return None


_NORM_VERBS = re.compile(
    r"^(killing|opening|completing|defeating|catching|subduing|the)\s+")


def norm_name(name: str) -> str:
    s = re.sub(r"[\[\]]", "", (name or "")).lower()
    s = re.sub(r"[^a-z0-9]+", " ", s).strip()
    prev = None
    while prev != s:
        prev, s = s, _NORM_VERBS.sub("", s)
    return s


# Rows where the wiki's rate is the truth and the spreadsheet's is a
# proven error (wiki-adjudicated 2026-08-03; the silk row was a straight
# copy of Sarachnis cudgel's 1/384). Key (activity name, itemId) ->
# corrected attempts. The audit still compares these against the live
# wiki, so a wiki rebalance fails loudly here instead of silently
# pinning a stale figure.
CURATED_ATTEMPTS = {
    # Pristine spider silk is a DIRECT 1/50 tertiary from Sarachnis (not
    # in the Grubby Chest); 384 was Sarachnis cudgel's rate copied over.
    ("Killing sarachnis", 33133): 50.0,
    # One Bryophyta kill = one chest; essence is 1/118 (members and F2P).
    # 1770 = 118 x 15 matches no wiki mechanic.
    ("Killing bryophyta", 22372): 118.0,
    # One Obor kill = one chest; club is 1/118. Key farming is the
    # separate "Killing hill giants" row's model.
    ("Killing obor", 20756): 118.0,
    # Key chain is right, key rate wrong: giant key is 1/128 (not 1/120),
    # so keys-per-club = 128 x 118.
    ("Killing hill giants", 20756): 15104.0,
    # Calvar'ion's dragon pickaxe is 1/358; 1701 matches no wiki figure
    # or chain (Vet'ion is 1/256).
    ("Killing calvar'ion", 11920): 358.0,
}

# Plain-activity divergences adjudicated as CORRECT in the pack (the
# spreadsheet models something the wiki's flat per-source rate doesn't).
# Waives the divergence gate; reason strings are documentation.
# Key: (activity name, itemId).
DIVERGENCE_WAIVERS = {
    # Grubby-key chains: Sarachnis drops the key 1/15, undead druids
    # 1/75; the chest's egg sacs are 1/25 — 15x25=375, 75x25=1875.
    ("Killing sarachnis", 25844): "grubby key 1/15 x chest 1/25 = 375",
    ("Killing sarachnis", 25846): "grubby key 1/15 x chest 1/25 = 375",
    ("Killing undead druids", 25844): "grubby key 1/75 x chest 1/25 = 1875",
    ("Killing undead druids", 25846): "grubby key 1/75 x chest 1/25 = 1875",
    # Unit conversion: activity counts molch pearls (1/75 per catch at
    # high levels); tench is 1/20,000 per catch -> 20000/75 ≈ 267 pearls.
    ("Collecting pearls while aerial fishing", 22840):
        "pearls-per-tench: 20000 fish-rate / 75 pearl-rate",
    # Direct 1/32,768 + chewed bones 3/128 x pyre 1/256 = 4/32,768 =
    # exactly 1/8,192 — the ironman route the wiki's flat rate ignores.
    ("Killing mithril dragons", 11335):
        "combined direct + chewed-bones pyre route = 1/8192",
    # The wiki's own second listed rate: a ring of wealth removes the
    # single-coin slot from the special gem table (1/173,670.4; 1/10,335).
    ("Sorting through opulent salvage", 2366):
        "wiki's ring-of-wealth rate (standard strategy)",
    ("Sorting through opulent salvage", 1249):
        "wiki's ring-of-wealth rate (standard strategy)",
}

# Whole activities whose attempts deliberately live in different units
# from the wiki's per-source rates (adjudicated 2026-08-03).
WAIVED_ACTIVITIES = {
    "Opening beginner caskets":
        "wiki rates are per reward ROLL; the pack models per casket "
        "(multiple rolls) plus milestone scroll cases",
    "Opening easy caskets": "per-roll vs per-casket (2-4 rolls)",
    "Opening medium caskets": "per-roll vs per-casket (3-5 rolls)",
    "Opening hard caskets": "per-roll vs per-casket (4-6 rolls)",
    "Opening elite caskets": "per-roll vs per-casket (4-6 rolls)",
    "Opening master caskets": "per-roll vs per-casket (5-7 rolls)",
    "Waiting for random events":
        "each event's outfit piece is a CHOICE ('Always' on the wiki); "
        "the pack models events-until-this-piece across the event pool",
    "Earning soul wars zeal":
        "attempts are in ZEAL; a Spoils of war costs 30 zeal x 1/400",
    "Earning volcanic mine reward points":
        "attempts are in POINTS; ore pack cost x per-pack rate",
}


def audit_rates(out_activities: list) -> dict:
    """Cross-validate drop rows' `attempts` against the wiki's
    collection_log_source bucket.

    The upstream spreadsheet deliberately encodes strategy-adjusted
    figures — on-task rates ("Killing araxxor (on task)"), gamble
    modelling ("… and gambling cape"), per-kill units in multi-boss
    activities ("3 kills = 1 completion") — so the wiki cannot blindly
    override it. The contract instead: for a PLAIN activity (no
    parenthetical modelling qualifier), an unambiguous single wiki rate
    that diverges more than 2.5x is a hard generation failure unless the
    row carries a curated correction or an adjudicated waiver. That is
    the class of error that shipped Pristine spider silk at 1/384
    (Sarachnis cudgel's rate) against the wiki's 1/50."""
    wiki = fetch_wiki_clog_rates()
    violations, corrected, divergent, checked = [], [], [], 0
    for a in out_activities:
        act_norm = norm_name(a["name"])
        qualified = "(" in a["name"]
        for it in a["items"]:
            key = (a["name"], it["itemId"])
            if key in CURATED_ATTEMPTS:
                old = it["attempts"]
                it["attempts"] = CURATED_ATTEMPTS[key]
                corrected.append({"activity": a["name"], "item": it["name"],
                                  "old": old, "new": it["attempts"]})
            if it["attempts"] <= 1:
                continue  # purchase/threshold or guaranteed rows: no rate
            pairs = wiki.get(it["itemId"], [])
            cands = []
            for src, rate in pairs:
                denom = parse_rate(rate)
                if denom is not None:
                    cands.append((norm_name(src), denom, src, rate))
            matched = [c for c in cands
                       if c[0] and (c[0] in act_norm or act_norm in c[0])]
            if not matched and len(pairs) == 1 and len(cands) == 1:
                matched = cands  # the item's only wiki source: unambiguous
            denoms = {round(c[1], 2) for c in matched}
            if len(denoms) != 1:
                continue  # no unambiguous wiki figure — nothing to hold
            denom = denoms.pop()
            checked += 1
            ratio = max(denom, it["attempts"]) / max(1e-9, min(denom, it["attempts"]))
            if ratio <= 2.5:
                continue
            row = {"activity": a["name"], "itemId": it["itemId"],
                   "item": it["name"], "attempts": it["attempts"],
                   "wiki": denom, "rate": matched[0][3],
                   "source": matched[0][2], "ratio": round(ratio, 1)}
            if key in CURATED_ATTEMPTS:
                # chain-model corrections legitimately diverge from the flat
                # wiki rate (hill giants = key 1/128 x chest 1/118); surface
                # them in the report so a wiki rebalance stays visible
                divergent.append({**row, "waiver": "curated (see CURATED_ATTEMPTS)"})
            elif key in DIVERGENCE_WAIVERS:
                divergent.append({**row, "waiver": DIVERGENCE_WAIVERS[key]})
            elif a["name"] in WAIVED_ACTIVITIES:
                divergent.append({**row, "waiver": WAIVED_ACTIVITIES[a["name"]]})
            elif qualified:
                divergent.append(row)  # modelling-qualified: report only
            else:
                violations.append(row)
    # A broken fetch, parse or join must fail the generation rather than
    # silently skipping the audit.
    assert checked > 800, f"rate audit only compared {checked} rows"
    if violations:
        for v in violations:
            print(f"RATE VIOLATION: {v['activity']} / {v['item']}: "
                  f"pack 1/{v['attempts']} vs wiki {v['rate']} ({v['source']})")
        raise SystemExit(
            f"{len(violations)} plain-activity drop rates diverge >2.5x from "
            "an unambiguous wiki rate — adjudicate each into "
            "CURATED_ATTEMPTS or DIVERGENCE_WAIVERS")
    return {"checked": checked, "corrected": corrected,
            "divergent": divergent}


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

    audit = audit_rates(out_activities)
    report = os.path.join(CACHE, "rate-audit-report.json")
    with open(report, "w", encoding="utf-8") as f:
        json.dump(audit, f, indent=1, ensure_ascii=False)
    print(f"rate audit: {audit['checked']} rows compared vs wiki, "
          f"{len(audit['corrected'])} curated corrections applied, "
          f"{len(audit['divergent'])} modelling-qualified divergences "
          f"(see {report})")

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
