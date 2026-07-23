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

# reclaim shops sell BACK what the player already earned — never a way of
# obtaining anything (2026-07-23: 27 items incl. the imbued god capes had
# "Lost Property shop" as their only source)
RECLAIM_SHOPS = ("Lost Property",)

# the 12 diaries' actual reward item families — anything ELSE a diary
# Rewards section links is benefit prose, not a reward (Pharaoh's sceptre
# was labelled "Reward: Desert Diary" because the diary improves it)
DIARY_REWARD_FAMILIES = (
    "Karamja gloves", "Ardougne cloak", "Desert amulet", "Explorer's ring",
    "Falador shield", "Fremennik sea boots", "Kandarin headgear",
    "Morytania legs", "Varrock armour", "Western banner", "Wilderness sword",
    "Rada's blessing", "Antique lamp")

# famous non-monster drop containers → the activity that owns them, for
# display context ("Grand Gold Chest" means nothing without Pyramid Plunder)
CONTAINER_ACTIVITY = {
    "Grand Gold Chest": "Pyramid Plunder",
    "Golden Chest": "Pyramid Plunder",
}

# storeline currency values → display copy
CURRENCY_DISPLAY = {
    "Tithe": "Tithe Farm points",
    "NMZ": "Nightmare Zone points",
    "Points": "points",
}

# How the RUNTIME reads a currency balance, so "buy it for N" becomes a
# real, progress-tracked requirement instead of a dead end. itemId =
# counted from bank+inventory; varbit = the game's own balance counter
# (gameval VarbitID constants, verified present in the API — never a
# guessed id). A currency with NEITHER stays an honest manual step.
CURRENCY_SOURCES = {
    "Coins": {"itemId": 995, "name": "Coins"},
    "Golden nugget": {"itemId": 12012, "name": "Golden nuggets"},
    "Molch pearl": {"itemId": 22820, "name": "Molch pearls"},
    "Hallowed mark": {"itemId": 24711, "name": "Hallowed marks"},
    "Abyssal pearls": {"itemId": 26792, "name": "Abyssal pearls"},
    "Pieces of eight": {"itemId": 8951, "name": "Pieces of eight"},
    "Mark of grace": {"itemId": 11849, "name": "Marks of grace"},
    "Tithe": {"varbit": 4893, "name": "Tithe Farm points"},   # HOSIDIUS_TITHE_REWARDPOINTS
    "NMZ": {"varbit": 3949, "name": "Nightmare Zone points"},  # NZONE_CURRENTPOINTS
}
# "Points" means a different currency per shop — resolve by shop, and only
# where the balance is genuinely readable
SHOP_CURRENCY = {
    "Slayer Rewards": {"varbit": 4068, "name": "Slayer reward points"},
    "Mahogany Homes Reward Shop": {"name": "Mahogany Homes points"},
    "Void Knights' Reward Options": {"name": "Void Knight commendation points"},
    "Mairin's Market": {"name": "Tempoross reward permits"},
}


# names with many ids where the lowest is NOT the one the game counts
CANONICAL_IDS = {"Coins": 995}


def currency_meta(shop, currency, price):
    """The machine-readable side of a price: what to count and how many."""
    qty = None
    if price and re.search(r"\d", price):
        qty = int(re.sub(r"[^\d]", "", price))
    base = SHOP_CURRENCY.get(shop) if (currency or "").strip() in ("Points", "") \
        else CURRENCY_SOURCES.get((currency or "Coins").strip())
    if base is None:
        base = CURRENCY_SOURCES.get((currency or "Coins").strip())
    if base is None or not qty:
        return None  # free (0 gp, Diango's holiday reclaims): nothing to gate on
    out = dict(base)
    out["qty"] = qty
    return out


def price_text(price, currency):
    """'250' + 'Tithe' → '250 Tithe Farm points'; no digits → None (honest)."""
    if not price or not re.search(r"\d", price):
        return None
    p = price.strip()
    if re.fullmatch(r"0+", p):
        return "free"
    if re.fullmatch(r"\d+", p):
        p = f"{int(p):,}"  # 99000 gp reads as 99,000 gp
    cur = (currency or "Coins").strip()
    if cur.lower() in ("coins", ""):
        return p + " gp"
    disp = CURRENCY_DISPLAY.get(cur, cur)
    if not disp.endswith("s") and p not in ("1",):
        disp += "s"
    return p + " " + disp

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


def shop_rows(rows):
    """Up to 2 shop options: the cheapest coin shop, and the best point shop
    (both when they coexist — herb sack is 250 Tithe points OR 750 slayer
    points; the player picks). Reclaim shops are excluded at load."""
    priced = []
    for shop, price, currency in rows:
        try:
            p = int(re.sub(r"[^\d]", "", price or ""))
        except ValueError:
            p = 1 << 30
        coins = (currency or "Coins").strip().lower() in ("coins", "")
        priced.append((not coins, p, shop, price, currency))
    priced.sort()
    out = []
    for coins_last, p, shop, price, currency in priced:
        shop_name = display_from(shop).rstrip(".")
        entry = {"how": "shop", "from": shop_name}
        text = price_text(price, currency)
        if text:
            entry["price"] = text
        meta = currency_meta(shop_name, currency, price)
        if meta:
            entry["currency"] = meta
        if any(o["from"] == entry["from"] for o in out):
            continue
        out.append(entry)
        if len(out) == 2:
            break
    return out


def make_rows(rows, name, ids_by_name=None):
    """Up to 3 recipe variants, each with its actual materials — never a
    bare '(see recipe)'. Imbue-style recipes (no skill gate, point-cost
    materials) surface as their own honest options."""
    variants = []
    for materials_json, skills_json in rows:
        try:
            materials = json.loads(materials_json) if materials_json else []
        except ValueError:
            materials = []
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
        parts = []
        mats = []
        for m in materials:
            mat = (m.get("name") or "").strip()
            if not mat:
                continue
            qty = str(m.get("quantity") or "").strip()
            try:
                q = int(qty.replace(",", ""))
            except ValueError:
                q = 1
            parts.append((f"{q:,} x " if q > 1 else "") + mat)
            # STRUCTURED materials: the UI renders one sprite row per
            # material instead of one clumped sentence, and the planner
            # turns them into real gather steps (Luke, 2026-07-23)
            row = {"name": mat, "qty": q}
            ids = (ids_by_name or {}).get(mat)
            if mat in CANONICAL_IDS:
                row["itemId"] = CANONICAL_IDS[mat]
            elif ids:
                row["itemId"] = sorted(ids)[0]
            mats.append(row)
        entry = {"how": "make"}
        if gate and gate[0]:
            entry["skill"] = f"{gate[0]} {gate[1]}"
        if parts:
            entry["detail"] = " + ".join(parts)
        if mats:
            entry["materials"] = mats
        detail = entry.get("detail")
        # note↔item conversions are bank mechanics, not recipes
        if detail in (name, name + " note"):
            continue
        # a skill-only row ("Make: Crafting 85") is still honest; only a row
        # with NEITHER skill nor materials — the old "(see recipe)" — dies
        if not detail and not entry.get("skill"):
            continue
        key = (entry.get("skill"), detail)
        if key not in {(v.get("skill"), v.get("detail")) for v in variants}:
            variants.append(entry)
    # cheapest gate first; detail-less recipes never made the list
    variants.sort(key=lambda v: int((v.get("skill") or "x 0").rsplit(" ", 1)[1]))
    return variants[:3]


def reward_label(source):
    """'quest: Cook's Assistant' → 'Cook's Assistant (quest)';
    'miniquest: Mage Arena II' → 'Mage Arena II (miniquest)';
    'diary: Ardougne Diary — Rewards' → 'Ardougne Diary'."""
    s = display_from(source)
    if s.startswith("quest: "):
        return s[7:] + " (quest)"
    if s.startswith("miniquest: "):
        return s[11:] + " (miniquest)"
    if s.startswith("diary: "):
        return s[7:].split(" — ")[0].split(" - ")[0]
    return s


def best_reward(name, sources):
    """Quest rewards outrank diary rows, and a diary row only counts for the
    12 diaries' actual reward items — everything else a diary Rewards
    section mentions is benefit prose."""
    quests = [s for s in sources if s.startswith(("quest: ", "miniquest: "))]
    if quests:
        return quests[0]
    diaries = [s for s in sources if s.startswith("diary: ")
               and name.startswith(DIARY_REWARD_FAMILIES)]
    return diaries[0] if diaries else None


def curated_sources(obtain_json):
    """equipment.obtain is already a curated per-item join — keep best per how."""
    try:
        rows = json.loads(obtain_json)
    except ValueError:
        return []
    by_how = {}
    for r in rows:
        how = {"combat": "drop"}.get(r.get("how"), r.get("how"))
        if how not in ("drop", "shop", "make", "reward", "spell", "open"):
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
        if any(r in (shop or "") for r in RECLAIM_SHOPS):
            continue
        shops.setdefault(item, []).append((shop, price, currency))
    recipes = {}
    for output, materials, skills in conn.execute(
            "SELECT output, materials, skills FROM recipes"):
        recipes.setdefault(output, []).append((materials, skills))
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
        # curated equipment rows predate the reclaim/reward fixes — same
        # filters; their shop/make rows are dropped whole (the enriched
        # shop_stock + recipes tables rebuild them with prices and details
        # the old join never had)
        sources = [s for s in sources if s["how"] not in ("shop", "make")]
        sources = [s for s in sources
                   if not any(r in (s.get("from") or "") for r in RECLAIM_SHOPS)]
        sources = [s for s in sources
                   if not (s["how"] == "reward"
                           and s.get("from", "").endswith("Diary")
                           and not name.startswith(DIARY_REWARD_FAMILIES))]
        have = {s["how"] for s in sources}
        if "drop" not in have and name in drops:
            d = best_drop(drops[name])
            if d:
                sources.append(d)
        if "shop" not in have and name in shops:
            sources.extend(shop_rows(shops[name]))
        if "make" not in have and name in recipes:
            sources.extend(make_rows(recipes[name], name, ids_by_name))
        if "reward" not in have and name in rewards:
            r = best_reward(name, rewards[name])
            if r:
                sources.append({"how": "reward", "from": reward_label(r)})
        for s in sources:
            frm = s.get("from")
            # a "drop" whose source is an ITEM is a container you open
            # (impling jars, caskets) — never a kill and never a "reward"
            if frm in ids_by_name and s["how"] in ("drop", "reward", "other"):
                s["how"] = "open"
            # famous activity containers carry their activity for context
            if frm in CONTAINER_ACTIVITY:
                s["from"] = CONTAINER_ACTIVITY[frm] + " — " + frm
        seen_from = set()
        deduped = []
        for s in sources:
            key = s.get("from", s.get("detail", s["how"]))
            if key not in seen_from:
                seen_from.add(key)
                deduped.append(s)
        sources = deduped[:5]

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
    pack = {"version": 2,
            "provenance": "knowledge.db (wiki Bucket API dropsline/storeline/recipe"
                          " buckets, slot categories, prose req extraction;"
                          " v2: recipe details, point prices, open/container"
                          " classification, reclaim shops excluded)",
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

    # ── the 2026-07-23 data-quality fixes stay fixed ──────────────────
    for e in entries:
        for s in e.get("sources", []):
            assert "Lost Property" not in (s.get("from") or ""), \
                f"reclaim shop leaked: {e['name']}"
    scep = by_name["Pharaoh's sceptre"]
    assert not any("Diary" in (s.get("from") or "") for s in scep["sources"]), \
        f"sceptre still credits a diary: {scep['sources']}"
    assert any("Pyramid Plunder" in (s.get("from") or "") for s in scep["sources"]), \
        f"sceptre lost its activity context: {scep['sources']}"
    salve = by_name["Salve amulet(ei)"]
    assert any(s["how"] == "make" and "Nightmare Zone points" in s.get("detail", "")
               for s in salve["sources"]), f"salve(ei) imbue recipe missing: {salve['sources']}"
    glory = by_name["Amulet of glory"]
    assert any(s["how"] == "make" and "Dragonstone amulet" in s.get("detail", "")
               for s in glory["sources"]), f"glory recipe detail missing: {glory['sources']}"
    assert any(s["how"] == "open" for s in glory["sources"]), \
        f"glory impling jar not an open source: {glory['sources']}"
    seed_box = by_name["Seed box"]
    assert any("250 Tithe Farm points" in (s.get("price") or "")
               for s in seed_box["sources"]), f"seed box price: {seed_box['sources']}"
    gem_bag = by_name["Gem bag"]
    assert any("100 Golden nuggets" in (s.get("price") or "")
               for s in gem_bag["sources"]), f"gem bag price: {gem_bag['sources']}"
    herb_sack = by_name["Herb sack"]
    assert sum(1 for s in herb_sack["sources"] if s["how"] == "shop") == 2, \
        f"herb sack should offer both point shops: {herb_sack['sources']}"
    cape = by_name.get("Imbued saradomin cape")
    assert cape and any("Mage Arena II" in (s.get("from") or "")
                        for s in cape["sources"]), f"imbued cape: {cape}"
    assert not any(s["how"] == "make" and not s.get("detail") and not s.get("skill")
                   for e in entries for s in e.get("sources", [])), \
        "a bare '(see recipe)' make row survived"
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
