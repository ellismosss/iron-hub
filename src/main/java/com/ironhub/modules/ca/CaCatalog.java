package com.ironhub.modules.ca;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Reads the full Combat Achievement task catalog from the game cache.
 * MUST run on the client thread.
 *
 * <p>Per-tier enums (CaTier.taskListEnumId) list the tier's task struct
 * ids; each struct carries the task fields under the param ids below.
 * Completion state is a 640-bit field across the 20 documented
 * CA_TASK_COMPLETED varps, indexed by task id. The enum/param ids have no
 * gameval constants yet — they are adopted from the production Combat
 * Achievements Tracker hub plugin and cross-checked against the wiki's
 * task table (637 ids, 0–636, exactly fitting the 20×32-bit field);
 * {@code load} fails soft (logs + skips) if the cache layout shifts.
 */
@Slf4j
final class CaCatalog
{
	// struct params (game cache)
	static final int PARAM_ID = 1306;
	static final int PARAM_NAME = 1308;
	static final int PARAM_DESCRIPTION = 1309;
	static final int PARAM_TYPE = 1311;
	static final int PARAM_BOSS = 1312;
	/** Boss id → display name enum (game cache). */
	static final int BOSS_NAMES_ENUM = 3971;

	static final Map<Integer, String> TYPES = Map.of(
		1, "Stamina",
		2, "Perfection",
		3, "Kill Count",
		4, "Mechanical",
		5, "Restriction",
		6, "Speed");

	static final int[] COMPLETED_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1,
		VarPlayerID.CA_TASK_COMPLETED_2, VarPlayerID.CA_TASK_COMPLETED_3,
		VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7,
		VarPlayerID.CA_TASK_COMPLETED_8, VarPlayerID.CA_TASK_COMPLETED_9,
		VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13,
		VarPlayerID.CA_TASK_COMPLETED_14, VarPlayerID.CA_TASK_COMPLETED_15,
		VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19,
	};

	private CaCatalog()
	{
	}

	/**
	 * The bit for a task id inside the completed bitfield.
	 * {@code varpValues[i]} must hold the value of COMPLETED_VARPS[i].
	 */
	static boolean completedBit(int taskId, int[] varpValues)
	{
		if (taskId < 0 || taskId >= varpValues.length * 32)
		{
			return false;
		}
		return (varpValues[taskId / 32] & (1 << (taskId % 32))) != 0;
	}

	/** Load the catalog (client thread only). Empty on cache-layout drift. */
	static List<CaTask> load(Client client)
	{
		int[] varps = new int[COMPLETED_VARPS.length];
		for (int i = 0; i < varps.length; i++)
		{
			varps[i] = client.getVarpValue(COMPLETED_VARPS[i]);
		}

		EnumComposition bossNames = client.getEnum(BOSS_NAMES_ENUM);
		List<CaTask> tasks = new ArrayList<>();
		for (CaTier tier : CaTier.values())
		{
			EnumComposition taskList = client.getEnum(tier.taskListEnumId);
			if (taskList == null)
			{
				log.warn("CA task-list enum missing for {} ({})", tier.display, tier.taskListEnumId);
				continue;
			}
			for (int structId : taskList.getIntVals())
			{
				StructComposition struct = client.getStructComposition(structId);
				if (struct == null)
				{
					continue;
				}
				int id = struct.getIntValue(PARAM_ID);
				String name = struct.getStringValue(PARAM_NAME);
				String description = struct.getStringValue(PARAM_DESCRIPTION);
				String type = TYPES.getOrDefault(struct.getIntValue(PARAM_TYPE), "");
				String boss = bossName(bossNames, struct.getIntValue(PARAM_BOSS));
				tasks.add(new CaTask(id, name == null ? "" : name,
					description == null ? "" : description, tier, type, boss,
					completedBit(id, varps)));
			}
		}
		// The catalog has ~637 tasks; far fewer means the cache layout moved.
		if (tasks.size() < 400)
		{
			log.warn("CA catalog loaded only {} tasks - cache layout may have changed", tasks.size());
		}
		return tasks;
	}

	private static String bossName(EnumComposition bossNames, int bossId)
	{
		if (bossNames == null)
		{
			return "";
		}
		String name = bossNames.getStringValue(bossId);
		return name == null || name.equals("null") || name.equalsIgnoreCase("Unknown") ? "" : name;
	}
}
