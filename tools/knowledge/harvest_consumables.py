#!/usr/bin/env python3
"""Harvest EVERY consumable: Category:Food, Category:Potions and
Category:Drinks bulk-fetched. Effects come from the page lead + healing
params; creation from {{Recipe}} templates (skills + materials); obtainment
marked from the wiki's own drop/shop category tags, with the detailed
per-monster rates arriving via the drops harvest. Anything without a
recipe AND without a drop/shop tag is flagged."""

import json
import re

import kb

CATEGORIES = {"Food": "food", "Potions": "potion", "Drinks": "drink"}


def recipe(text: str):
    boxes = kb.templates(text, "Recipe")
    if not boxes:
        return None
    box = boxes[0]
    skills = []
    for i in range(1, 6):
        name = box.get(f"skill{i}")
        if name:
            skills.append({"skill": name.strip().capitalize(),
                           "level": box.get(f"skill{i}lvl", "1"),
                           "boostable": box.get(f"skill{i}boostable", "")})
    mats = []
    for i in range(1, 11):
        mat = box.get(f"mat{i}")
        if mat:
            mats.append({"name": mat.strip(), "qty": box.get(f"mat{i}quantity", "1")})
    out = {"skills": skills, "materials": mats}
    if box.get("facilities"):
        out["facilities"] = kb.strip_markup(box["facilities"])
    return out if (skills or mats) else None


def main():
    conn = kb.db()
    total = 0
    seen = set()
    for category, kind in CATEGORIES.items():
        pages = kb.category_pages(category)
        print(f"Category:{category}: {len(pages)} pages")
        if len(pages) < 40:
            raise SystemExit(f"suspiciously few pages in {category}")
        for title, text in pages:
            if title in seen:
                continue
            seen.add(title)
            item_box = (kb.templates(text, "Infobox Item") or [{}])[0]
            made = recipe(text)
            obtain = []
            if made:
                obtain.append({"how": "make", "detail": "see make_reqs"})
            if "[[Category:Items dropped by monster]]" in text \
                    or "Category:Items dropped by monster" in text:
                obtain.append({"how": "drop", "detail": "monster drops (see drops harvest)"})
            if kb.templates(text, "Store locations list") or "{{StoreLine" in text:
                obtain.append({"how": "shop", "detail": "sold in stores"})
            # effect: healing param if present, else the lead sentence
            effects = []
            heal = item_box.get("heal") or item_box.get("heals")
            if heal:
                effects.append("Heals " + kb.strip_markup(heal))
            lead = None
            depth = 0
            for raw in text.split("\n"):
                line = raw.strip()
                depth += line.count("{{") - line.count("}}")
                if depth > 0 or not line:
                    continue
                if line.startswith(("{", "|", "=", "[[File:", "[[Category:", "<", "#", "*", "__")):
                    continue
                candidate = kb.strip_markup(line)
                if len(candidate) > 20:
                    lead = candidate[:400]
                    break
            if lead:
                effects.append(lead)
            flags = []
            if not obtain:
                flags.append("obtain-unknown")
                kb.add_gap(conn, "consumables", title, "obtain",
                           "no recipe, drop tag or store line found on the page")
            if not effects:
                flags.append("effects-missing")
            conn.execute(
                "INSERT OR REPLACE INTO consumables(name,kind,effects,make_reqs,obtain,src,flags)"
                " VALUES(?,?,?,?,?,?,?)",
                (title, kind, " · ".join(effects) or None,
                 json.dumps(made) if made else None,
                 json.dumps(obtain) if obtain else None,
                 "wiki:" + category, ",".join(flags) or None))
            total += 1
        conn.commit()
    kb.set_progress(conn, "consumables", len(seen), "consumables",
                    "wiki Food/Potions/Drinks categories",
                    f"{total} consumables; per-monster drop rates join via drops harvest")
    conn.close()
    print("consumables:", total)


if __name__ == "__main__":
    main()
