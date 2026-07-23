#!/usr/bin/env python3
"""Harvest the wiki's infobox_item bucket — EVERY item in the game with
ids, examine, quest-item flag, tradeability. Serves as the universal
fallback join: collection-log what-it-does (by exact item id), quest-item
marking on equipment, and name<->id verification for the packs."""

import json

import kb


def main():
    conn = kb.db()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS items(
            item_id INTEGER,
            name TEXT,
            version TEXT,
            examine TEXT,
            quest_item TEXT,
            tradeable TEXT,
            members TEXT,
            removal_date TEXT,
            src TEXT,
            flags TEXT,
            PRIMARY KEY(item_id, name))""")
    conn.execute("CREATE INDEX IF NOT EXISTS items_name ON items(name)")
    conn.execute("DELETE FROM items")
    offset = 0
    n = 0
    while True:
        q = ("bucket('infobox_item').select('item_id','item_name','version_anchor',"
             "'examine','quest','tradeable','is_members_only','removal_date')"
             f".offset({offset}).limit(5000).run()")
        data = kb.api({"action": "bucket", "query": q}, f"bucket-items-v2-{offset}.json")
        batch = data.get("bucket", [])
        rows = []
        for r in batch:
            ids = r.get("item_id")
            if isinstance(ids, list):
                ids = ids
            elif ids is None:
                ids = [None]
            else:
                ids = [ids]
            for item_id in ids[:20]:
                rows.append((
                    item_id, r.get("item_name") or r.get("page_name"),
                    r.get("version_anchor"),
                    kb.strip_markup(r.get("examine") or "")[:300] or None,
                    kb.strip_markup(str(r.get("quest") or "")) or None,
                    str(r.get("tradeable") or "") or None,
                    str(r.get("is_members_only") or "") or None,
                    str(r.get("removal_date") or "") or None,
                    "wiki:bucket infobox_item"))
        conn.executemany(
            "INSERT OR IGNORE INTO items(item_id,name,version,examine,quest_item,"
            "tradeable,members,removal_date,src) VALUES(?,?,?,?,?,?,?,?,?)", rows)
        n += len(batch)
        conn.commit()
        if len(batch) < 5000:
            break
        offset += 5000
    if n < 10000:
        raise SystemExit(f"suspiciously few items: {n}")
    kb.set_progress(conn, "items-index", None, "items", "wiki:bucket infobox_item",
                    f"{n} item infoboxes — the universal id/examine/quest join")
    conn.commit()
    conn.close()
    print("item rows:", n)


if __name__ == "__main__":
    main()
