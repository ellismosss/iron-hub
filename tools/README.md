# Data-pack tooling

Scripts that generate and verify `src/main/resources/data/gear-progression.json`.
The pack is **generated, never hand-edited** — edit `gen_gear.py` and rerun.

## Files

- `gen_gear.py` — the source of truth for the gear chart. Items are declared
  by `ItemID` constant name and resolved against `itemids.txt`, so a typo'd
  id aborts generation instead of shipping. Quest names are checked against
  `questnames.txt` (Quest enum display names), `implies` targets against the
  entry list. Run: `python3 gen_gear.py`.
- `wiki_audit.py` — fetches every entry's OSRS wiki page and diffs the
  pack's `skill:` requirements against the "requires ..." prose. Output is a
  flag list for **manual adjudication** (prose also mentions creation-method
  and unrelated levels — a flag is a question, not an error). Run after any
  content change: `python3 wiki_audit.py`.
- `itemids.txt` / `questnames.txt` — dumps from the pinned RuneLite API jar.
  Regenerate when the API version bumps:

  ```
  JAR=$(find ~/.gradle -name "runelite-api-*.jar" | grep -v sources | sort | tail -1)
  javap -classpath "$JAR" -constants net.runelite.api.ItemID \
    | sed -n 's/.*int \([A-Z0-9_]*\) = \([0-9]*\);/\1 \2/p' > itemids.txt
  # questnames.txt: print Quest.getName() for each enum value (needs a tiny
  # Java main on the API jar — see git history of this file)
  ```

## Gotchas learned the hard way (see DOMAIN-NOTES.md for the full list)

- The **unsuffixed ItemID constant is not always the real item** — for DT2
  rewards it's the pre-release beta (`ULTOR_RING` = 25485 beta,
  `ULTOR_RING_28307` = the actual ring). When a `NAME_<id>` duplicate exists
  at a much higher id, check which one is live.
- Requirements in the pack = **equip/use gates + iron-mandatory creation
  gates** (e.g. 93 Magic to enchant zenytes, 78 Fletching for a blowpipe).
  Buyable-alternative creation skills stay out.
