#!/usr/bin/env python3
"""Fill the collection log's what-it-does column: equipment effect prose
(name join) first, else the item's own examine (EXACT item-id join via the
infobox_item bucket), plus a gates column from what the plugin's own packs
gate on the item (gear progression, QoL, diary/quest requirement mentions).
Slots that still have nothing stay flagged effect-missing."""

import json

import kb


def main():
    conn = kb.db()
    # which pack requirements mention which item names (what the item GATES)
    gate_mentions = {}
    gear = kb.pack("gear-progression")
    for phase in gear["phases"]:
        for group in phase["groups"]:
            for item in group["items"]:
                for req in item.get("requirements") or []:
                    if req.startswith(("item:", "itemx:")):
                        parts = req.split(":")
                        label = parts[3] if len(parts) > 3 else None
                        if label:
                            gate_mentions.setdefault(label.lower(), set()).add(
                                "gear: " + item["name"])
    filled = 0
    still = 0
    for (item_id, name, flags) in conn.execute(
            "SELECT item_id, name, flags FROM clog_items").fetchall():
        effect = None
        eq = conn.execute(
            "SELECT effects, examine FROM equipment WHERE name = ? COLLATE NOCASE",
            (name,)).fetchone()
        if eq and (eq[0] or eq[1]):
            effect = eq[0] or eq[1]
        if not effect:
            it = conn.execute(
                "SELECT examine FROM items WHERE item_id = ?", (item_id,)).fetchone()
            if it and it[0]:
                effect = it[0]
        if not effect:
            it = conn.execute(
                "SELECT examine FROM items WHERE name = ? COLLATE NOCASE LIMIT 1",
                (name,)).fetchone()
            if it and it[0]:
                effect = it[0]
        gates = sorted(gate_mentions.get((name or "").lower(), []))
        new_flags = [f for f in (flags or "").split(",") if f]
        if effect:
            new_flags = [f for f in new_flags if f != "effect-missing"]
            filled += 1
        else:
            still += 1
            kb.add_gap(conn, "clog", f"{name} (id {item_id})", "what-it-does",
                       "no equipment effect and no examine matched by id or name")
        conn.execute(
            "UPDATE clog_items SET what_it_does = ?, gates = ?, flags = ?"
            " WHERE item_id = ?",
            (effect, "; ".join(gates) or None, ",".join(new_flags) or None, item_id))
    conn.commit()
    kb.set_progress(conn, "clog-items", None, "clog_items",
                    "pack:clog + wiki clogsource + dropsline + infobox_item",
                    f"what-it-does filled for {filled}/{filled + still} slots"
                    " (equipment effects else examine); gates from pack joins")
    conn.close()
    print(f"clog what-it-does: {filled} filled, {still} still missing")


if __name__ == "__main__":
    main()
