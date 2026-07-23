#!/usr/bin/env python3
"""Harvest the wiki's ENTIRE dropsline bucket — every {{DropsLine}} in the
game (monster kills, reward chests, activities), structured: item, source,
rarity, quantity, drop type, rolls. This is the obtainment ground truth the
equipment/consumable/clog tables join against."""

import json

import kb


def main():
    conn = kb.db()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS drops(
            item TEXT NOT NULL,
            source TEXT NOT NULL,
            rarity TEXT,
            quantity TEXT,
            drop_type TEXT,
            rolls TEXT,
            src TEXT
        )""")
    conn.execute("CREATE INDEX IF NOT EXISTS drops_item ON drops(item)")
    conn.execute("DELETE FROM drops")
    offset = 0
    n = 0
    while True:
        q = ("bucket('dropsline').select('item_name','drop_json','page_name_sub')"
             f".offset({offset}).limit(5000).run()")
        data = kb.api({"action": "bucket", "query": q}, f"bucket-drops-{offset}.json")
        batch = data.get("bucket", [])
        rows = []
        for row in batch:
            try:
                d = json.loads(row.get("drop_json") or "{}")
            except json.JSONDecodeError:
                d = {}
            rows.append((
                row.get("item_name") or d.get("Dropped item") or "",
                d.get("Dropped from") or row.get("page_name_sub") or "",
                d.get("Rarity"), str(d.get("Drop Quantity") or ""),
                d.get("Drop type"), str(d.get("Rolls") or ""),
                "wiki:bucket dropsline"))
        conn.executemany(
            "INSERT INTO drops(item,source,rarity,quantity,drop_type,rolls,src)"
            " VALUES(?,?,?,?,?,?,?)", rows)
        n += len(batch)
        conn.commit()
        print(f"  {n} drop lines...")
        if len(batch) < 5000:
            break
        offset += 5000
    if n < 20000:
        raise SystemExit(f"suspiciously few drop lines: {n}")
    conn.execute("""
        INSERT INTO progress(category, expected, harvested, flagged, source, updated_at, notes)
        VALUES('drops', NULL, ?, 0, 'wiki:bucket dropsline', datetime('now'),
               'every DropsLine in the game — the obtainment join source')
        ON CONFLICT(category) DO UPDATE SET harvested=excluded.harvested,
        updated_at=excluded.updated_at""", (n,))
    conn.commit()
    conn.close()
    print("drop lines:", n)


if __name__ == "__main__":
    main()
