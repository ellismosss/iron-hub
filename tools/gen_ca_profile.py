#!/usr/bin/env python3
"""Generate data/ca-profile.json — the Combat Profile's arithmetic.

The Combat Achievements interface opens on a "Combat Profile" panel with
seven rows (Tasks Completed, Boss Kill Count, Skilling Boss Kill Count,
Raid Completions, Top Boss, Top Skilling Boss, Top Raid). Every one of
those is a sum or a max over player vars the game hardcodes in cs2 — they
are not in any enum, so they cannot be read from the cache at runtime.

Parsed from the pinned RuneStar cs2 dump (the same source
tools/gen_weapon_styles.py uses):

  [proc,ca_specific_killcount]  boss index -> the varp(s) holding its KC
  [proc,script4776/4775/4774]   the boss / skilling-boss / raid totals,
                                exactly the varps the game adds up
  [proc,script4779]             the per-tier completed-task varbits
  [proc,script4777]             the "Top X" scan, whose ONE exclusion
                                (index 37) is preserved here

Everything that IS in the cache stays a runtime read: boss names, combat
levels, per-boss task counts and the boss category enum. A boss the dump
does not map (added since the pin) simply has no kill count, and the
panel says nothing rather than guessing.

Usage:
  python3 tools/gen_ca_profile.py
"""
import datetime
import json
import os
import re
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-ca-profile")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "ca-profile.json")
UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
CS2_COMMIT = "7da6c1fbab51b7528ff13b15a532a44208bf75d6"  # pinned with gen_weapon_styles.py
CS2_BASE = ("https://raw.githubusercontent.com/RuneStar/cs2-scripts/" + CS2_COMMIT
            + "/scripts/")

SCRIPTS = {
    "killcount": "[proc,ca_specific_killcount].cs2",
    "boss_total": "[proc,script4776].cs2",
    "skilling_total": "[proc,script4775].cs2",
    "raid_total": "[proc,script4774].cs2",
    "tasks_completed": "[proc,script4779].cs2",
    "top": "[proc,script4777].cs2",
}


def fetch(name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, re.sub(r"[^A-Za-z0-9._-]+", "_", name))
    if not os.path.exists(cached):
        url = CS2_BASE + urllib.parse.quote(name)
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
        with open(cached, "w", encoding="utf-8") as f:
            f.write(body)
    with open(cached, encoding="utf-8") as f:
        return f.read()


def parse_kill_varps(text: str) -> dict:
    """case <index> : $int1 = %var<N>  (or a calc() sum of several)."""
    out = {}
    index = None
    for line in text.split("\n"):
        case = re.match(r"\s*case (\d+) :", line)
        if case:
            index = int(case.group(1))
            continue
        if index is None:
            continue
        varps = re.findall(r"%var(\d+)", line)
        if varps:
            out[index] = [int(v) for v in varps]
            index = None
    if not out:
        raise SystemExit("ca_specific_killcount: no cases parsed")
    return out


def parse_sum(text: str, prefix: str) -> list:
    """The varps/varbits a total proc adds up, in source order."""
    found = [int(v) for v in re.findall(r"%" + prefix + r"(\d+)", text)]
    if not found:
        raise SystemExit(f"no %{prefix} sums found")
    return found


def main():
    kill_varps = parse_kill_varps(fetch(SCRIPTS["killcount"]))
    boss_total = parse_sum(fetch(SCRIPTS["boss_total"]), "var")
    skilling_total = parse_sum(fetch(SCRIPTS["skilling_total"]), "var")
    raid_total = parse_sum(fetch(SCRIPTS["raid_total"]), "var")
    tasks_completed = parse_sum(fetch(SCRIPTS["tasks_completed"]), "varbit")

    top = fetch(SCRIPTS["top"])
    excluded = re.search(r"\$int1 ! (\d+)", top)
    if not excluded:
        raise SystemExit("script4777 no longer excludes an index — check the Top X scan")

    if len(tasks_completed) != 6:
        raise SystemExit(f"expected 6 tier varbits, got {tasks_completed}")
    # every varp a total sums must belong to some boss index, or the panel
    # would total kills it cannot attribute to a boss
    mapped = {varp for varps in kill_varps.values() for varp in varps}
    for name, total in (("boss", boss_total), ("skilling", skilling_total),
                        ("raid", raid_total)):
        missing = [v for v in total if v not in mapped]
        if missing:
            raise SystemExit(f"{name} total sums unmapped varps {missing}")

    pack = {
        "source": "RuneStar/cs2-scripts@" + CS2_COMMIT[:9] + " ca_specific_killcount + "
                  "ca_overview_create_personal helpers",
        "generated": datetime.date.today().isoformat(),
        "excludedTopIndex": int(excluded.group(1)),
        "tasksCompletedVarbits": tasks_completed,
        "bossKillCountVarps": boss_total,
        "skillingKillCountVarps": skilling_total,
        "raidCompletionVarps": raid_total,
        "killVarps": {str(k): v for k, v in sorted(kill_varps.items())},
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=2)
        f.write("\n")
    print(f"wrote {len(kill_varps)} boss kill-count mappings; totals "
          f"{len(boss_total)} boss / {len(skilling_total)} skilling / "
          f"{len(raid_total)} raid varps; top-scan excludes index "
          f"{pack['excludedTopIndex']}")


if __name__ == "__main__":
    main()
