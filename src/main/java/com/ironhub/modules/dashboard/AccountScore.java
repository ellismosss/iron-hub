package com.ironhub.modules.dashboard;

import com.ironhub.data.QolPack;
import com.ironhub.modules.qol.QolModule;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.Status;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarbitID;

/**
 * Composite account score (DESIGN.md §3.20): equal-weight blend of the
 * component percentages that have data. Each component maps to the module
 * its dashboard label clicks through to.
 */
public final class AccountScore
{
	private AccountScore()
	{
	}

	/** Component label -> module name for click-through routing. */
	public static final Map<String, String> COMPONENT_MODULES = Map.of(
		"quests", "Quests",
		"diaries", "Achievement diaries",
		"CA", "Combat achievements",
		"QoL", "QoL checklist",
		"log", "Collection log");

	private static final int[] DIARY_VARBITS = {
		Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE,
		Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE,
		Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE,
		Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE,
		Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE,
		Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE,
		Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE,
		Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE,
		Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE,
		Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE,
		Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE,
		Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE,
	};

	/** Component percentages (0-100) in display order; -1 = no data yet. */
	public static Map<String, Integer> components(AccountState state, QolPack qolPack)
	{
		Map<String, Integer> components = new LinkedHashMap<>();

		long questsDone = java.util.Arrays.stream(Quest.values())
			.filter(q -> state.getQuestState(q) == QuestState.FINISHED).count();
		components.put("quests", pct(questsDone, Quest.values().length));

		long tiersDone = java.util.Arrays.stream(DIARY_VARBITS)
			.filter(v -> state.getVarbit(v) >= 1).count();
		components.put("diaries", pct(tiersDone, DIARY_VARBITS.length));

		int caPoints = state.getVarbit(VarbitID.CA_POINTS);
		int gmThreshold = state.getVarbit(VarbitID.CA_THRESHOLD_GRANDMASTER);
		components.put("CA", gmThreshold > 0 ? pct(Math.min(caPoints, gmThreshold), gmThreshold) : -1);

		long qolOwned = qolPack.getUnlocks().stream()
			.filter(u -> QolModule.status(state, u) == Status.OWNED).count();
		components.put("QoL", pct(qolOwned, qolPack.getUnlocks().size()));

		components.put("log", state.getCollectionLogTotal() > 0
			? pct(state.getCollectionLogSlots(), state.getCollectionLogTotal()) : -1);
		return components;
	}

	/** Equal-weight blend of the components that have data. */
	public static int composite(Map<String, Integer> components)
	{
		return (int) Math.round(components.values().stream()
			.filter(v -> v >= 0)
			.mapToInt(Integer::intValue)
			.average()
			.orElse(0));
	}

	private static int pct(long part, long whole)
	{
		return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
	}
}
