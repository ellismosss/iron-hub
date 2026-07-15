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

	private final Map<String, QuestsPack.QuestEntry> questByName = new HashMap<>();
	private final Map<Integer, GearProgressionPack.Item> gearByCanonicalId = new HashMap<>();
	private final Map<String, String> gearNameByMarkKey = new HashMap<>();

	public EnginePacks(QuestsPack quests, MethodsPack methods, EffectsPack effects,
		GearProgressionPack gear)
	{
		this(quests, methods, effects, gear, null);
	}

	public EnginePacks(QuestsPack quests, MethodsPack methods, EffectsPack effects,
		GearProgressionPack gear, com.ironhub.data.BoostsPack boosts)
	{
		this.quests = quests;
		this.methods = methods;
		this.effects = effects;
		this.gear = gear;
		this.boosts = boosts;
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
