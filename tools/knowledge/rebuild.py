#!/usr/bin/env python3
"""Rebuild the whole knowledge base from scratch: drop the DB, rerun every
importer/harvester in dependency order, regenerate the reports. All wiki
fetches are cached under tools/.cache-knowledge/, so a full rebuild after
the first run is minutes, not hours. Delete cache entries to refresh."""

import os
import subprocess
import sys

import kb

STEPS = [
    "import_packs.py",
    "harvest_equipment.py",
    "harvest_poh.py",
    "harvest_boosts.py",
    "harvest_ca.py",
    "harvest_consumables.py",
    "harvest_drops.py",
    "harvest_shops.py",
    "harvest_items.py",
    "harvest_qol.py",
    "harvest_training.py",
    "harvest_rewards.py",
    "harvest_recipes.py",
    "harvest_buckets.py",
    "join_obtainment.py",
    "join_effects.py",
    "apply_curated.py",
    "verify_packs.py",
    "report.py",
]


def main():
    if os.path.exists(kb.DB_PATH):
        os.remove(kb.DB_PATH)
    here = os.path.dirname(os.path.abspath(__file__))
    for step in STEPS:
        print(f"\n== {step} ==")
        subprocess.run([sys.executable, os.path.join(here, step)], check=True)


if __name__ == "__main__":
    main()
