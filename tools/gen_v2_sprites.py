#!/usr/bin/env python3
"""Import Luke's curated sprite set into the jar and index it.

Source of truth: design/use_these_sprites — hand-curated by Luke, one folder
per family. The V2 design system is built EXCLUSIVELY from these; nothing is
drawn by hand and nothing is pulled from elsewhere.

Two outputs:

  src/main/resources/data/v2/<family>/<name>.png   the shipped art
  src/main/resources/data/v2-sprites.json          the index V2Sprites reads

The index exists so the theme rule is DATA, not a filename guess at runtime
(Luke, 2026-07-24): "only sprites that explicitly have a _mystic variant in
the folder should change between themes. Otherwise, use the same sprite in
both themes." So each entry records whether a _mystic twin exists, and a
_mystic file with no vanilla original is flagged mysticOnly — those are art
we use in BOTH themes while knowing it isn't vanilla.

Folder names with spaces are normalised (a resource path with a space in it
is a lifetime of escaping bugs); the mapping is explicit below so a renamed
source folder fails loudly instead of silently shipping a second copy.
"""

import json
import os
import shutil
import struct
import sys

SRC = "design/use_these_sprites"
DEST = "src/main/resources/data/v2"
INDEX = "src/main/resources/data/v2-sprites.json"

# source folder -> shipped family name
FOLDERS = {
	"icons": "icons",
	"icons/attack_styles": "icons/attack_styles",
	"icons/chevron": "icons/chevron",
	"icons/clock": "icons/clock",
	"icons/combat": "icons/combat",
	"icons/combat_achievements": "icons/combat_achievements",
	"icons/equipment": "icons/equipment",
	"icons/inventory": "icons/inventory",
	"icons/magic": "icons/magic",
	"icons/money": "icons/money",
	"icons/prayer": "icons/prayer",
	"icons/prayers": "icons/prayers",
	"icons/quest icons": "icons/quest",
	"icons/sailing": "icons/sailing",
	"icons/search": "icons/search",
	"icons/skills": "icons/skills",
	"icons/star": "icons/star",
	"icons/wiki": "icons/wiki",
	"icons/xp skills": "icons/xp",
	"ui/arrows": "ui/arrows",
	"ui/borders": "ui/borders",
	"ui/buttons": "ui/buttons",
	"ui/buttons square": "ui/buttons_square",
	"ui/checkbox": "ui/checkbox",
	"ui/plus_minus": "ui/plus_minus",
	"ui/progress_bar": "ui/progress_bar",
	"ui/ticks crosses": "ui/ticks",
}

MYSTIC = "_mystic"


def png_size(path):
	"""Width/height straight out of the IHDR — no Pillow dependency."""
	with open(path, "rb") as f:
		head = f.read(24)
	if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
		sys.exit(f"not a PNG: {path}")
	return struct.unpack(">II", head[16:24])


def main():
	if not os.path.isdir(SRC):
		sys.exit(f"missing sprite source {SRC}")

	# every source folder must be mapped: an unmapped one means Luke added a
	# family and it would otherwise ship nowhere
	found = set()
	for root, dirs, files in os.walk(SRC):
		if any(f.endswith(".png") for f in files):
			found.add(os.path.relpath(root, SRC))
	unmapped = found - set(FOLDERS)
	if unmapped:
		sys.exit(f"unmapped sprite folders (add them to FOLDERS): {sorted(unmapped)}")
	missing = set(FOLDERS) - found
	if missing:
		sys.exit(f"mapped folders that no longer exist: {sorted(missing)}")

	if os.path.isdir(DEST):
		shutil.rmtree(DEST)

	sprites = {}
	copied = 0
	for src_folder, family in sorted(FOLDERS.items()):
		src_dir = os.path.join(SRC, src_folder)
		dest_dir = os.path.join(DEST, family)
		os.makedirs(dest_dir, exist_ok=True)
		names = sorted(f for f in os.listdir(src_dir) if f.endswith(".png"))
		stems = {n[:-4] for n in names}
		for name in names:
			shutil.copy2(os.path.join(src_dir, name), os.path.join(dest_dir, name))
			copied += 1
			stem = name[:-4]
			if stem.endswith(MYSTIC):
				base = stem[: -len(MYSTIC)]
				if base in stems:
					continue  # indexed under its vanilla original
				# a _mystic file with no vanilla original: still one sprite,
				# used in both themes, but flagged so we never claim it is
				# vanilla art
				sprites[f"{family}/{base}"] = {
					"w": png_size(os.path.join(src_dir, name))[0],
					"h": png_size(os.path.join(src_dir, name))[1],
					"themed": False,
					"mysticOnly": True,
				}
				continue
			w, h = png_size(os.path.join(src_dir, name))
			sprites[f"{family}/{stem}"] = {
				"w": w,
				"h": h,
				"themed": f"{stem}{MYSTIC}" in stems,
			}

	index = {
		"version": 1,
		"source": SRC,
		"sprites": dict(sorted(sprites.items())),
	}
	with open(INDEX, "w") as f:
		json.dump(index, f, indent="\t", sort_keys=False)
		f.write("\n")

	themed = sum(1 for s in sprites.values() if s["themed"])
	mystic_only = sum(1 for s in sprites.values() if s.get("mysticOnly"))
	print(f"copied {copied} files -> {DEST}")
	print(f"indexed {len(sprites)} sprites: {themed} themed, "
		f"{mystic_only} mystic-only, {len(sprites) - themed - mystic_only} theme-agnostic")

	# sanity floors: the families the atoms are built on must be present
	required = [
		"ui/borders/equipment_metal_corner_top_left",
		"ui/borders/equipment_edge_top",
		"ui/borders/bottom_line_mode_side_panel_edge_top",
		"ui/buttons/regular_large",
		"ui/checkbox/square_bordered_checkbox",
		"ui/progress_bar/progress_bar_green",
		"ui/ticks/checkmark_small",
	]
	for key in required:
		if key not in sprites:
			sys.exit(f"required sprite missing from the index: {key}")
	# every copied file is either its own entry or the _mystic half of one
	if len(sprites) + themed != copied:
		sys.exit(f"index doesn't account for every file: {len(sprites)} entries "
			f"+ {themed} mystic twins != {copied} copied")
	if copied < 450:
		sys.exit(f"only {copied} sprites copied — the source looks truncated")


if __name__ == "__main__":
	main()
