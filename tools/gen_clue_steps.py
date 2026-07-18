#!/usr/bin/env python3
"""Generate data/clue-steps.json — emote clue steps + STASH units.

Sources (pinned):
1. RuneLite core EmoteClue.java + emote/STASHUnit.java @ RL_TAG — every
   emote clue step: tier (from the clue item constant), text, area, world
   point, STASH unit link, and the item-requirement tree (item/any/all/
   range/xOfItem/emptySlot + the file's named requirement constants),
   converted to requirement-graph strings (lists, allOf semantics).
   BSD-2 (runelite).
2. STASH Tracker's StashUnit.java @ ST_COMMIT — tier + display name per
   STASH unit (its enum mirrors core's names; the built-object detection
   this module ports comes from the same plugin). BSD-2 (Nearvaas).
   https://github.com/Nearvaas/S.T.A.S.H-Toolkit

Item ids resolve through gameval ItemID/ObjectID (javap over the
runelite-api jar). Fail-fast on any unresolved constant or unjoined name.
"""

import glob
import json
import os
import re
import subprocess
import sys

RL_TAG = "runelite-parent-1.12.33"
ST_COMMIT = "a8caedca03159416dd5cd1948af6ecaf3fe24b9d"

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-clues", "gen")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "clue-steps.json")

CORE_BASE = (f"https://raw.githubusercontent.com/runelite/runelite/{RL_TAG}/"
             "runelite-client/src/main/java/net/runelite/client/plugins/cluescrolls/clues/")
ST_URL = (f"https://raw.githubusercontent.com/Nearvaas/S.T.A.S.H-Toolkit/{ST_COMMIT}/"
          "src/main/java/com/stashtracker/StashUnit.java")

RANGE_SPAN_LIMIT = 40


def fetch(url: str, cache_name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, cache_name)
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return f.read()
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        text = resp.read().decode("utf-8")
    with open(cached, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def javap(class_name: str) -> str:
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/runelite-api-*.jar"))
    jars = [j for j in jars if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    return subprocess.run(
        ["javap", "-classpath", sorted(jars)[-1], "-constants", class_name],
        capture_output=True, text=True, check=True).stdout


def constants_of(class_name: str) -> dict:
    return {m.group(1): int(m.group(2)) for m in
            re.finditer(r"public static final int (\w+) = (-?\d+);", javap(class_name))}


# ── requirement expression parser (core's ItemRequirements DSL) ──────────

def split_args(s: str):
    """Split a comma-separated argument list at depth 0 (parens + quotes)."""
    args, depth, cur, in_str = [], 0, [], False
    i = 0
    while i < len(s):
        c = s[i]
        if in_str:
            cur.append(c)
            if c == '\\':
                cur.append(s[i + 1])
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            cur.append(c)
        elif c == '(':
            depth += 1
            cur.append(c)
        elif c == ')':
            depth -= 1
            cur.append(c)
        elif c == ',' and depth == 0:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    tail = "".join(cur).strip()
    if tail:
        args.append(tail)
    return args


class ReqParser:
    """Requirement expression -> {'items': [[alt-ids...], ...]} where each
    inner list is one required thing with its acceptable item ids (AND of
    ORs — exactly the graph's item leaves / any: composites)."""

    def __init__(self, item_ids: dict, named: dict):
        self.item_ids = item_ids
        self.named = named  # constant name -> parsed groups

    def resolve_item(self, expr: str) -> int:
        m = re.fullmatch(r"ItemID\.(\w+)", expr.strip())
        if not m or m.group(1) not in self.item_ids:
            raise SystemExit(f"unresolved item constant: {expr!r}")
        return self.item_ids[m.group(1)]

    def parse(self, expr: str):
        """Returns (groups, nothing) — groups is a list of alternative-id
        lists (each = one AND term); nothing=True for emptySlot."""
        expr = expr.strip()
        if expr in self.named:
            return self.named[expr], False
        if "ItemVariationMapping.getVariations" in expr:
            # core's variation streams == our item: leaf semantics (the graph
            # counts every ItemVariationMapping variant of a canonical id),
            # plus any extra bare ids in the same stream expression
            ids = []
            for im in re.finditer(r"ItemID\.(\w+)", expr):
                name = im.group(1)
                if name not in self.item_ids:
                    raise SystemExit(f"unresolved item constant: {name}")
                ids.append(self.item_ids[name])
            if not ids:
                raise SystemExit(f"empty variation stream: {expr[:80]!r}")
            return [ids], False
        m = re.match(r"(\w+)\((.*)\)$", expr, re.S)
        if not m:
            raise SystemExit(f"unparseable requirement expression: {expr[:80]!r}")
        fn, body = m.group(1), m.group(2)
        args = split_args(body)
        if fn == "item":
            return [[self.resolve_item(args[0])]], False
        if fn == "xOfItem":
            # quantity folds into the graph leaf later via (id, qty)
            return [[(self.resolve_item(args[0]), int(args[1]))]], False
        if fn == "range":
            ids = [a for a in args if not a.startswith('"')]
            lo = self.resolve_item(ids[0])
            hi = self.resolve_item(ids[1])
            if hi - lo > RANGE_SPAN_LIMIT:
                raise SystemExit(f"range span too wide ({lo}..{hi}): {expr[:80]!r}")
            return [list(range(lo, hi + 1))], False
        if fn == "any":
            alts = []
            for arg in args:
                if arg.startswith('"'):
                    continue
                groups, _ = self.parse(arg)
                for group in groups:
                    alts.extend(group)
            return [alts], False
        if fn == "all":
            groups = []
            for arg in args:
                if arg.startswith('"'):
                    continue
                sub, _ = self.parse(arg)
                groups.extend(sub)
            return groups, False
        if fn == "emptySlot":
            return [], True
        raise SystemExit(f"unknown requirement factory {fn!r} in {expr[:80]!r}")


NAME_INDEX = os.path.join(HERE, "..", "src", "main", "resources", "data",
                          "index", "item-names.json")


def display_names():
    """id -> pretty display name from the item-name index (constant form)."""
    with open(NAME_INDEX, encoding="utf-8") as f:
        by_constant = json.load(f)
    names = {}
    for constant, item_id in by_constant.items():
        if item_id not in names:
            lower = constant.lower().replace("_", " ")
            names[item_id] = lower[:1].upper() + lower[1:]
    return names


def groups_to_reqs(groups, names):
    """AND-of-OR id groups -> requirement-graph strings (one per group);
    leaves carry display names so missing() lines read as item names."""
    reqs = []
    for group in groups:
        leaves = []
        for entry in group:
            item_id, qty = entry if isinstance(entry, tuple) else (entry, 1)
            name = names.get(item_id)
            leaves.append(f"item:{item_id}:{qty}:{name}" if name
                          else f"item:{item_id}:{qty}")
        if len(leaves) == 1:
            reqs.append(leaves[0])
        else:
            reqs.append("any:" + "|".join(leaves))
    return reqs


# ── core EmoteClue parsing ───────────────────────────────────────────────

def tier_of(clue_item_constant: str) -> str:
    for tier in ("BEGINNER", "EASY", "MEDIUM", "HARD", "ELITE", "MASTER"):
        if tier in clue_item_constant:
            return tier.capitalize()
    raise SystemExit(f"no tier in clue item constant: {clue_item_constant}")


def balanced_chunks(src: str, opener: str):
    """Every balanced-paren chunk starting at each occurrence of opener."""
    out = []
    start = 0
    while True:
        i = src.find(opener, start)
        if i < 0:
            return out
        depth = 0
        j = i + len(opener) - 1  # at the '('
        for k in range(j, len(src)):
            c = src[k]
            if c == '"':
                # skip string literal
                k2 = k + 1
                while k2 < len(src):
                    if src[k2] == '\\':
                        k2 += 2
                        continue
                    if src[k2] == '"':
                        break
                    k2 += 1
                continue
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    out.append(src[j + 1:k])
                    start = k
                    break
        else:
            return out


def strip_strings_aware_find(src):
    return src


def parse_named_constants(src: str, parser: ReqParser):
    """The file's static-final requirement constants (ANY_RING_OF_WEALTH…)."""
    for m in re.finditer(
            r"static final \w+ (\w+) = (any|all|range|item|xOfItem)\(", src):
        name = m.group(1)
        open_at = m.end() - 1
        depth = 0
        k = open_at
        while k < len(src):
            c = src[k]
            if c == '"':
                k += 1
                while src[k] != '"' or src[k - 1] == '\\':
                    k += 1
            elif c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    break
            k += 1
        expr = src[m.start(2):k + 1]
        groups, _ = parser.parse(expr)
        parser.named[name] = groups


def slugify(text: str, tier: str) -> str:
    words = re.sub(r"[^a-z0-9 ]", "", text.lower()).split()
    return tier.lower() + "_" + "_".join(words[:7])


def parse_clues(item_ids: dict, names: dict):
    src = fetch(CORE_BASE + "EmoteClue.java", f"core-EmoteClue-{RL_TAG}.java")
    parser = ReqParser(item_ids, {})
    parse_named_constants(src, parser)

    clues = []
    seen_texts = set()
    seen_ids = set()
    for body in balanced_chunks(src, "new EmoteClue("):
        args = split_args(body)
        if len(args) < 6:
            raise SystemExit(f"short EmoteClue entry: {body[:80]!r}")
        clue_item = re.fullmatch(r"ItemID\.(\w+)", args[0])
        if not clue_item:
            raise SystemExit(f"bad clue item arg: {args[0]!r}")
        text = json.loads(args[1])
        area = json.loads(args[2])
        stash = None if args[3] == "null" else args[3]
        wp = re.fullmatch(r"new WorldPoint\s*\((\d+),\s*(\d+),\s*(\d+)\)", args[4])
        if not wp:
            raise SystemExit(f"bad world point: {args[4]!r}")
        if text in seen_texts:
            continue  # core has a couple of duplicate steps sharing a STASH
        seen_texts.add(text)

        groups = []
        nothing = False
        for arg in args[5:]:
            if re.fullmatch(r"[A-Z0-9_]+", arg):
                if arg in parser.named:
                    groups.extend(parser.named[arg])
                # else: an emote or double-agent constant — not an item req
                continue
            if re.fullmatch(r"VarbitID\.\w+", arg):
                continue  # the dark-cave firelight varbit, not an item gate
            g, n = parser.parse(arg)
            groups.extend(g)
            nothing |= n

        clue = {
            "id": slugify(text, tier_of(clue_item.group(1))),
            "tier": tier_of(clue_item.group(1)),
            "text": text,
            "area": area,
            "x": int(wp.group(1)),
            "y": int(wp.group(2)),
            "plane": int(wp.group(3)),
            "reqs": groups_to_reqs(groups, names),
        }
        if nothing:
            clue["nothing"] = True
        if stash:
            clue["stash"] = stash
        if clue["id"] in seen_ids:
            n = 2
            while clue["id"] + "_" + str(n) in seen_ids:
                n += 1
            clue["id"] += "_" + str(n)
        seen_ids.add(clue["id"])
        clues.append(clue)
    if len(clues) < 110:
        raise SystemExit(f"suspiciously few emote clues: {len(clues)}")
    return clues


# ── STASH units (tier + display name from STASH Tracker, ids verified) ───

ST_ENTRY = re.compile(
    r"^\t(\w+)\(StashTier\.(\w+), ObjectID\.(\w+), \"([^\"]+)\", new WorldPoint\((\d+), (\d+), (\d+)\)",
    re.M)


def parse_stash_units(object_ids: dict):
    src = fetch(ST_URL, f"st-StashUnit-{ST_COMMIT[:7]}.java")
    units = []
    for m in ST_ENTRY.finditer(src):
        const = m.group(3)
        if const not in object_ids:
            raise SystemExit(f"unresolved ObjectID.{const}")
        units.append({
            "key": m.group(1),
            "tier": m.group(2).capitalize(),
            "objectId": object_ids[const],
            "name": m.group(4),
            "x": int(m.group(5)),
            "y": int(m.group(6)),
            "plane": int(m.group(7)),
        })
    if len(units) < 110:
        raise SystemExit(f"suspiciously few STASH units: {len(units)}")
    return units


def main():
    item_ids = constants_of("net.runelite.api.gameval.ItemID")
    # gameval ObjectID is split across two classes for class-file size
    object_ids = constants_of("net.runelite.api.gameval.ObjectID")
    object_ids.update(constants_of("net.runelite.api.gameval.ObjectID1"))

    print("parsing core EmoteClue ...")
    clues = parse_clues(item_ids, display_names())
    print(f"  {len(clues)} emote clue steps")

    print("parsing STASH units ...")
    units = parse_stash_units(object_ids)
    print(f"  {len(units)} STASH units")

    # core constant names that drifted from the tracker's mirror (verified
    # by object id: core WARRIORS_GUILD_BANK = HH_ELITE_EXP5 = the elite unit)
    aliases = {
        "WARRIORS_GUILD_BANK": "WARRIORS_GUILD_BANK_ELITE",
        "WARRIORS_GUILD_BANK_29047": "WARRIORS_GUILD_BANK_MASTER",
        "ORTUS_MEETS_PROUDSPIRE": "WHERE_ORTUS_MEETS_PROUDSPIRE",
        "_7TH_CHAMBER_OF_JALSAVRAH": "SEVENTH_CHAMBER_OF_JALSAVRAH",
    }
    for clue in clues:
        if clue.get("stash") in aliases:
            clue["stash"] = aliases[clue["stash"]]
    by_key = {u["key"]: u for u in units}
    unjoined = []
    for clue in clues:
        stash = clue.get("stash")
        if stash is None:
            continue
        unit = by_key.get(stash)
        if unit is None:
            unjoined.append(stash)
            continue
        # the unit stores exactly its clue's outfit; first join wins
        if "clueId" not in unit:
            unit["clueId"] = clue["id"]
    if unjoined:
        raise SystemExit("clue STASH constants missing from the unit table: "
                         + ", ".join(sorted(set(unjoined))))
    orphans = [u["key"] for u in units if "clueId" not in u]
    if orphans:
        print(f"  note: {len(orphans)} units have no emote clue in core (ok): "
              + ", ".join(orphans[:5]) + ("…" if len(orphans) > 5 else ""))

    pack = {
        "version": 1,
        "sources": {
            "runelite": RL_TAG,
            "stashTracker": ST_COMMIT,
        },
        "clues": clues,
        "stash": units,
    }
    with open(os.path.abspath(OUT), "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
        f.write("\n")
    tiers = {}
    for u in units:
        tiers[u["tier"]] = tiers.get(u["tier"], 0) + 1
    print(f"wrote {os.path.abspath(OUT)}")
    print(f"  units per tier: {tiers}")
    assert len(clues) >= 110 and len(units) >= 110


if __name__ == "__main__":
    main()
