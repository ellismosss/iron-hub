package com.ironhub.modules.suggest;

import com.ironhub.IronHubConfig;
import com.ironhub.data.BankedXpPack;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.HerbPatchesPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.bank.BankedXp;
import com.ironhub.modules.dailies.DailiesModule;
import com.ironhub.modules.farming.FarmingRunModule;
import com.ironhub.modules.goals.GoalPlannerModule;
import com.ironhub.modules.supplies.SuppliesRunwayModule;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.VarPlayer;

/**
 * "What Now?" suggestion engine (DESIGN.md §3.14): a ranked shortlist of
 * things worth doing right now, scored impact × urgency × fit against the
 * selected time budget. Deliberately explainable — every suggestion
 * carries its "why".
 */
@Slf4j
@Singleton
public class WhatNowModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private Packs packs;
	private WhatNowTab tab;

	/** The data packs the engine reads. */
	public static class Packs
	{
		final DailiesPack dailies;
		final HerbPatchesPack herbPatches;
		final BankedXpPack bankedXp;
		final GoalsPack goals;

		public Packs(DailiesPack dailies, HerbPatchesPack herbPatches,
			BankedXpPack bankedXp, GoalsPack goals)
		{
			this.dailies = dailies;
			this.herbPatches = herbPatches;
			this.bankedXp = bankedXp;
			this.goals = goals;
		}
	}

	@Inject
	public WhatNowModule(AccountState state, IronHubConfig config, DataPack dataPack)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
	}

	@Override
	public String name()
	{
		return "What now?";
	}

	@Override
	public boolean enabled()
	{
		return config.whatNow();
	}

	@Override
	public void startUp()
	{
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
			if (packs == null)
			{
				packs = new Packs(
					dataPack.load("dailies", DailiesPack.class),
					dataPack.load("herb-patches", HerbPatchesPack.class),
					dataPack.load("banked-xp", BankedXpPack.class),
					dataPack.load("goals", GoalsPack.class));
			}
			tab = new WhatNowTab(state, packs);
		}
		return tab;
	}

	// ── engine (pure; static for tests) ───────────────────────────────

	public static class Suggestion
	{
		public final String title;
		public final String why;
		public final int minutes;
		final double score;

		Suggestion(String title, String why, int minutes, double score)
		{
			this.title = title;
			this.why = why;
			this.minutes = minutes;
			this.score = score;
		}
	}

	/** Ranked suggestions for a time budget in minutes; top 5. */
	public static List<Suggestion> suggest(AccountState state, Packs packs, int budgetMinutes)
	{
		List<Suggestion> candidates = new ArrayList<>();

		int ready = FarmingRunModule.readyPatches(state, packs.herbPatches);
		if (ready > 0)
		{
			candidates.add(candidate("Herb run", ready + " patches ready", 6,
				3.0, 1.0 + ready / 10.0, budgetMinutes));
		}

		int dailies = DailiesModule.outstanding(state, packs.dailies);
		if (dailies > 0)
		{
			candidates.add(candidate("Do your dailies",
				dailies + " outstanding · resets 00:00 UTC", 15, 2.0, 1.0, budgetMinutes));
		}

		int taskLeft = state.getVarp(VarPlayer.SLAYER_TASK_SIZE);
		if (taskLeft > 0)
		{
			candidates.add(candidate("Finish your slayer task", taskLeft + " kills left",
				Math.min(120, Math.max(15, taskLeft / 2)), 2.0, 0.8, budgetMinutes));
		}

		SuppliesRunwayModule.compute(state).values().stream()
			.filter(r -> r.hoursLeft() < 6)
			.findFirst()
			.ifPresent(r -> candidates.add(candidate(
				"Restock " + state.itemName(r.itemId).toLowerCase(java.util.Locale.ROOT),
				SuppliesRunwayModule.formatHours(r.hoursLeft()) + " of stock left", 30,
				2.5, Math.min(3.0, 6.0 / Math.max(0.5, r.hoursLeft())), budgetMinutes)));

		packs.goals.getGoals().stream()
			.filter(g -> g.getId().equals(state.getActiveGoal()))
			.findFirst()
			.map(g -> Map.entry(g, GoalPlannerModule.nextStep(g, state)))
			.filter(e -> e.getValue() != null)
			.ifPresent(e -> candidates.add(candidate(e.getValue().label,
				"next step of your " + e.getKey().getName() + " goal", 60,
				3.0, 0.9, budgetMinutes)));

		BankedXp.compute(state, packs.bankedXp).entrySet().stream()
			.filter(e -> e.getValue().xp >= 50_000)
			.findFirst()
			.ifPresent(e -> candidates.add(candidate("Train " + e.getKey().getName(),
				formatXp(e.getValue().xp) + " XP already banked", 45, 1.5, 0.6, budgetMinutes)));

		candidates.sort(Comparator.comparingDouble((Suggestion s) -> -s.score));
		return candidates.subList(0, Math.min(5, candidates.size()));
	}

	private static Suggestion candidate(String title, String why, int minutes,
		double impact, double urgency, int budgetMinutes)
	{
		double fit = minutes <= budgetMinutes ? 1.0 : (budgetMinutes / (double) minutes) * 0.5;
		return new Suggestion(title, why, minutes, impact * urgency * fit);
	}

	private static String formatXp(double xp)
	{
		return net.runelite.client.util.QuantityFormatter
			.quantityToRSDecimalStack((int) Math.round(xp), true);
	}

}
