#!/usr/bin/env python3
"""Seed the knowledge base from the plugin's existing data packs.

Everything lands with src='pack:<name>' so the wiki harvests can diff
against it — pack data is a STARTING POINT here, never assumed complete
(Luke's brief: the plugin is full of data holes).
"""

import json

import kb


def diaries(conn):
    d = kb.pack("diaries")
    n = 0
    for region in d["regions"]:
        for tier in region["tiers"]:
            for task in tier["tasks"]:
                reqs = json.dumps([r.get("req") or r.get("text") for r in task.get("reqs") or []])
                conn.execute(
                    "INSERT OR REPLACE INTO diary_tasks(region,tier,task,reqs,notes,src,flags)"
                    " VALUES(?,?,?,?,?,?,?)",
                    (region["name"], tier["tier"], task["task"], reqs,
                     task.get("note"), "pack:diaries", None))
                n += 1
    kb.set_progress(conn, "diary-tasks", 492, "diary_tasks", "pack:diaries",
                    "wiki+QuestHelper generated; wiki re-verify pending")
    return n


def boat(conn):
    d = kb.pack("boat-upgrades")
    types = {-1: "any", 0: "raft", 1: "skiff", 2: "sloop"}
    for u in d["upgrades"]:
        conn.execute(
            "INSERT OR REPLACE INTO boat_upgrades(part,tier,boat_type,reqs,materials,effects,src,flags)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (u["part"], u["name"], types.get(u["boatType"], str(u["boatType"])),
             json.dumps([f"skillb:Sailing:{u['sailing']}", f"skillb:Construction:{u['construction']}"]
                        + (u.get("reqs") or [])
                        + ([f"unlock:schematic_{u['schematic']}"] if u.get("schematic") else [])),
             json.dumps(u.get("materials") or []),
             next((p.get("benefit") for p in d["parts"] if p["key"] == u["part"]), None),
             "pack:boat-upgrades", None))
    kb.set_progress(conn, "boat-upgrades", 119, "boat_upgrades", "pack:boat-upgrades",
                    "wiki-audited at generation (per-tier pages verified)")
    return len(d["upgrades"])


def money(conn):
    d = kb.pack("money-making")
    flagged = 0
    for m in d["methods"]:
        inputs = m.get("inputs") or []
        outputs = m.get("outputs") or []
        flags = None
        if not inputs and m.get("category") not in ("Collecting",):
            flags = "inputs-missing"
            flagged += 1
        conn.execute(
            "INSERT OR REPLACE INTO money_methods(method,gp_hr,category,intensity,reqs,inputs,outputs,src,flags)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (m["name"], str(m.get("profit")), m.get("category"), m.get("intensity"),
             json.dumps({"hard": m.get("reqs") or [], "recommended": m.get("recommends") or []}),
             json.dumps(inputs), json.dumps(outputs), "pack:money-making", flags))
    kb.set_progress(conn, "money-methods", len(d["methods"]), "money_methods",
                    "pack:money-making (wiki-generated 2026-07-19)")
    return len(d["methods"])


def qol(conn):
    d = kb.pack("qol")
    for u in d["unlocks"]:
        conn.execute(
            "INSERT OR REPLACE INTO qol_items(name,effect,sources,reqs,src,flags)"
            " VALUES(?,?,?,?,?,?)",
            (u["name"], None, None, json.dumps(u.get("requirements") or []),
             "pack:qol", "effect-missing,sources-missing"))
    # the pack is 9 items — the game has dozens; the harvest must widen this
    kb.add_gap(conn, "qol", "(coverage)", "catalog",
               "qol.json holds only 9 unlocks; full QoL catalog needs harvesting"
               " (sacks, jewellery, graceful, teleports, tool upgrades, ...)")
    kb.set_progress(conn, "qol-items", None, "qol_items", "pack:qol",
                    "KNOWN-PARTIAL: 9 unlocks only")
    return len(d["unlocks"])


def boosts(conn):
    d = kb.pack("boosts")
    n = 0
    for b in d["boosts"]:
        for skill in b["skills"]:
            conn.execute(
                "INSERT OR REPLACE INTO boosts(name,skill,amount,circumstances,reqs,src,flags)"
                " VALUES(?,?,?,?,?,?,?)",
                (b["name"], skill, str(b.get("boost")),
                 b.get("note"), json.dumps(b.get("reqs") or []), "pack:boosts", None))
            n += 1
    kb.add_gap(conn, "boosts", "(coverage)", "catalog",
               "boosts.json holds 10 boost sources; the wiki Temporary skill boost"
               " tables list far more — full harvest required")
    kb.set_progress(conn, "boosts", None, "boosts", "pack:boosts",
                    "KNOWN-PARTIAL: 10 sources only")
    return n


def clog(conn):
    d = kb.pack("clog")
    by_id = {}
    for a in d["activities"]:
        for item in a["items"]:
            row = by_id.setdefault(item["itemId"], {
                "name": item["name"], "activities": [], "rates": []})
            row["activities"].append(a["name"])
            if item.get("attempts"):
                row["rates"].append(f"~1/{item['attempts']:.0f} of {a['name']}")
    slot_names = {s["itemId"]: s["name"] for s in d["slots"]}
    n = 0
    for item_id, name in slot_names.items():
        row = by_id.get(item_id)
        conn.execute(
            "INSERT OR REPLACE INTO clog_items(item_id,name,activity,drop_rate,what_it_does,gates,src,flags)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (item_id, name,
             "; ".join(row["activities"]) if row else None,
             "; ".join(row["rates"]) if row else None,
             None, None, "pack:clog",
             ("effect-missing" + ("" if row else ",source-missing"))))
        n += 1
    kb.set_progress(conn, "clog-items", len(slot_names), "clog_items", "pack:clog",
                    "slots+rates imported; what-it-does/gates join pending")
    return n


def gear_seed(conn):
    """The 189 wiki-audited gear-progression entries seed equipment reqs —
    the full equipment harvest overlays stats/obtainment on top."""
    d = kb.pack("gear-progression")
    n = 0
    for phase in d["phases"]:
        for group in phase["groups"]:
            for item in group["items"]:
                conn.execute(
                    "INSERT INTO equipment(name,item_ids,equip_reqs,src,flags)"
                    " VALUES(?,?,?,?,?)"
                    " ON CONFLICT(name) DO UPDATE SET equip_reqs=excluded.equip_reqs,"
                    " src=equipment.src || '+pack:gear-progression'",
                    (item["name"], json.dumps([item.get("itemId")]),
                     json.dumps(item.get("requirements") or []),
                     "pack:gear-progression", "stats-missing"))
                n += 1
    return n


def main():
    conn = kb.db()
    print("diary tasks:", diaries(conn))
    print("boat upgrades:", boat(conn))
    print("money methods:", money(conn))
    print("qol:", qol(conn))
    print("boost rows:", boosts(conn))
    print("clog items:", clog(conn))
    print("gear-progression seeds:", gear_seed(conn))
    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
