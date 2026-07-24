#!/usr/bin/env python3
"""Generate data/poh.json — the COMPLETE player-owned-house catalog: every
one of the 24 buildable rooms, each with its hotspots, and each hotspot with
every piece of furniture that can be built there.

Source: the OSRS wiki.
  - The 24 room pages (e.g. Kitchen) give the hotspot structure: each
    ==section== is a hotspot, and each wikitable row is a furniture option.
    The furniture is the THUMBNAIL link ({{plinkt}} / {{ilinkt}}); the plain
    {{plink}} / {{ilink}} in the same row are its materials — that trailing
    "t" is the only discriminator, and both p- and i- forms occur (the whole
    Superior garden pool ladder uses ilinkt).
  - Each furniture's own page gives the hard data from its
    {{Infobox Construction}}: Construction level, the BUILT object id(s) (the
    in-house detection signal), the inventory item id used as the tile icon,
    and its {{Recipe}} build materials — plus a benefit sentence lifted from
    the page intro (what the furniture does for the player).
    Fields are read ONLY from inside that infobox — other infoboxes on the
    page carry unrelated ids (a pet's NPC id, the cape hanger's scenery list)
    — and the versioned forms (level1/id1/id2, used when one page covers a
    plain and a decorated variant) are tolerated.

Material names resolve against knowledge.db's items table (the wiki's own
names), so run tools/knowledge/rebuild.py first.

Levels emit as skillb:Construction:<n> — POH building is the textbook
boostable action gate (crystal saw + tea/stew). Material quantities are the
resource gate, shown as their own rows in the tab, not as requirements.

Fail-soft on a furniture page that 404s or lacks a Construction level (it is
skipped and logged); fails the build only if too many drop out or too many
materials go unresolved (a data-quality tripwire).

Usage: python3 tools/gen_poh.py
"""

import glob
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from collections import OrderedDict

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "knowledge"))
import kb  # noqa: E402 — the shared {{Recipe}} parser (pure functions only)

UA = "iron-hub-pack-generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-poh")
DB = os.path.join(HERE, "..", "knowledge", "knowledge.db")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "poh.json")
API = "https://oldschool.runescape.wiki/api.php"

# the 24 rooms, in the wiki's build order (parlour first, dungeon last)
ROOMS = [
    "Parlour", "Garden", "Kitchen", "Dining room", "Workshop", "Bedroom",
    "Hall (skill trophies)", "League hall", "Games room", "Combat room",
    "Hall (quest trophies)", "Menagerie", "Study", "Costume room", "Chapel",
    "Portal chamber", "Formal garden", "Throne room", "Superior garden",
    "Portal nexus", "Achievement gallery", "Oubliette", "Dungeon (Construction)",
    "Treasure room",
]

# room page title -> the display name shown in the tab
ROOM_DISPLAY = {
    "Hall (skill trophies)": "Skill Hall",
    "Hall (quest trophies)": "Quest Hall",
    "Dungeon (Construction)": "Dungeon",
}

# sections that are not hotspots
SKIP_SECTIONS = {"references", "gallery", "trivia", "changes", "history",
                 "see also", "notes", "directing a portal", "furniture"}

# a page whose plinkt link differs from its real title
PAGE_FIXES = {}


def fetch(page):
    os.makedirs(CACHE, exist_ok=True)
    slug = re.sub(r"[^A-Za-z0-9]+", "_", page)
    cached = os.path.join(CACHE, slug + ".json")
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return json.load(f)
    url = API + "?" + urllib.parse.urlencode(
        {"action": "parse", "page": page, "prop": "wikitext",
         "redirects": "1", "format": "json", "formatversion": "2"})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as resp:
        data = json.load(resp)
    with open(cached, "w", encoding="utf-8") as f:
        json.dump(data, f)
    time.sleep(0.2)
    return data


def room_page(room):
    slug = re.sub(r"[^A-Za-z0-9]+", "_", room)
    cached = os.path.join(CACHE, "room_" + slug + ".json")
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return json.load(f)["parse"]["wikitext"]
    data = fetch(room)
    # room pages are cached under room_ so the analysis and the generator share them
    with open(cached, "w", encoding="utf-8") as f:
        json.dump(data, f)
    return data["parse"]["wikitext"]


def room_hotspots(room):
    """OrderedDict{hotspot name: {"lead": prose, "names": [furniture pages]}}.
    The lead is the section's prose before its wikitable — often the best
    description of what the hotspot does."""
    text = room_page(room)
    out = OrderedDict()
    sections = re.split(r"\n==\s*([^=].*?)\s*==\s*\n", text)
    for i in range(1, len(sections), 2):
        header = sections[i].strip()
        if header.lower() in SKIP_SECTIONS:
            continue
        body = sections[i + 1]
        lead = body.split("{|", 1)[0]
        names = []
        for row in re.split(r"\n\|-", body):
            # the furniture uses the THUMBNAIL link variant (plinkt / ilinkt);
            # materials use the plain plink / ilink — that is the discriminator
            furn = re.search(r"\{\{[pi]linkt\|([^|}]+)", row)
            level = re.search(r"\n\|\s*(\d{1,3})\s*\n", row)
            if furn and level:
                name = furn.group(1).strip()
                if name not in names:
                    names.append(name)
        if names:
            out[header] = {"lead": lead, "names": names}
    return out


def template_block(text, name):
    """The full source of the first {{name ...}} template, brace-balanced, or
    None. Scoping matters: a furniture page often carries other infoboxes
    (a pet's Infobox Monster, a cape hanger's scenery list) whose own id
    fields would otherwise be scraped as built-object ids."""
    start = text.find("{{" + name)
    if start < 0:
        return None
    depth = 0
    i = start
    while i < len(text):
        if text.startswith("{{", i):
            depth += 1
            i += 2
            continue
        if text.startswith("}}", i):
            depth -= 1
            i += 2
            if depth == 0:
                return text[start:i]
            continue
        i += 1
    return text[start:]


def furniture(page):
    """Hard data for one furniture from its page, or None if it 404s / has no
    Construction level. {level, objectIds, icon, materials(raw names), intro}."""
    data = fetch(PAGE_FIXES.get(page, page))
    if "parse" not in data:
        return None
    text = data["parse"]["wikitext"]
    box_text = template_block(text, "Infobox Construction")
    if box_text is None:
        return None
    def field(key):
        """The infobox's value for a field, tolerating the multi-version form
        (a page covering "Marble fireplace"/"Decorated marble fireplace" writes
        level1/level2, never a plain level). Takes the first numeric one."""
        for pattern in (r"^\|\s*" + key + r"\s*=\s*(.+)$",
                        r"^\|\s*" + key + r"\d+\s*=\s*(.+)$"):
            for m in re.finditer(pattern, box_text, re.M):
                if re.search(r"\d", m.group(1)):
                    return m.group(1).strip()
        return None

    level_text = field("level")
    if level_text is None:
        return None
    level = int(re.search(r"\d+", level_text).group())
    box = {"itemid": field("itemid")}
    # the built object id(s) live under id / id1 / id2 / ... (one per lit-burner
    # or decoration state variant) — collect them all, deduped, in order. ONLY
    # from inside the construction infobox (see template_block).
    object_ids = []
    for m in re.finditer(r"^\|\s*id\d*\s*=\s*(.+)$", box_text, re.M):
        for x in re.findall(r"\d+", m.group(1)):
            if int(x) not in object_ids:
                object_ids.append(int(x))
    icon = None
    if box.get("itemid") and re.search(r"^\d+$", box["itemid"].strip()):
        icon = int(box["itemid"].strip())
    recipe = kb.recipe(text)
    materials = recipe["materials"] if recipe else []
    intro = text.split("\n==")[0]
    return {"level": level, "objectIds": object_ids, "icon": icon,
            "materials": materials, "intro": intro}


BOILERPLATE = re.compile(
    r"can be built|requires? \d+\s*Construction|grant(s|ing)?\b|gives?\b.*experience"
    r"|is a piece of furniture|must have a (hammer|saw)|player-owned house"
    r"|built in the\b", re.I)


def benefit_of(intro):
    """A short 'what it does for the player' line from a furniture intro,
    dropping the build-boilerplate sentences. None when purely cosmetic."""
    t = intro
    t = re.sub(r"\{\|.*?\|\}", " ", t, flags=re.S)             # wikitables
    # strip templates inner-out so a nested multi-version {{Infobox}} goes too
    prev = None
    while prev != t:
        prev = t
        t = re.sub(r"\{\{[^{}]*\}\}", "", t)
    t = re.sub(r"\[\[([^|\]]*\|)?([^\]]*)\]\]", r"\2", t)      # links
    t = re.sub(r"<ref[^>]*>.*?</ref>|<ref[^>]*/>", "", t, flags=re.S)
    t = re.sub(r"'''?|<[^>]+>", "", t)                          # bold, html
    t = re.sub(r"\s+", " ", t).strip()
    out = []
    for sentence in re.split(r"(?<=[.!])\s+", t):
        s = sentence.strip()
        # skip empty, boilerplate, or anything with residual markup
        if not s or BOILERPLATE.search(s) or re.search(r"[{}|=]", s):
            continue
        out.append(s)
        if len(" ".join(out)) > 90:
            break
    benefit = " ".join(out).strip()
    if len(benefit) > 200:
        benefit = benefit[:197].rstrip() + "…"
    return benefit or None


def slug(text):
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


def gameval_object_names():
    """gameval object id -> its constant NAMES, via javap on the runelite-api
    jar (the repo's fail-fast idiom — these are the client's own symbols)."""
    jars = [j for j in glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/net.runelite/runelite-api/*/*/"
        "runelite-api-*.jar")) if "sources" not in j and "javadoc" not in j]
    if not jars:
        raise SystemExit("no runelite-api jar in the Gradle cache — run a build first")
    out = {}
    for cls in ("net.runelite.api.gameval.ObjectID", "net.runelite.api.gameval.ObjectID1"):
        dump = subprocess.run(
            ["javap", "-classpath", sorted(jars)[-1], "-constants", cls],
            capture_output=True, text=True, check=True).stdout
        for m in re.finditer(r"public static final int (\w+) = (-?\d+);", dump):
            out.setdefault(int(m.group(2)), []).append(m.group(1))
    if len(out) < 40000:
        raise SystemExit(f"only {len(out)} gameval object ids — javap parse broke")
    return out


def expand_configured_variants(spaces, names):
    """Absorb the object ids the game swaps in once furniture is CONFIGURED.

    The wiki's {{Infobox Construction}} lists the object a hotspot builds, but
    that is only what stands there while the furniture is unconfigured. Set a
    destination on a Teak portal and the game replaces
    POH_PORTAL_TEAK_EMPTY with POH_PORTAL_TEAK_VARROCK — one of 47 per
    destination, none of which the wiki lists. Detection matches on object id,
    so before this every portal anyone actually uses was invisible to it.

    The `_EMPTY` suffix is the client's own marker for that placeholder, which
    is what makes the rule machine-derivable instead of hand-guessed. It is
    kept deliberately narrow: a looser "strip the last token" stem rule is
    WRONG, because POH_CURTAINS_1/2/3 are three different tiers of the same
    hotspot, and the stem POH_DISPLAY_ would drag POH_DISPLAY_CASE_RUNE1_6 (a
    different furniture entirely) into the boss-lair display. Families that do
    not mark their placeholder stay unexpanded rather than guessed at.
    """
    added = 0
    families = set()
    for space in spaces:
        for tier in space["tiers"]:
            extra = set()
            for oid in tier["objectIds"]:
                for name in names.get(oid, []):
                    if not name.endswith("_EMPTY"):
                        continue
                    stem = name[: -len("EMPTY")]
                    families.add(stem)
                    extra |= {j for j, ns in names.items()
                              if any(x.startswith(stem) for x in ns)}
            new = extra - set(tier["objectIds"])
            if new:
                tier["objectIds"] = sorted(set(tier["objectIds"]) | new)
                added += len(new)
    return added, sorted(families)


def split_league_collisions(spaces, names):
    """Stop a Leagues reskin being claimed as built by its base furniture.

    The wiki lists the same object ids on both "Marble portal" and "Raging
    echoes portal", so either one marked BOTH tiers built — and because the
    highest built tier is what the tab reports, an ordinary player with a
    marble portal was told they had a Raging echoes portal. That is an
    invented claim, which the pack must never make.

    The client's own symbols separate them (POH_PORTAL_LEAGUE_5_* vs
    POH_PORTAL_MARBLE_*), and the pack's other league tiers already pair the
    two — "Raging echoes rug" holds POH_LEAGUE5_RUG*, "Raging echoes curtains"
    holds POH_CURTAINS_LEAGUE5 — so the LEAGUE ids belong to the league tier
    alone. Applied ONLY inside a hotspot that actually has a league tier, so
    nothing else loses its reskin ids.
    """
    def is_league(oid):
        return any("LEAGUE" in n for n in names.get(oid, []))

    moved = 0
    for space in spaces:
        league = [t for t in space["tiers"] if t["name"].startswith("Raging echoes")]
        if not league:
            continue
        for tier in space["tiers"]:
            keep = [o for o in tier["objectIds"]
                    if is_league(o) == (tier in league)]
            if keep and keep != tier["objectIds"]:
                moved += len(tier["objectIds"]) - len(keep)
                tier["objectIds"] = keep
    return moved


def main():
    conn = sqlite3.connect(DB)
    cur = conn.cursor()

    def lookup(name):
        rows = cur.execute(
            "SELECT item_id FROM items WHERE name=? AND removal_date IS NULL",
            (name,)).fetchall()
        ids = [int(r[0]) for r in rows if str(r[0]).isdigit()]
        return min(ids) if ids else None

    def resolve(name):
        got = lookup(name)
        if got is not None:
            return got
        # a disambiguation suffix the items table drops: "Large map (item)",
        # "Fancy hedge (bagged)", "Decorative armour (gold platebody)"
        stripped = re.sub(r"\s*\([^)]*\)\s*$", "", name).strip()
        return lookup(stripped) if stripped != name else None

    # every furniture name across all rooms — a material that IS a furniture is
    # an upgrade base (the previous tier), not a gatherable item, so it is dropped
    all_furniture = set()
    for room in ROOMS:
        for hs in room_hotspots(room).values():
            all_furniture.update(hs["names"])

    spaces = []
    space_ids = set()
    missing = []
    unresolved = set()
    furn_count = 0

    for room in ROOMS:
        room_slug = slug(room)
        room_name = ROOM_DISPLAY.get(room, room)
        for hotspot, hs in room_hotspots(room).items():
            space_id = room_slug + "__" + slug(hotspot)
            if space_id in space_ids:
                sys.exit(f"duplicate space id {space_id}")
            tiers = []
            for name in hs["names"]:
                data = furniture(name)
                if data is None:
                    missing.append(name)
                    continue
                furn_count += 1
                mats = []
                for m in data["materials"]:
                    mname = m["name"]
                    if mname in all_furniture:
                        continue   # an upgrade base (the previous tier), not a material
                    item_id = resolve(mname)
                    if item_id is None:
                        unresolved.add(mname)
                        continue
                    qty = re.search(r"\d+", str(m.get("qty") or "1"))
                    mats.append({"itemId": item_id, "name": mname,
                                 "qty": int(qty.group()) if qty else 1})
                tier = {
                    "id": space_id + ":" + slug(name),
                    # "(Construction)" is a wiki disambiguation suffix — drop it
                    # from the display name, keep it on the page for the link
                    "name": re.sub(r"\s*\(Construction\)$", "", name),
                    "page": name,
                    "level": data["level"],
                    "reqs": ["skillb:Construction:" + str(data["level"])],
                    "objectIds": data["objectIds"],
                    "materials": mats,
                    "_intro": data["intro"],
                }
                if data["icon"] is not None:
                    tier["icon"] = data["icon"]
                tiers.append(tier)
            if not tiers:
                continue
            tiers.sort(key=lambda t: (t["level"], t["name"]))
            # the hotspot's own section prose describes it best; fall back to
            # the top tier's page intro
            benefit = benefit_of(hs["lead"]) or benefit_of(tiers[-1]["_intro"])
            for t in tiers:
                t.pop("_intro", None)
            icon = next((t["icon"] for t in tiers if t.get("icon")), None)
            space = {"id": space_id, "name": hotspot, "room": room_name,
                     "tiers": tiers}
            if icon is not None:
                space["icon"] = icon
            if benefit:
                space["benefit"] = benefit
            spaces.append(space)
            space_ids.add(space_id)

    # ── configured-object variants, from the client's own symbol table ────
    obj_names = gameval_object_names()
    added, families = expand_configured_variants(spaces, obj_names)
    print(f"configured variants: +{added} object ids across "
          f"{len(families)} placeholder families {families}")
    print(f"league collisions split: {split_league_collisions(spaces, obj_names)} "
          "ids moved off their base tier")
    if added < 150:
        sys.exit(f"only {added} configured variants absorbed — expected ~180; "
                 "the gameval ObjectID names or the _EMPTY convention moved")

    # ── sanity gates ──────────────────────────────────────────────────────
    print(f"rooms={len(ROOMS)} hotspots={len(spaces)} furniture={furn_count}")
    if missing:
        print(f"  skipped {len(missing)} furniture with no page/level: "
              f"{sorted(set(missing))[:12]}", file=sys.stderr)
    if unresolved:
        print(f"  {len(unresolved)} unresolved materials: "
              f"{sorted(unresolved)[:12]}", file=sys.stderr)
    if len(spaces) < 110:
        sys.exit(f"only {len(spaces)} hotspots — expected ~129")
    if furn_count < 380:
        sys.exit(f"only {furn_count} furniture — expected ~430")
    if len(missing) > 40:
        sys.exit(f"{len(missing)} furniture dropped — parser/fetch problem")
    if len(unresolved) > 15:
        sys.exit(f"{len(unresolved)} unresolved materials — resolver problem")

    # The game's unbuilt-hotspot markers. They are what the scene itself shows
    # while the player is editing, so detection can corroborate building mode
    # against the house in front of it instead of trusting one varbit whose
    # truthy values nothing available documents.
    built_ids = {i for s in spaces for t in s["tiers"] for i in t["objectIds"]}
    markers = sorted(i for i, ns in obj_names.items()
                     if i not in built_ids
                     and any(n.startswith("POH") and "HOTSPOT" in n for n in ns))
    print(f"build-mode hotspot markers: {len(markers)}")
    if len(markers) < 50:
        sys.exit(f"only {len(markers)} hotspot markers — the gameval names moved")

    pack = {"version": 4, "spaces": spaces, "buildModeMarkers": markers}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=1, ensure_ascii=False)
    n_tiers = sum(len(s["tiers"]) for s in spaces)
    print(f"wrote {os.path.relpath(OUT, os.path.join(HERE, '..'))} — "
          f"{len(spaces)} hotspots, {n_tiers} tiers across "
          f"{len(set(s['room'] for s in spaces))} rooms")


if __name__ == "__main__":
    main()
