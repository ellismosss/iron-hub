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

	private final Map<String, QuestsPack.QuestEntry> questByName = new HashMap<>();
	private final Map<Integer, GearProgressionPack.Item> gearByCanonicalId = new HashMap<>();

	public EnginePacks(QuestsPack quests, MethodsPack methods, EffectsPack effects,
		GearProgressionPack gear)
	{
		this.quests = quests;
		this.methods = methods;
		this.effects = effects;
		this.gear = gear;
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

	private static String normalize(String name)
	{
		String n = name.trim().toLowerCase();
		return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
	}
}
