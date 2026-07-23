#!/usr/bin/env python3
"""Harvest EVERY recipe in the game (the wiki's recipe bucket — the same
structured store the {{Recipe}} templates feed) and derive the MATERIALS
table Luke asked for: every resource/material, where it comes from
(gathering recipes, drops, shops, rewards), how it is processed and what
it turns into with required quantities.

recipes:   one row per production (output, qty, materials+qty, skills,
           facilities, tools, ticks) — includes gathering actions (mining
           rocks, fishing spots) which have skills but no materials.
materials: one row per distinct substance seen as a recipe INPUT or
           OUTPUT, with obtained-from and used-in joins. A material with
           no obtainment path at all lands in gaps."""

import html
import json

import kb


def clean_name(name: str) -> str:
    # bucket rows carry HTML entities ("Ahrim&#39;s hood") — unescape or
    # every such material becomes a phantom sourceless duplicate
    return html.unescape((name or "").strip())


def fetch_recipes():
    rows = []
    offset = 0
    while True:
        q = ("bucket('recipe').select('page_name','production_json')"
             f".offset({offset}).limit(5000).run()")
        data = kb.api({"action": "bucket", "query": q}, f"bucket-recipes-{offset}.json")
        batch = data.get("bucket", [])
        rows.extend(batch)
        if len(batch) < 5000:
            break
        offset += 5000
    if len(rows) < 2000:
        raise SystemExit(f"suspiciously few recipes: {len(rows)}")
    return rows


def main():
    conn = kb.db()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS recipes(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            page TEXT,
            output TEXT,
            output_qty TEXT,
            materials TEXT,     -- JSON [{name, quantity}]
            skills TEXT,        -- JSON [{name, level, experience, boostable}]
            facilities TEXT,
            tools TEXT,
            ticks TEXT,
            members INTEGER,
            src TEXT,
            flags TEXT);
        CREATE INDEX IF NOT EXISTS recipes_output ON recipes(output);
        CREATE TABLE IF NOT EXISTS materials(
            name TEXT PRIMARY KEY,
            obtained TEXT,      -- JSON: gathering recipes, drops, shops, rewards
            used_in TEXT,       -- JSON: [{makes, qty_needed, skill}]
            makes_count INTEGER,
            src TEXT,
            flags TEXT);
        DELETE FROM recipes;
        DELETE FROM materials;
        """)
    rows = fetch_recipes()
    n = 0
    used_in = {}    # material -> [{makes, qty, skill}]
    produced = {}   # output -> [recipe summary]
    for r in rows:
        try:
            p = json.loads(r.get("production_json") or "{}")
        except json.JSONDecodeError:
            kb.add_gap(conn, "recipes", r.get("page_name") or "?", "production_json",
                       "unparseable production_json on the recipe bucket row")
            continue
        output = (p.get("output") or {})
        if isinstance(output, str):
            output = {"name": output}
        out_name = clean_name(output.get("name") or p.get("name") or r.get("page_name") or "")
        mats = [{"name": clean_name(m.get("name", "")), "quantity": m.get("quantity", "1")}
                for m in p.get("materials") or [] if m.get("name")]
        skills = [{k: s.get(k) for k in ("name", "level", "experience", "boostable")}
                  for s in p.get("skills") or []]
        skill_label = ", ".join(f"{s['name']} {s['level']}" for s in skills if s.get("name"))
        conn.execute(
            "INSERT INTO recipes(page,output,output_qty,materials,skills,facilities,"
            "tools,ticks,members,src,flags) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (r.get("page_name"), out_name or None, str(output.get("quantity") or ""),
             json.dumps(mats) if mats else None,
             json.dumps(skills) if skills else None,
             kb.strip_markup(str(p.get("facilities") or "")) or None,
             kb.strip_markup(str(p.get("tools") or "")) or None,
             str(p.get("ticks") or "") or None,
             1 if p.get("members") else 0,
             "wiki:bucket recipe", None))
        n += 1
        for m in mats:
            used_in.setdefault(m["name"], []).append(
                {"makes": out_name or r.get("page_name"),
                 "qty_needed": m["quantity"], "skill": skill_label or None})
        if out_name:
            produced.setdefault(out_name, []).append(
                {"how": "make" if mats else "gather/action",
                 "from": r.get("page_name"), "skill": skill_label or None,
                 "qty": str(output.get("quantity") or "1")})
    conn.commit()
    print(f"recipes: {n}")

    # the materials table: every substance seen as input or output
    names = sorted(set(used_in) | set(produced))
    no_source = 0
    for name in names:
        obtained = list(produced.get(name, []))[:8]
        # anchored version names ("A stone bowl#Empty") fall back to the
        # base page name for the source joins
        candidates = [name] if "#" not in name else [name, name.split("#")[0]]
        for cand in candidates:
            if len(obtained) > 8:
                break
            for source, rarity in conn.execute(
                    "SELECT source, rarity FROM drops WHERE item = ? LIMIT 5", (cand,)):
                obtained.append({"how": "drop", "from": source, "rate": rarity})
            for shop, price in conn.execute(
                    "SELECT shop, price FROM shop_stock WHERE item = ? COLLATE NOCASE"
                    " LIMIT 3", (cand,)):
                obtained.append({"how": "shop", "from": shop, "price": price})
            for (source,) in conn.execute(
                    "SELECT source FROM rewards WHERE item = ? COLLATE NOCASE LIMIT 3",
                    (cand,)):
                obtained.append({"how": "reward", "from": source})
            if obtained:
                break
        uses = used_in.get(name, [])
        flags = None
        if not obtained:
            flags = "obtain-unknown"
            no_source += 1
        conn.execute(
            "INSERT OR REPLACE INTO materials(name,obtained,used_in,makes_count,src,flags)"
            " VALUES(?,?,?,?,?,?)",
            (name, json.dumps(obtained) if obtained else None,
             json.dumps(uses[:40]) if uses else None, len(uses),
             "derived: recipes+drops+shops+rewards", flags))
    conn.commit()
    # only gap the SOURCELESS INPUTS (a sourceless output name is usually a
    # display-name variant; inputs someone must acquire are the real holes)
    for name in names:
        if name in used_in and not produced.get(name):
            row = conn.execute("SELECT flags FROM materials WHERE name=?",
                               (name,)).fetchone()
            if row and row[0] == "obtain-unknown":
                kb.add_gap(conn, "materials", name, "obtain",
                           "used as a recipe input but no gathering recipe, drop,"
                           " shop or reward source found")
    kb.set_progress(conn, "recipes", None, "recipes", "wiki:bucket recipe",
                    f"{n} productions incl. gathering actions")
    kb.set_progress(conn, "materials", len(names), "materials",
                    "derived from recipes + drops/shops/rewards joins",
                    f"{len(names)} distinct materials; {no_source} with no known source")
    conn.commit()
    conn.close()
    print(f"materials: {len(names)} ({no_source} without a source)")


if __name__ == "__main__":
    main()
