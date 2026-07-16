/*
 * Copyright (c) 2018 Abex
 * Copyright (c) 2022, David Reess
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 *  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.ironhub.modules.farming.rl;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Setter;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.SummaryState;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;

/**
 * Read-only patch prediction over the core Time Tracking plugin's persisted
 * data — a trimmed port of core's FarmingTracker via the Time Tracking
 * Reminder plugin's read-only variant (both BSD-2). The write path
 * (updateData, growth-tick offset learning, notifications) is deliberately
 * absent: the core plugin, enabled by default, is the single writer of the
 * "timetracking" config group; we only read and predict.
 *
 * Hand-maintained alongside the generated rl/ files.
 */
public class FarmingTracker
{
	private static final int FARMING_GUILD_REGION_ID = 4922;

	/** Patch categories that exist both inside and outside the farming
	 *  guild, and are therefore affected by the ignore flag. */
	private static final Set<Tab> FARMING_GUILD_AFFECTED_TABS = new HashSet<>(Arrays.asList(
		Tab.ALLOTMENT, Tab.FLOWER, Tab.HERB, Tab.TREE, Tab.FRUIT_TREE, Tab.BUSH, Tab.CACTUS));

	private final ConfigManager configManager;
	private final FarmingWorld farmingWorld;

	private final Map<Tab, SummaryState> summaries = new EnumMap<>(Tab.class);
	private final Map<Tab, Boolean> harvestable = new EnumMap<>(Tab.class);

	/**
	 * The time at which all patches of a particular type will be ready to be
	 * harvested, or {@code -1} if we have no data about any patch of the type.
	 */
	private final Map<Tab, Long> completionTimes = new EnumMap<>(Tab.class);

	private Map<Tab, Set<FarmingPatch>> customizedTabData;

	/** "Prefer soonest completion" semantics from the core plugin's config:
	 *  true = a category is harvestable when ANY patch is; false = ALL. */
	@Setter
	private boolean preferSoonest;
	@Setter
	private boolean ignoreFarmingGuild;

	public FarmingTracker(ConfigManager configManager, FarmingWorld farmingWorld)
	{
		this.configManager = configManager;
		this.farmingWorld = farmingWorld;
	}

	@Nullable
	public PatchPrediction predictPatch(FarmingPatch patch)
	{
		return predictPatch(patch, configManager.getRSProfileKey());
	}

	@Nullable
	public PatchPrediction predictPatch(FarmingPatch patch, String profile)
	{
		long unixNow = Instant.now().getEpochSecond();

		boolean autoweed = Integer.toString(Autoweed.ON.ordinal())
			.equals(configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.AUTOWEED));

		String key = patch.configKey();
		String storedValue = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, key);

		if (storedValue == null)
		{
			return null;
		}

		long unixTime = 0;
		int value = 0;
		{
			String[] parts = storedValue.split(":");
			if (parts.length == 2)
			{
				try
				{
					value = Integer.parseInt(parts[0]);
					unixTime = Long.parseLong(parts[1]);
				}
				catch (NumberFormatException e)
				{
					// ignored — treated as no data below
				}
			}
		}

		if (unixTime <= 0)
		{
			return null;
		}

		PatchState state = patch.getImplementation().forVarbitValue(value);

		if (state == null)
		{
			return null;
		}

		int stage = state.getStage();
		int stages = state.getStages();
		int tickrate = state.getTickRate();

		if (autoweed && state.getProduce() == Produce.WEEDS)
		{
			stage = 0;
			stages = 1;
			tickrate = 0;
		}

		long doneEstimate = 0;
		if (tickrate > 0)
		{
			long tickNow = getTickTime(tickrate, 0, unixNow, profile);
			long tickTime = getTickTime(tickrate, 0, unixTime, profile);
			int delta = (int) (tickNow - tickTime) / (tickrate * 60);

			doneEstimate = getTickTime(tickrate, stages - 1 - stage, tickTime, profile);

			stage += delta;
			if (stage >= stages)
			{
				stage = stages - 1;
			}
		}

		return new PatchPrediction(state.getProduce(), state.getCropState(), doneEstimate, stage, stages);
	}

	public long getTickTime(int tickRate, int ticks)
	{
		return getTickTime(tickRate, ticks, Instant.now().getEpochSecond(), configManager.getRSProfileKey());
	}

	public long getTickTime(int tickRate, int ticks, long requestedTime, String profile)
	{
		Integer offsetPrecisionMins = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.FARM_TICK_OFFSET_PRECISION, int.class);
		Integer offsetTimeMins = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profile, TimeTrackingConfig.FARM_TICK_OFFSET, int.class);

		// All offsets are negative but are stored as positive
		long calculatedOffsetTime = 0L;
		if (offsetPrecisionMins != null && offsetTimeMins != null && (offsetPrecisionMins >= tickRate || offsetPrecisionMins >= 40))
		{
			calculatedOffsetTime = (offsetTimeMins % tickRate) * 60;
		}

		// Calculate "now" as +offset seconds in the future so we calculate the correct ticks
		long unixNow = requestedTime + calculatedOffsetTime;

		// The time that the tick requested will happen
		long timeOfCurrentTick = (unixNow - (unixNow % (tickRate * 60)));
		long timeOfGoalTick = timeOfCurrentTick + (ticks * tickRate * 60);

		// Move ourselves back to real time
		return timeOfGoalTick - calculatedOffsetTime;
	}

	/** Reset and recompute every category summary from stored data. */
	public void loadCompletionTimes()
	{
		summaries.clear();
		harvestable.clear();
		completionTimes.clear();
		updateCompletionTime();
	}

	public SummaryState getSummary(Tab patchType)
	{
		SummaryState summary = summaries.get(patchType);
		return summary == null ? SummaryState.UNKNOWN : summary;
	}

	public boolean getHarvestable(Tab patchType)
	{
		Boolean isHarvestable = harvestable.get(patchType);
		return isHarvestable != null && isHarvestable;
	}

	/** Overall completion time for the patch type, unix seconds; 0 = done,
	 *  -1 = no data. */
	public long getCompletionTime(Tab patchType)
	{
		Long completionTime = completionTimes.get(patchType);
		return completionTime == null ? -1 : completionTime;
	}

	public void updateCompletionTime()
	{
		for (Map.Entry<Tab, Set<FarmingPatch>> tab : getTabData())
		{
			long extremumCompletionTime = preferSoonest ? Long.MAX_VALUE : 0;
			boolean allUnknown = true;
			boolean allEmpty = true;
			boolean anyHarvestable = false;
			boolean allHarvestable = true;

			for (FarmingPatch patch : tab.getValue())
			{
				if (shouldSkipFarmingGuildPatch(tab.getKey(), patch))
				{
					continue;
				}

				PatchPrediction prediction = predictPatch(patch);
				if (prediction == null || prediction.getProduce().getItemID() < 0)
				{
					continue; // unknown state
				}

				allUnknown = false;

				if (prediction.getProduce() != Produce.WEEDS && prediction.getProduce() != Produce.SCARECROW)
				{
					allEmpty = false;

					if (preferSoonest)
					{
						extremumCompletionTime = Math.min(extremumCompletionTime, prediction.getDoneEstimate());
					}
					else
					{
						extremumCompletionTime = Math.max(extremumCompletionTime, prediction.getDoneEstimate());
					}

					boolean isHarvestable = false;
					if (prediction.getCropState() == CropState.GROWING)
					{
						isHarvestable = prediction.getStage() == (prediction.getStages() - 1);
					}
					else if (prediction.getCropState() == CropState.HARVESTABLE || prediction.getCropState() == CropState.DEAD)
					{
						PatchImplementation impl = prediction.getProduce().getPatchImplementation();
						if (impl.equals(PatchImplementation.BUSH)
							|| impl.equals(PatchImplementation.HERB)
							|| impl.equals(PatchImplementation.ALLOTMENT)
							|| impl.equals(PatchImplementation.BELLADONNA)
							|| impl.equals(PatchImplementation.FLOWER)
							|| impl.equals(PatchImplementation.SEAWEED)
							|| impl.equals(PatchImplementation.HOPS)
							|| impl.equals(PatchImplementation.MUSHROOM)
							|| impl.equals(PatchImplementation.GRAPES)
							|| impl.equals(PatchImplementation.HESPORI)
							|| impl.equals(PatchImplementation.COMPOST))
						{
							isHarvestable = true;
						}
						else
						{
							isHarvestable = prediction.getStage() > 0;
						}
					}

					anyHarvestable |= isHarvestable;
					allHarvestable &= isHarvestable;
				}
			}
			allHarvestable &= !allEmpty;

			final SummaryState state;
			final long completionTime;

			if (allUnknown)
			{
				state = SummaryState.UNKNOWN;
				completionTime = -1L;
			}
			else if (allEmpty)
			{
				state = SummaryState.EMPTY;
				completionTime = -1L;
			}
			else if (extremumCompletionTime <= Instant.now().getEpochSecond())
			{
				state = SummaryState.COMPLETED;
				completionTime = 0;
			}
			else
			{
				state = SummaryState.IN_PROGRESS;
				completionTime = extremumCompletionTime;
			}
			summaries.put(tab.getKey(), state);
			harvestable.put(tab.getKey(), preferSoonest ? anyHarvestable : allHarvestable);
			completionTimes.put(tab.getKey(), completionTime);
		}
	}

	public Set<Map.Entry<Tab, Set<FarmingPatch>>> getTabData()
	{
		if (customizedTabData == null)
		{
			customizedTabData = buildCustomTabData();
		}
		return customizedTabData.entrySet();
	}

	/** FarmingWorld's Tab->patches map with the Anima patch regrouped onto
	 *  its own category (the reminder plugin's customization). */
	private Map<Tab, Set<FarmingPatch>> buildCustomTabData()
	{
		// EnumMap, not HashMap: enums hash by IDENTITY, so a HashMap keyed by Tab
		// iterates in a different order on every JVM run — which is exactly what
		// made the overview's category tiles rearrange between sessions. An
		// EnumMap always iterates in Tab order, like FarmingWorld's own TreeMap.
		Map<Tab, Set<FarmingPatch>> customTabData = new EnumMap<>(Tab.class);
		for (Map.Entry<Tab, Set<FarmingPatch>> entry : farmingWorld.getTabs().entrySet())
		{
			for (FarmingPatch patch : entry.getValue())
			{
				Tab tab = patch.getImplementation() == PatchImplementation.ANIMA
					? Tab.ANIMA : entry.getKey();
				customTabData.computeIfAbsent(tab, t -> new HashSet<>()).add(patch);
			}
		}
		return customTabData;
	}

	private boolean shouldSkipFarmingGuildPatch(Tab tab, FarmingPatch patch)
	{
		return ignoreFarmingGuild
			&& FARMING_GUILD_AFFECTED_TABS.contains(tab)
			&& patch.getRegion().getRegionID() == FARMING_GUILD_REGION_ID;
	}
}
