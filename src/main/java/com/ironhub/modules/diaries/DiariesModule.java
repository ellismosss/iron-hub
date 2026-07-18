package com.ironhub.modules.diaries;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.DiariesPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarbitID;

/**
 * Achievement diary tracker (DESIGN.md §3.11): per-region task list from
 * the bundled diary pack (task completion decoded from the diary varplayer
 * bitfields, Karamja from its per-task varbits), requirement-graph doable
 * checks, and per-tier rewards. Tier claims still come from the documented
 * tier varbits.
 */
@Slf4j
@Singleton
public class DiariesModule implements IronHubModule
{
	static final DiaryRegion[] REGIONS = {
		new DiaryRegion("Ardougne",
			new int[]{Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE},
			new int[]{VarbitID.ARDOUGNE_EASY_COUNT, VarbitID.ARDOUGNE_MED_COUNT, VarbitID.ARDOUGNE_HARD_COUNT, VarbitID.ARDOUGNE_ELITE_COUNT}),
		new DiaryRegion("Desert",
			new int[]{Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE},
			new int[]{VarbitID.DESERT_EASY_COUNT, VarbitID.DESERT_MED_COUNT, VarbitID.DESERT_HARD_COUNT, VarbitID.DESERT_ELITE_COUNT}),
		new DiaryRegion("Falador",
			new int[]{Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE},
			new int[]{VarbitID.FALADOR_EASY_COUNT, VarbitID.FALADOR_MED_COUNT, VarbitID.FALADOR_HARD_COUNT, VarbitID.FALADOR_ELITE_COUNT}),
		new DiaryRegion("Fremennik",
			new int[]{Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE},
			new int[]{VarbitID.FREMENNIK_EASY_COUNT, VarbitID.FREMENNIK_MED_COUNT, VarbitID.FREMENNIK_HARD_COUNT, VarbitID.FREMENNIK_ELITE_COUNT}),
		new DiaryRegion("Kandarin",
			new int[]{Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE},
			new int[]{VarbitID.KANDARIN_EASY_COUNT, VarbitID.KANDARIN_MED_COUNT, VarbitID.KANDARIN_HARD_COUNT, VarbitID.KANDARIN_ELITE_COUNT}),
		new DiaryRegion("Karamja",
			new int[]{Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE},
			new int[]{VarbitID.KARAMJA_EASY_COUNT, VarbitID.KARAMJA_MED_COUNT, VarbitID.KARAMJA_HARD_COUNT, VarbitID.KARAMJA_ELITE_COUNT}),
		new DiaryRegion("Kourend & Kebos",
			new int[]{Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE},
			new int[]{VarbitID.KOUREND_EASY_COUNT, VarbitID.KOUREND_MED_COUNT, VarbitID.KOUREND_HARD_COUNT, VarbitID.KOUREND_ELITE_COUNT}),
		new DiaryRegion("Lumbridge & Draynor",
			new int[]{Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE},
			new int[]{VarbitID.LUMBRIDGE_EASY_COUNT, VarbitID.LUMBRIDGE_MED_COUNT, VarbitID.LUMBRIDGE_HARD_COUNT, VarbitID.LUMBRIDGE_ELITE_COUNT}),
		new DiaryRegion("Morytania",
			new int[]{Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE},
			new int[]{VarbitID.MORYTANIA_EASY_COUNT, VarbitID.MORYTANIA_MED_COUNT, VarbitID.MORYTANIA_HARD_COUNT, VarbitID.MORYTANIA_ELITE_COUNT}),
		new DiaryRegion("Varrock",
			new int[]{Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE},
			new int[]{VarbitID.VARROCK_EASY_COUNT, VarbitID.VARROCK_MED_COUNT, VarbitID.VARROCK_HARD_COUNT, VarbitID.VARROCK_ELITE_COUNT}),
		new DiaryRegion("Western Provinces",
			new int[]{Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE},
			new int[]{VarbitID.WESTERN_EASY_COUNT, VarbitID.WESTERN_MED_COUNT, VarbitID.WESTERN_HARD_COUNT, VarbitID.WESTERN_ELITE_COUNT}),
		new DiaryRegion("Wilderness",
			new int[]{Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE},
			new int[]{VarbitID.WILDERNESS_EASY_COUNT, VarbitID.WILDERNESS_MED_COUNT, VarbitID.WILDERNESS_HARD_COUNT, VarbitID.WILDERNESS_ELITE_COUNT}),
	};

	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private DiariesPack pack;
	private DiariesTab tab;
	/** Parsed requirement per pack string — parse once, shared across tasks. */
	private final Map<String, Requirement> parsedReqs = new ConcurrentHashMap<>();

	@Inject
	public DiariesModule(AccountState state, IronHubConfig config, DataPack dataPack)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
	}

	@Override
	public String name()
	{
		return "Achievement diaries";
	}

	@Override
	public boolean enabled()
	{
		return config.diaries();
	}

	@Override
	public void startUp()
	{
		pack = dataPack.load("diaries", DiariesPack.class);
		for (DiaryRegion region : REGIONS)
		{
			state.watchVarbits(region.tierVarbits);
			state.watchVarbits(region.countVarbits);
		}
		for (DiariesPack.Region region : pack.regions)
		{
			for (DiariesPack.Tier tier : region.tiers)
			{
				for (DiariesPack.Task task : tier.tasks)
				{
					if (task.varp != null)
					{
						state.watchVarps(task.varp);
					}
					else if (task.varbit != null)
					{
						state.watchVarbits(task.varbit);
					}
				}
			}
		}
		state.addListener(goalProofListener);
		markDiaryGoalProofs();
	}

	@Override
	public void shutDown()
	{
		state.removeListener(goalProofListener);
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
			tab = new DiariesTab(this, state, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: the next buildTab dresses it fresh. */
	@Override
	public void onThemeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}

	DiariesPack pack()
	{
		return pack;
	}

	/** Region metadata (tier claim varbits) by pack region name. */
	static DiaryRegion regionMeta(String name)
	{
		return Arrays.stream(REGIONS).filter(r -> r.name.equals(name)).findFirst().orElse(null);
	}

	/**
	 * Whether the account has completed this diary task. Besides the task's
	 * own flag, a fully complete tier proves every task in it — some tasks
	 * have ironman-only alternate flags the client can't see (e.g. Desert
	 * Medium's Pollnivneach house portal), but the game's own per-tier
	 * count and claim varbits still cover them.
	 */
	boolean taskComplete(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		return taskFlagSet(task) || tierAllDone(region, tierIndex);
	}

	/** The task's own completion flag (varp bit, or varbit >= min). */
	private boolean taskFlagSet(DiariesPack.Task task)
	{
		if (task.varbit != null)
		{
			return state.getVarbit(task.varbit) >= (task.min != null ? task.min : 1);
		}
		return task.varp != null && task.bit != null
			&& ((state.getVarp(task.varp) >> task.bit) & 1) == 1;
	}

	/** Tier finished: claimed, or the game's own count says every task is done. */
	boolean tierAllDone(DiariesPack.Region region, int tierIndex)
	{
		DiaryRegion meta = regionMeta(region.name);
		if (meta == null)
		{
			return false;
		}
		return state.getVarbit(meta.tierVarbits[tierIndex]) >= 1
			|| state.getVarbit(meta.countVarbits[tierIndex]) >= region.tiers.get(tierIndex).tasks.size();
	}

	/**
	 * Incomplete but every parsed requirement is met at current levels —
	 * the "you could do this now" highlight. Display-only requirement
	 * lines (items to bring, partial quests) never gate.
	 */
	boolean taskDoable(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		if (taskComplete(region, tierIndex, task))
		{
			return false;
		}
		for (DiariesPack.Req req : task.reqs)
		{
			if (req.req != null && !parsed(req.req).isMet(state))
			{
				return false;
			}
		}
		return true;
	}

	/** Met state for one requirement line; null when display-only. */
	Boolean reqMet(DiariesPack.Req req)
	{
		return req.req == null ? null : parsed(req.req).isMet(state);
	}

	private Requirement parsed(String s)
	{
		return parsedReqs.computeIfAbsent(s, Requirements::parse);
	}

	/**
	 * Completed tasks in a tier — the game's own count varbit when it is
	 * ahead of the decoded bits (it includes ironman-alternate flags),
	 * capped at the tier size.
	 */
	int tierDone(DiariesPack.Region region, int tierIndex)
	{
		DiariesPack.Tier tier = region.tiers.get(tierIndex);
		int bits = (int) tier.tasks.stream()
			.filter(t -> taskComplete(region, tierIndex, t)).count();
		DiaryRegion meta = regionMeta(region.name);
		int counted = meta == null ? 0 : state.getVarbit(meta.countVarbits[tierIndex]);
		return Math.min(tier.tasks.size(), Math.max(bits, counted));
	}

	/** Completed tasks in a region. */
	int regionDone(DiariesPack.Region region)
	{
		int done = 0;
		for (int i = 0; i < region.tiers.size(); i++)
		{
			done += tierDone(region, i);
		}
		return done;
	}

	// ── goal-planner integration ──────────────────────────────────────

	private final Runnable goalProofListener = this::markDiaryGoalProofs;
	private volatile boolean markingProofs;

	/** Stable id for a task's completion flag ("<varp>_<bit>" / "vb<varbit>"). */
	static String slug(DiariesPack.Task task)
	{
		return task.varbit != null ? "vb" + task.varbit : task.varp + "_" + task.bit;
	}

	/**
	 * Prove goal-planner diary goals: mark the {@code diarytask_<slug>}
	 * unlock (the goal's achieved proof) for every goal-added task that now
	 * shows complete — one bulk persist. Runs on every state change; the
	 * re-entrant notify from setUnlockedBulk finds nothing new and stops.
	 */
	void markDiaryGoalProofs()
	{
		var goalSlugs = state.goalSeedIds("diary");
		if (goalSlugs.isEmpty() || markingProofs || pack == null)
		{
			return;
		}
		List<String> newlyDone = new java.util.ArrayList<>();
		for (DiariesPack.Region region : pack.regions)
		{
			for (int i = 0; i < region.tiers.size(); i++)
			{
				for (DiariesPack.Task task : region.tiers.get(i).tasks)
				{
					String key = "diarytask_" + slug(task);
					if (goalSlugs.contains(slug(task)) && !state.isUnlocked(key)
						&& taskComplete(region, i, task))
					{
						newlyDone.add(key);
					}
				}
			}
		}
		if (!newlyDone.isEmpty())
		{
			markingProofs = true;
			try
			{
				state.setUnlockedBulk(newlyDone);
			}
			finally
			{
				markingProofs = false;
			}
		}
	}

	static int regionTotal(DiariesPack.Region region)
	{
		return region.tiers.stream().mapToInt(t -> t.tasks.size()).sum();
	}

	/** Tiers complete for a region; a tier counts once its varbit is ≥ 1. */
	static int tiersComplete(AccountState state, DiaryRegion region)
	{
		return (int) Arrays.stream(region.tierVarbits).filter(v -> state.getVarbit(v) >= 1).count();
	}

	static int totalTiersComplete(AccountState state)
	{
		return Arrays.stream(REGIONS).mapToInt(r -> tiersComplete(state, r)).sum();
	}

	static class DiaryRegion
	{
		final String name;
		final int[] tierVarbits;  // claim state per tier: easy, medium, hard, elite
		final int[] countVarbits; // the game's own completed-task count per tier

		DiaryRegion(String name, int[] tierVarbits, int[] countVarbits)
		{
			this.name = name;
			this.tierVarbits = tierVarbits;
			this.countVarbits = countVarbits;
		}
	}
}
