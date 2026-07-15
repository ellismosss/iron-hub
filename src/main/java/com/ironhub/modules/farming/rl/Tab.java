/*
 * Copyright (c) 2018 Abex
 * Copyright (c) 2022, David Reess
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 *  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.ironhub.modules.farming.rl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

/**
 * Patch categories — core Time Tracking's Tab split one-category-per-patch-type
 * (the Time Tracking Reminder plugin's regrouping), so each type can carry its
 * own summary, ready-infobox and display row. Hand-maintained alongside the
 * generated rl/ files; PatchImplementation's Tab assignments are rewritten onto
 * these constants by tools/gen_timetracking.py.
 */
@RequiredArgsConstructor
@Getter
public enum Tab
{
	OVERVIEW("Overview", ItemID.OLD_NOTES),
	CLOCK("Timers & Stopwatches", ItemID.WATCH),
	BIRD_HOUSE("Bird Houses", ItemID.OAK_BIRD_HOUSE),
	ALLOTMENT("Allotment Patches", ItemID.CABBAGE),
	FLOWER("Flower Patches", ItemID.RED_FLOWERS),
	HERB("Herb Patches", ItemID.GRIMY_RANARR_WEED),
	TREE("Tree Patches", ItemID.YEW_LOGS),
	FRUIT_TREE("Fruit Tree Patches", ItemID.PINEAPPLE),
	HOPS("Hops Patches", ItemID.BARLEY),
	BUSH("Bush Patches", ItemID.POISON_IVY_BERRIES),
	GRAPE("Grape Patches", ItemID.GRAPES),
	SPECIAL("Special Patches", ItemID.MUSHROOM),
	MUSHROOM("Mushroom Patch", ItemID.MUSHROOM),
	BELLADONNA("Belladonna Patch", ItemID.CAVE_NIGHTSHADE),
	BIG_COMPOST("Giant Compost Bin", ItemID.ULTRACOMPOST),
	CORAL("Coral Patches", ItemID.UMBRAL_CORAL),
	SEAWEED("Seaweed Patches", ItemID.GIANT_SEAWEED),
	CALQUAT("Calquat Patches", ItemID.CALQUAT_FRUIT),
	CELASTRUS("Celastrus Patch", ItemID.BATTLESTAFF),
	HARDWOOD("Hardwood Patches", ItemID.TEAK_LOGS),
	REDWOOD("Redwood Patch", ItemID.REDWOOD_LOGS),
	CACTUS("Cactus Patches", ItemID.POTATO_CACTUS),
	HESPORI("Hespori Patch", ItemID.TANGLEROOT),
	CRYSTAL("Crystal Patch", ItemID.CRYSTAL_SHARD),
	TIME_OFFSET("Farming Tick Offset", ItemID.WATERING_CAN),
	ANIMA("Anima Patch", ItemID.ANIMAINFUSED_BARK);

	private final String name;
	private final int itemID;
}
