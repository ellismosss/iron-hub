package com.ironhub.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The packs' implicit unlock graph made explicit (2026-07-20 intelligence
 * arc): every pack carries requirement strings, and collectively they
 * encode hundreds of "quest X gates Y" edges — but until this index the
 * only cross-pack join was effects.json's 8 hand-curated travel effects,
 * and nobody could answer "what does finishing this quest actually open?"
 *
 * <p>Built lazily on a background thread from the bundled packs (DataPack
 * caches the parses); queries return empty until the build lands — the
 * consumers are hover tooltips, so by the time a human asks, it is ready.
 * The join is a string scan over the graph's leaf grammar, not a graph
 * evaluation: quest names never contain {@code |} or {@code &}, so
 * splitting composite paths on those and taking everything after the
 * first colon of a {@code quest:}/{@code queststarted:} piece is exact
 * (Recipe subquest names keep their inner colons).
 */
@Singleton
public class UnlockIndex
{
	/** One pack entry gated on a quest: where it lives + its display name. */
	public static final class Ref
	{
		public final String source; // "diary task", "gear", "money method"...
		public final String name;

		Ref(String source, String name)
		{
			this.source = source;
			this.name = name;
		}
	}

	private final DataPack dataPack;
	private volatile Map<String, List<Ref>> byQuest;
	private boolean building;

	@Inject
	public UnlockIndex(DataPack dataPack)
	{
		this.dataPack = dataPack;
	}

	/**
	 * Everything the packs gate on this quest (fully or as one path of an
	 * any:), grouped by source in insertion order. Empty until the lazy
	 * build completes and for unknown quests.
	 */
	public List<Ref> questUnlocks(String questName)
	{
		Map<String, List<Ref>> index = byQuest;
		if (index == null)
		{
			ensureBuilding();
			return List.of();
		}
		List<Ref> refs = index.get(normalize(questName));
		return refs == null ? List.of() : refs;
	}

	private synchronized void ensureBuilding()
	{
		if (building)
		{
			return;
		}
		building = true;
		Thread thread = new Thread(this::build, "iron-hub-unlock-index");
		thread.setDaemon(true);
		thread.start();
	}

	/** Package-private synchronous build — the test seam. */
	void build()
	{
		Map<String, List<Ref>> index = new HashMap<>();
		try
		{
			DiariesPack diaries = dataPack.load("diaries", DiariesPack.class);
			for (DiariesPack.Region region : diaries.regions)
			{
				for (DiariesPack.Tier tier : region.tiers)
				{
					for (DiariesPack.Task task : tier.tasks)
					{
						if (task.reqs == null)
						{
							continue;
						}
						for (DiariesPack.Req req : task.reqs)
						{
							add(index, req.req, "diary task", region.name + " " + task.task);
						}
					}
				}
			}
			GearProgressionPack gear =
				dataPack.load("gear-progression", GearProgressionPack.class);
			gear.getPhases().forEach(phase -> phase.getGroups().forEach(group ->
				group.getItems().forEach(item ->
					addAll(index, item.getRequirements(), "gear item", item.getName()))));
			ClueStepsPack clues = dataPack.load("clue-steps", ClueStepsPack.class);
			for (ClueStepsPack.Clue clue : clues.clues)
			{
				addAll(index, clue.reqs, "clue step", clue.tier + ": " + clue.text);
			}
			MoneyMakingPack money = dataPack.load("money-making", MoneyMakingPack.class);
			for (MoneyMakingPack.Method method : money.methods)
			{
				addAll(index, method.reqs, "money method", method.name);
			}
			BoatUpgradesPack boats = dataPack.load("boat-upgrades", BoatUpgradesPack.class);
			for (BoatUpgradesPack.Upgrade upgrade : boats.upgrades)
			{
				addAll(index, upgrade.reqs, "boat upgrade", upgrade.name);
			}
			PohPack poh = dataPack.load("poh", PohPack.class);
			for (PohPack.Space space : poh.spaces)
			{
				for (PohPack.Tier tier : space.tiers)
				{
					addAll(index, tier.reqs, "POH tier", tier.name);
				}
			}
			QolPack qol = dataPack.load("qol", QolPack.class);
			for (QolPack.Unlock entry : qol.getUnlocks())
			{
				addAll(index, entry.getRequirements(), "QoL unlock", entry.getName());
			}
			ClogPack clog = dataPack.load("clog", ClogPack.class);
			for (ClogPack.Activity activity : clog.activities)
			{
				addAll(index, activity.reqs, "collection log", activity.name);
			}
		}
		catch (RuntimeException e)
		{
			// a malformed pack must not take the tooltips down with it —
			// publish what was joined before the failure
		}
		byQuest = index;
	}

	private static void addAll(Map<String, List<Ref>> index, List<String> reqs,
		String source, String name)
	{
		if (reqs != null)
		{
			for (String req : reqs)
			{
				add(index, req, source, name);
			}
		}
	}

	/** Scan one requirement string for quest leaves (see class doc). */
	private static void add(Map<String, List<Ref>> index, String req,
		String source, String name)
	{
		if (req == null || !req.contains("quest"))
		{
			return;
		}
		String body = req.toLowerCase(Locale.ROOT).startsWith("any:")
			? req.substring("any:".length()) : req;
		for (String path : body.split("\\|"))
		{
			for (String leaf : path.split("&"))
			{
				String lower = leaf.trim().toLowerCase(Locale.ROOT);
				if (lower.startsWith("quest:") || lower.startsWith("queststarted:"))
				{
					String quest = leaf.trim().substring(leaf.trim().indexOf(':') + 1).trim();
					index.computeIfAbsent(normalize(quest), k -> new ArrayList<>())
						.add(new Ref(source, name));
				}
			}
		}
	}

	private static String normalize(String questName)
	{
		String lower = questName.trim().toLowerCase(Locale.ROOT);
		return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
	}
}
