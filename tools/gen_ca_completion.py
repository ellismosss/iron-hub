#!/usr/bin/env python3
"""Generate data/ca-completion.json from the OSRS wiki's Combat
Achievements task table (community completion rates per task).

The wiki's /w/Combat_Achievements/All_tasks page renders one <tr> per task
with a data-ca-task-id attribute (the in-game task id, struct param 1306)
and the completion percentage in the 6th cell. We bundle a snapshot so the
plugin needs no runtime HTTP; regenerate occasionally to refresh the rates.

Usage:
  python3 tools/gen_ca_completion.py [saved_page.html]

Without an argument the page is fetched live (descriptive User-Agent, per
the wiki's bot etiquette).
"""
import datetime
import html as htmllib
import json
import re
import sys
import urllib.request

URL = "https://oldschool.runescape.wiki/w/Combat_Achievements/All_tasks"
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
OUT = "src/main/resources/data/ca-completion.json"


def fetch() -> str:
    req = urllib.request.Request(URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        return resp.read().decode("utf-8")


def text_of(cell: str) -> str:
    return htmllib.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", cell))).strip()


def parse(page: str):
    tasks = []
    for tid, row in re.findall(r'<tr[^>]*data-ca-task-id="(\d+)"[^>]*>(.*?)</tr>', page, re.DOTALL):
        cells = row.split("</td>")
        if len(cells) < 6:
            continue
        name = text_of(cells[1])
        pct_match = re.search(r"([0-9.]+)%", cells[5])
        if not name or not pct_match:
            continue
        tasks.append({"id": int(tid), "name": name, "pct": float(pct_match.group(1))})
    tasks.sort(key=lambda t: t["id"])
    return tasks


def main():
    page = open(sys.argv[1], encoding="utf-8").read() if len(sys.argv) > 1 else fetch()
    tasks = parse(page)
    if len(tasks) < 500:
        raise SystemExit(f"only {len(tasks)} tasks parsed - wiki page layout changed?")
    ids = [t["id"] for t in tasks]
    if len(set(ids)) != len(ids):
        raise SystemExit("duplicate task ids parsed")
    pack = {
        "source": URL,
        "generated": datetime.date.today().isoformat(),
        "tasks": tasks,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {OUT}: {len(tasks)} tasks")


if __name__ == "__main__":
    main()
