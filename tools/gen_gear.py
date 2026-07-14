#!/usr/bin/env python3
"""Generate gear-progression.json from ItemID constant names.

Every item is specified by its net.runelite.api.ItemID constant name and
resolved against the javap dump (itemids.txt) — an unknown constant aborts
the run, so a typo'd id can never ship. Quest requirement names are checked
against the Quest enum display names (questnames.txt).
"""
import json, os, re, sys

IDS = {}
for line in open(os.path.join(os.path.dirname(__file__), 'itemids.txt')):
    n, v = line.split()
    IDS.setdefault(n, int(v))  # first occurrence wins (base ids come first)
QUESTS = {q.strip().lower() for q in open(os.path.join(os.path.dirname(__file__), 'questnames.txt'))}

CATS = {'melee', 'ranged', 'magic', 'utility', 'poh', 'boat'}
missing = []

def I(name, const, cats, reqs=(), wiki=None, exact=False, implies=()):
    """Detectable item: const is both icon and ownership detection."""
    e = _entry(name, const, None, cats, reqs, wiki)
    if exact:
        e['exact'] = True
    if implies:
        e['implies'] = list(implies)
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
      I("Rune pouch", 'RUNE_POUCH', ['utility']),
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
      I("Coal bag", 'COAL_BAG_12019', ['utility'], ['skill:Mining:30']),
      I("Gem bag", 'GEM_BAG_12020', ['utility'], ['skill:Mining:30']),
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
      I("Imbued god cape", 'IMBUED_SARADOMIN_CAPE', ['magic'], ['skill:Magic:75'], 'Imbued_god_cape', implies=['God cape']),
      I("Infinity boots", 'INFINITY_BOOTS', ['magic'], ['skill:Magic:50', 'skill:Defence:25']),
      I("Mage's book", 'MAGES_BOOK', ['magic'], ['skill:Magic:60'], "Mage's_book"),
    ),
    G('[L10-15] The slayer on-ramp',
      I("Arkan blade", 'ARKAN_BLADE', ['melee'], ['quest:The Final Dawn', 'skill:Attack:60']),
      I("Black mask (i)", 'BLACK_MASK_I', ['melee'], ['skill:Slayer:58', 'skill:Strength:20', 'skill:Defence:10'], 'Black_mask', exact=True),
      I("Pharaoh's sceptre", 'PHARAOHS_SCEPTRE', ['utility'], ['skill:Thieving:21'], "Pharaoh's_sceptre"),
      I("Arclight", 'ARCLIGHT', ['melee'], ['skill:Attack:75']),
      I("Slayer helmet (i)", 'SLAYER_HELMET_I', ['melee', 'ranged', 'magic'], ['skill:Slayer:58'], 'Slayer_helmet', exact=True, implies=['Black mask (i)']),
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
      I("Elite void top", 'ELITE_VOID_TOP', ['ranged', 'magic'], ['Hard Western Provinces diary', 'skill:Prayer:22', 'skill:Attack:42', 'skill:Strength:42', 'skill:Defence:42', 'skill:Hitpoints:42', 'skill:Ranged:42', 'skill:Magic:42'], 'Elite_Void_Knight_equipment', implies=['Void knight top']),
      I("Crystal halberd", 'CRYSTAL_HALBERD', ['melee'], ['quest:Roving Elves', 'skill:Attack:70', 'skill:Strength:35', 'skill:Agility:50']),
      I("Red chinchompa", 'RED_CHINCHOMPA', ['ranged'], ['skill:Hunter:63', 'skill:Ranged:55']),
    ),
    G('Convenience grinds',
      I("Fish barrel", 'FISH_BARREL', ['utility'], ['skill:Fishing:35']),
      I("Tackle box", 'TACKLE_BOX', ['utility'], ['skill:Fishing:35']),
      I("Gem sack", 'GEM_SACK', ['utility'], implies=['Gem bag']),
      I("Plank sack", 'PLANK_SACK', ['utility']),
      I("Amy's saw", 'AMYS_SAW', ['utility'], [], "Amy's_saw"),
      I("Seed box", 'SEED_BOX', ['utility'], ['skill:Farming:34']),
      I("Herb sack", 'HERB_SACK', ['utility']),
      I("Divine rune pouch", 'DIVINE_RUNE_POUCH', ['utility'], ['quest:Beneath Cursed Sands', 'skill:Crafting:75'], implies=['Rune pouch']),
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
      I("Ava's assembler", 'AVAS_ASSEMBLER', ['ranged'], ['quest:Dragon Slayer II', 'kc:Vorkath:1', 'skill:Ranged:70'], "Ava's_assembler", implies=["Ava's accumulator"]),
      I("Dragon pickaxe", 'DRAGON_PICKAXE', ['utility'], ['skill:Mining:61', 'skill:Attack:60']),
      I("Explorer's ring 4", 'EXPLORERS_RING_4', ['utility'], ['Elite Lumbridge & Draynor diary'], "Explorer's_ring", exact=True),
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
      I("Abyssal tentacle", 'ABYSSAL_TENTACLE', ['melee'], ['skill:Slayer:87', 'skill:Attack:75'], implies=['Abyssal whip']),
      I("Dragon warhammer", 'DRAGON_WARHAMMER', ['melee'], ['skill:Strength:60']),
      I("Emberlight", 'EMBERLIGHT', ['melee'], ['quest:While Guthix Sleeps', 'skill:Attack:77', 'skill:Smithing:74'], implies=['Arclight']),
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
      I("Confliction gauntlets", 'CONFLICTION_GAUNTLETS', ['magic'], ['skill:Hitpoints:90', 'skill:Crafting:83', 'skill:Smithing:70'], implies=['Tormented bracelet']),
      I("Avernic treads", 'AVERNIC_TREADS', ['melee', 'ranged', 'magic'], [], implies=['Primordial boots', 'Eternal boots', 'Pegasian boots']),
    ),
    G('[L33-34] Diaries, boots, amulet endgame',
      I("Desert amulet 4", 'DESERT_AMULET_4', ['utility'], ['Elite Desert diary'], 'Desert_amulet', exact=True),
      I("Rada's blessing 4", 'RADAS_BLESSING_4', ['utility'], ['Elite Kourend & Kebos diary'], "Rada's_blessing", exact=True),
      I("Primordial boots", 'PRIMORDIAL_BOOTS', ['melee'], ['skill:Slayer:91', 'skill:Strength:75', 'skill:Defence:75'], implies=['Dragon boots']),
      I("Eternal boots", 'ETERNAL_BOOTS', ['magic'], ['skill:Slayer:91', 'skill:Magic:75', 'skill:Defence:75'], implies=['Infinity boots']),
      I("Pegasian boots", 'PEGASIAN_BOOTS', ['ranged'], ['skill:Slayer:91', 'skill:Ranged:75', 'skill:Defence:75']),
      I("Amulet of rancour", 'AMULET_OF_RANCOUR', ['melee'], ['skill:Slayer:92', 'skill:Hitpoints:90'], implies=['Amulet of torture']),
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
      I("Neitiznot faceguard", 'NEITIZNOT_FACEGUARD', ['melee'], ['quest:The Fremennik Exiles', 'skill:Defence:70', 'skill:Slayer:60'], implies=['Helm of Neitiznot']),
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
      I("Avernic defender", 'AVERNIC_DEFENDER', ['melee'], ['skill:Attack:70', 'skill:Defence:70'], implies=['Dragon defender']),
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
      M("Mounted amulet of glory", 'AMULET_OF_GLORY4', ['poh'], ['skill:Construction:47'], 'Amulet_of_Glory_(mounted)', iconfile='mounted_glory.png'),
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
      M("Mounted Xeric's talisman", 'XERICS_TALISMAN', ['poh'], ['skill:Construction:72'], "Mounted_Xeric's_Talisman", iconfile='mounted_xerics.png'),
      M("Marble portal nexus", 'TELEPORT_TO_HOUSE', ['poh'], ['skill:Construction:72'], 'Portal_Nexus', iconfile='marble_nexus.png'),
      M("Gilded altar", 'DRAGON_BONES', ['poh'], ['skill:Construction:75'], 'Altar_space', iconfile='gilded_altar.png'),
      M("Spirit tree", 'SPIRIT_SEED', ['poh'], ['skill:Construction:75', 'skill:Farming:83'], 'Spirit_tree_(Construction)', iconfile='spirit_tree.png'),
      M("Wilderness Obelisk", 'BURNING_AMULET5', ['poh'], ['skill:Construction:80'], 'Obelisk_(Construction)', iconfile='wilderness_obelisk.png'),
      M("Ancient / Lunar / Dark altar", 'BLOOD_RUNE', ['poh'], ['skill:Construction:80'], 'Occult_altar', iconfile='spellbook_altar.png'),
      M("Basic jewellery box", 'RING_OF_DUELING8', ['poh'], ['skill:Construction:81'], 'Jewellery_box', iconfile='basic_jewellery_box.png'),
      M("Mounted digsite pendant", 'DIGSITE_PENDANT_5', ['poh'], ['skill:Construction:82', 'quest:The Dig Site'], 'Mounted_digsite_pendant', iconfile='mounted_digsite.png'),
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

pack = {'$schema': './schemas/gear-progression.schema.json', 'version': 1, 'phases': phases}

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
with open(out, 'w') as f:
    json.dump(pack, f, indent=2)
    f.write('\n')
n = sum(len(g['items']) for p in phases for g in p['groups'])
print(f'wrote {n} entries across {sum(len(p["groups"]) for p in phases)} groups')
