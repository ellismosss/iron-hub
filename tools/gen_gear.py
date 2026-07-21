#!/usr/bin/env python3
"""Generate gear-progression.json from ItemID constant names.

Every item is specified by its net.runelite.api.ItemID constant name and
resolved against the javap dump (itemids.txt) — an unknown constant aborts
the run, so a typo'd id can never ship. Quest requirement names are checked
against the Quest enum display names (questnames.txt).
"""
import json, os, re, sys, time, urllib.parse, urllib.request

UA = "IronHub RuneLite plugin data generator (github.com/ellismosss/iron-hub; info@ellismoss.co.uk)"


def fetch_pages(titles, cache_dir):
    """Batch-fetch wiki page wikitext, cached per title."""
    out = {}
    to_fetch = []
    for t in titles:
        cached = os.path.join(cache_dir, re.sub(r"[^A-Za-z0-9]+", "_", t) + ".txt")
        if os.path.exists(cached):
            out[t] = open(cached, encoding="utf-8").read()
        else:
            to_fetch.append(t)
    for i in range(0, len(to_fetch), 50):
        batch = to_fetch[i:i + 50]
        url = ("https://oldschool.runescape.wiki/api.php?"
               + urllib.parse.urlencode({
                   "action": "query", "prop": "revisions", "rvprop": "content",
                   "rvslots": "main", "redirects": "1", "format": "json",
                   "formatversion": "2", "titles": "|".join(batch)}))
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req) as resp:
            data = json.load(resp)
        time.sleep(1)
        normalized = {}
        for n in data["query"].get("normalized", []) + data["query"].get("redirects", []):
            normalized[n["from"]] = n["to"]
        by_title = {p["title"]: p for p in data["query"]["pages"]}
        for t in batch:
            page = by_title.get(normalized.get(t.replace("_", " "), t.replace("_", " ")))
            if page and "revisions" in page:
                text = page["revisions"][0]["slots"]["main"]["content"]
                out[t] = text
                os.makedirs(cache_dir, exist_ok=True)
                cached = os.path.join(cache_dir, re.sub(r"[^A-Za-z0-9]+", "_", t) + ".txt")
                open(cached, "w", encoding="utf-8").write(text)
    return out


def parse_recipe_materials(wikitext):
    """Materials from the page's first {{Recipe}}: [(name, qty)]."""
    m = re.search(r"\{\{Recipe(.*?)\n\}\}", wikitext, re.DOTALL)
    if not m:
        return []
    block = m.group(1)
    mats = []
    for i in range(1, 16):
        nm = re.search(r"\|\s*mat%d\s*=\s*([^\n|]+)" % i, block)
        if not nm:
            break
        qm = re.search(r"\|\s*mat%dquantity\s*=\s*([\d,]+)" % i, block)
        qty = int(qm.group(1).replace(",", "")) if qm else 1
        mats.append((nm.group(1).strip(), qty))
    return mats


def normalize_item_name(name):
    return re.sub(r"[^A-Za-z0-9]+", "_", name.upper()).strip("_")

IDS = {}
for line in open(os.path.join(os.path.dirname(__file__), 'itemids.txt')):
    n, v = line.split()
    IDS.setdefault(n, int(v))  # first occurrence wins (base ids come first)
QUESTS = {q.strip().lower() for q in open(os.path.join(os.path.dirname(__file__), 'questnames.txt'))}

CATS = {'melee', 'ranged', 'magic', 'utility', 'poh', 'boat'}
missing = []

def I(name, const, cats, reqs=(), wiki=None, exact=False, implies=(), hours=None, source_note=None,
      materials=()):
    """Detectable item: const is both icon and ownership detection.
    materials = ((CONST, qty, 'Display name'), ...) — bulk items the craft
    consumes (shards, bars); rare COMPONENT drops belong in reqs as
    item:CONST:qty:Name so they route as their own obtain step."""
    e = _entry(name, const, None, cats, reqs, wiki)
    if exact:
        e['exact'] = True
    if implies:
        e['implies'] = list(implies)
    if hours is not None:
        e['hours'] = hours
    if source_note is not None:
        e['sourceNote'] = source_note
    if materials:
        mats = []
        for mc, qty, display in materials:
            if mc not in IDS:
                missing.append(f'MATERIAL? {mc} ({name})')
                continue
            mats.append({'itemId': IDS[mc], 'qty': qty, 'name': display})
        e['materials'] = mats
    return e

def M(name, icon_const, cats, reqs=(), wiki=None, iconfile=None, implies=()):
    """Manual entry (POH furniture / undetectable): obtained by right-click mark.
    iconfile = bundled wiki object icon under /data/icons/poh/."""
    e = _entry(name, None, icon_const, cats, reqs, wiki)
    if iconfile:
        e['iconFile'] = 'poh/' + iconfile
    if implies:
        e['implies'] = list(implies)
    return e

def _entry(name, const, icon_const, cats, reqs, wiki):
    for c in cats:
        assert c in CATS, f'bad category {c} on {name}'
    reqs = [_resolve_req(r, name) for r in reqs]
    for r in reqs:
        if r.startswith('quest:'):
            q = r[len('quest:'):].strip().lower()
            if q not in QUESTS:
                missing.append(f'QUEST? {r} ({name})')
    e = {'name': name}
    for key, cn in (('itemId', const), ('iconItemId', icon_const)):
        if cn:
            if cn not in IDS:
                missing.append(f'ITEM? {cn} ({name})')
                e[key] = 0
            else:
                e[key] = IDS[cn]
    e['categories'] = list(cats)
    e['requirements'] = list(reqs)
    if wiki:
        e['wiki'] = wiki
    return e

def _resolve_req(r, entry_name):
    """item:/itemx: reqs may name the id by ItemID CONSTANT for safety —
    resolve to the numeric id here, failing the run on an unknown name."""
    for prefix in ('item:', 'itemx:'):
        if r.startswith(prefix):
            parts = r.split(':')
            if not parts[1].isdigit():
                if parts[1] not in IDS:
                    missing.append(f'REQITEM? {parts[1]} ({entry_name})')
                    return r
                parts[1] = str(IDS[parts[1]])
                return ':'.join(parts)
    return r


def G(label, *items):
    return {'label': label, 'items': list(items)}

phases = [
  {'phase': 1, 'name': 'Early game', 'groups': [
    G('First steps',
      I("Chronicle", 'CHRONICLE', ['utility']),
      I("Ardougne cloak 1", 'ARDOUGNE_CLOAK_1', ['utility'], ['skill:Thieving:15'], 'Ardougne_cloak'),
      I("Climbing boots", 'CLIMBING_BOOTS', ['melee'], ['quest:Death Plateau']),
      I("Anti-dragon shield", 'ANTIDRAGON_SHIELD', ['melee', 'magic'], [], 'Anti-dragon_shield'),
    ),
    G('[L1] Foundations',
      I("Amulet of strength", 'AMULET_OF_STRENGTH', ['melee'], ['skill:Crafting:50', 'skill:Magic:49']),
      I("Rune pouch", 'RUNE_POUCH', ['utility'],
        source_note='750 slayer points — accrues from normal slayer tasks'),
      I("Adamant scimitar", 'ADAMANT_SCIMITAR', ['melee'], ['quest:The Feud']),
      I("Proselyte hauberk", 'PROSELYTE_HAUBERK', ['melee'], ['quest:The Slug Menace', 'skill:Defence:30', 'skill:Prayer:20']),
      I("Rune platebody", 'RUNE_PLATEBODY', ['melee'], ['quest:Dragon Slayer I', 'skill:Defence:40']),
    ),
    G('[L2] First real weapons',
      I("Iban's staff (u)", 'IBANS_STAFF_U', ['magic'], ['quest:Underground Pass', 'skill:Magic:50', 'skill:Attack:50'], "Iban's_staff"),
      I("Ancient staff", 'ANCIENT_STAFF', ['magic'], ['quest:Desert Treasure I', 'skill:Magic:50', 'skill:Attack:50']),
      I("Mystic robe top", 'MYSTIC_ROBE_TOP', ['magic'], ['skill:Magic:66'], 'Mystic_robes'),
    ),
    G('Early ranged track',
      I("Dorgeshuun crossbow", 'DORGESHUUN_CROSSBOW', ['ranged'], ['quest:The Lost Tribe', 'skill:Ranged:28']),
      I("Studded body", 'STUDDED_BODY', ['ranged'], ['skill:Ranged:20']),
      I("Snakeskin body", 'SNAKESKIN_BODY', ['ranged'], ['skill:Ranged:30']),
      I("Ava's attractor", 'AVAS_ATTRACTOR', ['ranged'], ['quest:Animal Magnetism', 'skill:Ranged:30'], "Ava's_attractor"),
      I("Green d'hide body", 'GREEN_DHIDE_BODY', ['ranged'], ['quest:Dragon Slayer I', 'skill:Ranged:40'], "Green_d'hide_body"),
      I("Magic shortbow", 'MAGIC_SHORTBOW', ['ranged'], ['skill:Ranged:50']),
      I("Ava's accumulator", 'AVAS_ACCUMULATOR', ['ranged'], ['quest:Animal Magnetism', 'skill:Ranged:50'], "Ava's_accumulator", implies=["Ava's attractor"]),
    ),
    G('[L3] Barbarian Assault',
      I("Fighter torso", 'FIGHTER_TORSO', ['melee'], ['skill:Defence:40']),
      I("Granite body", 'GRANITE_BODY', ['melee'], ['skill:Defence:50', 'skill:Strength:50']),
    ),
    G('[L4] Scimitar, spec and ring',
      I("Dragon scimitar", 'DRAGON_SCIMITAR', ['melee'], ['quest:Monkey Madness I', 'skill:Attack:60']),
      I("Dragon dagger", 'DRAGON_DAGGER', ['melee'], ['quest:Lost City', 'skill:Attack:60']),
      I("Berserker ring (i)", 'BERSERKER_RING_I', ['melee'], ['kc:Dagannoth Rex:1'], 'Berserker_ring', exact=True),
    ),
    G('[L5] Early-game capstones',
      I("Helm of Neitiznot", 'HELM_OF_NEITIZNOT', ['melee'], ['quest:The Fremennik Isles', 'skill:Defence:55']),
      I("God cape", 'SARADOMIN_CAPE', ['magic'], ['skill:Magic:60'], 'God_capes'),
      I("Fire cape", 'FIRE_CAPE', ['melee'], ['kc:TzTok-Jad:1']),
      I("Barrows gloves", 'BARROWS_GLOVES', ['melee', 'ranged', 'magic'], ['quest:Recipe for Disaster']),
    ),
    G('Phase 1 utility',
      I("Ectophial", 'ECTOPHIAL', ['utility'], ['quest:Ghosts Ahoy']),
      I("Dramen staff", 'DRAMEN_STAFF', ['utility'], ['quest:Lost City'], 'Fairy_ring'),
      I("Kharedst's memoirs", 'KHAREDSTS_MEMOIRS', ['utility'], ['quest:Client of Kourend'], "Kharedst's_memoirs"),
      I("Imcando hammer", 'IMCANDO_HAMMER', ['utility'], ['quest:Below Ice Mountain']),
      I("Coal bag", 'COAL_BAG_12019', ['utility'], ['skill:Mining:30'],
        hours=6, source_note='100 golden nuggets · Motherlode Mine'),
      I("Gem bag", 'GEM_BAG_12020', ['utility'], ['skill:Mining:30'],
        hours=6, source_note='100 golden nuggets · Motherlode Mine'),
    ),
  ]},
  {'phase': 2, 'name': 'Mid game', 'groups': [
    G('[L6] Mid-game keystones',
      I("Dragon defender", 'DRAGON_DEFENDER', ['melee'], ['skill:Attack:65', 'skill:Strength:65', 'skill:Defence:60']),
      I("Book of the dead", 'BOOK_OF_THE_DEAD', ['magic', 'utility'], ['quest:A Kingdom Divided'], implies=["Kharedst's memoirs"]),
      I("Salve amulet (ei)", 'SALVE_AMULETEI', ['melee', 'ranged', 'magic'], ['quest:Haunted Mine'], 'Salve_amulet_(ei)', exact=True),
    ),
    G('Weapons and Barrows',
      I("Zombie axe", 'ZOMBIE_AXE', ['melee'], ['quest:Defender of Varrock', 'skill:Attack:65', 'skill:Smithing:70']),
      I("Dharok's platebody", 'DHAROKS_PLATEBODY', ['melee'], ['skill:Defence:70'], "Dharok_the_Wretched's_equipment"),
      I("Guthan's chainskirt", 'GUTHANS_CHAINSKIRT', ['melee'], ['skill:Defence:70'], "Guthan_the_Infested's_equipment"),
    ),
    G('Moons of Peril',
      I("Dual macuahuitl", 'DUAL_MACUAHUITL', ['melee'], ['skill:Attack:70', 'skill:Strength:75']),
      I("Blood moon chestplate", 'BLOOD_MOON_CHESTPLATE', ['melee'], ['skill:Strength:75', 'skill:Defence:50']),
      I("Blood moon tassets", 'BLOOD_MOON_TASSETS', ['melee'], ['skill:Strength:75', 'skill:Defence:50']),
      I("Eclipse atlatl", 'ECLIPSE_ATLATL', ['ranged'], ['skill:Ranged:75', 'skill:Attack:50', 'skill:Strength:50']),
      I("Eclipse moon chestplate", 'ECLIPSE_MOON_CHESTPLATE', ['ranged'], ['skill:Ranged:75', 'skill:Defence:50']),
      I("Blue moon spear", 'BLUE_MOON_SPEAR', ['magic'], ['skill:Attack:70', 'skill:Magic:75']),
      I("Amulet of glory", 'AMULET_OF_GLORY', ['utility'], ['any:skill:Crafting:80|skill:Hunter:83', 'skill:Magic:68']),
    ),
    G('[L7-9] Capes, boots and MTA',
      I("Mixed hide cape", 'MIXED_HIDE_CAPE', ['ranged'], ['skill:Ranged:60', 'skill:Defence:50']),
      I("Mixed hide boots", 'MIXED_HIDE_BOOTS', ['ranged'], ['skill:Ranged:60', 'skill:Defence:50']),
      I("Imbued god cape", 'IMBUED_SARADOMIN_CAPE', ['magic'], ['skill:Magic:75', 'any:item:2412:1:Saradomin cape|item:2413:1:Guthix cape|item:2414:1:Zamorak cape'], 'Imbued_god_cape', implies=['God cape']),
      I("Infinity boots", 'INFINITY_BOOTS', ['magic'], ['skill:Magic:50', 'skill:Defence:25']),
      I("Mage's book", 'MAGES_BOOK', ['magic'], ['skill:Magic:60'], "Mage's_book"),
    ),
    G('[L10-15] The slayer on-ramp',
      I("Arkan blade", 'ARKAN_BLADE', ['melee'], ['quest:The Final Dawn', 'skill:Attack:60']),
      I("Black mask (i)", 'BLACK_MASK_I', ['melee'], ['skill:Slayer:58', 'skill:Strength:20', 'skill:Defence:10'], 'Black_mask', exact=True),
      I("Pharaoh's sceptre", 'PHARAOHS_SCEPTRE', ['utility'], ['skill:Thieving:21'], "Pharaoh's_sceptre"),
      I("Arclight", 'ARCLIGHT', ['melee'], ['skill:Attack:75']),
      I("Slayer helmet (i)", 'SLAYER_HELMET_I', ['melee', 'ranged', 'magic'], ['skill:Slayer:58', 'item:8901:1:Black mask'], 'Slayer_helmet', exact=True, implies=['Black mask (i)']),
      I("Ghommal's hilt 2", 'GHOMMALS_HILT_2', ['utility'], [], "Ghommal's_hilt", exact=True),
    ),
    G('Royal Titans',
      I("Deadeye prayer scroll", 'DEADEYE_PRAYER_SCROLL', ['ranged'], ['skill:Prayer:62'], 'Deadeye'),
      I("Mystic vigour prayer scroll", 'MYSTIC_VIGOUR_PRAYER_SCROLL', ['magic'], ['skill:Prayer:63'], 'Mystic_Vigour'),
      I("Twinflame staff", 'TWINFLAME_STAFF', ['magic'], ['skill:Magic:60']),
      I("Giantsoul amulet", 'GIANTSOUL_AMULET', ['utility'], [], 'Giantsoul_amulet'),
      I("Warped sceptre", 'WARPED_SCEPTRE', ['magic'], ['quest:The Path of Glouphrie', 'skill:Magic:62', 'skill:Slayer:56']),
      I("Hunters' sunlight crossbow", 'HUNTERS_SUNLIGHT_CROSSBOW', ['ranged'], ['skill:Ranged:66', 'skill:Hunter:72', 'skill:Fletching:74'], "Hunters'_sunlight_crossbow"),
      I("Dragon hunter wand", 'DRAGON_HUNTER_WAND', ['magic'], ['skill:Magic:65']),
    ),
    G('[L16-19] Void and the chinning ramp',
      I("Void knight top", 'VOID_KNIGHT_TOP', ['ranged', 'melee', 'magic'], ['skill:Prayer:22', 'skill:Attack:42', 'skill:Strength:42', 'skill:Defence:42', 'skill:Hitpoints:42', 'skill:Ranged:42', 'skill:Magic:42'], 'Void_Knight_equipment'),
      I("Void ranger helm", 'VOID_RANGER_HELM', ['ranged'], ['skill:Prayer:22', 'skill:Attack:42', 'skill:Strength:42', 'skill:Defence:42', 'skill:Hitpoints:42', 'skill:Ranged:42', 'skill:Magic:42'], 'Void_Knight_equipment'),
      I("Elite void top", 'ELITE_VOID_TOP', ['ranged', 'magic'], ['diary:Western Provinces:Hard', 'skill:Prayer:22', 'skill:Attack:42', 'skill:Strength:42', 'skill:Defence:42', 'skill:Hitpoints:42', 'skill:Ranged:42', 'skill:Magic:42', 'item:8839:1:Void knight top'], 'Elite_Void_Knight_equipment', implies=['Void knight top']),
      I("Crystal halberd", 'CRYSTAL_HALBERD', ['melee'], ['quest:Roving Elves', 'skill:Attack:70', 'skill:Strength:35', 'skill:Agility:50']),
      I("Red chinchompa", 'RED_CHINCHOMPA', ['ranged'], ['skill:Hunter:63', 'skill:Ranged:55']),
    ),
    G('Convenience grinds',
      I("Fish barrel", 'FISH_BARREL', ['utility'], ['skill:Fishing:35'],
        hours=10, source_note='Tempoross reward pool · ~1/400 per permit roll'),
      I("Tackle box", 'TACKLE_BOX', ['utility'], ['skill:Fishing:35'],
        hours=10, source_note='Tempoross reward pool · ~1/400 per permit roll'),
      I("Gem sack", 'GEM_SACK', ['utility'], ['item:12020:1:Gem bag'], implies=['Gem bag']),
      I("Plank sack", 'PLANK_SACK', ['utility'], ['skillb:Construction:20'],
        hours=3.5, source_note='350 carpenter points · Mahogany Homes Reward Shop'),
      I("Amy's saw", 'AMYS_SAW', ['utility'], ['skillb:Construction:20'], "Amy's_saw",
        hours=5, source_note='500 carpenter points · Mahogany Homes Reward Shop'),
      I("Seed box", 'SEED_BOX', ['utility'], ['skill:Farming:34'],
        hours=2.5, source_note='250 Tithe Farm points · Farmer Gricoller'),
      I("Herb sack", 'HERB_SACK', ['utility'], ['skillb:Farming:34'],
        hours=2.5, source_note='250 Tithe Farm points (or 750 slayer points)'),
      I("Divine rune pouch", 'DIVINE_RUNE_POUCH', ['utility'], ['quest:Beneath Cursed Sands', 'skill:Crafting:75', 'item:12791:1:Rune pouch'], implies=['Rune pouch']),
      I("Fletching knife", 'FLETCHING_KNIFE', ['utility']),
      I("Basic quetzal whistle", 'BASIC_QUETZAL_WHISTLE', ['utility'], ['skill:Hunter:46']),
      I("Pendant of Ates", 'PENDANT_OF_ATES', ['utility'], ['quest:The Heart of Darkness'], 'Pendant_of_Ates'),
      I("Huntsman's kit", 'HUNTSMANS_KIT', ['utility'], ['skill:Hunter:46'], "Huntsman's_kit"),
      I("Costume needle", 'COSTUME_NEEDLE', ['utility'], ['quest:Death on the Isle']),
      I("Bottomless compost bucket", 'BOTTOMLESS_COMPOST_BUCKET', ['utility'], ['skill:Farming:65']),
      I("Ash covered tome", 'ASH_COVERED_TOME', ['utility'], ['skill:Mining:50']),
    ),
  ]},
  {'phase': 3, 'name': 'Late game', 'groups': [
    G('[L20] The Corrupted Gauntlet',
      I("Bow of Faerdhinen", 'BOW_OF_FAERDHINEN', ['ranged'], ['quest:Song of the Elves', 'skill:Ranged:80']),
      I("Crystal helm", 'CRYSTAL_HELM', ['ranged'], ['quest:Song of the Elves', 'skill:Defence:70', 'skill:Agility:50'], 'Crystal_armour'),
      I("Crystal body", 'CRYSTAL_BODY', ['ranged'], ['quest:Song of the Elves', 'skill:Defence:70', 'skill:Agility:50'], 'Crystal_armour'),
      I("Crystal legs", 'CRYSTAL_LEGS', ['ranged'], ['quest:Song of the Elves', 'skill:Defence:70', 'skill:Agility:50'], 'Crystal_armour'),
    ),
    G('[L21-25] Post-Gauntlet consolidation',
      I("Amulet of fury", 'AMULET_OF_FURY', ['melee', 'ranged', 'magic'], ['skill:Crafting:90', 'skill:Magic:87']),
      I("Bloodbark body", 'BLOODBARK_BODY', ['magic'], ['skill:Magic:60', 'skill:Defence:60', 'skill:Runecraft:81'], 'Bloodbark_armour'),
      I("Ava's assembler", 'AVAS_ASSEMBLER', ['ranged'], ['quest:Dragon Slayer II', 'kc:Vorkath:50', 'skill:Ranged:70', "item:10499:1:Ava's accumulator"], "Ava's_assembler", implies=["Ava's accumulator"]),
      I("Dragon pickaxe", 'DRAGON_PICKAXE', ['utility'], ['skill:Mining:61', 'skill:Attack:60']),
      I("Explorer's ring 4", 'EXPLORERS_RING_4', ['utility'], ['diary:Lumbridge & Draynor:Elite'], "Explorer's_ring", exact=True),
    ),
    G('[L26] Zenytes and the GWD spec pair',
      I("Necklace of anguish", 'NECKLACE_OF_ANGUISH', ['ranged'], ['quest:Monkey Madness II', 'skill:Hitpoints:75', 'skill:Magic:93', 'skill:Crafting:92']),
      I("Amulet of torture", 'AMULET_OF_TORTURE', ['melee'], ['quest:Monkey Madness II', 'skill:Hitpoints:75', 'skill:Magic:93', 'skill:Crafting:98']),
      I("Zamorakian hasta", 'ZAMORAKIAN_HASTA', ['melee'], ['skill:Attack:70']),
      I("Bandos godsword", 'BANDOS_GODSWORD', ['melee'], ['skill:Attack:75', 'kc:General Graardor:1']),
    ),
    G('[L27-30] Voidwaker, prayers, ToA dip',
      I("Voidwaker", 'VOIDWAKER', ['melee'], ['skill:Attack:75']),
      I("Lightbearer", 'LIGHTBEARER', ['utility'], ['quest:Beneath Cursed Sands']),
      I("Ghommal's hilt 4", 'GHOMMALS_HILT_4', ['utility'], [], "Ghommal's_hilt", exact=True, implies=["Ghommal's hilt 2"]),
    ),
    G('[L31] The great slayer block',
      I("Abyssal whip", 'ABYSSAL_WHIP', ['melee'], ['skill:Slayer:85', 'skill:Attack:70']),
      I("Abyssal tentacle", 'ABYSSAL_TENTACLE', ['melee'], ['skill:Slayer:87', 'skill:Attack:75', 'item:4151:1:Abyssal whip'], implies=['Abyssal whip']),
      I("Dragon warhammer", 'DRAGON_WARHAMMER', ['melee'], ['skill:Strength:60']),
      I("Emberlight", 'EMBERLIGHT', ['melee'], ['quest:While Guthix Sleeps', 'skill:Attack:77', 'skill:Smithing:74', 'item:19675:1:Arclight'], implies=['Arclight']),
      I("Scorching bow", 'SCORCHING_BOW', ['ranged'], ['quest:While Guthix Sleeps', 'skill:Ranged:77', 'skill:Fletching:74']),
      I("Purging staff", 'PURGING_STAFF', ['magic'], ['quest:While Guthix Sleeps', 'skill:Magic:77', 'skill:Attack:50', 'skill:Crafting:74']),
      I("Burning claws", 'BURNING_CLAWS', ['melee'], ['quest:While Guthix Sleeps', 'skill:Attack:60']),
      I("Dragon boots", 'DRAGON_BOOTS', ['melee'], ['skill:Slayer:83', 'skill:Defence:60']),
      I("Tormented bracelet", 'TORMENTED_BRACELET', ['magic'], ['quest:Monkey Madness II', 'skill:Hitpoints:75', 'skill:Magic:93', 'skill:Crafting:95']),
      I("Trident of the seas", 'TRIDENT_OF_THE_SEAS', ['magic'], ['skill:Slayer:87', 'skill:Magic:75']),
      I("Occult necklace", 'OCCULT_NECKLACE', ['magic'], ['skill:Slayer:93', 'skill:Magic:70']),
      I("Ring of suffering (i)", 'RING_OF_SUFFERING_I', ['utility'], ['quest:Monkey Madness II', 'skill:Hitpoints:75', 'skill:Magic:93', 'skill:Crafting:89'], 'Ring_of_suffering', exact=True),
    ),
    G('[L32/35] Doom of Mokhaiotl and Yama',
      I("Eye of ayak", 'EYE_OF_AYAK', ['magic'], ['skill:Magic:83']),
      I("Confliction gauntlets", 'CONFLICTION_GAUNTLETS', ['magic'], ['skill:Hitpoints:90', 'skill:Crafting:83', 'skill:Smithing:70', 'item:19544:1:Tormented bracelet'], implies=['Tormented bracelet']),
      I("Avernic treads", 'AVERNIC_TREADS', ['melee', 'ranged', 'magic'], ['item:13239:1:Primordial boots', 'item:13235:1:Eternal boots', 'item:13237:1:Pegasian boots'], implies=['Primordial boots', 'Eternal boots', 'Pegasian boots']),
    ),
    G('[L33-34] Diaries, boots, amulet endgame',
      I("Desert amulet 4", 'DESERT_AMULET_4', ['utility'], ['diary:Desert:Elite'], 'Desert_amulet', exact=True),
      I("Rada's blessing 4", 'RADAS_BLESSING_4', ['utility'], ['diary:Kourend & Kebos:Elite'], "Rada's_blessing", exact=True),
      I("Primordial boots", 'PRIMORDIAL_BOOTS', ['melee'], ['skill:Slayer:91', 'skill:Strength:75', 'skill:Defence:75'], implies=['Dragon boots']),
      I("Eternal boots", 'ETERNAL_BOOTS', ['magic'], ['skill:Slayer:91', 'skill:Magic:75', 'skill:Defence:75'], implies=['Infinity boots']),
      I("Pegasian boots", 'PEGASIAN_BOOTS', ['ranged'], ['skill:Slayer:91', 'skill:Ranged:75', 'skill:Defence:75']),
      I("Amulet of rancour", 'AMULET_OF_RANCOUR', ['melee'], ['skill:Slayer:92', 'skill:Hitpoints:90', 'item:19553:1:Amulet of torture'], implies=['Amulet of torture']),
      I("Ferocious gloves", 'FEROCIOUS_GLOVES', ['melee'], ['quest:Dragon Slayer II', 'skill:Slayer:95', 'skill:Attack:80', 'skill:Defence:80']),
    ),
    G('[L36-39] Zulrah to Rigour & Augury',
      I("Toxic blowpipe", 'TOXIC_BLOWPIPE', ['ranged'], ['skill:Ranged:75', 'kc:Zulrah:1', 'skill:Fletching:78']),
      I("Serpentine helm", 'SERPENTINE_HELM', ['melee', 'ranged'], ['kc:Zulrah:1', 'skill:Defence:75', 'skill:Crafting:52']),
      I("Osmumten's fang", 'OSMUMTENS_FANG', ['melee'], ['quest:Beneath Cursed Sands', 'skill:Attack:82'], "Osmumten's_fang"),
      I("Dexterous prayer scroll", 'DEXTEROUS_PRAYER_SCROLL', ['ranged'], ['skill:Prayer:74', 'skill:Defence:70'], 'Rigour'),
      I("Arcane prayer scroll", 'ARCANE_PRAYER_SCROLL', ['magic'], ['skill:Prayer:77', 'skill:Defence:70'], 'Augury'),
    ),
    G('Phase 3 utility',
      I("Mythical cape", 'MYTHICAL_CAPE', ['utility'], ['quest:Dragon Slayer II']),
      I("Drakan's medallion", 'DRAKANS_MEDALLION', ['utility'], ['quest:Sins of the Father'], "Drakan's_medallion"),
      I("Royal seed pod", 'ROYAL_SEED_POD', ['utility'], ['quest:Monkey Madness II']),
      I("Neitiznot faceguard", 'NEITIZNOT_FACEGUARD', ['melee'], ['quest:The Fremennik Exiles', 'skill:Defence:70', 'skill:Slayer:60', 'item:10828:1:Helm of Neitiznot'], implies=['Helm of Neitiznot']),
    ),
  ]},
  {'phase': 4, 'name': 'Endgame & megarares', 'groups': [
    G('[L40-43] The capes and Yama',
      I("Infernal cape", 'INFERNAL_CAPE', ['melee'], ['kc:TzKal-Zuk:1'], implies=['Fire cape']),
      I("Oathplate helm", 'OATHPLATE_HELM', ['melee'], ['kc:Yama:1', 'skill:Defence:78'], 'Oathplate_armour'),
      I("Oathplate chest", 'OATHPLATE_CHEST', ['melee'], ['kc:Yama:1', 'skill:Defence:78'], 'Oathplate_armour'),
      I("Oathplate legs", 'OATHPLATE_LEGS', ['melee'], ['kc:Yama:1', 'skill:Defence:78'], 'Oathplate_armour'),
      I("Ultor ring", 'ULTOR_RING_28307', ['melee'], ['quest:Desert Treasure II - The Fallen Empire', 'skill:Magic:90', 'skill:Crafting:80'], 'Ultor_ring'),
      I("Dizana's quiver", 'DIZANAS_QUIVER', ['ranged'], ['kc:Sol Heredit:1', 'skill:Ranged:75'], "Dizana's_quiver"),
    ),
    G('[L44-49] The megarare trio',
      I("Avernic defender", 'AVERNIC_DEFENDER', ['melee'], ['skill:Attack:70', 'skill:Defence:70', 'item:12954:1:Dragon defender'], implies=['Dragon defender']),
      I("Scythe of vitur", 'SCYTHE_OF_VITUR', ['melee'], ['skill:Attack:80', 'skill:Strength:90']),
      I("Tumeken's shadow", 'TUMEKENS_SHADOW', ['magic'], ['quest:Beneath Cursed Sands', 'skill:Magic:85'], "Tumeken's_shadow"),
      I("Magus ring", 'MAGUS_RING_28313', ['magic'], ['quest:Desert Treasure II - The Fallen Empire', 'skill:Magic:90', 'skill:Crafting:80']),
      I("Twisted bow", 'TWISTED_BOW', ['ranged'], ['skill:Ranged:85']),
      I("Dragon claws", 'DRAGON_CLAWS', ['melee'], ['skill:Attack:60']),
      I("Ancestral hat", 'ANCESTRAL_HAT', ['magic'], ['skill:Magic:75', 'skill:Defence:65'], 'Ancestral_robes'),
      I("Ancestral robe top", 'ANCESTRAL_ROBE_TOP', ['magic'], ['skill:Magic:75', 'skill:Defence:65'], 'Ancestral_robes'),
      I("Ancestral robe bottom", 'ANCESTRAL_ROBE_BOTTOM', ['magic'], ['skill:Magic:75', 'skill:Defence:65'], 'Ancestral_robes'),
      I("Elder maul", 'ELDER_MAUL', ['melee'], ['skill:Strength:75']),
      I("Masori mask (f)", 'MASORI_MASK_F', ['ranged'], ['quest:Beneath Cursed Sands', 'skill:Ranged:80', 'skill:Defence:80'], 'Masori_armour'),
      I("Masori body (f)", 'MASORI_BODY_F', ['ranged'], ['quest:Beneath Cursed Sands', 'skill:Ranged:80', 'skill:Defence:80'], 'Masori_armour'),
      I("Masori chaps (f)", 'MASORI_CHAPS_F', ['ranged'], ['quest:Beneath Cursed Sands', 'skill:Ranged:80', 'skill:Defence:80'], 'Masori_armour'),
      I("Elidinis' ward (f)", 'ELIDINIS_WARD_F', ['magic'], ['quest:Beneath Cursed Sands', 'skill:Magic:80', 'skill:Defence:80', 'skill:Prayer:80'], "Elidinis'_ward"),
      I("Saturated heart", 'SATURATED_HEART', ['magic'], ['skill:Magic:98']),
      I("Zaryte crossbow", 'ZARYTE_CROSSBOW', ['ranged'], ['skill:Ranged:80', 'kc:Nex:1']),
      I("Zaryte vambraces", 'ZARYTE_VAMBRACES', ['ranged'], ['skill:Ranged:80', 'skill:Defence:45', 'kc:Nex:1']),
    ),
    G('Rounding out the endgame',
      I("Virtus robe top", 'VIRTUS_ROBE_TOP', ['magic'], ['quest:Desert Treasure II - The Fallen Empire', 'skill:Magic:78', 'skill:Defence:75'], 'Virtus_robes'),
      I("Venator ring", 'VENATOR_RING_28310', ['ranged'], ['quest:Desert Treasure II - The Fallen Empire', 'skill:Magic:90', 'skill:Crafting:80']),
      I("Bellator ring", 'BELLATOR_RING_28316', ['melee'], ['quest:Desert Treasure II - The Fallen Empire', 'skill:Magic:90', 'skill:Crafting:80']),
      I("Soulreaper axe", 'SOULREAPER_AXE_28338', ['melee'], ['quest:Desert Treasure II - The Fallen Empire', 'skill:Attack:80', 'skill:Strength:80']),
      I("Torva platebody", 'TORVA_PLATEBODY', ['melee'], ['skill:Defence:80', 'kc:Nex:1'], 'Torva_armour'),
    ),
  ]},
  {'phase': 5, 'name': 'POH & Sailing', 'groups': [
    G('Oak era (to ~50 Construction)',
      M("Demon butler", 'TEAK_PLANK', ['poh'], ['skill:Construction:40'], 'Demon_butler', iconfile='demon_butler.png'),
      M("Oak altar", 'HOLY_SYMBOL', ['poh'], ['skill:Construction:45'], iconfile='oak_altar.png'),
      M("Mounted amulet of glory", 'AMULET_OF_GLORY4', ['poh'], ['skill:Construction:47', 'item:1704:1:Amulet of glory'], 'Amulet_of_Glory_(mounted)', iconfile='mounted_glory.png'),
      M("Teak portal", 'LAW_RUNE', ['poh'], ['skill:Construction:50'], 'Portal_Chamber', iconfile='teak_portal.png'),
    ),
    G('Teak era (50-67)',
      M("Armour stand", 'HAMMER', ['poh'], ['skill:Construction:55'], 'Armour_stand', iconfile='armour_stand.png'),
      M("Spice rack", 'SPICE', ['poh'], ['skill:Construction:60'], 'Spice_rack', iconfile='spice_rack.png'),
      M("Restoration pool", 'SUPER_RESTORE4', ['poh'], ['skill:Construction:65'], 'Restoration_pool', iconfile='restoration_pool.png'),
      M("Teak shelves 2", 'CUP_OF_TEA', ['poh'], ['skill:Construction:67'], 'Shelves', iconfile='teak_shelves.png'),
    ),
    G('Boost toolkit (build up to +8 early)',
      I("Crystal saw", 'CRYSTAL_SAW', ['poh', 'utility'], ['quest:The Eyes of Glouphrie'], 'Crystal_saw'),
      M("Spicy stew boost", 'SPICY_STEW', ['poh'], ['quest:Recipe for Disaster - Evil Dave'], 'Spicy_stew'),
    ),
    G('Portal-nexus era (70-92; saw + POH tea boost +6)',
      M("Barrows portal", 'LAW_RUNE', ['poh'], ['skill:Construction:50', 'skill:Magic:83'], 'Portal_Chamber', iconfile='barrows_portal.png'),
      M("Mounted Xeric's talisman", 'XERICS_TALISMAN', ['poh'], ['skill:Construction:72', "item:13393:1:Xeric's talisman"], "Mounted_Xeric's_Talisman", iconfile='mounted_xerics.png'),
      M("Marble portal nexus", 'TELEPORT_TO_HOUSE', ['poh'], ['skill:Construction:72'], 'Portal_Nexus', iconfile='marble_nexus.png'),
      M("Gilded altar", 'DRAGON_BONES', ['poh'], ['skill:Construction:75'], 'Altar_space', iconfile='gilded_altar.png'),
      M("Spirit tree", 'SPIRIT_SEED', ['poh'], ['skill:Construction:75', 'skill:Farming:83'], 'Spirit_tree_(Construction)', iconfile='spirit_tree.png'),
      M("Wilderness Obelisk", 'BURNING_AMULET5', ['poh'], ['skill:Construction:80'], 'Obelisk_(Construction)', iconfile='wilderness_obelisk.png'),
      M("Ancient / Lunar / Dark altar", 'BLOOD_RUNE', ['poh'], ['skill:Construction:80'], 'Occult_altar', iconfile='spellbook_altar.png'),
      M("Basic jewellery box", 'RING_OF_DUELING8', ['poh'], ['skill:Construction:81'], 'Jewellery_box', iconfile='basic_jewellery_box.png'),
      M("Mounted digsite pendant", 'DIGSITE_PENDANT_5', ['poh'], ['skill:Construction:82', 'quest:The Dig Site', 'item:11190:1:Digsite pendant'], 'Mounted_digsite_pendant', iconfile='mounted_digsite.png'),
      M("Fancy rejuvenation pool", 'SUPER_RESTORE4', ['poh'], ['skill:Construction:85'], 'Fancy_rejuvenation_pool', iconfile='fancy_pool.png', implies=['Restoration pool']),
      M("Fairy ring", 'DRAMEN_STAFF', ['poh'], ['skill:Construction:85', 'quest:Fairytale II - Cure a Queen'], 'Fairy_ring_(Construction)', iconfile='fairy_ring.png'),
      M("Fancy jewellery box", 'RING_OF_DUELING8', ['poh'], ['skill:Construction:86'], 'Jewellery_box', iconfile='fancy_jewellery_box.png', implies=['Basic jewellery box']),
      M("Occult altar", 'SOUL_RUNE', ['poh'], ['skill:Construction:90', 'quest:Lunar Diplomacy', 'quest:Desert Treasure I'], 'Occult_altar', iconfile='occult_altar.png', implies=['Ancient / Lunar / Dark altar']),
      M("Ornate rejuvenation pool", 'SANFEW_SERUM4', ['poh'], ['skill:Construction:90'], 'Ornate_rejuvenation_pool', iconfile='ornate_pool.png', implies=['Fancy rejuvenation pool']),
      M("Ornate jewellery box", 'RING_OF_WEALTH', ['poh'], ['skill:Construction:91'], 'Jewellery_box', iconfile='ornate_jewellery_box.png', implies=['Fancy jewellery box']),
      M("Crystalline portal nexus", 'TELEPORT_CRYSTAL_1', ['poh'], ['skill:Construction:92'], 'Portal_Nexus', iconfile='crystalline_nexus.png', implies=['Marble portal nexus']),
      M("Spirit tree & fairy ring", 'DRAMEN_STAFF', ['poh'], ['skill:Construction:95', 'skill:Farming:83', 'quest:Fairytale II - Cure a Queen'], 'Spirit_tree_%26_fairy_ring', iconfile='spirit_tree_fairy_ring.png', implies=['Spirit tree', 'Fairy ring']),
    ),
    G('Sailing (early data)',
      I("Camphor blowpipe", 'CAMPHOR_BLOWPIPE', ['boat', 'ranged'], ['skill:Ranged:45', 'skill:Fletching:58']),
      I("Ironwood blowpipe", 'IRONWOOD_BLOWPIPE', ['boat', 'ranged'], ['skill:Ranged:55', 'skill:Fletching:72']),
    ),
  ]},
]

# ── 2026-07-21 wiki requirement audit (Luke: "find and patch all of the
# holes") — every entry swept against its wiki page; additions only, applied
# over the curated entries above so the whole audit reviews in one block.
# Shape: name -> (add_reqs, add_materials [(CONST, qty, display)], source_note|None).
# item: reqs may use CONSTANT names (resolved via _resolve_req).
# DECLINED from the sweep, deliberately: raid completion kc: gates (CoX/ToB/
# ToA rewards are not NpcLootReceived — the gate would never detect; the
# clog-rated obtain step already prices the raid) and Penance Queen kc:
# (chest reward, same gap — sourceNotes carry the honesty instead).
WIKI_AUDIT = {
  'Ardougne cloak 1': (['diary:Ardougne:Easy'], [], None),
  'Anti-dragon shield': (['queststarted:Dragon Slayer I'], [], None),
  'Amulet of strength': ([], [('GOLD_BAR', 1, 'Gold bar'), ('RUBY', 1, 'Ruby'),
    ('COSMIC_RUNE', 1, 'Cosmic rune'), ('FIRE_RUNE', 5, 'Fire rune')], None),
  'Studded body': (['skillb:Crafting:41'],
    [('LEATHER_BODY', 1, 'Leather body'), ('STEEL_STUDS', 1, 'Steel studs')], None),
  'Snakeskin body': (['skillb:Crafting:53'], [('SNAKESKIN', 15, 'Snakeskin')], None),
  "Green d'hide body": (['skillb:Crafting:63'],
    [('GREEN_DRAGON_LEATHER', 3, 'Green dragon leather')], None),
  'Magic shortbow': (['skillb:Fletching:80'],
    [('MAGIC_LOGS', 1, 'Magic logs'), ('BOW_STRING', 1, 'Bow string')], None),
  "Ava's accumulator": ([], [('STEEL_ARROW', 75, 'Steel arrow')], None),
  'Fighter torso': ([], [], '375+ honour points in every role, then a Penance Queen kill'),
  'Granite body': ([], [], 'Requires a Penance Queen kill; 95,000 coins'),
  'God cape': (['quest:Mage Arena I'], [], None),
  'Imcando hammer': (['skillb:Mining:14'], [('BARRONITE_SHARDS', 1250, 'Barronite shards')], None),
  # ── chunk 2 ──
  'Dragon defender': (['item:RUNE_DEFENDER:1:Rune defender'], [], None),
  'Salve amulet (ei)': (['quest:Lair of Tarn Razorlor'], [], None),
  'Zombie axe': (['item:BROKEN_ZOMBIE_AXE:1:Broken zombie axe'], [], None),
  'Dual macuahuitl': (['quest:Perilous Moons'], [], None),
  'Blood moon chestplate': (['quest:Perilous Moons'], [], None),
  'Blood moon tassets': (['quest:Perilous Moons'], [], None),
  'Eclipse atlatl': (['quest:Perilous Moons'], [], None),
  'Eclipse moon chestplate': (['quest:Perilous Moons'], [], None),
  'Blue moon spear': (['quest:Perilous Moons'], [], None),
  'Mixed hide cape': (['quest:Children of the Sun', 'skillb:Crafting:68'],
    [('MIXED_HIDE_BASE', 1, 'Mixed hide base'), ('JAGUAR_FUR', 1, 'Jaguar fur')], None),
  'Mixed hide boots': (['quest:Children of the Sun', 'skillb:Crafting:69', 'skillb:Hunter:72'],
    [('MIXED_HIDE_BASE', 1, 'Mixed hide base'), ('SUNLIGHT_ANTELOPE_FUR', 1, 'Sunlight antelope fur')], None),
  'Imbued god cape': (['quest:Mage Arena II'], [], None),
  'Black mask (i)': (['quest:Cabin Fever'], [], None),
  "Pharaoh's sceptre": (["queststarted:Icthlarin's Little Helper"], [], None),
  'Arclight': (['quest:Shadow of the Storm'], [('ANCIENT_SHARD', 3, 'Ancient shard')], None),
  'Slayer helmet (i)': (['skillb:Crafting:55'],
    [('EARMUFFS', 1, 'Earmuffs'), ('FACEMASK', 1, 'Facemask'), ('NOSE_PEG', 1, 'Nose peg'),
     ('SPINY_HELMET', 1, 'Spiny helmet'), ('ENCHANTED_GEM', 1, 'Enchanted gem')], None),
  "Ghommal's hilt 2": ([], [], 'Medium Combat Achievements reward'),
  "Ghommal's hilt 4": ([], [], 'Elite Combat Achievements reward'),
  'Twinflame staff': (['item:FIRE_ELEMENT_STAFF_CROWN:1:Fire element staff crown',
    'item:ICE_ELEMENT_STAFF_CROWN:1:Ice element staff crown'],
    [('BATTLESTAFF', 1, 'Battlestaff')], None),
  "Hunters' sunlight crossbow": (['quest:Children of the Sun'],
    [('SUNLIGHT_ANTELOPE_ANTLER', 1, 'Sunlight antelope antler'),
     ('HUNTERS_CROSSBOW', 1, "Hunters' crossbow")], None),
  'Dragon hunter wand': (['quest:Children of the Sun'], [], None),
  # ── chunk 3 ──
  'Crystal halberd': (['diary:Western Provinces:Hard'], [], None),
  'Gem sack': (['skillb:Crafting:81', 'item:GEM_TOTE:1:Gem tote',
    'item:IMMACULATE_MOLE_SKIN:1:Immaculate mole skin'], [], None),
  'Divine rune pouch': (['item:THREAD_OF_ELIDINIS:1:Thread of Elidinis'], [], None),
  'Fletching knife': (['quest:Children of the Sun'], [], None),
  'Basic quetzal whistle': (['quest:Children of the Sun'], [], None),
  "Huntsman's kit": (['quest:Children of the Sun'], [], None),
  'Bottomless compost bucket': (['kc:Hespori:1'], [], None),
  'Ash covered tome': (['quest:Bone Voyage'], [], None),
  'Bow of Faerdhinen': (['skill:Agility:70', 'skillb:Smithing:82', 'skillb:Crafting:82',
    'item:ENHANCED_CRYSTAL_WEAPON_SEED:1:Enhanced crystal weapon seed'],
    [('CRYSTAL_SHARD', 100, 'Crystal shard')], None),
  'Crystal helm': (['skillb:Smithing:70', 'skillb:Crafting:70',
    'item:CRYSTAL_ARMOUR_SEED:1:Crystal armour seed'], [('CRYSTAL_SHARD', 50, 'Crystal shard')], None),
  'Crystal body': (['skillb:Smithing:74', 'skillb:Crafting:74',
    'item:CRYSTAL_ARMOUR_SEED:3:Crystal armour seed'], [('CRYSTAL_SHARD', 150, 'Crystal shard')], None),
  'Crystal legs': (['skillb:Smithing:72', 'skillb:Crafting:72',
    'item:CRYSTAL_ARMOUR_SEED:2:Crystal armour seed'], [('CRYSTAL_SHARD', 100, 'Crystal shard')], None),
  'Amulet of fury': (['item:UNCUT_ONYX:1:Uncut onyx'], [], None),
  'Bloodbark body': (['item:SPLITBARK_BODY:1:Splitbark body',
    'item:RUNESCROLL_OF_BLOODBARK:1:Runescroll of bloodbark'],
    [('BLOOD_RUNE', 500, 'Blood rune')], None),
  "Ava's assembler": ([], [('MITHRIL_ARROW', 75, 'Mithril arrow')], None),
  'Necklace of anguish': (['item:ZENYTE_SHARD:1:Zenyte shard', 'item:UNCUT_ONYX:1:Uncut onyx'], [], None),
  'Amulet of torture': (['item:ZENYTE_SHARD:1:Zenyte shard', 'item:UNCUT_ONYX:1:Uncut onyx'], [], None),
  'Zamorakian hasta': (['item:ZAMORAKIAN_SPEAR:1:Zamorakian spear', 'quest:Barbarian Training'], [], None),
  'Bandos godsword': (['skillb:Smithing:80', 'item:BANDOS_HILT:1:Bandos hilt',
    'item:GODSWORD_SHARD_1:1:Godsword shard 1', 'item:GODSWORD_SHARD_2:1:Godsword shard 2',
    'item:GODSWORD_SHARD_3:1:Godsword shard 3'], [], None),
  'Voidwaker': (['item:VOIDWAKER_BLADE:1:Voidwaker blade', 'item:VOIDWAKER_HILT:1:Voidwaker hilt',
    'item:VOIDWAKER_GEM:1:Voidwaker gem'], [], None),
  # ── chunk 4 ──
  'Abyssal tentacle': (['item:KRAKEN_TENTACLE:1:Kraken tentacle'], [], None),
  'Emberlight': (['item:TORMENTED_SYNAPSE:1:Tormented synapse'], [],
    'Requires a fully-charged or infused Arclight'),
  'Scorching bow': (['item:TORMENTED_SYNAPSE:1:Tormented synapse'],
    [('MAGIC_LONGBOW_U', 1, 'Magic longbow (u)')], None),
  'Purging staff': (['item:TORMENTED_SYNAPSE:1:Tormented synapse', 'skill:Smithing:55'],
    [('BATTLESTAFF', 1, 'Battlestaff'), ('IRON_BAR', 1, 'Iron bar')], None),
  'Burning claws': (['item:BURNING_CLAW:2:Burning claw'], [], None),
  'Tormented bracelet': (['item:ZENYTE_SHARD:1:Zenyte shard', 'item:UNCUT_ONYX:1:Uncut onyx'],
    [('GOLD_BAR', 1, 'Gold bar'), ('COSMIC_RUNE', 1, 'Cosmic rune'),
     ('SOUL_RUNE', 20, 'Soul rune'), ('BLOOD_RUNE', 20, 'Blood rune')], None),
  'Ring of suffering (i)': (['item:ZENYTE_SHARD:1:Zenyte shard', 'item:UNCUT_ONYX:1:Uncut onyx'],
    [('GOLD_BAR', 1, 'Gold bar'), ('COSMIC_RUNE', 1, 'Cosmic rune'),
     ('SOUL_RUNE', 20, 'Soul rune'), ('BLOOD_RUNE', 20, 'Blood rune')],
    'Imbue: 725,000 NMZ points or 300 Soul Wars zeal'),
  'Eye of ayak': (['kc:Doom of Mokhaiotl:1'], [], None),
  'Confliction gauntlets': (['item:MOKHAIOTL_CLOTH:1:Mokhaiotl cloth'],
    [('DEMON_TEAR', 10000, 'Demon tear')], None),
  'Avernic treads': (['kc:Doom of Mokhaiotl:1', 'skillb:Magic:80', 'skillb:Runecraft:60'],
    [('DEMON_TEAR', 12000, 'Demon tear')], None),
  'Primordial boots': (['item:PRIMORDIAL_CRYSTAL:1:Primordial crystal',
    'item:DRAGON_BOOTS:1:Dragon boots', 'skill:Magic:60', 'skill:Runecraft:60'], [], None),
  'Eternal boots': (['item:ETERNAL_CRYSTAL:1:Eternal crystal',
    'item:INFINITY_BOOTS:1:Infinity boots', 'skill:Magic:60', 'skill:Runecraft:60'], [], None),
  'Pegasian boots': (['item:PEGASIAN_CRYSTAL:1:Pegasian crystal',
    'item:RANGER_BOOTS:1:Ranger boots', 'skill:Magic:60', 'skill:Runecraft:60'], [], None),
  'Amulet of rancour': (['skillb:Crafting:86', 'item:ARAXYTE_FANG:1:Araxyte fang'], [], None),
  'Ferocious gloves': (['item:HYDRA_LEATHER:1:Hydra leather'], [], None),
  'Toxic blowpipe': (['item:TANZANITE_FANG:1:Tanzanite fang'], [], None),
  'Serpentine helm': (['item:SERPENTINE_VISAGE:1:Serpentine visage'], [], None),
  'Neitiznot faceguard': (['item:BASILISK_JAW:1:Basilisk jaw'], [], None),
  # ── chunk 5 ──
  'Infernal cape': (['item:FIRE_CAPE:1:Fire cape'], [], None),
  'Oathplate helm': (['quest:A Kingdom Divided'], [], None),
  'Oathplate chest': (['quest:A Kingdom Divided'], [], None),
  'Oathplate legs': (['quest:A Kingdom Divided'], [], None),
  'Ultor ring': (['item:ULTOR_VESTIGE:1:Ultor vestige', 'item:BERSERKER_RING:1:Berserker ring'],
    [('CHROMIUM_INGOT', 3, 'Chromium ingot'), ('BLOOD_RUNE', 500, 'Blood rune')], None),
  "Dizana's quiver": (['quest:Children of the Sun'], [], None),
  'Avernic defender': (['item:AVERNIC_DEFENDER_HILT:1:Avernic defender hilt'], [], None),
  'Magus ring': (['item:MAGUS_VESTIGE:1:Magus vestige', 'item:SEERS_RING:1:Seers ring'],
    [('CHROMIUM_INGOT', 3, 'Chromium ingot'), ('BLOOD_RUNE', 500, 'Blood rune')], None),
  'Masori mask (f)': (['skillb:Crafting:90', 'item:MASORI_MASK:1:Masori mask',
    'item:ARMADYLEAN_PLATE:1:Armadylean plate'], [], None),
  'Masori body (f)': (['skillb:Crafting:90', 'item:MASORI_BODY:1:Masori body',
    'item:ARMADYLEAN_PLATE:4:Armadylean plate'], [], None),
  'Masori chaps (f)': (['skillb:Crafting:90', 'item:MASORI_CHAPS:1:Masori chaps',
    'item:ARMADYLEAN_PLATE:3:Armadylean plate'], [], None),
  "Elidinis' ward (f)": (['skillb:Prayer:90', 'skillb:Smithing:90',
    "item:ELIDINIS_WARD:1:Elidinis' ward", 'item:ARCANE_SIGIL:1:Arcane sigil'],
    [('SOUL_RUNE', 10000, 'Soul rune')], None),
  'Saturated heart': (['item:IMBUED_HEART:1:Imbued heart'],
    [('ANCIENT_ESSENCE', 150000, 'Ancient essence')], None),
  'Zaryte crossbow': (['item:ARMADYL_CROSSBOW:1:Armadyl crossbow', 'item:NIHIL_HORN:1:Nihil horn'],
    [('NIHIL_SHARD', 250, 'Nihil shard')], None),
  'Venator ring': (['item:VENATOR_VESTIGE:1:Venator vestige', 'item:ARCHERS_RING:1:Archers ring'],
    [('CHROMIUM_INGOT', 3, 'Chromium ingot'), ('BLOOD_RUNE', 500, 'Blood rune')], None),
  'Bellator ring': (['item:BELLATOR_VESTIGE:1:Bellator vestige', 'item:WARRIOR_RING:1:Warrior ring'],
    [('CHROMIUM_INGOT', 3, 'Chromium ingot'), ('BLOOD_RUNE', 500, 'Blood rune')], None),
  'Soulreaper axe': (['skill:Magic:75', "item:LEVIATHANS_LURE:1:Leviathan's lure",
    "item:SIRENS_STAFF:1:Siren's staff", "item:EXECUTIONERS_AXE_HEAD:1:Executioner's axe head",
    'item:EYE_OF_THE_DUKE:1:Eye of the duke'], [('BLOOD_RUNE', 2000, 'Blood rune')], None),
  'Torva platebody': (['skillb:Smithing:90', 'item:TORVA_PLATEBODY_DAMAGED:1:Torva platebody (damaged)',
    'item:BANDOSIAN_COMPONENTS:2:Bandosian components'], [], None),
  'Camphor blowpipe': ([], [('CAMPHOR_LOGS', 2, 'Camphor logs'), ('SQUID_BEAK', 1, 'Squid beak')], None),
  'Ironwood blowpipe': ([], [('IRONWOOD_LOGS', 2, 'Ironwood logs'), ('SQUID_BEAK', 1, 'Squid beak')], None),
}

for _p in phases:
    for _g in _p['groups']:
        for _i in _g['items']:
            if _i['name'] not in WIKI_AUDIT:
                continue
            _reqs, _mats, _note = WIKI_AUDIT.pop(_i['name'])
            for _r in _reqs:
                _r = _resolve_req(_r, _i['name'])
                if _r.startswith('quest:') and _r[len('quest:'):].strip().lower() not in QUESTS:
                    missing.append(f'QUEST? {_r} ({_i["name"]})')
                if _r not in _i['requirements']:
                    _i['requirements'].append(_r)
            if _mats:
                _out = _i.setdefault('materials', [])
                for _mc, _qty, _disp in _mats:
                    if _mc not in IDS:
                        missing.append(f'MATERIAL? {_mc} ({_i["name"]})')
                    else:
                        _out.append({'itemId': IDS[_mc], 'qty': _qty, 'name': _disp})
            if _note and 'sourceNote' not in _i:
                _i['sourceNote'] = _note
assert not WIKI_AUDIT, f'audit patches for unknown entries: {sorted(WIKI_AUDIT)}'

pack = {'$schema': './schemas/gear-progression.schema.json', 'version': 1, 'phases': phases}


# ── boostable gates ─────────────────────────────────────────────────
# skill: → skillb: for action gates that temporary boosts can satisfy.
# Equip/wield requirements stay skill: — OSRS checks base levels for
# gear (wiki: "boosted Magic will not be able to equip... but will be
# able to cast"). POH Construction reqs are all boostable (that's the
# whole Theoatrix strategy), as are creation gates (crafting/smithing/
# fletching/enchanting), slayer kill gates and activity-entry gates.
BOOSTABLE = {
  "Zombie axe": ['skill:Smithing:70'],
  "Bloodbark body": ['skill:Runecraft:81'],
  "Serpentine helm": ['skill:Crafting:52'],
  "Toxic blowpipe": ['skill:Fletching:78'],
  "Hunters' sunlight crossbow": ['skill:Fletching:74', 'skill:Hunter:72'],
  "Emberlight": ['skill:Smithing:74'],
  "Scorching bow": ['skill:Fletching:74'],
  "Purging staff": ['skill:Crafting:74'],
  "Confliction gauntlets": ['skill:Crafting:83', 'skill:Smithing:70'],
  "Necklace of anguish": ['skill:Magic:93', 'skill:Crafting:92'],
  "Amulet of torture": ['skill:Magic:93', 'skill:Crafting:98'],
  "Tormented bracelet": ['skill:Magic:93', 'skill:Crafting:95'],
  "Ring of suffering (i)": ['skill:Magic:93', 'skill:Crafting:89'],
  "Amulet of glory": ['any:skill:Crafting:80|skill:Hunter:83', 'skill:Magic:68'],
  "Amulet of fury": ['skill:Crafting:90', 'skill:Magic:87'],
  "Ultor ring": ['skill:Magic:90', 'skill:Crafting:80'],
  "Magus ring": ['skill:Magic:90', 'skill:Crafting:80'],
  "Venator ring": ['skill:Magic:90', 'skill:Crafting:80'],
  "Bellator ring": ['skill:Magic:90', 'skill:Crafting:80'],
  "Divine rune pouch": ['skill:Crafting:75'],
  "Camphor blowpipe": ['skill:Fletching:58'],
  "Ironwood blowpipe": ['skill:Fletching:72'],
  "Black mask (i)": ['skill:Slayer:58'],
  "Warped sceptre": ['skill:Slayer:56'],
  "Dragon boots": ['skill:Slayer:83'],
  "Abyssal whip": ['skill:Slayer:85'],
  "Abyssal tentacle": ['skill:Slayer:87'],
  "Trident of the seas": ['skill:Slayer:87'],
  "Occult necklace": ['skill:Slayer:93'],
  "Primordial boots": ['skill:Slayer:91'],
  "Eternal boots": ['skill:Slayer:91'],
  "Pegasian boots": ['skill:Slayer:91'],
  "Amulet of rancour": ['skill:Slayer:92'],
  "Ferocious gloves": ['skill:Slayer:95'],
  "Coal bag": ['skill:Mining:30'],
  "Gem bag": ['skill:Mining:30'],
  "Ash covered tome": ['skill:Mining:50'],
  "Fish barrel": ['skill:Fishing:35'],
  "Tackle box": ['skill:Fishing:35'],
  "Pharaoh's sceptre": ['skill:Thieving:21'],
  "Red chinchompa": ['skill:Hunter:63'],
}

def _boostify(req, names):
    for target in names:
        if req == target:
            return req.replace('skill:', 'skillb:')
    return req

for _p in phases:
    for _g in _p['groups']:
        for _i in _g['items']:
            if 'poh' in _i['categories']:
                # every POH build gate is boostable (saw/tea/stew strategy)
                _i['requirements'] = [r.replace('skill:Construction:', 'skillb:Construction:')
                                          .replace('skill:Farming:', 'skillb:Farming:')
                                      for r in _i['requirements']]
            if _i['name'] in BOOSTABLE:
                _i['requirements'] = [_boostify(r, BOOSTABLE[_i['name']]) for r in _i['requirements']]
_covered = {n for _p in phases for _g in _p['groups'] for _i in _g['items'] if _i['name'] in BOOSTABLE for n in [_i['name']]}
assert _covered == set(BOOSTABLE), set(BOOSTABLE) - _covered

all_names = {i['name'] for p in phases for g in p['groups'] for i in g['items']}
for p in phases:
    for g in p['groups']:
        for i in g['items']:
            for target in i.get('implies', ()):
                if target not in all_names:
                    missing.append(f'IMPLIES? {target} ({i["name"]})')

if missing:
    print('\n'.join(missing), file=sys.stderr)
    sys.exit(1)

out = os.path.join(os.path.dirname(__file__), '../src/main/resources/data/gear-progression.json')
if True:
    # ── POH build materials from the wiki (manual entries only) ──
    _here = os.path.dirname(os.path.abspath(__file__))
    item_names = json.load(open(os.path.join(_here, '../src/main/resources/data/index/item-names.json')))
    cache_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_here, '.gearcache')
    manual_entries = []
    for phase in pack['phases']:
        for group in phase['groups']:
            for entry in group['items']:
                if entry.get('itemId') is None and entry.get('wiki'):
                    manual_entries.append(entry)
    # the entry's own name usually has the Recipe; the wiki field often
    # points at a hub page (Jewellery box, Altar space) for chart linking
    titles = []
    for e in manual_entries:
        titles.append(e['name'].replace(' ', '_'))
        titles.append(e['wiki'])
    pages = fetch_pages(list(dict.fromkeys(titles)), cache_dir)
    # predecessor furniture appears as Recipe "materials": the build CONSUMES
    # the previous tier, so it becomes a HARD GATE on the entry (Luke's ornate
    # jewellery box report — you must have built the fancy box first), mapped
    # to the pack's nearest ladder entry when the wiki tier isn't carried.
    entry_by_lname = {e['name'].lower(): e for e in manual_entries}
    furniture_alias = {
        "rejuvenation pool": "restoration pool",              # pool tiers we skip
        "ancient altar": "ancient / lunar / dark altar",
        "gilded portal nexus": "marble portal nexus",
        "portal nexus": "marble portal nexus",
    }
    furniture = set(entry_by_lname) | set(furniture_alias)

    # apostrophes/hyphens/doses normalize differently than the constants
    # ("Anti-venom(4)" vs ANTIVENOM4, "Curator's medallion" vs
    # CURATORS_MEDALLION) — an underscore-collapsed index absorbs them all
    collapsed = {}
    for cn, cid in item_names.items():
        collapsed.setdefault(cn.replace('_', ''), cid)
    for cn, cid in IDS.items():
        collapsed.setdefault(cn.replace('_', ''), cid)

    def resolve_material(name):
        key = normalize_item_name(name)
        for k in (key, re.sub(r'_(\d+)$', r'\1', key)):
            if k in item_names:
                return item_names[k]
            if k in IDS:
                return IDS[k]
        return collapsed.get(key.replace('_', ''))

    for entry in manual_entries:
        text = pages.get(entry['name'].replace(' ', '_')) or pages.get(entry['wiki'])
        if not text or "mat1" not in text:
            text = pages.get(entry['wiki']) if text != pages.get(entry['wiki']) else text
        if not text:
            continue
        materials = []
        for name, qty in parse_recipe_materials(text):
            lname = name.lower()
            if lname in furniture:
                target = entry_by_lname.get(furniture_alias.get(lname, lname))
                if target is not None and target is not entry:
                    slug = re.sub(r'[^a-z0-9]+', '_', target['name'].lower()).strip('_')
                    req = 'unlock:gearmark_' + slug
                    if req not in entry['requirements']:
                        entry['requirements'].append(req)
                continue
            item_id = resolve_material(name)
            if item_id is None:
                # a silently-dropped material shipped ornate's box without its
                # 8 glories + 8 rings of wealth (2026-07-21) — fail loud now
                missing.append(f'MATERIAL? {name} ({entry["name"]})')
                continue
            materials.append({"itemId": item_id, "qty": qty, "name": name})
        if materials:
            entry['materials'] = materials

# the write happens only once everything resolved — a fail-fast mid-write
# used to truncate the shipped pack
if missing:
    print('\n'.join(missing), file=sys.stderr)
    sys.exit(1)
with open(out, 'w') as f:
    json.dump(pack, f, indent=2)
    f.write('\n')
n = sum(len(g['items']) for p in phases for g in p['groups'])
print(f'wrote {n} entries across {sum(len(p["groups"]) for p in phases)} groups')
