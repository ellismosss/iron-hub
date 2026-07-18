#!/usr/bin/env python3
"""Generate data/recipes.json — item crafting/production recipes for Goals v2
supply-goal component-material breakdown (a supply Route decomposes to its raw
materials in the Goals hub).

Source: the OSRS wiki's {{Recipe}} templates (every mainspace page that
transcludes Template:Recipe), joined to the official GE price-guide item
mapping (https://prices.runescape.wiki/api/v1/osrs/mapping) for display
name -> item id. Recipes whose output or any material is not a tradeable
mapped item are skipped (logged) — the decomposer treats an item with no
recipe as a raw leaf, which is honest.

Each output keeps ONE canonical recipe (fewest distinct materials, so the
plain "Silver ore -> Silver bar" wins over the Superheat/Blast variants).
The Java decomposer (GoalsHubTab) recurses these direct recipes to raw
leaves; cycles are broken there.

Cache under tools/.cache-recipes/ (gitignored). Re-run: python3 tools/gen_recipes.py
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

UA = "iron-hub-recipe-gen/1.0 (+github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
WIKI = "https://oldschool.runescape.wiki/api.php"
MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping"
CACHE = Path(__file__).parent / ".cache-recipes"
ROOT = Path(__file__).parent.parent
OUT = ROOT / "src/main/resources/data/recipes.json"

# Materials that are never a decomposable component (payment / catalysts we do
# not model as gatherable). Coins in particular appears as a "buy" material.
SKIP_MATERIALS = {"Coins"}


def http(url, cache_key):
    CACHE.mkdir(exist_ok=True)
    cf = CACHE / cache_key
    if cf.exists():
        return cf.read_text(encoding="utf-8")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(5):
        try:
            data = urllib.request.urlopen(req, timeout=90).read().decode("utf-8")
            cf.write_text(data, encoding="utf-8")
            return data
        except Exception as e:  # noqa: BLE001
            if attempt == 4:
                raise
            time.sleep(2 ** attempt)


def ge_mapping():
    d = json.loads(http(MAPPING_URL, "mapping.json"))
    by_name = {}
    for it in d:
        # first id wins for a display name (mapping has no dup display names)
        by_name.setdefault(it["name"], it["id"])
    return by_name


def all_recipe_pages():
    pages, cont = [], None
    while True:
        url = (WIKI + "?action=query&list=embeddedin&eititle=Template:Recipe"
               "&eilimit=500&einamespace=0&format=json")
        if cont:
            url += "&eicontinue=" + urllib.parse.quote(cont)
        d = json.loads(http(url, f"pages_{cont or 'start'}.json"))
        pages += [e["title"] for e in d["query"]["embeddedin"]]
        cont = d.get("continue", {}).get("eicontinue")
        if not cont:
            break
    return pages


def fetch_wikitext(titles):
    out = {}
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        url = (WIKI + "?action=query&prop=revisions&rvprop=content&rvslots=main"
               "&format=json&titles=" + urllib.parse.quote("|".join(batch)))
        d = json.loads(http(url, f"wt_{i:05d}.json"))
        for pg in d.get("query", {}).get("pages", {}).values():
            if "revisions" in pg:
                out[pg["title"]] = pg["revisions"][0]["slots"]["main"]["*"]
        time.sleep(0.05)
    return out


def find_templates(wikitext, name):
    """Every {{name ...}} block, brace-matched (handles nested {{ }})."""
    out = []
    needle = "{{" + name
    i = 0
    low = wikitext.lower()
    nlow = needle.lower()
    while True:
        start = low.find(nlow, i)
        if start < 0:
            break
        # ensure the char after the name is a boundary (| } or whitespace)
        after = wikitext[start + len(needle):start + len(needle) + 1]
        if after and (after.isalnum() or after == "_"):
            i = start + len(needle)
            continue
        depth = 0
        j = start
        while j < len(wikitext):
            if wikitext[j:j + 2] == "{{":
                depth += 1
                j += 2
            elif wikitext[j:j + 2] == "}}":
                depth -= 1
                j += 2
                if depth == 0:
                    break
            else:
                j += 1
        out.append(wikitext[start:j])
        i = j
    return out


def split_params(block):
    """Split a template body on top-level '|' (ignoring nested {{}} / [[]])."""
    inner = block[2:-2]  # strip {{ }}
    parts = []
    depth_c = depth_b = 0
    cur = []
    for ch in inner:
        if ch == "{":
            depth_c += 1
        elif ch == "}":
            depth_c = max(0, depth_c - 1)
        elif ch == "[":
            depth_b += 1
        elif ch == "]":
            depth_b = max(0, depth_b - 1)
        if ch == "|" and depth_c == 0 and depth_b == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts[1:]  # drop the template name


def clean_name(raw):
    s = raw.strip()
    s = re.sub(r"\[\[([^\]|]*\|)?([^\]]*)\]\]", r"\2", s)   # [[a|b]] -> b
    s = re.sub(r"\{\{[Pp]link\|([^}|]*).*?\}\}", r"\1", s)  # {{plink|x}} -> x
    s = re.sub(r"\{\{[^}]*\}\}", "", s)                     # drop other templates
    s = s.replace("[[", "").replace("]]", "").strip()
    return s


def parse_int(raw, default=1):
    if raw is None:
        return default
    m = re.match(r"\s*([0-9][0-9,]*)", str(raw))
    return int(m.group(1).replace(",", "")) if m else default


def parse_recipes(block):
    """One {{Recipe}} block -> list of (output_name, output_qty, [(mat, qty)])."""
    params = {}
    for part in split_params(block):
        if "=" not in part:
            continue
        k, v = part.split("=", 1)
        params[k.strip().lower()] = v.strip()

    mats = []
    for n in range(1, 30):
        key = f"mat{n}"
        if key not in params or not params[key].strip():
            continue
        name = clean_name(params[key])
        if not name or name in SKIP_MATERIALS:
            continue
        qty = parse_int(params.get(f"mat{n}quantity"))
        mats.append((name, qty))
    if not mats:
        return []

    outputs = []
    for n in range(1, 10):
        key = f"output{n}"
        if key not in params or not params[key].strip():
            continue
        name = clean_name(params[key])
        if not name:
            continue
        oqty = parse_int(params.get(f"output{n}quantity"))
        outputs.append((name, oqty))
    return [(oname, oqty, mats) for (oname, oqty) in outputs]


def main():
    print("Fetching Template:Recipe transclusions…", file=sys.stderr)
    pages = all_recipe_pages()
    print(f"  {len(pages)} pages", file=sys.stderr)
    print("Fetching wikitext…", file=sys.stderr)
    wt = fetch_wikitext(pages)
    print(f"  {len(wt)} pages with content", file=sys.stderr)
    mapping = ge_mapping()
    print(f"  {len(mapping)} mapped tradeable items", file=sys.stderr)

    # output id -> list of candidate recipes (materials resolved to ids)
    candidates = {}
    stats = {"blocks": 0, "recipes": 0, "skipped_unresolved": 0, "self_loop": 0}
    unresolved = {}
    for title, text in wt.items():
        for block in find_templates(text, "Recipe"):
            stats["blocks"] += 1
            for oname, oqty, mats in parse_recipes(block):
                stats["recipes"] += 1
                oid = mapping.get(oname)
                if oid is None:
                    stats["skipped_unresolved"] += 1
                    unresolved[oname] = unresolved.get(oname, 0) + 1
                    continue
                resolved = []
                ok = True
                for mname, mqty in mats:
                    mid = mapping.get(mname)
                    if mid is None:
                        ok = False
                        unresolved[mname] = unresolved.get(mname, 0) + 1
                        break
                    resolved.append({"itemId": mid, "qty": mqty, "name": mname})
                if not ok:
                    stats["skipped_unresolved"] += 1
                    continue
                if any(m["itemId"] == oid for m in resolved):
                    stats["self_loop"] += 1
                    continue
                candidates.setdefault(oid, []).append(
                    {"name": oname, "outputQty": oqty, "materials": resolved})

    # canonical: fewest distinct materials, then fewest total qty, then name
    recipes = {}
    for oid, cands in candidates.items():
        best = min(cands, key=lambda r: (len(r["materials"]),
                                         sum(m["qty"] for m in r["materials"]),
                                         r["name"]))
        recipes[str(oid)] = best

    out = {
        "$schema": "./schemas/recipes.schema.json",
        "source": "OSRS wiki Template:Recipe + prices.runescape.wiki GE mapping",
        "generated": time.strftime("%Y-%m-%d", time.gmtime()),
        "version": 1,
        "recipes": recipes,
    }
    OUT.write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")

    # ── sanity: the bracelet-of-slaughter chain must resolve end to end ──
    def need(oid, *mat_ids):
        r = recipes.get(str(oid))
        assert r, f"missing recipe for {oid}"
        got = {m["itemId"] for m in r["materials"]}
        for mid in mat_ids:
            assert mid in got, f"recipe {oid} missing material {mid}; got {got}"

    need(21183, 21123, 564, 554)   # Bracelet of slaughter <- topaz bracelet, cosmic, fire
    need(21123, 1613, 2355)        # Topaz bracelet <- red topaz, silver bar
    need(2355, 442)                # Silver bar <- silver ore
    assert len(recipes) > 1500, f"too few recipes: {len(recipes)}"

    print(json.dumps(stats), file=sys.stderr)
    top_unresolved = sorted(unresolved.items(), key=lambda kv: -kv[1])[:20]
    print(f"{len(recipes)} canonical recipes written to {OUT}", file=sys.stderr)
    print("top unresolved material/output names:", file=sys.stderr)
    for n, c in top_unresolved:
        print(f"  {c:4d}  {n}", file=sys.stderr)


if __name__ == "__main__":
    main()
