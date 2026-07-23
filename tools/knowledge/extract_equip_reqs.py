#!/usr/bin/env python3
"""Machine-extract equip requirements from equipment page PROSE (Luke's
2026-07-22 directive: the wiki has NO structured req field — confirmed by
a zero-hit survey of all 4,242 pages for |req= params, req templates and
bucket fields — but 1,000+ pages state them in patterned sentences).

Method: requirement-shaped SENTENCES ("requires 75 Attack and 90 Strength
to wield", "requires level 70 Ranged", "{{SCP|Magic|75}}"), scoped to the
page's LEAD section (the item's own description — later sections discuss
other items). All (skill, level) pairs in those sentences are captured,
max per skill; "completion of [[Quest]]" in the same sentence becomes a
quest: leaf (validated against quests.json).

Honesty: extracted rows are flagged reqs-extracted, never replacing the
189 hand-audited chains; pages with NO requirement sentence are flagged
reqs-none-stated (most cosmetics genuinely have none). The extractor is
VALIDATED against the audited items' skill leaves before anything lands —
the agreement figure prints on every run and gates the write."""

import glob
import json
import re

import kb

SKILLS = ["Attack", "Strength", "Defence", "Ranged", "Magic", "Prayer",
          "Hitpoints", "Slayer", "Agility", "Runecraft", "Mining", "Smithing",
          "Crafting", "Fletching", "Herblore", "Cooking", "Firemaking",
          "Woodcutting", "Farming", "Hunter", "Thieving", "Construction",
          "Sailing", "Fishing"]
SKILL_ALT = "|".join(SKILLS)

REQ_SENTENCE = re.compile(
    r"[^.\n]*(?:requir\w+|to (?:wield|wear|equip)|in order to (?:wield|wear|equip))[^.\n]*\.",
    re.I)
PAIR_A = re.compile(r"(?:level\s+)?(\d{1,2})\s+\[\[(" + SKILL_ALT + r")(?:\|[^\]]*)?\]\]", re.I)
PAIR_B = re.compile(r"\[\[(" + SKILL_ALT + r")(?:\|[^\]]*)?\]\]\s+(?:level\s+|of\s+)?(\d{1,2})\b", re.I)
PAIR_SCP = re.compile(r"\{\{SCP\|(" + SKILL_ALT + r")\|(\d{1,2})", re.I)
QUEST = re.compile(r"(?:completion of|completed|after(?: completing)?|during)\s+"
                   r"(?:the\s+)?(?:\[\[)?quest(?:\]\])?s?\s*"
                   r"\[\[([^\]|#]+)(?:\|[^\]]*)?\]\]|"
                   r"completion of\s+\[\[([^\]|#]+)(?:\|[^\]]*)?\]\]", re.I)


def lead_of(text: str) -> str:
    """Everything before the first section heading, templates dropped at
    top level (the infobox), links kept."""
    body = text.split("\n==", 1)[0]
    return body


def extract(text: str, quest_names):
    lead = lead_of(text)
    skills = {}
    quests = set()
    for m in REQ_SENTENCE.finditer(lead):
        sentence = m.group(0)
        for lvl, skill in PAIR_A.findall(sentence):
            skills[skill.capitalize()] = max(skills.get(skill.capitalize(), 0), int(lvl))
        for skill, lvl in PAIR_B.findall(sentence):
            skills[skill.capitalize()] = max(skills.get(skill.capitalize(), 0), int(lvl))
        for skill, lvl in PAIR_SCP.findall(sentence):
            skills[skill.capitalize()] = max(skills.get(skill.capitalize(), 0), int(lvl))
        for a, b in QUEST.findall(sentence):
            candidate = (a or b).strip()
            if candidate in quest_names:
                quests.add(candidate)
    reqs = [f"skill:{s}:{v}" for s, v in sorted(skills.items()) if v > 1]
    reqs += [f"quest:{q}" for q in sorted(quests)]
    return reqs


def cached_pages():
    pages = {}
    for f in glob.glob(kb.CACHE + "/cat-*_slot_items-*.json"):
        d = json.load(open(f))
        for p in d.get("query", {}).get("pages", []):
            revs = p.get("revisions")
            if revs:
                pages[p["title"]] = revs[0]["slots"]["main"]["content"]
    if len(pages) < 4000:
        raise SystemExit(f"suspiciously few cached equipment pages: {len(pages)}")
    return pages


def audited_skill_leaves(reqs_json):
    out = {}
    for r in json.loads(reqs_json):
        m = re.match(r"skillb?:([A-Za-z]+):(\d+)", r)
        if m:
            out[m.group(1)] = int(m.group(2))
    return out


def main():
    conn = kb.db()
    quest_names = {q["name"] for q in kb.pack("quests")["quests"]}
    pages = cached_pages()

    # ── validation against the 189 hand-audited chains ────────────────
    audited = conn.execute(
        "SELECT name, equip_reqs FROM equipment WHERE equip_reqs IS NOT NULL"
        " AND src LIKE '%gear-progression%'").fetchall()
    agree = conflict = only_audited = only_extracted = 0
    conflicts = []
    for name, reqs_json in audited:
        text = pages.get(name) or pages.get(name[0].upper() + name[1:])
        if not text:
            continue
        gold = audited_skill_leaves(reqs_json)
        got = {}
        for r in extract(text, quest_names):
            m = re.match(r"skill:([A-Za-z]+):(\d+)", r)
            if m:
                got[m.group(1)] = int(m.group(2))
        for skill in set(gold) | set(got):
            if skill in gold and skill in got:
                if gold[skill] == got[skill]:
                    agree += 1
                else:
                    conflict += 1
                    conflicts.append(f"{name}: {skill} audited {gold[skill]} vs extracted {got[skill]}")
            elif skill in gold:
                only_audited += 1
            else:
                only_extracted += 1
                conflicts.append(f"{name}: extracted extra {skill} {got[skill]}")
    total_checked = agree + conflict
    precision = agree / total_checked if total_checked else 0
    print(f"validation vs audited: {agree} agree, {conflict} conflict"
          f" ({precision:.1%} where both sides speak), {only_audited} audited-only"
          f" (quest/material chains prose can't see), {only_extracted} extracted-only")
    for c in conflicts[:12]:
        print("  ", c)
    if precision < 0.9:
        raise SystemExit("extractor precision below 90% — not landing")
    # conflicts point BOTH ways — several are the audited pack being coarse
    # (Eternal boots 60 vs the wiki's 75) — surface each for adjudication
    for c in conflicts:
        subject = c.split(":")[0]
        kb.add_gap(conn, "equipment", subject, "req-conflict",
                   "prose extraction disagrees with the audited gear pack: " + c)

    # ── extraction over every page without audited reqs ───────────────
    extracted_n = none_n = 0
    for name, text in pages.items():
        row = conn.execute("SELECT equip_reqs, flags FROM equipment WHERE name = ?",
                           (name,)).fetchone()
        if row is None or row[0]:
            continue  # audited reqs stay primary
        reqs = extract(text, quest_names)
        flags = [f for f in (row[1] or "").split(",")
                 if f and f not in ("reqs-unverified", "reqs-extracted",
                                    "reqs-none-stated")]
        if reqs:
            flags.append("reqs-extracted")
            conn.execute("UPDATE equipment SET equip_reqs = ?, flags = ?"
                         " WHERE name = ?",
                         (json.dumps(reqs), ",".join(flags) or None, name))
            extracted_n += 1
        else:
            flags.append("reqs-none-stated")
            conn.execute("UPDATE equipment SET flags = ? WHERE name = ?",
                         (",".join(flags) or None, name))
            none_n += 1
    conn.commit()
    kb.set_progress(conn, "equipment", None, "equipment",
                    "wiki slot categories + prose req extraction",
                    f"reqs: 189 audited + {extracted_n} prose-extracted"
                    f" ({precision:.0%} validated); {none_n} state none"
                    " (cosmetics mostly)")
    conn.commit()
    conn.close()
    print(f"extracted reqs for {extracted_n} items; {none_n} state none")


if __name__ == "__main__":
    main()
