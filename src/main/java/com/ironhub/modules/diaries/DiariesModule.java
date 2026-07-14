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
		new DiaryRegion("Ardougne", Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE),
		new DiaryRegion("Desert", Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE),
		new DiaryRegion("Falador", Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE),
		new DiaryRegion("Fremennik", Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE),
		new DiaryRegion("Kandarin", Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE),
		new DiaryRegion("Karamja", Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE),
		new DiaryRegion("Kourend & Kebos", Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE),
		new DiaryRegion("Lumbridge & Draynor", Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE),
		new DiaryRegion("Morytania", Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE),
		new DiaryRegion("Varrock", Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE),
		new DiaryRegion("Western Provinces", Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE),
		new DiaryRegion("Wilderness", Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE),
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
			tab = new DiariesTab(this, state);
		}
		return tab;
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

	/** Whether the account has completed this diary task. */
	boolean taskComplete(DiariesPack.Task task)
	{
		if (task.varbit != null)
		{
			return state.getVarbit(task.varbit) >= (task.min != null ? task.min : 1);
		}
		return task.varp != null && task.bit != null
			&& ((state.getVarp(task.varp) >> task.bit) & 1) == 1;
	}

	/**
	 * Incomplete but every parsed requirement is met at current levels —
	 * the "you could do this now" highlight. Display-only requirement
	 * lines (items to bring, partial quests) never gate.
	 */
	boolean taskDoable(DiariesPack.Task task)
	{
		if (taskComplete(task))
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

	/** Completed tasks in a tier. */
	int tierDone(DiariesPack.Tier tier)
	{
		return (int) tier.tasks.stream().filter(this::taskComplete).count();
	}

	/** Completed tasks in a region. */
	int regionDone(DiariesPack.Region region)
	{
		return region.tiers.stream().mapToInt(this::tierDone).sum();
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
		final int[] tierVarbits; // easy, medium, hard, elite

		DiaryRegion(String name, int easy, int medium, int hard, int elite)
		{
			this.name = name;
			this.tierVarbits = new int[]{easy, medium, hard, elite};
		}
	}
}
