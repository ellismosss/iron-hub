#!/usr/bin/env python3
"""Harvest raw wiki BUCKETS in their entirety (Luke's 2026-07-21 list) —
one KB table per bucket, named bucket_<name>, schema-driven: each bucket's
own Bucket:<Name> definition page supplies the field list, so a wiki-side
schema change flows through on the next run instead of silently dropping
columns. Lists/objects store as JSON text. Pagination advances by actual
batch size until an EMPTY batch — robust to any server-side row cap.

These are the RAW stores; the derived tables (equipment, materials, ...)
keep joining from them. Already-harvested buckets (dropsline, storeline,
collection_log_source, combat_achievement, infobox_item, recipe) keep
their dedicated harvesters."""

import json
import re

import kb

BUCKETS = [
    "bountytaskline", "couriertaskline", "quest", "infobox_monster",
    "recommended_equipment", "seachart", "varbit", "mine",
    "infobox_scenery", "locline", "infobox_spell", "drop_table_sources",
    "infobox_shop", "exchange", "ge_index_header", "infobox_bonuses",
    "infobox_construction",
]

# sanity floors — fail fast on a silently-empty harvest
FLOORS = {
    "bountytaskline": 50, "couriertaskline": 100, "quest": 150,
    "infobox_monster": 3000, "recommended_equipment": 100, "seachart": 50,
    "varbit": 2000, "mine": 100, "infobox_scenery": 3000, "locline": 5000,
    "infobox_spell": 200, "drop_table_sources": 50, "infobox_shop": 200,
    "exchange": 3000, "ge_index_header": 5, "infobox_bonuses": 3000,
    "infobox_construction": 300,
}


def bucket_fields(name: str):
    """The bucket's declared fields from its Bucket:<Name> schema page."""
    title = name[0].upper() + name[1:]
    text = kb._get(f"https://oldschool.runescape.wiki/w/Bucket:{title}?action=raw",
                   f"bucketdef-{name}.json")
    return list(json.loads(text).keys())


def main():
    conn = kb.db()
    for name in BUCKETS:
        fields = bucket_fields(name)
        cols = ["page_name"] + fields
        table = "bucket_" + name
        col_ddl = ", ".join(f'"{c}" TEXT' for c in cols)
        conn.execute(f'DROP TABLE IF EXISTS "{table}"')
        conn.execute(f'CREATE TABLE "{table}" ({col_ddl}, src TEXT)')
        offset = 0
        n = 0
        while True:
            q = ("bucket('{}').select({}).offset({}).limit(5000).run()".format(
                name, ",".join(f"'{f}'" for f in cols), offset))
            data = kb.api({"action": "bucket", "query": q},
                          f"bucketraw-{name}-{offset}.json")
            if "error" in data:
                raise SystemExit(f"{name}: {data['error']}")
            batch = data.get("bucket", [])
            if not batch:
                break
            rows = []
            for r in batch:
                row = []
                for c in cols:
                    v = r.get(c)
                    if isinstance(v, (list, dict)):
                        v = json.dumps(v, ensure_ascii=False)
                    elif v is not None:
                        v = str(v)
                    row.append(v)
                row.append("wiki:bucket " + name)
                rows.append(tuple(row))
            placeholders = ",".join("?" * (len(cols) + 1))
            conn.executemany(f'INSERT INTO "{table}" VALUES({placeholders})', rows)
            n += len(batch)
            conn.commit()
            offset += len(batch)
        if n == 0:
            # a DEFINED but unpopulated bucket (mine, 2026-07-21) — the wiki
            # hasn't migrated that data in yet; record it, don't die
            kb.add_gap(conn, "buckets", name, "empty",
                       "bucket exists wiki-side but holds zero rows — recheck"
                       " on a future rebuild")
            print(f"{name}: EMPTY wiki-side (gap recorded)")
            continue
        if n < FLOORS.get(name, 1):
            raise SystemExit(f"suspiciously few rows in {name}: {n}")
        kb.set_progress(conn, "bucket:" + name, None, f'"{table}"',
                        "wiki:bucket " + name,
                        f"raw bucket, {len(cols)} fields")
        print(f"{name}: {n} rows x {len(cols)} fields")
    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
