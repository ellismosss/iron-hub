#!/usr/bin/env python3
"""Harvest the wiki's entire Storeline bucket (every shop's stock: item,
shop, price, currency, stock) and the Collection log source bucket (the
wiki's OWN per-slot item_id -> sources/rates/kinds mapping — the exact
coverage Luke asked for on the collection log)."""

import json
import re

import kb

# ── point-shop prices ─────────────────────────────────────────────────
# The storeline bucket carries NO price for point-currency shops (verified
# against the live bucket 2026-07-23: store_buy_price is literally "N/A") —
# the costs live only in each shop's own wiki page table. The page fetch is
# keyed by the shop name itself, with aliases where the table lives on an
# activity page instead. None = skip whole shop (restricted modes, or the
# "shop" is really a drop table).
POINT_PAGE_ALIASES = {
    "Farmer Gricoller's Rewards": "Tithe Farm",
    "Dom Onion's Reward Shop": "Nightmare Zone",
    "Soul Wars Reward Shop": "Soul Wars/Rewards",
    "Mahogany Homes Reward Shop": "Mahogany Homes",
    "Chest (Theatre of Blood)": None,   # ToB uniques are drops, not purchases
    "Justine's stuff for the Last Shopper Standing": None,  # LMS
    "Leagues Reward Shop": None,        # leagues-restricted
    "Events Reward Shop": None,         # holiday events
    "Speedrunning Reward Shop": None,   # speedrun worlds
    "PvP Arena Rewards": None,          # PvP Arena points
}
# spot checks against hand-verified wiki prices — a parser drifting onto the
# wrong column must FAIL, never ship a wrong number
POINT_PRICE_PINS = {
    ("Farmer Gricoller's Rewards", "Seed box"): "250",
    ("Farmer Gricoller's Rewards", "Herb sack"): "250",
    ("Slayer Rewards", "Herb sack"): "750",
    ("Mahogany Homes Reward Shop", "Amy's saw"): "500",
    # NOTE: Culinaromancer's Chest states no price on ANY of its StoreLines
    # (Barrows gloves' 130k lives only in item prose) — it stays an honest
    # no-price row + a gap rather than an invented number.
}


def point_prices(conn):
    """Fill N/A prices for point-currency shops from their wiki page tables.
    Only fills EXISTING holes (join on the bucket's own item rows) and only
    from a numeric-only cell within two cells of the item's {{plink}} row —
    an unparseable shop keeps its honest N/A and gets a gap."""
    # every missing price, point-currency AND coin (1,169 coin rows were
    # blank too — Barrows gloves showed "Buy: Culinaromancer's Chest" with
    # no price at all)
    holes = {}
    for item, shop in conn.execute(
            "SELECT item, shop FROM shop_stock WHERE price IS NULL OR price=''"
            " OR price='N/A'"):
        holes.setdefault(shop, []).append(item)
    filled = 0
    for shop, items in sorted(holes.items()):
        page = POINT_PAGE_ALIASES.get(shop, shop)
        if page is None:
            continue
        try:
            text = kb.page_text(page)
            m = re.match(r"#REDIRECT\s*\[\[([^\]|#]+)", text or "", re.I)
            if m:
                text = kb.page_text(m.group(1).strip())
        except Exception:
            kb.add_gap(conn, "shops", shop, "point-prices",
                       f"no readable wiki page ('{page}') to parse point costs from")
            continue
        # format A: {{StoreLine|name=Herb sack|...|sell=750|...}} — sell= is
        # what the PLAYER pays at this shop (the bucket's store_buy_price is
        # the other direction, hence its N/A on point shops)
        storeline = {}
        for sm in re.finditer(r"\{\{StoreLine\s*\|([^{}]*)\}\}", text):
            params = dict(p.split("=", 1) for p in sm.group(1).split("|") if "=" in p)
            name = (params.get("name") or "").strip()
            sell = (params.get("sell") or "").strip().replace(",", "")
            if name and re.fullmatch(r"\d+", sell):
                storeline[name] = sell
        hits = 0
        for item in items:
            price = storeline.get(item)
            if price is None:
                # format B: a plink table row — cost = a numeric-ONLY cell
                # within the next two cells (time columns never match)
                m = re.search(r"\{\{plinkt?\|" + re.escape(item) + r"[|}]", text)
                if not m:
                    continue
                tail = text[m.end():m.end() + 400]
                cells = re.findall(r"\n\|\s*([^\n|]*)", tail)[:2]
                for cell in cells:
                    cm = re.fullmatch(r"([\d,]+)", cell.strip())
                    if cm:
                        price = cm.group(1).replace(",", "")
                        break
            if price is None:
                continue
            pin = POINT_PRICE_PINS.get((shop, item))
            assert pin is None or pin == price, \
                f"point-price parser drifted: {shop}/{item} = {price}, pinned {pin}"
            conn.execute("UPDATE shop_stock SET price = ?,"
                         " src = src || '+wiki:shop page' WHERE item = ?"
                         " AND shop = ?", (price, item, shop))
            hits += 1
        filled += hits
        if hits == 0:
            kb.add_gap(conn, "shops", shop, "point-prices",
                       f"page '{page}' fetched but no point costs parsed")
        print(f"  point prices: {shop} — {hits}/{len(items)} filled")
    for (shop, item), pin in POINT_PRICE_PINS.items():
        row = conn.execute("SELECT price FROM shop_stock WHERE shop=? AND item=?",
                           (shop, item)).fetchone()
        assert row and row[0] == pin, f"pinned point price missing: {shop}/{item}"
    conn.commit()
    print("point-shop prices filled:", filled)


def shops(conn):
    conn.execute("""
        CREATE TABLE IF NOT EXISTS shop_stock(
            item TEXT NOT NULL,
            shop TEXT NOT NULL,
            price TEXT, currency TEXT, stock TEXT,
            src TEXT)""")
    conn.execute("CREATE INDEX IF NOT EXISTS shop_item ON shop_stock(item)")
    conn.execute("DELETE FROM shop_stock")
    offset = 0
    n = 0
    while True:
        q = ("bucket('storeline').select('sold_item','sold_by','store_buy_price',"
             f"'store_currency','store_stock').offset({offset}).limit(5000).run()")
        data = kb.api({"action": "bucket", "query": q}, f"bucket-shops-{offset}.json")
        batch = data.get("bucket", [])
        conn.executemany(
            "INSERT INTO shop_stock(item,shop,price,currency,stock,src) VALUES(?,?,?,?,?,?)",
            [(r.get("sold_item") or "", r.get("sold_by") or "",
              r.get("store_buy_price"), r.get("store_currency"),
              r.get("store_stock"), "wiki:bucket storeline") for r in batch])
        n += len(batch)
        conn.commit()
        if len(batch) < 5000:
            break
        offset += 5000
    if n < 3000:
        raise SystemExit(f"suspiciously few store lines: {n}")
    print("store lines:", n)
    return n


def clog_sources(conn):
    offset = 0
    rows = []
    while True:
        q = ("bucket('collection_log_source').select('item_id','item_name',"
             f"'sources','rates','kinds').offset({offset}).limit(5000).run()")
        data = kb.api({"action": "bucket", "query": q}, f"bucket-clogsrc-{offset}.json")
        batch = data.get("bucket", [])
        rows.extend(batch)
        if len(batch) < 5000:
            break
        offset += 5000
    if len(rows) < 1000:
        raise SystemExit(f"suspiciously few clog source rows: {len(rows)}")
    updated = 0
    inserted = 0
    for r in rows:
        item_id = r.get("item_id")
        if item_id is None:
            continue
        sources = r.get("sources") or []
        rates = r.get("rates") or []
        pairs = "; ".join(
            (f"{rate} from {src}" if rate and rate not in ("N/A", "") else src)
            for src, rate in zip(sources, rates + [""] * len(sources)))
        hit = conn.execute("SELECT flags FROM clog_items WHERE item_id = ?",
                           (item_id,)).fetchone()
        if hit:
            flags = [f for f in (hit[0] or "").split(",")
                     if f and f != "source-missing"]
            conn.execute(
                "UPDATE clog_items SET activity = COALESCE(activity, ?),"
                " drop_rate = ?, src = src || '+wiki:clogsource', flags = ?"
                " WHERE item_id = ?",
                ("; ".join(sources[:6]) or None, pairs or None,
                 ",".join(flags) or None, item_id))
            updated += 1
        else:
            # the wiki knows a clog slot the plugin's pack does not — that IS
            # a data hole worth surfacing
            conn.execute(
                "INSERT INTO clog_items(item_id,name,activity,drop_rate,src,flags)"
                " VALUES(?,?,?,?,?,?)",
                (item_id, r.get("item_name"), "; ".join(sources[:6]) or None,
                 pairs or None, "wiki:clogsource", "missing-from-plugin-pack"))
            kb.add_gap(conn, "clog", f"{r.get('item_name')} (id {item_id})",
                       "pack-coverage",
                       "wiki collection-log source lists this slot; clog.json does not")
            inserted += 1
    print(f"clog sources: {updated} slots enriched, {inserted} slots the pack lacks")
    kb.set_progress(conn, "clog-items", None, "clog_items",
                    "pack:clog + wiki collection_log_source bucket",
                    f"wiki's own per-slot sources/rates joined; {inserted} slots"
                    " missing from clog.json flagged")


def main():
    conn = kb.db()
    shops(conn)
    point_prices(conn)
    clog_sources(conn)
    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
