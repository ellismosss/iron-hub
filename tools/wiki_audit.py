#!/usr/bin/env python3
"""Audit gear-progression.json requirements against OSRS wiki prose.

Fetches each entry's wiki page (50/request), extracts (level, skill) pairs
from sentences containing 'require', and diffs them against the pack's
skill: requirements. Output = mismatch report for manual adjudication —
prose parsing is a flagging tool, not ground truth.
"""
import json, os, re, sys, time, urllib.request, urllib.parse

UA = {'User-Agent': 'iron-hub-dev-audit/1.0 (RuneLite plugin dev; contact info@ellismoss.co.uk)'}
API = 'https://oldschool.runescape.wiki/api.php'
SKILLS = ('Attack|Strength|Defence|Ranged|Magic|Prayer|Hitpoints|Slayer|Mining|Smithing|'
          'Fishing|Cooking|Firemaking|Woodcutting|Crafting|Fletching|Herblore|Agility|'
          'Thieving|Farming|Runecraft|Runecrafting|Hunter|Construction')

pack = json.load(open(os.path.join(os.path.dirname(__file__), '../src/main/resources/data/gear-progression.json')))
items = [i for p in pack['phases'] for g in p['groups'] for i in g['items']]

def page_title(i):
    return (i.get('wiki') or i['name'].replace(' ', '_')).replace('_', ' ').split('%26')[0].strip()

titles = {}
for i in items:
    titles.setdefault(page_title(i), []).append(i)

def fetch(batch):
    q = urllib.parse.urlencode({
        'action': 'query', 'prop': 'revisions', 'rvprop': 'content', 'rvslots': 'main',
        'format': 'json', 'redirects': 1, 'titles': '|'.join(batch)})
    req = urllib.request.Request(f'{API}?{q}', headers=UA)
    d = json.load(urllib.request.urlopen(req, timeout=30))
    out = {}
    redirect = {r['to']: r['from'] for r in d['query'].get('redirects', [])}
    norm = {n['to']: n['from'] for n in d['query'].get('normalized', [])}
    for page in d['query']['pages'].values():
        name = page.get('title', '')
        orig = redirect.get(name, name)
        orig = norm.get(orig, orig)
        if 'revisions' in page:
            out[orig] = page['revisions'][0]['slots']['main']['*']
        else:
            out[orig] = None
    return out

def require_pairs(text):
    """(level, skill) pairs from 'require' sentences in the lead prose."""
    # strip templates/tables crudely, keep lead prose
    body = re.sub(r'\{\{[^{}]*\}\}', '', text)
    body = re.sub(r'\{\{[^{}]*\}\}', '', body)  # nested pass
    lead = body[:4000]
    pairs = set()
    for sentence in re.split(r'(?<=[.!?])\s+', lead):
        if 'requir' not in sentence.lower():
            continue
        # "level 70 [[Attack]]", "75 [[Strength]]", "[[Attack]] level 70"
        for m in re.finditer(r'(\d+)\s*(?:\[\[)?(%s)\b' % SKILLS, sentence):
            pairs.add((int(m.group(1)), m.group(2).replace('Runecrafting', 'Runecraft')))
        for m in re.finditer(r'\[\[(%s)\]\]\s*(?:of\s*)?(?:level\s*)?(\d+)' % SKILLS, sentence):
            pairs.add((int(m.group(2)), m.group(1).replace('Runecrafting', 'Runecraft')))
    return pairs

all_titles = list(titles)
wikitext = {}
for i in range(0, len(all_titles), 50):
    wikitext.update(fetch(all_titles[i:i + 50]))
    time.sleep(1)

report = []
for title, entries in titles.items():
    text = wikitext.get(title)
    for e in entries:
        pack_reqs = {}
        for r in e['requirements']:
            if r.startswith('skill:'):
                _, skill, lvl = r.split(':')
                pack_reqs[skill] = int(lvl)
        if text is None:
            report.append(f"MISSING PAGE: {e['name']} -> {title}")
            continue
        wiki_pairs = require_pairs(text)
        wiki_by_skill = {}
        for lvl, sk in wiki_pairs:
            wiki_by_skill.setdefault(sk, set()).add(lvl)
        for skill, lvl in sorted(pack_reqs.items()):
            if skill in wiki_by_skill and lvl not in wiki_by_skill[skill]:
                report.append(f"LEVEL? {e['name']}: pack {skill} {lvl}, wiki mentions {sorted(wiki_by_skill[skill])}")
        for sk, lvls in sorted(wiki_by_skill.items()):
            if sk not in pack_reqs and any(l >= 20 for l in lvls):
                report.append(f"EXTRA? {e['name']}: wiki 'require' mentions {sk} {sorted(lvls)}, pack has none")

print('\n'.join(report))
print(f'\n{len(report)} flags across {len(items)} entries / {len(all_titles)} pages')
