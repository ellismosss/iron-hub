#!/usr/bin/env python3
"""Shared library for the Iron Hub knowledge base (Luke's 2026-07-21 goal:
harvest COMPLETE game knowledge per plugin domain into one database he can
open, navigate and correct).

Canonical store: knowledge/knowledge.db (SQLite, committed). Every table row
carries `src` (where the fact came from) and `flags` (unverified/partial
markers). The `progress` table tracks per-category coverage; the `gaps`
table is the "Luke, please help" list — anything the harvest could not
establish lands there instead of being invented.

Fetching follows the tools/ conventions: pinned descriptive User-Agent,
cache under tools/.cache-knowledge/ (gitignored), polite pacing, fail fast
on malformed responses. Bulk page content comes via the wiki's
generator=categorymembers API (50 full pages per request).
"""

import json
import os
import re
import sqlite3
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CACHE = os.path.join(ROOT, "tools", ".cache-knowledge")
DB_PATH = os.path.join(ROOT, "knowledge", "knowledge.db")
UA = "iron-hub-knowledge-harvester (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"
WIKI_API = "https://oldschool.runescape.wiki/api.php"


# ── fetching ─────────────────────────────────────────────────────────────

def _get(url: str, cache_name: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, cache_name)
    if os.path.exists(cached):
        with open(cached, encoding="utf-8") as f:
            return f.read()
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(5):
        time.sleep(0.6 if attempt == 0 else 15 * attempt)  # polite pace + backoff
        try:
            with urllib.request.urlopen(req) as resp:
                text = resp.read().decode("utf-8")
            break
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
        except urllib.error.URLError:
            if attempt == 4:  # transient SSL/connection drops retry too
                raise
    with open(cached, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def api(params: dict, cache_name: str) -> dict:
    params = dict(params, format="json", formatversion="2")
    url = WIKI_API + "?" + urllib.parse.urlencode(params)
    return json.loads(_get(url, cache_name))


def page_text(page: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "_", page)
    data = api({"action": "parse", "page": page, "prop": "wikitext"},
               f"page-{slug}.json")
    if "parse" not in data:
        raise SystemExit(f"wiki page missing: {page}")
    return data["parse"]["wikitext"]


def category_pages(category: str, namespace: int = 0):
    """Every page in a category with full wikitext: [(title, wikitext)].
    Batched 50/request, cached per batch."""
    out = []
    cont = {}
    batch = 0
    slug = re.sub(r"[^A-Za-z0-9]+", "_", category)
    while True:
        params = {
            "action": "query",
            "generator": "categorymembers",
            "gcmtitle": f"Category:{category}",
            "gcmnamespace": namespace,
            "gcmlimit": 50,
            "prop": "revisions",
            "rvprop": "content",
            "rvslots": "main",
        }
        params.update(cont)
        data = api(params, f"cat-{slug}-{batch}.json")
        for p in data.get("query", {}).get("pages", []):
            revs = p.get("revisions")
            if revs:
                out.append((p["title"], revs[0]["slots"]["main"]["content"]))
        cont = data.get("continue")
        if not cont:
            break
        cont = dict(cont)
        cont.pop("continue", None)
        batch += 1
    return out


def category_titles(category: str, namespace: int = 0):
    """Just the member titles of a category (cheap, one request per 500)."""
    out = []
    cont = {}
    batch = 0
    slug = re.sub(r"[^A-Za-z0-9]+", "_", category)
    while True:
        params = {
            "action": "query",
            "list": "categorymembers",
            "cmtitle": f"Category:{category}",
            "cmnamespace": namespace,
            "cmlimit": 500,
        }
        params.update(cont)
        data = api(params, f"cattitles-{slug}-{batch}.json")
        out.extend(m["title"] for m in data.get("query", {}).get("categorymembers", []))
        cont = data.get("continue")
        if not cont:
            break
        cont = dict(cont)
        cont.pop("continue", None)
        batch += 1
    return out


# ── wikitext parsing ─────────────────────────────────────────────────────

def templates(wikitext: str, name: str):
    """Every {{name ...}} template in a page as a param dict (brace-aware;
    positional params land under "1","2",...). Case-insensitive name."""
    out = []
    low = wikitext.lower()
    needle = "{{" + name.lower()
    start = 0
    while True:
        i = low.find(needle, start)
        if i < 0:
            return out
        # ensure the name is not a prefix of a longer template name
        after = wikitext[i + len(needle):i + len(needle) + 1]
        if after and after not in " |}\n\t":
            start = i + 2
            continue
        depth = 0
        j = i
        while j < len(wikitext) - 1:
            two = wikitext[j:j + 2]
            if two == "{{":
                depth += 1
                j += 2
            elif two == "}}":
                depth -= 1
                j += 2
                if depth == 0:
                    break
            else:
                j += 1
        body = wikitext[i + 2:j - 2]
        out.append(_params(body))
        start = j


def _params(body: str) -> dict:
    """Split a template body on TOP-LEVEL pipes into a param dict."""
    parts = []
    depth_t = 0  # {{ }}
    depth_l = 0  # [[ ]]
    current = []
    k = 0
    while k < len(body):
        two = body[k:k + 2]
        if two == "{{":
            depth_t += 1
            current.append(two)
            k += 2
        elif two == "}}":
            depth_t -= 1
            current.append(two)
            k += 2
        elif two == "[[":
            depth_l += 1
            current.append(two)
            k += 2
        elif two == "]]":
            depth_l -= 1
            current.append(two)
            k += 2
        elif body[k] == "|" and depth_t == 0 and depth_l == 0:
            parts.append("".join(current))
            current = []
            k += 1
        else:
            current.append(body[k])
            k += 1
    parts.append("".join(current))
    params = {}
    positional = 0
    for part in parts[1:]:  # parts[0] is the template name
        if "=" in part:
            key, _, value = part.partition("=")
            params[key.strip().lower()] = value.strip()
        else:
            positional += 1
            params[str(positional)] = part.strip()
    return params


def recipe(wikitext: str):
    """The page's first {{Recipe}} as {skills, materials, facilities?}, or
    None — how an item is MADE (shared by the consumable/equipment harvests)."""
    boxes = templates(wikitext, "Recipe")
    if not boxes:
        return None
    box = boxes[0]
    skills = []
    for i in range(1, 6):
        name = box.get(f"skill{i}")
        if name:
            skills.append({"skill": name.strip().capitalize(),
                           "level": box.get(f"skill{i}lvl", "1"),
                           "boostable": box.get(f"skill{i}boostable", "")})
    mats = []
    for i in range(1, 11):
        mat = box.get(f"mat{i}")
        if mat:
            mats.append({"name": mat.strip(), "qty": box.get(f"mat{i}quantity", "1")})
    out = {"skills": skills, "materials": mats}
    if box.get("facilities"):
        out["facilities"] = strip_markup(box["facilities"])
    return out if (skills or mats) else None


def wikitables(wikitext: str):
    """Every {| ... |} table as a list of rows, each row a list of raw cell
    strings (multi-line cells joined; header rows included with '!' cells)."""
    out = []
    for m in re.finditer(r"\{\|.*?\n(.*?)\n\|\}", wikitext, re.S):
        rows = []
        for chunk in re.split(r"\n\|-[^\n]*", "\n" + m.group(1)):
            lines = [l for l in chunk.split("\n") if l.strip()]
            cells = []
            for line in lines:
                if line.startswith(("|", "!")) and not line.startswith(("|+", "|}")):
                    body = line[1:]
                    for part in re.split(r"\|\||!!", body):
                        cells.append(part)
            # continuation lines belong to the previous cell
                elif cells:
                    cells[-1] += "\n" + line
            if cells:
                rows.append(cells)
        if rows:
            out.append(rows)
    return out


def strip_markup(text: str) -> str:
    """Wikitext -> plain-ish text (links to labels, refs/templates dropped)."""
    text = re.sub(r"<ref[^>]*>.*?</ref>", "", text, flags=re.S)
    text = re.sub(r"<ref[^>]*/>", "", text)
    text = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]", r"\1", text)
    text = re.sub(r"\{\{[^{}]*\}\}", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"'{2,}", "", text)
    return re.sub(r"\s+", " ", text).strip()


# ── the database ─────────────────────────────────────────────────────────

SCHEMA = """
CREATE TABLE IF NOT EXISTS progress(
    category TEXT PRIMARY KEY,
    expected INTEGER,           -- how many entries SHOULD exist (null = unknown)
    harvested INTEGER,          -- rows actually in the table
    flagged INTEGER,            -- rows carrying an unresolved flag
    source TEXT,                -- where the data came from
    updated_at TEXT,
    notes TEXT
);
CREATE TABLE IF NOT EXISTS gaps(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category TEXT NOT NULL,
    subject TEXT NOT NULL,      -- the item/task/method the gap is about
    field TEXT,                 -- which fact is missing
    why TEXT,                   -- what was tried / why it could not be established
    status TEXT DEFAULT 'open', -- open | resolved | wont-fix
    notes TEXT,                 -- Luke's column
    UNIQUE(category, subject, field)
);
CREATE TABLE IF NOT EXISTS equipment(
    name TEXT PRIMARY KEY,
    slot TEXT,
    item_ids TEXT,              -- JSON list (versions)
    members INTEGER,
    stats TEXT,                 -- JSON: full Infobox Bonuses params (per version)
    equip_reqs TEXT,            -- JSON list of requirement strings, or null
    effects TEXT,               -- prose: passive/set effects
    obtain TEXT,                -- JSON list: {how, detail, rate?}
    examine TEXT,
    src TEXT,
    flags TEXT                  -- comma flags: reqs-unverified, obtain-unverified, ...
);
CREATE TABLE IF NOT EXISTS poh_furniture(
    name TEXT NOT NULL,
    room TEXT,
    level INTEGER,
    materials TEXT,             -- JSON [{name, qty}]
    xp REAL,
    flatpackable INTEGER,
    reqs TEXT,                  -- JSON list beyond the Construction level
    effects TEXT,
    src TEXT,
    flags TEXT,
    PRIMARY KEY(name, room)
);
CREATE TABLE IF NOT EXISTS boosts(
    name TEXT NOT NULL,
    skill TEXT NOT NULL,
    amount TEXT,                -- the wiki's own expression (e.g. "+3", "2-6")
    circumstances TEXT,         -- how/when it applies
    reqs TEXT,
    src TEXT,
    flags TEXT,
    PRIMARY KEY(name, skill)
);
CREATE TABLE IF NOT EXISTS diary_tasks(
    region TEXT, tier TEXT, task TEXT,
    reqs TEXT, notes TEXT, src TEXT, flags TEXT,
    PRIMARY KEY(region, tier, task)
);
CREATE TABLE IF NOT EXISTS consumables(
    name TEXT PRIMARY KEY,
    kind TEXT,                  -- food | potion | drink | other
    effects TEXT,
    make_reqs TEXT,             -- JSON: skill/materials to create, or null
    obtain TEXT,                -- JSON list of ways to get it
    src TEXT, flags TEXT
);
CREATE TABLE IF NOT EXISTS clog_items(
    item_id INTEGER PRIMARY KEY,
    name TEXT,
    activity TEXT,
    drop_rate TEXT,
    what_it_does TEXT,
    gates TEXT,                 -- what having it unlocks/gates
    src TEXT, flags TEXT
);
CREATE TABLE IF NOT EXISTS ca_tasks(
    name TEXT PRIMARY KEY,
    tier TEXT, type TEXT, monster TEXT,
    description TEXT,
    reqs TEXT,                  -- gear/skill requirements (mostly curated)
    src TEXT, flags TEXT
);
CREATE TABLE IF NOT EXISTS boat_upgrades(
    part TEXT, tier TEXT,
    boat_type TEXT,
    reqs TEXT, materials TEXT, effects TEXT,
    src TEXT, flags TEXT,
    PRIMARY KEY(part, tier, boat_type)
);
CREATE TABLE IF NOT EXISTS qol_items(
    name TEXT PRIMARY KEY,
    effect TEXT, sources TEXT, reqs TEXT,
    src TEXT, flags TEXT
);
CREATE TABLE IF NOT EXISTS training_methods(
    skill TEXT, method TEXT,
    xp_hr TEXT, reqs TEXT, inputs TEXT, outputs TEXT, notes TEXT,
    src TEXT, flags TEXT,
    PRIMARY KEY(skill, method)
);
CREATE TABLE IF NOT EXISTS money_methods(
    method TEXT PRIMARY KEY,
    gp_hr TEXT, category TEXT, intensity TEXT,
    reqs TEXT, inputs TEXT, outputs TEXT,
    src TEXT, flags TEXT
);
"""


def db() -> sqlite3.Connection:
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.executescript(SCHEMA)
    return conn


def set_progress(conn, category, expected, table, source, notes=""):
    harvested = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] \
        if " " not in table else int(table)
    try:
        flagged = conn.execute(
            f"SELECT COUNT(*) FROM {table} WHERE flags IS NOT NULL AND flags != ''"
        ).fetchone()[0] if " " not in table else 0
    except sqlite3.OperationalError:
        flagged = 0  # raw tables carry no flags column
    conn.execute(
        "INSERT INTO progress(category, expected, harvested, flagged, source, updated_at, notes)"
        " VALUES(?,?,?,?,?,datetime('now'),?)"
        " ON CONFLICT(category) DO UPDATE SET expected=excluded.expected,"
        " harvested=excluded.harvested, flagged=excluded.flagged,"
        " source=excluded.source, updated_at=excluded.updated_at, notes=excluded.notes",
        (category, expected, harvested, flagged, source, notes))
    conn.commit()


def add_gap(conn, category, subject, field, why):
    # a re-detected gap REOPENS (harvests retire-then-rejudge); only a
    # manual wont-fix is sticky
    conn.execute(
        "INSERT INTO gaps(category, subject, field, why) VALUES(?,?,?,?)"
        " ON CONFLICT(category, subject, field) DO UPDATE SET why=excluded.why,"
        " status='open' WHERE gaps.status != 'wont-fix'",
        (category, subject, field, why))


def pack(name: str):
    """A bundled plugin data pack, parsed."""
    path = os.path.join(ROOT, "src", "main", "resources", "data", name + ".json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)
