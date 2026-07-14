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

Facts that have already bitten us (all wiki-verified now): dual macuahuitl is
70 Att/75 Str (not 75/75); the demonbane trio is level **77** (not 75);
Moons armour is 75 style + **50 Defence**; the eclipse atlatl needs 50
Attack AND 50 Strength; the DWH needs 60 **Strength** (not Attack); Masori
(f) is 80 Def (base Masori is 30); Elidinis' ward (f) is 80/80/80;
eye of ayak is 83 Magic; dragon hunter wand 65 Magic; Virtus is 78 Magic/75
Def; full Void needs 42 in six combat stats + 22 Prayer.

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
