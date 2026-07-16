package com.ironhub.modules.farming;

import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import org.mockito.Mockito;

/**
 * An in-memory ConfigManager wearing the core Time Tracking plugin's data:
 * seed it with {@code patch(...)} / {@code birdHouse(...)} entries exactly
 * as the core plugin persists them ("&lt;value&gt;:&lt;unixtime&gt;" under
 * "timetracking.&lt;profile&gt;.&lt;region&gt;.&lt;varbit&gt;"), then hand
 * it to FarmTrackingService. Profile key is fixed to "test".
 */
public final class TimetrackingFixture
{
	public static final String PROFILE = "test";

	private TimetrackingFixture()
	{
	}

	public static ConfigManager configManager()
	{
		ConfigManager mock = Mockito.mock(ConfigManager.class);
		Map<String, String> store = new HashMap<>();

		Mockito.when(mock.getRSProfileKey()).thenReturn(PROFILE);
		Mockito.when(mock.getConfig(TimeTrackingConfig.class))
			.thenReturn(Mockito.mock(TimeTrackingConfig.class));
		// plain group.key reads (preferSoonest, plugin toggles)
		Mockito.when(mock.getConfiguration(Mockito.anyString(), Mockito.anyString()))
			.thenAnswer(inv -> store.get(inv.getArgument(0) + "." + inv.getArgument(1)));
		// profile-scoped reads — the shapes the vendored trackers use
		Mockito.when(mock.getConfiguration(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
			.thenAnswer(inv -> store.get(
				inv.getArgument(0) + "." + inv.getArgument(1) + "." + inv.getArgument(2)));
		Mockito.when(mock.getConfiguration(
				Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.eq(int.class)))
			.thenAnswer(inv ->
			{
				String raw = store.get(
					inv.getArgument(0) + "." + inv.getArgument(1) + "." + inv.getArgument(2));
				return raw == null ? null : Integer.parseInt(raw);
			});
		Mockito.when(mock.getRSProfileConfiguration(Mockito.anyString(), Mockito.anyString()))
			.thenAnswer(inv -> store.get(
				inv.getArgument(0) + "." + PROFILE + "." + inv.getArgument(1)));
		// seeding writes (tests only — the plugin itself never writes the group)
		Mockito.doAnswer(inv ->
		{
			store.put(inv.getArgument(0) + "." + inv.getArgument(1), inv.getArgument(2).toString());
			return null;
		}).when(mock).setConfiguration(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
		return mock;
	}

	/** Seed one patch exactly as the core plugin stores it. */
	public static void patch(ConfigManager configManager, int regionId, int varbitId,
		int varbitValue, long unixTime)
	{
		configManager.setConfiguration(TimeTrackingConfig.CONFIG_GROUP,
			PROFILE + "." + regionId + "." + varbitId, varbitValue + ":" + unixTime);
	}

	/** Seed one bird house space exactly as the core plugin stores it. */
	public static void birdHouse(ConfigManager configManager, int varp, int varpValue, long unixTime)
	{
		configManager.setConfiguration(TimeTrackingConfig.CONFIG_GROUP,
			PROFILE + "." + TimeTrackingConfig.BIRD_HOUSE + "." + varp,
			varpValue + ":" + unixTime);
	}

	/** Seed the farming contract exactly as the core plugin stores it — the
	 *  assigned Produce's item id under the "contract" RSProfile key. */
	public static void contract(ConfigManager configManager,
		com.ironhub.modules.farming.rl.Produce produce)
	{
		configManager.setConfiguration(TimeTrackingConfig.CONFIG_GROUP,
			PROFILE + ".contract", String.valueOf(produce.getItemID()));
	}

	public static FarmTrackingService service(ConfigManager configManager)
	{
		return new FarmTrackingService(null, null, configManager,
			Mockito.mock(TimeTrackingConfig.class), null);
	}

	/** Publish a ready-patch count for cross-module tests (WhatNow, dashboard). */
	public static void publishReadyPatches(int count)
	{
		FarmingRunModule.publishSharedReadyPatches(count);
	}
}
