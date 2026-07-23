#!/usr/bin/env python3
"""Bundle the Progression hub's tile icons (Luke, 2026-07-24: the nine
collapsible module plates became a 4x2 icon tile grid).

One coherent family — the game's own inventory sprites, fetched at their
NATIVE 1x size from the wiki (never rescaled; the tile sizes to the art).
Each pick is the item players read as that system's emblem; the tile's
label and tooltip carry the module name, so the icon only has to be
recognisable, never encyclopaedic.

Writes src/main/resources/data/icons/osrs/progression/<slug>.png.
"""
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache-hub-icons")
ICON_ROOT = os.path.join(HERE, "..", "src", "main", "resources", "data", "icons", "osrs")
OUT = os.path.join(ICON_ROOT, "progression")
UA = "iron-hub-datagen/1.0 (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"

# tile slug -> the wiki File: name of the item's inventory sprite
ICONS = {
    "collection_log": "Collection log.png",       # the log itself
    "combat_tasks": "Ghommal's hilt 6.png",       # the combat-achievement reward
    "gear": "Rune platebody.png",                 # armour reads as "gear"
    "build": "Crystal saw.png",                   # PoH + Sailing: things you build
    "diaries": "Ardougne cloak 4.png",            # the diary reward everyone knows
    "quests": "Quest point cape.png",
    "clues": "Clue scroll (master).png",
    "qol": "Herb sack.png",                       # the archetypal QoL unlock
}

# The collection log's own five tabs, for the browser's category row. The
# game's overview screen uses a sprite per tab too; these are OUR picks (the
# tab's name and count ride in the tile's tooltip, so the emblem only has to
# be recognisable at a glance).
CLOG_ICONS = {
    "bosses": "Baby mole.png",              # a boss pet — the log's signature
    "raids": "Twisted bow.png",
    "clues": "Clue scroll (master).png",
    "minigames": "Fighter torso.png",
    "other": "Bird nest.png",
}


def fetch(file_name: str) -> bytes:
    safe = file_name.strip().replace(" ", "_")
    cached = os.path.join(CACHE, re.sub(r"[^A-Za-z0-9._-]+", "_", safe))
    if os.path.exists(cached):
        with open(cached, "rb") as f:
            return f.read()
    url = ("https://oldschool.runescape.wiki/w/Special:FilePath/"
           + urllib.parse.quote(safe))
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    data = None
    for attempt in range(5):
        time.sleep(1 if attempt == 0 else 15 * attempt)  # polite pace + 429 backoff
        try:
            with urllib.request.urlopen(req) as resp:
                data = resp.read()
            break
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
    if not data.startswith(b"\x89PNG"):
        raise SystemExit(f"not a PNG: {file_name}")
    os.makedirs(CACHE, exist_ok=True)
    with open(cached, "wb") as f:
        f.write(data)
    return data


# The Combat Achievements reward ladder: each tier hands over the next
# Ghommal's hilt, which is what the surface's hero banner counts between.
HILT_ICONS = {
    "hilt1": "Ghommal's hilt 1.png",
    "hilt2": "Ghommal's hilt 2.png",
    "hilt3": "Ghommal's hilt 3.png",
    "hilt4": "Ghommal's hilt 4.png",
    "hilt5": "Ghommal's hilt 5.png",
    "hilt6": "Ghommal's hilt 6.png",
}


def bundle(icons: dict, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    for slug, file_name in icons.items():
        data = fetch(file_name)
        # native size sanity: inventory sprites are at most 36x32
        width = int.from_bytes(data[16:20], "big")
        height = int.from_bytes(data[20:24], "big")
        if not (8 <= width <= 36 and 8 <= height <= 36):
            raise SystemExit(f"{file_name}: {width}x{height} is not an "
                             "inventory sprite (wrong wiki file?)")
        with open(os.path.join(out_dir, slug + ".png"), "wb") as f:
            f.write(data)
        print(f"{slug:16s} {file_name:28s} {width}x{height}")


def main():
    bundle(ICONS, OUT)
    bundle(CLOG_ICONS, os.path.join(ICON_ROOT, "clog"))
    bundle(HILT_ICONS, os.path.join(ICON_ROOT, "cahilt"))
    print(f"wrote {len(ICONS)} hub tile icons, {len(CLOG_ICONS)} collection log tabs, "
          f"{len(HILT_ICONS)} combat achievement hilts")


if __name__ == "__main__":
    main()
