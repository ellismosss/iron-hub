# OSRS domain notes

Game knowledge that shapes this plugin's design, collected from the OSRS wiki
(https://oldschool.runescape.wiki/) and in-client testing. **Read this before
writing any feature that infers account state from game data.** When in doubt,
verify against the wiki — its MediaWiki API is public (`/api.php`, send a
descriptive User-Agent); `tools/wiki_audit.py` shows the pattern.

## Item identity is layered — never assume one id = one item

Every visual/mechanical variant is its own item id. RuneLite's
`ItemVariationMapping` groups related ids, but the groups mix THREE
relationships that the plugin must treat differently:

1. **Interchangeable variants** — recolours (24 graceful hood ids), charge
   states (blowpipe empty/charged, trident), broken/repaired (fire cape
   variants), open/closed containers (coal bag). Owning any = owning the
   item. → `item:` requirements, `canonicalStock`.
2. **Tiers and imbues in one group** — Ghommal's hilts 1–6, diary rewards
   (Ardougne cloak 1–4, Explorer's ring, Desert amulet, Rada's blessing),
   salve amulet /(e)/(i)/(ei), black mask ↔ (i), berserker ring ↔ (i).
   Owning tier 2 does NOT mean owning tier 4. → `itemx:` requirements,
   `exact: true` in the pack.
3. **Upgrade/consumption chains across groups** — the successor destroys or
   supersedes the predecessor, so owning it PROVES the predecessor was
   obtained even though it no longer exists: Ava's attractor → accumulator →
   assembler, Kharedst's memoirs → Book of the dead, black mask → slayer
   helmet, whip → tentacle, Arclight → Emberlight, dragon defender → Avernic
   defender, torture → rancour, the three Cerberus boots → Avernic treads,
   fire cape → infernal cape (sacrificed for Inferno entry). → `implies`
   lists in the pack.

**ItemID constant trap:** RuneLite names constants from cache order — the
unsuffixed name is the FIRST id with that name, which for content that had a
pre-release beta (DT2 rings, Soulreaper axe) is the dead beta item. The real
item is the `NAME_<id>` duplicate at a higher id.

## Requirement semantics — three different "levels" per item

The wiki (and players) distinguish gates the plugin must not conflate:

- **Equip/use gates** — hard, always apply (75 Ranged for a blowpipe,
  90 HP for rancour/confliction gauntlets, 70 Def + 50 Agility for crystal
  armour, Rigour/Augury also need 70 Defence to *activate*).
- **Obtainment gates** — how the account gets it (quest completion, kill
  counts, 66 Magic to enter the Magic Guild shop, diary tiers). For ironmen
  these are mandatory; for mains often bypassable via GE.
- **Creation gates** — skills to assemble it. Include only when
  iron-mandatory with no alternative (93 Magic zenyte enchants, 89–98
  Crafting for zenyte jewellery, 78 Fletching for the blowpipe, 75 Crafting
  for the divine pouch). Skip when boostable-trivial or when the guide's
  obtainment path avoids it.

**Obtainment is usually a DISJUNCTION of paths, each with its own nested
gates.** An amulet of glory is craft-it-yourself (80 Crafting for the
dragonstone amulet, then 68 Magic to enchant) OR dragon implings (83 Hunter)
— a player with neither meets no path, so "requirements met" must mean "at
least one full path met". Encode with the `any:` requirement form
(`any:skill:Crafting:80|skill:Hunter:83`; `&` joins leaves within a path).
Rules of thumb when writing entries:
- Chase each path to its OWN gates (the impling path's gate is Hunter, not
  Crafting; the crossbow's antler needs 72 Hunter before 74 Fletching).
- If one practical path is UNGATED (magic shortbow from hard clues, mixed
  hide cape made-for-you by Pellem for 20k), the gated alternative must NOT
  appear as a requirement — it would block a perfectly reachable item.
  Ignore fringe paths (wilderness-only drops like bloodbark from zombie
  pirates) rather than making everything vacuous.
- Enchant levels are per-gem, not per-item: sapphire 7, emerald 27, ruby 49,
  diamond 57, dragonstone 68, onyx 87, zenyte 93.

Facts that have already bitten us (all wiki-verified now): dual macuahuitl is
70 Att/75 Str (not 75/75); the demonbane trio is level **77** (not 75);
Moons armour is 75 style + **50 Defence**; the eclipse atlatl needs 50
Attack AND 50 Strength; the DWH needs 60 **Strength** (not Attack); Masori
(f) is 80 Def (base Masori is 30); Elidinis' ward (f) is 80/80/80;
eye of ayak is 83 Magic; dragon hunter wand 65 Magic; Virtus is 78 Magic/75
Def; full Void needs 42 in six combat stats + 22 Prayer.

## Temporary skill boosts

Boosts satisfy **action gates only** — creation, activities, slayer kills —
never equip gates (wiki, Magic potion: "will not be able to equip... but
will be able to cast"). The requirement graph encodes this as `skillb:`
(boostable) vs `skill:` (hard/equip); only `skillb:` leaves consult boosts.

Mechanics that matter:
- **Visible boosts don't stack with each other** — the best one wins. The
  crystal saw is *invisible* and stacks on top: saw (+3) + POH tea (+3) = +6
  (86→92, Theoatrix's max-house line), saw + spicy stew (+5) = +8.
- **Boost sources have their own obtainment gates**, tracked in
  `data/boosts.json`: spicy stews need RFD Evil Dave; pies need the Cooking
  level (or owning one); the saw needs The Eyes of Glouphrie; POH tea needs
  Teak shelves 2. An unavailable boost gives no headroom.
- Slayer: a wild pie (+5) lets you *kill* higher monsters but not *receive*
  tasks above your level.
- The chart paints an **amber** corner triangle for boost-reachable items
  (green = met outright); tooltips name the usable sources per gap.

## Detection limits (what the client can and cannot tell us)

- **Kill counts** only accumulate from loot events after install — historic
  accomplishments need ownership proofs (`achieved` lists on goals).
- **Bank contents** are only known from the last bank visit; equipment and
  inventory sync live. POH furniture, diary completion states (varbits exist
  for diaries; furniture no), and spent currencies (marks of grace) are not
  item-detectable → manual marks / varbits where documented.
- Farming varbits only sync in-region; quest states need script runs on the
  client thread.

## Wiki page-name gotchas

Override `wiki` in the pack when the display name isn't the page: "God capes"
(plural), "Spirit tree (Construction)", "Fairy ring (Construction)",
"Jewellery box" (lower-case b), POH altars live under "Occult altar" /
"Altar space". Set items (Barrows, crystal, Masori, oathplate, Virtus,
Ancestral) have per-set overview pages that read better than per-piece ones.
