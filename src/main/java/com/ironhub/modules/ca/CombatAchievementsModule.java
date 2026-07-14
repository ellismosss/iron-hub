package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarbitID;

/**
 * Combat achievements tracker (mockup frame 2b, tier card): points, tier
 * thresholds and per-tier completed counts all come from varbits. The
 * per-boss task grid and "easiest next" ranking arrive with the CA data
 * pack. See DESIGN.md §3.10.
 */
@Slf4j
@Singleton
public class CombatAchievementsModule implements IronHubModule
{
	static final Tier[] TIERS = {
		new Tier("Easy", VarbitID.CA_THRESHOLD_EASY, Varbits.COMBAT_TASK_EASY, Varbits.COMBAT_ACHIEVEMENT_TIER_EASY),
		new Tier("Medium", VarbitID.CA_THRESHOLD_MEDIUM, Varbits.COMBAT_TASK_MEDIUM, Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM),
		new Tier("Hard", VarbitID.CA_THRESHOLD_HARD, Varbits.COMBAT_TASK_HARD, Varbits.COMBAT_ACHIEVEMENT_TIER_HARD),
		new Tier("Elite", VarbitID.CA_THRESHOLD_ELITE, Varbits.COMBAT_TASK_ELITE, Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE),
		new Tier("Master", VarbitID.CA_THRESHOLD_MASTER, Varbits.COMBAT_TASK_MASTER, Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER),
		new Tier("Grandmaster", VarbitID.CA_THRESHOLD_GRANDMASTER, Varbits.COMBAT_TASK_GRANDMASTER, Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER),
	};

	private final AccountState state;
	private final IronHubConfig config;
	private CombatAchievementsTab tab;

	@Inject
	public CombatAchievementsModule(AccountState state, IronHubConfig config)
	{
		this.state = state;
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Combat achievements";
	}

	@Override
	public boolean enabled()
	{
		return config.combatAchievements();
	}

	@Override
	public void startUp()
	{
		state.watchVarbits(VarbitID.CA_POINTS);
		for (Tier tier : TIERS)
		{
			state.watchVarbits(tier.thresholdVarbit, tier.completedCountVarbit, tier.statusVarbit);
		}
	}

	@Override
	public void shutDown()
	{
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new CombatAchievementsTab(state);
		}
		return tab;
	}

	/**
	 * The next tier still in progress: the first whose points threshold is
	 * above the current points, or null when all thresholds are passed.
	 * Thresholds come from the game (varbits), never hardcoded.
	 */
	static Tier nextTier(AccountState state)
	{
		int points = state.getVarbit(VarbitID.CA_POINTS);
		for (Tier tier : TIERS)
		{
			int threshold = state.getVarbit(tier.thresholdVarbit);
			if (threshold > 0 && points < threshold)
			{
				return tier;
			}
		}
		return null;
	}

	static class Tier
	{
		final String name;
		final int thresholdVarbit;      // points needed for this tier
		final int completedCountVarbit; // tasks of this tier completed
		final int statusVarbit;         // ≥1 = tier complete

		Tier(String name, int thresholdVarbit, int completedCountVarbit, int statusVarbit)
		{
			this.name = name;
			this.thresholdVarbit = thresholdVarbit;
			this.completedCountVarbit = completedCountVarbit;
			this.statusVarbit = statusVarbit;
		}
	}
}
