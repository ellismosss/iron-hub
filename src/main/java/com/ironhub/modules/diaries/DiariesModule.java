package com.ironhub.modules.diaries;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Varbits;

/**
 * Achievement diary tracker: per-region, per-tier completion from the
 * documented diary varbits. Task-level tracking and requirement diffs
 * arrive with the diary data pack. See DESIGN.md §3.11.
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
	private DiariesTab tab;

	@Inject
	public DiariesModule(AccountState state, IronHubConfig config)
	{
		this.state = state;
		this.config = config;
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
		for (DiaryRegion region : REGIONS)
		{
			state.watchVarbits(region.tierVarbits);
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
			tab = new DiariesTab(state);
		}
		return tab;
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
