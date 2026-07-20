#!/usr/bin/env python3
"""Generate data/port-tasks.json — Sailing port-task static tables.

Ported from the Port Tasks hub plugin (nucleon/port-tasks, BSD-2,
nucleon + Cooper Morris) at the plugin-hub pinned commit. The task
CATALOG itself is not static — the game serves it from cache DBTable 197
at runtime (names, ports, cargo, bounty targets) — but three tables are
hand-maintained in the reference and port as data:

  ports    - PortLocation.java: dbrow, name, Sailing level, nav point,
             whether the port has a task noticeboard
  rewards  - TaskReward.java: DBRow -> Sailing XP (the DBTable does not
             expose XP; the reference authors transcribed it), plus the
             comment label ("Catherby grog delivery") and its
             courier/bounty type
  routes   - PortPaths.java: per port pair, the sailing distance in
             tiles computed exactly as the reference's computeDistance
             (sum of Euclidean hops along the relative-move path, plus
             the hop onto the destination's nav point)

Distances feed the noticeboard advisor's marginal-route ranking; the
polylines themselves are NOT ported (Iron Hub draws no ocean lines —
run the reference plugin alongside for those).

Usage: python3 tools/gen_port_tasks.py
"""

import json
import math
import os
import re
import urllib.request

COMMIT = "8004542e56b7420578f0602cdae642486e3131d2"
RAW = ("https://raw.githubusercontent.com/nucleon/port-tasks/"
       + COMMIT + "/src/main/java/com/nucleon/porttasks/")

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-port-tasks")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "port-tasks.json")


def fetch_source(name: str) -> str:
    cached = os.path.join(CACHE, name.replace("/", "_"))
    os.makedirs(CACHE, exist_ok=True)
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return f.read()
    req = urllib.request.Request(RAW + name, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        text = resp.read().decode("utf-8")
    with open(cached, "w", encoding="utf-8") as f:
        f.write(text)
    return text


# ── PortLocation.java ────────────────────────────────────────────────────

def parse_ports(java: str):
    ports = {}
    pattern = re.compile(
        r'^\t(\w+)\((\d+),\s*"([^"]+)",\s*(\d+|null),\s*([^,]+),\s*(-1|ObjectID\.\w+),'
        r'\s*[^,]+,\s*new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)\)',
        re.M)
    for m in pattern.finditer(java):
        enum_name, dbrow, name, level, _gangplank, board, x, y, plane = m.groups()
        if enum_name == "EMPTY":
            continue
        ports[enum_name] = {
            "dbrow": int(dbrow),
            "name": name,
            "level": None if level == "null" else int(level),
            "x": int(x), "y": int(y), "plane": int(plane),
            "board": board != "-1",
        }
    return ports


# ── TaskReward.java ──────────────────────────────────────────────────────

def parse_rewards(java: str):
    rewards = []
    # the last enum constant ends ");" not "),"
    for m in re.finditer(r"TASK_(\d+)\(\1,\s*(\d+)\)[,;]\s*//\s*(.+)", java):
        dbrow, xp, label = int(m.group(1)), int(m.group(2)), m.group(3).strip()
        if label.endswith(" bounty"):
            kind = "bounty"
        elif "delivery" in label:  # usually "... delivery"; one row reads
            kind = "courier"       # "delivery of nothing sinister"
        else:
            raise SystemExit(f"TASK_{dbrow}: unclassifiable label {label!r}")
        rewards.append({"dbrow": dbrow, "xp": xp, "label": label, "type": kind})
    return rewards


# ── PortPaths.java ───────────────────────────────────────────────────────

def parse_routes(java: str, ports: dict):
    routes = []
    # one constant closes ")\n\t\t)," (double tab) — accept both indents,
    # or its match swallows the next constant and inflates its distance
    pattern = re.compile(
        r"^\t(\w+)\(\s*PortLocation\.(\w+),\s*PortLocation\.(\w+),(.*?)^\t{1,2}\)",
        re.M | re.S)
    for m in pattern.finditer(java):
        const, start, end, body = m.groups()
        if const == "DEFAULT":
            continue
        moves = [(int(mm.group(1)), int(mm.group(2))) for mm in
                 re.finditer(r"new RelativeMove\((-?\d+),\s*(-?\d+)\)", body)]
        if not moves:
            raise SystemExit(f"route {const}: no moves parsed")
        a, b = ports[start], ports[end]
        # computeDistance, byte-faithful: hop along the deltas, then onto
        # the destination nav point if the path doesn't land on it
        x, y = a["x"], a["y"]
        dist = 0.0
        for dx, dy in moves:
            dist += math.hypot(dx, dy)
            x, y = x + dx, y + dy
        if (x, y) != (b["x"], b["y"]):
            dist += math.hypot(b["x"] - x, b["y"] - y)
        routes.append({"a": a["dbrow"], "b": b["dbrow"],
                       "distance": round(dist, 1)})
    return routes


def main():
    ports = parse_ports(fetch_source("enums/PortLocation.java"))
    rewards = parse_rewards(fetch_source("enums/TaskReward.java"))
    routes = parse_routes(fetch_source("enums/PortPaths.java"), ports)

    # ── sanity asserts ───────────────────────────────────────────────────
    assert len(ports) == 30, len(ports)
    assert len(rewards) == 271, len(rewards)
    assert len(routes) == 163, len(routes)
    kinds = [r["type"] for r in rewards]
    assert kinds.count("courier") == 211 and kinds.count("bounty") == 60, (
        kinds.count("courier"), kinds.count("bounty"))
    dbrows = {p["dbrow"] for p in ports.values()}
    assert len(dbrows) == 30
    names = {p["name"] for p in ports.values()}
    assert len(names) == 30
    assert sum(1 for p in ports.values() if not p["board"]) == 7
    seen_pairs = set()
    for r in routes:
        assert r["a"] in dbrows and r["b"] in dbrows, r
        assert r["distance"] > 0, r
        pair = tuple(sorted((r["a"], r["b"])))
        assert pair not in seen_pairs, f"duplicate route {pair}"
        seen_pairs.add(pair)
    # the route graph must be connected — the advisor Dijkstras over it,
    # and an unreachable port would silently cost infinity
    adj = {}
    for r in routes:
        adj.setdefault(r["a"], set()).add(r["b"])
        adj.setdefault(r["b"], set()).add(r["a"])
    start = next(iter(dbrows))
    seen = {start}
    frontier = [start]
    while frontier:
        for nxt in adj.get(frontier.pop(), ()):
            if nxt not in seen:
                seen.add(nxt)
                frontier.append(nxt)
    assert seen == dbrows, f"unreachable ports: {sorted(dbrows - seen)}"
    # anchors from the reference
    assert ports["PORT_SARIM"]["dbrow"] == 8587 and ports["PORT_SARIM"]["level"] == 1
    assert ports["RED_ROCK"]["level"] is None
    by_dbrow = {r["dbrow"]: r for r in rewards}
    assert by_dbrow[8665]["xp"] == 155      # Port Sarim spice delivery
    assert by_dbrow[9100]["xp"] == 12670    # Lunar Isle potion delivery
    assert by_dbrow[13311]["type"] == "bounty"

    out = {
        "version": 1,
        "source": "nucleon/port-tasks @ " + COMMIT[:7] + " (BSD-2)",
        "ports": sorted(ports.values(), key=lambda p: p["dbrow"]),
        "rewards": sorted(rewards, key=lambda r: r["dbrow"]),
        "routes": sorted(routes, key=lambda r: (r["a"], r["b"])),
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(f"{len(ports)} ports, {len(rewards)} rewards "
          f"({kinds.count('courier')} courier / {kinds.count('bounty')} bounty), "
          f"{len(routes)} routes -> {os.path.relpath(OUT, HERE + '/..')}")


if __name__ == "__main__":
    main()
