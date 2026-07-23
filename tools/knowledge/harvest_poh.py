#!/usr/bin/env python3
"""Harvest EVERY PoH furniture piece: Category:Furniture bulk-fetched, each
page's {{Infobox Construction}} parsed (level, room, materials, xp,
flatpack). Versioned infoboxes (level1/level2...) emit one row per version.
Pages without the infobox land in gaps. This covers the full catalog, not
just the plugin's 39-tier useful ladder."""

import json
import re

import kb


def versions(box: dict):
    """Split a possibly-versioned infobox into per-version param dicts."""
    ids = sorted({m.group(1) for k in box for m in [re.fullmatch(r"version(\d+)", k)] if m})
    if not ids:
        return [("", box)]
    out = []
    for v in ids:
        merged = {k: val for k, val in box.items() if not re.search(r"\d+$", k)}
        for k, val in box.items():
            if k.endswith(v) and not k.startswith("version"):
                merged[k[:-len(v)]] = val
        out.append((box.get("version" + v, "v" + v), merged))
    return out


# materials/skills live in the page's {{Recipe}} templates (the same
# grammar as items — the infobox has no materials param at all; the first
# harvest read 0/658 before the ornate-box sample caught it)


def main():
    conn = kb.db()
    pages = kb.category_pages("Furniture")
    print(f"Category:Furniture: {len(pages)} pages")
    if len(pages) < 300:
        raise SystemExit("suspiciously few furniture pages")
    rows = 0
    for title, text in pages:
        boxes = kb.templates(text, "Infobox Construction")
        if not boxes:
            kb.add_gap(conn, "poh", title, "infobox",
                       "furniture page without Infobox Construction — needs a look")
            continue
        made = kb.recipe(text)
        mats = made["materials"] if made else []
        extra_reqs = [f"{s['skill']} {s['level']}"
                      + (" (boostable)" if str(s.get("boostable", "")).lower().startswith("y") else "")
                      for s in (made["skills"] if made else [])
                      if s["skill"] != "Construction"]
        for box in boxes:
            for vname, params in versions(box):
                name = params.get("name", title) or title
                if vname and vname not in name:
                    name = f"{title} ({vname})" if name == title else name
                level = None
                lm = re.search(r"\d+", params.get("level", ""))
                if lm:
                    level = int(lm.group())
                xp = None
                xm = re.search(r"[\d.]+", params.get("experience", "").replace(",", ""))
                if xm:
                    try:
                        xp = float(xm.group())
                    except ValueError:
                        pass
                room = kb.strip_markup(params.get("room", "")) or None
                flags = []
                if level is None:
                    flags.append("level-missing")
                if not mats:
                    flags.append("materials-missing")
                if flags:
                    kb.add_gap(conn, "poh", name, ",".join(flags),
                               "no parsed level/Recipe materials on the page — check "
                               + title)
                conn.execute(
                    "INSERT OR REPLACE INTO poh_furniture(name,room,level,materials,xp,"
                    " flatpackable,reqs,effects,src,flags) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (name, room, level, json.dumps(mats) if mats else None, xp,
                     1 if params.get("flatpack", "").lower().startswith("yes") else 0,
                     json.dumps(extra_reqs) if extra_reqs else None,
                     None, "wiki:" + title, ",".join(flags) or None))
                rows += 1
    kb.set_progress(conn, "poh-furniture", len(pages), "poh_furniture",
                    "wiki:Category:Furniture",
                    f"{len(pages)} pages -> {rows} furniture rows (versions split)")
    conn.commit()
    conn.close()
    print(f"done: {rows} furniture rows")


if __name__ == "__main__":
    main()
