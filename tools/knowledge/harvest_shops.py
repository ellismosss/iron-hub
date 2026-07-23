#!/usr/bin/env python3
"""Harvest the wiki's entire Storeline bucket (every shop's stock: item,
shop, price, currency, stock) and the Collection log source bucket (the
wiki's OWN per-slot item_id -> sources/rates/kinds mapping — the exact
coverage Luke asked for on the collection log)."""

import json

import kb


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
    clog_sources(conn)
    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
