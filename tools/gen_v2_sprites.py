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
both themes." Each entry therefore records exactly WHICH variants exist —
vanilla, mystic, dark — and the runtime falls back to vanilla for a theme
the pack doesn't re-sprite, which is how a resource pack behaves in game.

Dark vanilla joined on 2026-07-25 (Luke: "I wanted a dark-mode that looked
exactly like Vanilla"), which is why variants are a LIST now rather than a
themed boolean: coverage is per theme and genuinely partial. A key with no
vanilla original at all keeps whatever variants it has and is flagged, so
the gallery can say so instead of implying it is vanilla art.

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

# theme -> the filename suffix that carries its art. Vanilla has none.
VARIANTS = {"mystic": "_mystic", "dark": "_dark"}


def png_size(path):
	"""Width/height straight out of the IHDR — no Pillow dependency."""
	with open(path, "rb") as f:
		head = f.read(24)
	if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
		sys.exit(f"not a PNG: {path}")
	return struct.unpack(">II", head[16:24])


def split_variant(stem):
	"""'button_hovered_mystic' -> ('button_hovered', 'mystic')."""
	for variant, suffix in VARIANTS.items():
		if stem.endswith(suffix):
			return stem[: -len(suffix)], variant
	return stem, "vanilla"


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
			base, variant = split_variant(stem)
			key = f"{family}/{base}"
			entry = sprites.setdefault(key, {"variants": [], "sizes": {}})
			entry["variants"].append(variant)
			entry["sizes"][variant] = png_size(os.path.join(src_dir, name))

	# w/h describe the sprite for layout; a pack that trims transparent
	# padding gives a smaller canvas for the same ink, so vanilla is the
	# reference and any disagreement is REPORTED rather than silently kept
	mismatched = []
	for key, entry in sorted(sprites.items()):
		sizes = entry["sizes"]
		reference = sizes.get("vanilla") or sizes[sorted(sizes)[0]]
		if len(set(sizes.values())) > 1:
			mismatched.append((key, dict(sorted(sizes.items()))))
		sprites[key] = {
			"w": reference[0],
			"h": reference[1],
			"variants": sorted(entry["variants"]),
		}

	index = {
		"version": 1,
		"source": SRC,
		"sprites": dict(sorted(sprites.items())),
	}
	with open(INDEX, "w") as f:
		json.dump(index, f, indent="\t", sort_keys=False)
		f.write("\n")

	counts = {name: sum(1 for s in sprites.values() if name in s["variants"])
		for name in ["vanilla", "mystic", "dark"]}
	no_vanilla = [k for k, s in sprites.items() if "vanilla" not in s["variants"]]
	print(f"copied {copied} files -> {DEST}")
	print(f"indexed {len(sprites)} sprites: " + ", ".join(
		f"{n} {c}" for n, c in counts.items()))
	print(f"  {len(no_vanilla)} have no vanilla original (pack-only art)")
	if mismatched:
		print(f"  {len(mismatched)} sprites whose variants differ in canvas size "
			"(w/h follow vanilla):")
		for key, sizes in mismatched:
			print("    " + key + " " + " ".join(
				f"{n}={w}x{h}" for n, (w, h) in sizes.items()))

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
	# every copied file is accounted for by exactly one variant of one entry
	indexed_files = sum(len(s["variants"]) for s in sprites.values())
	if indexed_files != copied:
		sys.exit(f"index doesn't account for every file: {indexed_files} variants "
			f"!= {copied} copied")
	if copied < 600:
		sys.exit(f"only {copied} sprites copied — the source looks truncated")
	# the atoms are all built on art the dark pack re-sprites; if a future
	# curation drops one, the dark theme silently falls back to a vanilla
	# sprite in the middle of a dark panel
	for key in ["ui/buttons/enter_wilderness_teleport",
		"ui/borders/equipment_metal_corner_top_left",
		"ui/borders/bottom_line_mode_side_panel_edge_top",
		"ui/buttons/regular_large",
		"ui/checkbox/square_bordered_checkbox"]:
		if "dark" not in sprites[key]["variants"]:
			sys.exit(f"{key} has no dark variant — the dark theme's surfaces need it")


if __name__ == "__main__":
	main()
