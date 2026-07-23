#!/usr/bin/env python3
"""Widen the QoL catalog beyond qol.json's 9 unlocks. "QoL" is a plugin
concept, not a wiki category, so the catalog is assembled from the wiki
families that fit the plugin's definition (life-easier utility unlocks):

- Category:Storage items (sacks, bags, pouches, barrels, kits)
- a curated list of one-off utility unlocks the game gates behind
  content (graceful, ava's devices, tool upgrades, ardy cloak line, ...)

Each row gets its effect (page lead), sources (drops/shops/recipe joins)
and requirements where stated. The CATALOG DEFINITION itself is flagged as
a standing question for Luke — add/remove families freely."""

import json

import kb

# one-off utility unlocks that aren't storage items; wiki page names.
# curated 2026-07-21 — extend freely, the harvest fills the details.
CURATED = [
    "Graceful outfit", "Ava's accumulator", "Ava's assembler", "Rogue equipment",
    "Bottomless compost bucket", "Magic secateurs", "Farming cape",
    "Ardougne cloak", "Explorer's ring", "Karamja gloves", "Fremennik sea boots",
    "Desert amulet", "Falador shield", "Fremennik sea boots 4", "Morytania legs",
    "Varrock armour", "Wilderness sword", "Western banner", "Rada's blessing",
    "Kandarin headgear", "Book of the dead", "Dramen staff", "Achievement diary cape",
    "Crystal saw", "Amy's saw", "Imcando hammer", "Bruma torch", "Infernal axe",
    "Infernal pickaxe", "Infernal harpoon", "Crystal harpoon", "Crystal axe",
    "Crystal pickaxe", "Dragon harpoon", "Lunar spellbook", "Arceuus spellbook",
    "Kourend castle teleport", "Royal seed pod", "Ectophial", "Camulet",
    "Fairy ring", "Spirit tree", "Gnome glider", "Quetzal whistle",
    "Steel key ring", "Master scroll book", "Rune pouch", "Looting bag",
    "Basic quetzal whistle", "Enhanced quetzal whistle", "Perfected quetzal whistle",
    "Small fur pouch", "Medium fur pouch", "Large fur pouch",
    "Gem pouch", "Gem satchel", "Gem tote", "Gem sack",
    "Small pouch", "Medium pouch", "Large pouch", "Giant pouch", "Colossal pouch",
    "Rogue mask", "Rogue top", "Rogue trousers", "Rogue gloves", "Rogue boots",
    "Plank sack", "Log basket", "Forestry kit",
]


def row_for(conn, title, text):
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
    sources = []
    made = kb.recipe(text)
    if made:
        sources.append({"how": "make", **made})
    for source, rarity in conn.execute(
            "SELECT source, rarity FROM drops WHERE item = ? LIMIT 6", (title,)).fetchall():
        sources.append({"how": "drop", "from": source, "rate": rarity})
    for shop, price in conn.execute(
            "SELECT shop, price FROM shop_stock WHERE item = ? COLLATE NOCASE LIMIT 4",
            (title,)).fetchall():
        sources.append({"how": "shop", "from": shop, "price": price})
    flags = []
    if not lead:
        flags.append("effect-missing")
    if not sources:
        flags.append("sources-unknown")
        kb.add_gap(conn, "qol", title, "sources",
                   "no recipe/drop/shop found — likely a quest/diary/minigame"
                   " reward; needs the reward-source pass")
    conn.execute(
        "INSERT OR REPLACE INTO qol_items(name,effect,sources,reqs,src,flags)"
        " VALUES(?,?,?,?,?,?)",
        (title, lead, json.dumps(sources) if sources else None,
         None, "wiki:" + title, ",".join(flags) or None))


def main():
    conn = kb.db()
    done = set()
    storage = kb.category_pages("Storage items")
    for title, text in storage:
        row_for(conn, title, text)
        done.add(title)
    print(f"storage items: {len(storage)}")
    curated_n = 0
    for title in CURATED:
        if title in done:
            continue
        try:
            text = kb.page_text(title)
        except SystemExit:
            kb.add_gap(conn, "qol", title, "wiki-page",
                       "curated QoL entry has no wiki page under this name")
            continue
        row_for(conn, title, text)
        curated_n += 1
    # keep the pack's own 9 (they overlap; INSERT OR REPLACE keeps wiki rows)
    kb.add_gap(conn, "qol", "(catalog definition)", "scope",
               "QoL is a plugin concept: current catalog = Category:Storage"
               " items + a curated utility list. Review what belongs —"
               " additions are one line in harvest_qol.py CURATED")
    kb.set_progress(conn, "qol-items", None, "qol_items",
                    "wiki Storage items + curated utility list",
                    f"{len(storage)} storage + {curated_n} curated unlocks;"
                    " catalog scope flagged for review")
    conn.commit()
    conn.close()
    print(f"qol rows: {len(storage) + curated_n}")


if __name__ == "__main__":
    main()
