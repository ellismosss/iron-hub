#!/usr/bin/env python3
"""Generate data/index/item-names.json: normalized item name -> id.

ItemID constants are RuneLite's normalization of cache names, so wiki item
names normalize straight onto them (uppercase, non-alnum -> _). First
occurrence (lowest id) wins, except known dead pre-release beta ids.
"""
import json, os, re
OVERRIDES = {
    'ULTOR_RING': 28307, 'MAGUS_RING': 28313, 'VENATOR_RING': 28310,
    'BELLATOR_RING': 28316, 'SOULREAPER_AXE': 28338,
}
here = os.path.dirname(__file__)
names = {}
for line in open(os.path.join(here, 'itemids.txt')):
    n, v = line.split()
    if re.fullmatch(r'.*_\d+', n):
        continue
    names.setdefault(n, int(v))
names.update(OVERRIDES)
out = os.path.join(here, '../src/main/resources/data/index/item-names.json')
json.dump(names, open(out, 'w'), separators=(',', ':'))
print(len(names), 'names')
