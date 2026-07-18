package com.ironhub.engine;

import com.ironhub.data.EffectsPack;
import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.MethodsPack;
import com.ironhub.data.QuestsPack;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.game.ItemVariationMapping;

/** The data packs the engine plans over, with derived lookup indexes. */
public class EnginePacks
{
	public final QuestsPack quests;
	public final MethodsPack methods;
	public final EffectsPack effects;
	public final GearProgressionPack gear;
	public final com.ironhub.data.BoostsPack boosts;
	public final com.ironhub.data.DiariesPack diaries;
	public final com.ironhub.data.ClogPack clog;
	/** Drop/kill rates for KILL and clog OBTAIN costing (G3); null when no clog pack. */
	public final RateSource rates;

	private final Map<String, QuestsPack.QuestEntry> questByName = new HashMap<>();
	private final Map<Integer, GearProgressionPack.Item> gearByCanonicalId = new HashMap<>();
	private final Map<String, String> gearNameByMarkKey = new HashMap<>();
	private final Map<String, GearProgressionPack.Item> gearByMarkKey = new HashMap<>();

	public EnginePacks(QuestsPack quests, MethodsPack methods, EffectsPack effects,
		GearProgressionPack gear)
	{
		this(quests, methods, effects, gear, null, null);
	}

	public EnginePacks(QuestsPack quests, MethodsPack methods, EffectsPack effects,
		GearProgressionPack gear, com.ironhub.data.BoostsPack boosts)
	{
		this(quests, methods, effects, gear, boosts, null);
	}

	public EnginePacks(QuestsPack quests, MethodsPack methods, EffectsPack effects,
		GearProgressionPack gear, com.ironhub.data.BoostsPack boosts,
		com.ironhub.data.DiariesPack diaries)
	{
		this(quests, methods, effects, gear, boosts, diaries, null);
	}

	public EnginePacks(QuestsPack quests, MethodsPack methods, EffectsPack effects,
		GearProgressionPack gear, com.ironhub.data.BoostsPack boosts,
		com.ironhub.data.DiariesPack diaries, com.ironhub.data.ClogPack clog)
	{
		this.quests = quests;
		this.methods = methods;
		this.effects = effects;
		this.gear = gear;
		this.boosts = boosts;
		this.diaries = diaries;
		this.clog = clog;
		this.rates = new RateSource(clog);
		if (quests != null)
		{
			quests.quests.forEach(q -> questByName.put(normalize(q.name), q));
		}
		if (gear != null)
		{
			gear.getPhases().forEach(phase -> phase.getGroups().forEach(group ->
				group.getItems().forEach(item ->
				{
					if (item.getItemId() != null)
					{
						gearByCanonicalId.putIfAbsent(
							ItemVariationMapping.map(item.getItemId()), item);
					}
					gearNameByMarkKey.putIfAbsent(item.markKey(), item.getName());
					gearByMarkKey.putIfAbsent(item.markKey(), item);
				})));
		}
	}

	public QuestsPack.QuestEntry quest(String name)
	{
		return questByName.get(normalize(name));
	}

	public GearProgressionPack.Item gearItem(int itemId)
	{
		return gearByCanonicalId.get(ItemVariationMapping.map(itemId));
	}

	/**
	 * Aggregated requirement strings for a whole diary tier: the max
	 * demanded level per skill plus every quest, from the diaries pack.
	 * This is what lets "Elite Lumbridge Diary" place correctly in the
	 * route instead of floating requirement-free.
	 */
	public java.util.List<String> diaryTierReqs(String region, String tier)
	{
		com.ironhub.data.DiariesPack.Tier found = diaryTier(region, tier);
		if (found == null)
		{
			return java.util.List.of();
		}
		java.util.Map<String, Integer> skills = new java.util.TreeMap<>();
		java.util.Map<String, Boolean> boostable = new HashMap<>();
		java.util.Set<String> quests = new java.util.LinkedHashSet<>();
		for (com.ironhub.data.DiariesPack.Task task : found.tasks)
		{
			for (com.ironhub.data.DiariesPack.Req req : task.reqs)
			{
				if (req.req == null)
				{
					continue;
				}
				collectDiaryLeaf(req.req, skills, boostable, quests);
			}
		}
		java.util.List<String> out = new java.util.ArrayList<>();
		skills.forEach((skill, level) -> out.add(
			(boostable.getOrDefault(skill, true) ? "skillb:" : "skill:") + skill + ":" + level));
		out.addAll(quests);
		return out;
	}

	private static void collectDiaryLeaf(String req, java.util.Map<String, Integer> skills,
		java.util.Map<String, Boolean> boostable, java.util.Set<String> quests)
	{
		if (req.startsWith("any:"))
		{
			// alternative paths: skip for tier aggregation (either works)
			return;
		}
		String[] parts = req.split(":");
		if ((parts[0].equals("skill") || parts[0].equals("skillb")) && parts.length >= 3)
		{
			int level = Integer.parseInt(parts[2]);
			if (level > skills.getOrDefault(parts[1], 0))
			{
				skills.put(parts[1], level);
				boostable.put(parts[1], parts[0].equals("skillb"));
			}
		}
		else if (parts[0].equals("quest"))
		{
			quests.add(req);
		}
	}

	/** A single diary task's requirement strings, looked up by flag slug. */
	public java.util.List<String> diaryTaskReqs(String slug)
	{
		if (diaries == null)
		{
			return java.util.List.of();
		}
		for (com.ironhub.data.DiariesPack.Region region : diaries.regions)
		{
			for (com.ironhub.data.DiariesPack.Tier tier : region.tiers)
			{
				for (com.ironhub.data.DiariesPack.Task task : tier.tasks)
				{
					String taskSlug = task.varbit != null
						? "vb" + task.varbit : task.varp + "_" + task.bit;
					if (taskSlug.equals(slug))
					{
						java.util.List<String> out = new java.util.ArrayList<>();
						for (com.ironhub.data.DiariesPack.Req req : task.reqs)
						{
							if (req.req != null)
							{
								out.add(req.req);
							}
						}
						return out;
					}
				}
			}
		}
		return java.util.List.of();
	}

	private com.ironhub.data.DiariesPack.Tier diaryTier(String region, String tier)
	{
		if (diaries == null)
		{
			return null;
		}
		for (com.ironhub.data.DiariesPack.Region r : diaries.regions)
		{
			if (r.name.equalsIgnoreCase(region.trim()))
			{
				for (com.ironhub.data.DiariesPack.Tier t : r.tiers)
				{
					if (t.tier.equalsIgnoreCase(tier.trim()))
					{
						return t;
					}
				}
			}
		}
		return null;
	}

	/** The gear entry behind a manual mark key, or null. */
	public GearProgressionPack.Item gearItemByMarkKey(String key)
	{
		return gearByMarkKey.get(key);
	}

	/** Human name for an unlock key, or null when unknown. */
	public String unlockDisplayName(String key)
	{
		String gear = gearNameByMarkKey.get(key);
		if (gear != null)
		{
			return "Obtain " + gear;
		}
		if (key.startsWith("diarytask_"))
		{
			return "Diary task (see Diaries tab)";
		}
		if (key.startsWith("catask_"))
		{
			return "Combat task (see Combat achievements tab)";
		}
		return null;
	}

	private static String normalize(String name)
	{
		String n = name.trim().toLowerCase();
		return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
	}
}
