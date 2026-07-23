#!/usr/bin/env python3
"""Verify the pack-sourced tables against the live wiki, flagging drift
rather than overwriting (the packs are generated; a mismatch is either
wiki churn or a pack bug — Luke decides which):

- boat upgrades: each upgrade's own page {{Recipe}} materials vs the pack
- money methods: the wiki money_making_guide bucket's value vs the pack
- diary tasks: the 12 diary pages' task strings vs the 492 pack rows
"""

import json
import re

import kb

DIARY_PAGES = {
    "Ardougne": "Ardougne Diary", "Desert": "Desert Diary",
    "Falador": "Falador Diary", "Fremennik": "Fremennik Diary",
    "Kandarin": "Kandarin Diary", "Karamja": "Karamja Diary",
    "Kourend & Kebos": "Kourend & Kebos Diary",
    "Lumbridge & Draynor": "Lumbridge & Draynor Diary",
    "Morytania": "Morytania Diary", "Varrock": "Varrock Diary",
    "Western Provinces": "Western Provinces Diary",
    "Wilderness": "Wilderness Diary",
}


def norm(s):
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def boat(conn):
    pack = kb.pack("boat-upgrades")
    drift = 0
    for u in pack["upgrades"]:
        page = u.get("page")
        if not page:
            continue
        try:
            text = kb.page_text(page)
        except SystemExit:
            kb.add_gap(conn, "boat", u["name"], "wiki-page",
                       f"pack page '{page}' no longer resolves")
            drift += 1
            continue
        # one part page carries a Recipe PER BOAT TYPE (skiff 4 bars, sloop
        # 8) — the pack row matches ONE of them; drift only when it matches
        # none (comparing against just the first recipe false-flagged 53
        # rows as doubled quantities)
        all_boxes = kb.templates(text, "Recipe")
        wiki_variants = []
        for box in all_boxes:
            mats = {}
            for i in range(1, 11):
                mat = box.get(f"mat{i}")
                if mat:
                    mats[norm(mat)] = str(box.get(f"mat{i}quantity", "1"))
            if mats:
                wiki_variants.append(mats)
        pack_mats = {norm(m["name"]): str(m["qty"]) for m in u.get("materials") or []}
        if wiki_variants and pack_mats and pack_mats not in wiki_variants:
            kb.add_gap(conn, "boat", u["name"], "materials-drift",
                       f"pack {json.dumps(sorted(pack_mats.items()))} matches none of"
                       f" the page's {len(wiki_variants)} recipe versions on '{page}'")
            conn.execute("UPDATE boat_upgrades SET flags='wiki-drift' WHERE tier=?",
                         (u["name"],))
            drift += 1
    print(f"boat: {drift} drift flags across {len(pack['upgrades'])} upgrades")
    kb.set_progress(conn, "boat-upgrades", 119, "boat_upgrades",
                    "pack:boat-upgrades, wiki-verified",
                    f"per-page Recipe diff: {drift} drift flags")


def money(conn):
    rows = []
    offset = 0
    while True:
        q = ("bucket('money_making_guide').select('page_name','value','recurring')"
             f".offset({offset}).limit(5000).run()")
        data = kb.api({"action": "bucket", "query": q}, f"bucket-mmg-{offset}.json")
        batch = data.get("bucket", [])
        rows.extend(batch)
        if len(batch) < 5000:
            break
        offset += 5000
    wiki = {r["page_name"].replace("Money making guide/", "").replace("_", " "): r
            for r in rows if r.get("page_name")}
    pack = kb.pack("money-making")
    missing = 0
    for m in pack["methods"]:
        if m["name"] not in wiki and m["wiki"].replace("Money_making_guide/", "").replace("_", " ") not in wiki:
            missing += 1
    extra = len(wiki) - (len(pack["methods"]) - missing)
    note = (f"wiki bucket lists {len(wiki)} methods, pack has {len(pack['methods'])};"
            f" {missing} pack methods gone from the wiki, ~{extra} wiki methods"
            " not in the pack (pack filters account-doable — expected)")
    if missing > 20:
        kb.add_gap(conn, "money", "(pack freshness)", "coverage", note)
    print("money:", note)
    kb.set_progress(conn, "money-methods", len(wiki), "money_methods",
                    "pack:money-making vs wiki mmg bucket", note)


def diaries(conn):
    """Per-tier task COUNTS vs the wiki pages. Prose matching is hopeless —
    the pack carries the JOURNAL wording, the wiki pages shortened map text
    ("View Aleck's Hunter Emporium" vs "... in Yanille."), so a text diff
    false-flagged 209 tasks. A changed COUNT is the real drift signal
    (tasks added/removed by an update)."""
    drift = 0
    for region, page in DIARY_PAGES.items():
        try:
            text = kb.page_text(page)
        except SystemExit:
            kb.add_gap(conn, "diary", region, "wiki-page", f"page missing: {page}")
            continue
        tiers = re.split(r"\n===?\s*(Easy|Medium|Hard|Elite)\s*===?\n", "\n" + text)
        wiki_counts = {}
        for i in range(1, len(tiers) - 1, 2):
            body = tiers[i + 1]
            nums = [int(m.group(1)) for m in
                    re.finditer(r"(?:desc:|^\|)(\d+)\.\s", body, re.M)]
            if nums:
                wiki_counts[tiers[i]] = max(nums)
        for tier, wiki_n in wiki_counts.items():
            pack_n = conn.execute(
                "SELECT COUNT(*) FROM diary_tasks WHERE region=? AND tier=?",
                (region, tier)).fetchone()[0]
            if pack_n != wiki_n:
                kb.add_gap(conn, "diary", f"{region} {tier}", "task-count-drift",
                           f"pack has {pack_n} tasks, the wiki page numbers {wiki_n}"
                           " — a task was added/removed since generation")
                drift += 1
        if not wiki_counts:
            kb.add_gap(conn, "diary", region, "task-counts",
                       f"could not read per-tier task numbering on {page}")
    print(f"diaries: {drift} tier-count drift flags")
    kb.set_progress(conn, "diary-tasks", 492, "diary_tasks",
                    "pack:diaries, wiki re-verified",
                    f"per-tier task counts re-checked vs the 12 diary pages:"
                    f" {drift} drift flags")


def main():
    conn = kb.db()
    boat(conn)
    money(conn)
    diaries(conn)
    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
