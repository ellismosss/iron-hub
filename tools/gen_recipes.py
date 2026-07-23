#!/usr/bin/env python3
"""Generate data/recipes.json — item crafting/production recipes for Goals v2
supply-goal component-material breakdown (a supply Route decomposes to its raw
materials in the Goals hub).

Source: the OSRS wiki's RECIPE BUCKET (action=bucket, the structured store
the {{Recipe}} templates feed — Luke's 2026-07-22 directive: prefer bucket
sources over wikitext parsing), joined to the official GE price-guide item
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


def bucket_productions():
    """Every production in the wiki's recipe bucket as
    (output_name, output_qty, [(material, qty)]) tuples — the same shape
    parse_recipes produced from wikitext."""
    rows, offset = [], 0
    while True:
        q = ("bucket('recipe').select('page_name','production_json')"
             f".offset({offset}).limit(5000).run()")
        url = WIKI + "?action=bucket&format=json&query=" + urllib.parse.quote(q)
        d = json.loads(http(url, f"bucket_{offset}.json"))
        batch = d.get("bucket", [])
        rows.extend(batch)
        if not batch:
            break
        offset += len(batch)
    if len(rows) < 5000:
        raise SystemExit(f"suspiciously few bucket recipes: {len(rows)}")
    out = []
    for row in rows:
        try:
            prod = json.loads(row.get("production_json") or "{}")
        except json.JSONDecodeError:
            continue
        mats = []
        for m in prod.get("materials") or []:
            name = clean_name(m.get("name") or "")
            if not name or name in SKIP_MATERIALS:
                continue
            mats.append((name, parse_int(m.get("quantity"))))
        if not mats:
            continue
        output = prod.get("output") or {}
        if isinstance(output, str):
            output = {"name": output}
        oname = clean_name(output.get("name") or "")
        if not oname:
            continue
        out.append((oname, parse_int(output.get("quantity")), mats))
    return out


def fetch_wikitext(titles, prefix="wt"):
    # prefix keys the cache per CALLER — reusing "wt_00000" across different
    # title sets silently returns the wrong pages (the flatpack supplement
    # fetched the old crawl's batches before this)
    out = {}
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        url = (WIKI + "?action=query&prop=revisions&rvprop=content&rvslots=main"
               "&format=json&titles=" + urllib.parse.quote("|".join(batch)))
        d = json.loads(http(url, f"{prefix}_{i:05d}.json"))
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
    # clamp: a handful of bucket rows carry quantity "0" (cost rows) — the
    # schema demands >=1 and zero-quantity materials are meaningless here
    return max(1, int(m.group(1).replace(",", ""))) if m else default


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
    print("Fetching the wiki recipe bucket…", file=sys.stderr)
    productions = bucket_productions()
    print(f"  {len(productions)} productions", file=sys.stderr)
    mapping = ge_mapping()
    print(f"  {len(mapping)} mapped tradeable items", file=sys.stderr)

    # flatpack supplement: the bucket does NOT cover POH furniture pages —
    # a flatpack is built from the same materials as its furniture, so the
    # furniture-page recipes emit "<name> (flatpack)" productions wherever
    # that flatpack is a real (mapped) item
    flat_n = 0
    furniture_titles = []
    for category in ("Furniture", "Flatpacks"):
        cont = None
        while True:
            url = (WIKI + "?action=query&list=categorymembers&cmtitle=Category:"
                   + category + "&cmnamespace=0&cmlimit=500&format=json")
            if cont:
                url += "&cmcontinue=" + urllib.parse.quote(cont)
            d = json.loads(http(url, f"cat_{category}_{cont or 'start'}.json"))
            furniture_titles += [m["title"] for m in d["query"]["categorymembers"]]
            cont = d.get("continue", {}).get("cmcontinue")
            if not cont:
                break
    for title, text in fetch_wikitext(furniture_titles, prefix="furn").items():
        for block in find_templates(text, "Recipe"):
            for oname, oqty, mats in parse_recipes(block):
                # furniture recipes list the flatpack as its OWN output row
                # (output2 = "Bookcase (flatpack)") — feed every output
                # through; the mapping filter below drops the unbuyable
                # furniture outputs themselves
                productions.append((oname, oqty, mats))
                flat_n += 1
    print(f"  +{flat_n} furniture-page productions (flatpacks ride output2)",
          file=sys.stderr)

    # chain-collapse: untradeable intermediates (coconut-milk unf potions)
    # are unmapped and would drop the whole chain — substitute their own
    # bucket materials instead (bounded depth), keeping raw-leaf honesty
    by_output = {}
    for oname, oqty, mats in productions:
        by_output.setdefault(oname, (oqty, mats))

    def collapse(mats, depth=0):
        out = []
        for name, qty in mats:
            if name in mapping or depth >= 3 or name not in by_output:
                out.append((name, qty))
                continue
            sub_qty, sub_mats = by_output[name]
            for sub_name, sq in collapse(sub_mats, depth + 1):
                out.append((sub_name, sq * qty))
        return out

    productions = [(oname, oqty, collapse(mats)) for oname, oqty, mats in productions]

    # output id -> list of candidate recipes (materials resolved to ids)
    candidates = {}
    stats = {"blocks": len(productions), "recipes": 0, "skipped_unresolved": 0, "self_loop": 0}
    unresolved = {}
    if True:
        if True:
            for oname, oqty, mats in productions:
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
        "source": "OSRS wiki recipe bucket + prices.runescape.wiki GE mapping",
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
