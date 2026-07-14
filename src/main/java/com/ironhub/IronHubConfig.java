package com.ironhub;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(IronHubConfig.GROUP)
public interface IronHubConfig extends Config
{
	String GROUP = "ironhub";

	@ConfigSection(
		name = "Modules",
		description = "Enable or disable individual Iron Hub modules",
		position = 0
	)
	String modulesSection = "modules";

	@ConfigItem(keyName = "gearProgression", name = "Gear progression", description = "Per-slot gear upgrade ladders", section = modulesSection, position = 1)
	default boolean gearProgression() { return true; }

	@ConfigItem(keyName = "questProgression", name = "Quest progression", description = "Optimal quest ordering and requirement graphs", section = modulesSection, position = 2)
	default boolean questProgression() { return true; }

	@ConfigItem(keyName = "skillMilestones", name = "Skill milestones", description = "Milestones and meta method guidance", section = modulesSection, position = 3)
	default boolean skillMilestones() { return true; }

	@ConfigItem(keyName = "qolChecklist", name = "QoL checklist", description = "Account QoL unlock tracking (PoH, herb sack, ...)", section = modulesSection, position = 4)
	default boolean qolChecklist() { return true; }

	@ConfigItem(keyName = "bankTracker", name = "Bank tracker", description = "Bank snapshots, banked XP and best-in-bank loadouts", section = modulesSection, position = 5)
	default boolean bankTracker() { return true; }

	@ConfigItem(keyName = "farmingRuns", name = "Farming runs", description = "Patch tracking, run timers and reminders", section = modulesSection, position = 6)
	default boolean farmingRuns() { return true; }

	@ConfigItem(keyName = "lootSupplies", name = "Loot & supplies", description = "Loot and supply consumption tracking", section = modulesSection, position = 7)
	default boolean lootSupplies() { return true; }

	@ConfigItem(keyName = "combatAchievements", name = "Combat achievements", description = "CA tier progress and suggestions", section = modulesSection, position = 8)
	default boolean combatAchievements() { return true; }

	@ConfigItem(keyName = "diaries", name = "Achievement diaries", description = "Diary progress and closest-to-completion", section = modulesSection, position = 9)
	default boolean diaries() { return true; }

	@ConfigItem(keyName = "dailies", name = "Dailies", description = "Daily and weekly activity reminders", section = modulesSection, position = 10)
	default boolean dailies() { return true; }

	@ConfigItem(keyName = "boatUpgrades", name = "Boat upgrades", description = "Sailing boat upgrade tracking", section = modulesSection, position = 11)
	default boolean boatUpgrades() { return true; }

	@ConfigItem(keyName = "goalPlanner", name = "Goal planner", description = "Dependency-tree plans for any target (item, quest, capstone)", section = modulesSection, position = 12)
	default boolean goalPlanner() { return true; }

	@ConfigItem(keyName = "whatNow", name = "\"What now?\" suggestions", description = "Ranked suggestions for what to do right now", section = modulesSection, position = 13)
	default boolean whatNow() { return true; }

	@ConfigItem(keyName = "clueStash", name = "Clues & STASH", description = "STASH tracking and emote-clue readiness", section = modulesSection, position = 14)
	default boolean clueStash() { return true; }

	@ConfigItem(keyName = "slayerOptimizer", name = "Slayer optimizer", description = "Point planning, block/skip advisor and per-task readiness", section = modulesSection, position = 15)
	default boolean slayerOptimizer() { return true; }

	@ConfigItem(keyName = "suppliesRunway", name = "Supplies runway", description = "Consumable runway estimates and restock guidance", section = modulesSection, position = 16)
	default boolean suppliesRunway() { return true; }

	@ConfigItem(keyName = "collectionLog", name = "Collection log", description = "Log progress, dry-streak stats and slot suggestions", section = modulesSection, position = 17)
	default boolean collectionLog() { return true; }

	@ConfigItem(keyName = "dashboard", name = "Dashboard & account score", description = "Composite progress score, trends and snapshot export", section = modulesSection, position = 18)
	default boolean dashboard() { return true; }

	@ConfigItem(keyName = "deathRecovery", name = "Death recovery", description = "Grave contents, reclaim info and death history", section = modulesSection, position = 19)
	default boolean deathRecovery() { return true; }

	@ConfigSection(
		name = "Integrations",
		description = "Optional external integrations",
		position = 1
	)
	String integrationsSection = "integrations";

	@ConfigItem(keyName = "shortestPathBridge", name = "Shortest Path routing", description = "Send Path requests to the Shortest Path plugin if installed", section = integrationsSection, position = 1)
	default boolean shortestPathBridge() { return true; }

	@ConfigItem(keyName = "dpsCalcExport", name = "DPS calculator export", description = "Export loadouts to the OSRS Wiki DPS calculator", section = integrationsSection, position = 2)
	default boolean dpsCalcExport() { return true; }

	@ConfigItem(keyName = "wikiSync", name = "WikiSync account sync", description = "Opt-in: sync quest/level/unlock state so OSRS Wiki tools can read your account", section = integrationsSection, position = 3)
	default boolean wikiSync() { return false; }

	@ConfigItem(keyName = "womSync", name = "Wise Old Man / TempleOSRS", description = "Opt-in: pull EHP/EHB context and ping gain updates on logout", section = integrationsSection, position = 4)
	default boolean womSync() { return false; }

	@ConfigItem(keyName = "discordWebhookUrl", name = "Discord webhook URL", description = "Opt-in: milestone notifications (log slots, uniques, pets, levels, goals)", section = integrationsSection, position = 5)
	default String discordWebhookUrl() { return ""; }
}
