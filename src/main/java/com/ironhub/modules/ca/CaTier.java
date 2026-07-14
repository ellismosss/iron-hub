package com.ironhub.modules.ca;

import java.awt.Color;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarbitID;

/**
 * The six Combat Achievement tiers. Threshold/completed/status varbits are
 * documented RuneLite API constants; the task-list enum ids come from the
 * game cache (each enum maps task index → struct id) — they have no gameval
 * constants yet, so the values are adopted from the production Combat
 * Achievements Tracker hub plugin and sanity-checked at load time.
 * Points per task are fixed per tier (wiki: Combat Achievements).
 */
enum CaTier
{
	EASY("Easy", 1, 3981, VarbitID.CA_THRESHOLD_EASY, Varbits.COMBAT_TASK_EASY,
		Varbits.COMBAT_ACHIEVEMENT_TIER_EASY, new Color(0xBF, 0x7F, 0x3F)),
	MEDIUM("Medium", 2, 3982, VarbitID.CA_THRESHOLD_MEDIUM, Varbits.COMBAT_TASK_MEDIUM,
		Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM, new Color(0xB8, 0xB8, 0xB8)),
	HARD("Hard", 3, 3983, VarbitID.CA_THRESHOLD_HARD, Varbits.COMBAT_TASK_HARD,
		Varbits.COMBAT_ACHIEVEMENT_TIER_HARD, new Color(0x8A, 0x8A, 0x8A)),
	ELITE("Elite", 4, 3984, VarbitID.CA_THRESHOLD_ELITE, Varbits.COMBAT_TASK_ELITE,
		Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE, new Color(0x64, 0x95, 0xED)),
	MASTER("Master", 5, 3985, VarbitID.CA_THRESHOLD_MASTER, Varbits.COMBAT_TASK_MASTER,
		Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER, new Color(0xDC, 0x50, 0x50)),
	GRANDMASTER("Grandmaster", 6, 3986, VarbitID.CA_THRESHOLD_GRANDMASTER, Varbits.COMBAT_TASK_GRANDMASTER,
		Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER, new Color(0xE8, 0xC5, 0x3A));

	final String display;
	final int points;
	final int taskListEnumId;
	final int thresholdVarbit;      // points needed to finish this tier
	final int completedCountVarbit; // tasks of this tier completed
	final int statusVarbit;         // ≥1 = tier complete
	/** Tier identity colour (matches the wiki tier icon), NOT a status colour. */
	final Color color;

	CaTier(String display, int points, int taskListEnumId, int thresholdVarbit,
		int completedCountVarbit, int statusVarbit, Color color)
	{
		this.display = display;
		this.points = points;
		this.taskListEnumId = taskListEnumId;
		this.thresholdVarbit = thresholdVarbit;
		this.completedCountVarbit = completedCountVarbit;
		this.statusVarbit = statusVarbit;
		this.color = color;
	}

	/** Bundled wiki tier icon resource path (small variant). */
	String iconResource()
	{
		return "/data/icons/ca/" + name().toLowerCase() + ".png";
	}
}
