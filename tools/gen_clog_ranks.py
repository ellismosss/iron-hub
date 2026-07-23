#!/usr/bin/env python3
"""Generate data/clog-ranks.json — the collection log's ranks.

The log's overview screen frames your slot count between the rank you have
reached and the one you are climbing to, each shown by its staff of
collection (Luke's brief, mirroring the game's own overview).

Source: the wiki's [[Collection log]] article, section "Ranks" — the table
of staff -> items logged. Each staff's item id comes from its OWN page's
{{Infobox Item}}, so nothing here is transcribed by hand. The article's
stated total slot count comes along for the Gilded rank, whose threshold is
90% of the total rounded down to the nearest 25 and therefore moves with
every game update: the pack carries the formula so the runtime can recompute
it from the player's live total instead of trusting a stale number.

Usage:
  python3 tools/gen_clog_ranks.py
"""
import datetime
import json
import os
import re
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-clog-ranks")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "clog-ranks.json")
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
WIKI = "https://oldschool.runescape.wiki/w/"

# The ranks, in order — a sanity list the parse must reproduce exactly, so a
# reordered or renamed wiki table fails here instead of shipping wrong.
EXPECTED = ["Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune",
            "Dragon", "Gilded"]


def wikitext(page: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, re.sub(r"[^A-Za-z0-9._-]+", "_", page) + ".wiki")
    if not os.path.exists(cached):
        url = WIKI + urllib.parse.quote(page.replace(" ", "_")) + "?action=raw"
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        time.sleep(1)  # polite pace
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
        with open(cached, "w", encoding="utf-8") as f:
            f.write(body)
    with open(cached, encoding="utf-8") as f:
        return f.read()


def parse_ranks(text: str):
    """[(rank name, staff page, items logged or None for the formula row)]."""
    section = re.split(r"^==[^=\n]+==\s*$", text, flags=re.M)
    body = None
    for i, chunk in enumerate(re.split(r"^==([^=\n]+)==\s*$", text, flags=re.M)):
        if chunk.strip() == "Ranks":
            body = re.split(r"^==([^=\n]+)==\s*$", text, flags=re.M)[i + 1]
            break
    if body is None:
        raise SystemExit("no Ranks section on the Collection log page")
    ranks = []
    for row in body.split("|-"):
        staff = re.search(r"\{\{plinkp\|([^|}]+staff of collection)\}\}", row, re.I)
        label = re.search(r"\[\[[^|\]]*staff of collection\|([^\]]+)\]\]", row, re.I)
        if not staff or not label:
            continue
        cells = [c.strip() for c in row.strip().split("\n|") if c.strip()]
        count = None
        for cell in reversed(cells):
            digits = re.match(r"^([\d,]+)\b", cell)
            if digits:
                count = int(digits.group(1).replace(",", ""))
                break
        ranks.append((label.group(1).strip(), staff.group(1).strip(), count))
    return ranks


def item_id(page: str) -> int:
    text = wikitext(page)
    found = re.search(r"^\|\s*id\s*=\s*(\d+)", text, re.M)
    if not found:
        raise SystemExit(f"no item id in the infobox on {page}")
    return int(found.group(1))


def main():
    text = wikitext("Collection log")
    parsed = parse_ranks(text)
    names = [name for name, _, _ in parsed]
    if names != EXPECTED:
        raise SystemExit(f"rank table changed: {names} != {EXPECTED}")

    stated = re.search(r"total of ([\d,]+) slots to fill", text)
    if not stated:
        raise SystemExit("the article no longer states the total slot count")
    total = int(stated.group(1).replace(",", ""))

    ranks = []
    for name, staff_page, slots in parsed:
        rank = {"name": name, "itemId": item_id(staff_page)}
        if slots is None:
            # Gilded: 90% of the total, rounded down to the nearest 25 —
            # the runtime recomputes it from the player's live slot total
            rank["percentOfTotal"] = 0.9
            rank["roundDown"] = 25
            rank["slots"] = int(total * 0.9) // 25 * 25
        else:
            rank["slots"] = slots
        ranks.append(rank)

    climbing = [r["slots"] for r in ranks]
    if climbing != sorted(climbing) or len(set(climbing)) != len(climbing):
        raise SystemExit(f"rank thresholds are not strictly increasing: {climbing}")

    pack = {
        "source": "oldschool.runescape.wiki Collection log#Ranks + each staff's infobox",
        "generated": datetime.date.today().isoformat(),
        "totalSlots": total,
        "ranks": ranks,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=2)
        f.write("\n")
    print(f"wrote {len(ranks)} ranks (total slots {total}): "
          + ", ".join(f"{r['name']} {r['slots']}" for r in ranks))


if __name__ == "__main__":
    main()
