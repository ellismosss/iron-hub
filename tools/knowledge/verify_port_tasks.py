#!/usr/bin/env python3
"""The port-tasks mapping pass (Luke, 2026-07-21): join port-tasks.json's
hand-transcribed reward table (DBRow -> xp + label) to the wiki's
courier/bounty task buckets (task_id in the SLOT-VARBIT space, xp, item,
ports, monster) by label semantics — couriers on destination + item stem,
bounties on board + monster — and verify every transcribed XP figure.

Outputs: xp mismatches and unmatched labels land in gaps; a coverage note
records how many bucket tasks the pack's reward table lacks."""

import json
import re

import kb


def norm(s):
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def stems(text):
    """Word stems with simple plural tolerance (platebodies->platebody,
    spices->spice, logs->log)."""
    out = set()
    for w in norm(text).split():
        out.add(w)
        if w.endswith("ies"):
            out.add(w[:-3] + "y")
        if w.endswith("es"):
            out.add(w[:-2])
        if w.endswith("s"):
            out.add(w[:-1])
    return out


CONTAINERS = {"crate", "of", "barrel", "chest", "sack", "bag", "the"}


def main():
    conn = kb.db()
    # rerunnable: retire the previous pass's findings before re-judging
    conn.execute("UPDATE gaps SET status='resolved'"
                 " WHERE category='port-tasks' AND status='open'")
    pack = kb.pack("port-tasks")
    rewards = pack["rewards"]
    # a handful of Red Rock couriers carry no xp on the wiki yet — they
    # can neither verify nor refute a pack figure, so they sit out
    couriers = conn.execute(
        "SELECT task_id, xp, item, cargo_location, destination"
        " FROM bucket_couriertaskline WHERE xp IS NOT NULL").fetchall()
    bounties = conn.execute(
        "SELECT task_id, xp, monster, item, notice_board"
        " FROM bucket_bountytaskline").fetchall()

    matched = mismatched = unmatched = ambiguous = 0
    for r in rewards:
        label = r["label"]
        lstem = stems(label)
        hits = set()
        if r["type"] == "courier":
            for task_id, xp, item, cargo, dest in couriers:
                dest_short = norm(dest).replace("the ", "")
                if dest_short and dest_short in norm(label):
                    item_stems = stems(item) - CONTAINERS
                    if item_stems & lstem:
                        hits.add(int(float(xp)))
        else:
            for task_id, xp, monster, item, board in bounties:
                board_short = norm(board).replace("the ", "")
                if board_short and board_short in norm(label) \
                        and stems(monster) & lstem:
                    hits.add(int(float(xp)))
        if not hits:
            unmatched += 1
            kb.add_gap(conn, "port-tasks", label, "bucket-join",
                       "no wiki courier/bounty row matched this pack label"
                       " (dest/board + item/monster stems)")
        elif r["xp"] in hits:
            # multiple xp tiers can share a dest+item; the pack value being
            # AMONG the wiki's candidates is a verification, not ambiguity
            matched += 1
        elif len(hits) > 1:
            ambiguous += 1
            kb.add_gap(conn, "port-tasks", label, "ambiguous-join",
                       f"pack xp {r['xp']} matches NONE of the wiki candidates"
                       f" {sorted(hits)}")
        else:
            mismatched += 1
            kb.add_gap(conn, "port-tasks", label, "xp-drift",
                       f"pack xp {r['xp']} vs wiki {hits.pop()}")

    # coverage the other way: how many distinct wiki tasks have no pack reward
    courier_tasks = {r[0] for r in couriers}
    bounty_tasks = {r[0] for r in bounties}
    note = (f"{matched} rewards xp-verified vs the wiki buckets, {mismatched}"
            f" mismatches, {ambiguous} ambiguous, {unmatched} unmatched;"
            f" wiki lists {len(courier_tasks)} courier + {len(bounty_tasks)}"
            f" bounty task ids vs the pack's {len(rewards)} reward rows")
    # set_progress's literal-count seam wants a non-identifier (leading space)
    kb.set_progress(conn, "port-task-rewards", len(rewards),
                    f" {matched}", "pack:port-tasks vs wiki task buckets", note)
    conn.commit()
    conn.close()
    print(note)


if __name__ == "__main__":
    main()
