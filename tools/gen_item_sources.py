#!/usr/bin/env python3
"""Generate data/item-sources.json — the universal per-item obtainment +
equip-requirement projection from the knowledge base (design/KB-RUNTIME.md).

Source: knowledge/knowledge.db (build it first: python3 tools/knowledge/rebuild.py).
The KB is the wiki ground truth — this generator only SELECTS and prunes:
best source per how (drop/shop/make/reward), max 4 per item; discontinued
(removal_date) and restricted-mode names excluded; rates/prices kept as the
wiki's own strings, never converted (display data, not math).

Equip reqs ride along as requirement-graph strings with their origin
(audited = the hand-audited gear-progression chains, extracted = the
validated prose extraction) so surfaces can phrase them honestly.

Fails fast on an unreadable DB, an unresolvable crown-jewel item, or count
floors; the pack is written only after every validation passes.
"""

import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "knowledge" / "knowledge.db"
OUT = ROOT / "src" / "main" / "resources" / "data" / "item-sources.json"

# game-mode/beta/discontinued variants (join_obtainment.py's filter)
RESTRICTED = re.compile(
    r"\((?:beta|Last Man Standing|Deadman(?: Mode)?|historical|"
    r"Trailblazer|Shattered Relics|Twisted League|Leagues)\b|^Dni\d|^Team-\d",
    re.I)

TIER_PROB = {"always": 1.0, "common": 1 / 20, "uncommon": 1 / 60,
             "rare": 1 / 500, "very rare": 1 / 5000}


def prob(rate):
    """Rank-only probability for a wiki rate string; 0 = unknown (ranks last)."""
    if not rate:
        return 0.0
    r = rate.strip().rstrip("[1]").strip()
    m = re.match(r"(\d+(?:\.\d+)?)\s*/\s*([\d,]+(?:\.\d+)?)", r)
    if m:
        denom = float(m.group(2).replace(",", ""))
        return float(m.group(1)) / denom if denom else 0.0
    m = re.match(r"(\d+(?:\.\d+)?)\s*%", r)
    if m:
        return float(m.group(1)) / 100
    return TIER_PROB.get(r.lower(), 0.0)


def display_from(source):
    """'Abyssal demon#Wilderness Slayer Cave' → 'Abyssal demon'."""
    return source.split("#", 1)[0].replace("_", " ").strip()


def best_drop(rows):
    rows = [r for r in rows if not RESTRICTED.search(r[0])]
    if not rows:
        return None
    rows.sort(key=lambda r: -prob(r[1]))
    src, rate = rows[0]
    out = {"how": "drop", "from": display_from(src)}
    if rate:
        out["rate"] = rate.strip()
    return out


def best_shop(rows):
    priced = []
    for shop, price, currency in rows:
        try:
            p = int(re.sub(r"[^\d]", "", price or ""))
        except ValueError:
            p = 1 << 30
        coins = (currency or "Coins").strip().lower() in ("coins", "")
        priced.append((not coins, p, shop, price, currency))
    priced.sort()
    coins_last, p, shop, price, currency = priced[0]
    out = {"how": "shop", "from": display_from(shop).rstrip(".")}
    if price:
        out["price"] = price.strip() + (
            "" if not coins_last else " " + (currency or "").strip().lower())
        if not coins_last:
            out["price"] += " gp"
    return out


def best_make(rows):
    """Lowest-gating-level recipe → 'Make: Herblore 25'."""
    ranked = []
    for skills_json in rows:
        try:
            skills = json.loads(skills_json) if skills_json else []
        except ValueError:
            skills = []
        gate = None
        for s in skills:
            try:
                lvl = int(s.get("level") or 0)
            except (TypeError, ValueError):
                lvl = 0
            if lvl and (gate is None or lvl > gate[1]):
                gate = (s.get("name"), lvl)
        ranked.append((gate[1] if gate else 0, gate))
    ranked.sort(key=lambda r: r[0])
    gate = ranked[0][1]
    out = {"how": "make"}
    if gate and gate[0]:
        out["skill"] = f"{gate[0]} {gate[1]}"
    return out


def reward_label(source):
    """'quest: Cook's Assistant' → 'Cook's Assistant (quest)';
    'diary: Ardougne Diary — Rewards' → 'Ardougne Diary'."""
    s = display_from(source)
    if s.startswith("quest: "):
        return s[7:] + " (quest)"
    if s.startswith("diary: "):
        return s[7:].split(" — ")[0].split(" - ")[0]
    return s


def curated_sources(obtain_json):
    """equipment.obtain is already a curated per-item join — keep best per how."""
    try:
        rows = json.loads(obtain_json)
    except ValueError:
        return []
    by_how = {}
    for r in rows:
        how = {"combat": "drop"}.get(r.get("how"), r.get("how"))
        if how not in ("drop", "shop", "make", "reward", "spell"):
            how = "other"
        frm = display_from(r.get("from") or "")
        if not frm or RESTRICTED.search(frm):
            continue
        entry = {"how": how, "from": frm}
        if r.get("rate"):
            entry["rate"] = str(r["rate"]).strip()
        if r.get("skill"):
            entry["skill"] = str(r["skill"]).strip()
        if r.get("price"):
            entry["price"] = str(r["price"]).strip()
        cur = by_how.get(how)
        if cur is None or (how == "drop" and prob(entry.get("rate")) > prob(cur.get("rate"))):
            by_how[how] = entry
    order = {"drop": 0, "reward": 1, "shop": 2, "make": 3, "spell": 4, "other": 5}
    return sorted(by_how.values(), key=lambda e: order[e["how"]])[:4]


def main():
    if not DB.exists():
        raise SystemExit(f"{DB} missing — run: python3 tools/knowledge/rebuild.py")
    conn = sqlite3.connect(DB)

    # removal filters at the ID level: LMS/holiday duplicates share the NAME
    # of real items (Eternal boots, Bandos godsword) — a name-level filter
    # deleted the real item along with its discontinued twin
    ids_by_name = {}
    for item_id, name, removal in conn.execute(
            "SELECT item_id, name, removal_date FROM items WHERE item_id IS NOT NULL"):
        try:
            item_id = int(item_id)
        except (TypeError, ValueError):
            continue  # bucket rows occasionally carry non-numeric ids
        if item_id > 0 and not (removal or "").strip():
            ids_by_name.setdefault(name, []).append(item_id)

    equipment = {name: (obtain, reqs, src, flags) for name, obtain, reqs, src, flags
                 in conn.execute("SELECT name, obtain, equip_reqs, src, flags FROM equipment")}

    drops = {}
    for item, source, rarity in conn.execute("SELECT item, source, rarity FROM drops"):
        drops.setdefault(item, []).append((source, rarity))
    shops = {}
    for item, shop, price, currency in conn.execute(
            "SELECT item, shop, price, currency FROM shop_stock"):
        shops.setdefault(item, []).append((shop, price, currency))
    recipes = {}
    for output, skills in conn.execute("SELECT output, skills FROM recipes"):
        recipes.setdefault(output, []).append(skills)
    rewards = {}
    for item, source in conn.execute("SELECT item, source FROM rewards"):
        rewards.setdefault(item, []).append(source)

    candidates = set(equipment) | set(drops) | set(shops) | set(recipes)
    entries = []
    for name in sorted(candidates):
        if RESTRICTED.search(name):
            continue
        ids = sorted(ids_by_name.get(name, []))
        if not ids:
            continue  # no item id = nothing the runtime can join on
        equip = equipment.get(name)
        if equip and equip[0]:
            sources = curated_sources(equip[0])
        else:
            sources = []
        have = {s["how"] for s in sources}
        if "drop" not in have and name in drops:
            d = best_drop(drops[name])
            if d:
                sources.append(d)
        if "shop" not in have and name in shops:
            sources.append(best_shop(shops[name]))
        if "make" not in have and name in recipes:
            sources.append(best_make(recipes[name]))
        if "reward" not in have and name in rewards:
            sources.append({"how": "reward", "from": reward_label(rewards[name][0])})
        seen_from = set()
        deduped = []
        for s in sources:
            key = s.get("from", s["how"])
            if key not in seen_from:
                seen_from.add(key)
                deduped.append(s)
        sources = deduped[:4]

        reqs = []
        origin = None
        if equip and equip[1]:
            reqs = json.loads(equip[1])
            flags = equip[3] or ""
            origin = ("audited" if "gear-progression" in (equip[2] or "")
                      else "extracted" if "reqs-extracted" in flags else "audited")
        if not sources and not reqs:
            continue
        entry = {"name": name, "ids": ids}
        if sources:
            entry["sources"] = sources
        if reqs:
            entry["reqs"] = reqs
            entry["reqsOrigin"] = origin
        entries.append(entry)

    conn.close()
    pack = {"version": 1,
            "provenance": "knowledge.db (wiki Bucket API dropsline/storeline/recipe"
                          " buckets, slot categories, prose req extraction)",
            "items": entries}

    # ── validation BEFORE the write (a mid-write exit must never truncate) ──
    by_name = {e["name"]: e for e in entries}
    whip = by_name.get("Abyssal whip")
    assert whip and 4151 in whip["ids"], "crown jewel: Abyssal whip missing"
    assert any(s["how"] == "drop" and s["from"] == "Abyssal demon"
               for s in whip["sources"]), f"whip drop source wrong: {whip}"
    assert "skill:Attack:70" in whip.get("reqs", []), f"whip reqs wrong: {whip}"
    rope = by_name.get("Rope")
    assert rope and any(s["how"] == "shop" for s in rope["sources"]), "Rope shop missing"
    assert "Ranarr weed" in by_name, "Ranarr weed missing"
    n_reqs = sum(1 for e in entries if e.get("reqs"))
    n_sources = sum(1 for e in entries if e.get("sources"))
    assert len(entries) >= 6000, f"suspiciously few entries: {len(entries)}"
    assert n_reqs >= 1000, f"suspiciously few req'd items: {n_reqs}"
    for e in entries:
        for r in e.get("reqs", []):
            assert re.match(r"(skillb?:[A-Za-z ]+:\d+|quest:[^:]+|item:.+|"
                            r"itemx:.+|unlock:[^:]+|any:.+|kc:.+|qp:\d+|"
                            r"diary:.+|combat:\d+|queststarted:.+)$", r), \
                f"unparseable req on {e['name']}: {r}"

    OUT.write_text(json.dumps(pack, indent=0, ensure_ascii=False) + "\n")
    size = OUT.stat().st_size
    print(f"item-sources.json: {len(entries)} items ({n_sources} sourced,"
          f" {n_reqs} with reqs), {size / 1024:.0f} KB raw")


if __name__ == "__main__":
    main()
