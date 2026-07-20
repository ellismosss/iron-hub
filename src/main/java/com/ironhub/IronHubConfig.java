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

	@ConfigItem(keyName = "boatUpgrades", name = "Sailing upgrades", description = "Per-boat available upgrades, materials owned and next locked tiers", section = modulesSection, position = 11)
	default boolean boatUpgrades() { return true; }

	@ConfigItem(keyName = "goalPlanner", name = "Goal planner", description = "Dependency-tree plans for any target (item, quest, capstone)", section = modulesSection, position = 12)
	default boolean goalPlanner() { return true; }

	@ConfigItem(keyName = "clueStash", name = "Clues & STASH", description = "STASH tracking and emote-clue readiness", section = modulesSection, position = 14)
	default boolean clueStash() { return true; }

	@ConfigItem(keyName = "pohProgression", name = "PoH progression", description = "Useful house builds: built, buildable and locked tiers per space", section = modulesSection, position = 13)
	default boolean pohProgression() { return true; }

	@ConfigItem(keyName = "hunterRumours", name = "Hunters' Rumours", description = "Current rumour target, catch progress, hunting locations and history", section = modulesSection, position = 13)
	default boolean hunterRumours() { return true; }

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
		name = "Notifications",
		description = "Per-category notification toggles",
		position = 2
	)
	String notificationsSection = "notifications";

	@ConfigItem(keyName = "notifyPatchReady", name = "Patches ready", description = "Notify when a patch category (herbs, trees, ...) becomes ready to harvest", section = notificationsSection, position = 1)
	default boolean notifyPatchReady() { return true; }

	@ConfigItem(keyName = "farmingReadyInfoboxes", name = "Ready infoboxes", description = "Show an infobox while a patch category, your bird houses or the farming contract is ready", section = notificationsSection, position = 2)
	default boolean farmingReadyInfoboxes() { return true; }

	@ConfigItem(keyName = "farmBankSetup", name = "Run bank setup", description = "While a farm or daily run with a saved setup is active, lay its gear and inventory out over the bank so you can re-stock", section = notificationsSection, position = 3)
	default boolean farmBankSetup() { return true; }

	@ConfigItem(keyName = "farmGroupSites", name = "Group combined-run sites", description = "In the combined all-runs sequence, do every patch at a site in one stop-over (Catherby's fruit tree with its herb and allotment, everything at the Farming Guild)", section = notificationsSection, position = 3)
	default boolean farmGroupSites() { return true; }

	@ConfigItem(keyName = "notifyDailyReset", name = "Daily reset", description = "Notify at the daily reset when dailies are outstanding", section = notificationsSection, position = 4)
	default boolean notifyDailyReset() { return true; }

	@ConfigItem(keyName = "runwayWarningHours", name = "Runway warning (hours)", description = "Warn when a consumable's runway drops below this many hours", section = notificationsSection, position = 5)
	default int runwayWarningHours() { return 6; }

	@ConfigItem(keyName = "caTierGoal", name = "CA tier goal", description = "Combat Achievements tier to work toward; Auto advances to the next incomplete tier", section = notificationsSection, position = 6)
	default CaTierGoal caTierGoal() { return CaTierGoal.AUTO; }

	@ConfigItem(keyName = "caGoalMessages", name = "CA goal progress in chat", description = "On completing a combat task, show points progress toward your tier goal in the chatbox", section = notificationsSection, position = 7)
	default boolean caGoalMessages() { return true; }

	@ConfigItem(keyName = "slayerHighlight", name = "Highlight slayer targets", description = "Outline the monsters your current slayer task assigns", section = notificationsSection, position = 8)
	default boolean slayerHighlight() { return true; }

	@ConfigItem(keyName = "slayerSuperiorNotify", name = "Superior foe notification", description = "Notify when a superior slayer creature appears (turn off if RuneLite's own Slayer plugin already notifies)", section = notificationsSection, position = 9)
	default boolean slayerSuperiorNotify() { return true; }

	@ConfigItem(keyName = "slayerOverlay", name = "Slayer task overlay", description = "Floating overlay with the current task, progress, location hints, missing items and advisories", section = notificationsSection, position = 10)
	default boolean slayerOverlay() { return true; }

	@ConfigItem(keyName = "slayerBlockAdvice", name = "Block/skip advice in chat", description = "When you visit a slayer master, note preferred blocks not yet made; when a task on your always-skip list is assigned, say so", section = notificationsSection, position = 11)
	default boolean slayerBlockAdvice() { return true; }

	@ConfigItem(keyName = "hunterOverlay", name = "Rumour overlay", description = "Floating overlay with the current rumour, catch progress, trap and location hints", section = notificationsSection, position = 12)
	default boolean hunterOverlay() { return true; }

	@ConfigItem(keyName = "hunterHighlight", name = "Highlight rumour targets", description = "Outline the creatures your current rumour targets", section = notificationsSection, position = 13)
	default boolean hunterHighlight() { return true; }

	@ConfigItem(keyName = "hunterNavAids", name = "Rumour navigation aids", description = "World-map pins at the rumour's hunting areas, and highlight + scroll to its code in the fairy ring travel log", section = notificationsSection, position = 14)
	default boolean hunterNavAids() { return true; }

	enum CaTierGoal
	{
		AUTO("Auto"),
		EASY("Easy"),
		MEDIUM("Medium"),
		HARD("Hard"),
		ELITE("Elite"),
		MASTER("Master"),
		GRANDMASTER("Grandmaster");

		private final String display;

		CaTierGoal(String display)
		{
			this.display = display;
		}

		@Override
		public String toString()
		{
			return display;
		}
	}

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

	@ConfigItem(keyName = "gearHideComplete", name = "Gear chart hide complete", description = "Internal: gear chart hide-complete preference", hidden = true)
	default boolean gearHideComplete() { return false; }

	@ConfigItem(keyName = "loadoutLab", name = "Loadout Lab", description = "Exact-DPS best-in-slot sets from your owned gear, per enemy and combat style (imported Loadout Lab plugin)", section = modulesSection, position = 22)
	default boolean loadoutLab() { return true; }

	@ConfigItem(keyName = "labFollowActivity", name = "Loadout Lab follows activity", description = "Automatically select your slayer task or most recently fought/killed NPC in Loadout Lab", section = modulesSection, position = 23)
	default boolean labFollowActivity() { return true; }

	@ConfigItem(keyName = "plannerOverlay", name = "Goal overlay", description = "On-screen overlay that follows your current plan step (part of the Goal planner)", section = modulesSection, position = 24)
	default boolean plannerOverlay() { return true; }

	@ConfigItem(keyName = "designLab", name = "Design lab", description = "OSRS-look design system test gallery (development stage)", section = modulesSection, position = 25)
	default boolean designLab() { return true; }

	@ConfigItem(keyName = "osrsTheme", name = "OSRS skin theme", description = "Which stone the OSRS-look surfaces wear: the game's default, or the Mystic resource pack's greys", section = modulesSection, position = 26)
	default com.ironhub.ui.osrs.OsrsTheme osrsTheme() { return com.ironhub.ui.osrs.OsrsTheme.MYSTIC; }

	@ConfigItem(keyName = "dailiesNew", name = "Dailies (New)", description = "The Dailies tab in the OSRS skin, beside the classic one for comparison (migration preview)", section = modulesSection, position = 27)
	default boolean dailiesNew() { return true; }

	@ConfigItem(keyName = "portTasks", name = "Port tasks", description = "Sailing courier and bounty tasks: active slots, port suggestions and the noticeboard advisor", section = modulesSection, position = 30)
	default boolean portTasks() { return true; }

	@ConfigItem(keyName = "portBoardAdvice", name = "Port task board advisor", description = "While a port task board is open, overlay the best courier picks (Sailing XP per tile added to your route)", section = notificationsSection, position = 13)
	default boolean portBoardAdvice() { return true; }

	@ConfigItem(keyName = "moneyMaking", name = "Money making", description = "The OSRS wiki Money making guide, filtered to what your account can do", section = modulesSection, position = 29)
	default boolean moneyMaking() { return true; }

	@ConfigItem(keyName = "freezeWatchdog", name = "Freeze detector", description = "Log a stack dump when the client UI stalls, so freezes can be diagnosed (development aid, negligible cost)", section = modulesSection, position = 28)
	default boolean freezeWatchdog() { return true; }

	@ConfigItem(keyName = "discordWebhookUrl", name = "Discord webhook URL", description = "Opt-in: milestone notifications (log slots, uniques, pets, levels, goals)", section = integrationsSection, position = 5)
	default String discordWebhookUrl() { return ""; }
}
