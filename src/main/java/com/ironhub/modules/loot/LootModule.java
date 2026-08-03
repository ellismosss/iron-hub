package com.ironhub.modules.loot;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;

/**
 * Loot & supplies tracker (DESIGN.md §3.9, reworked 2026-08-03 L1-L6):
 * per-source loot as a Loot-Tracker-style tile grid, monster tile
 * selector by recency, all-time vs session scopes, drop economics, and
 * picked-up vs left-behind classification ({@link LootPickupTracker}).
 * The plugin-level NpcLootReceived handler owns the loot AGGREGATION;
 * this module owns the pickup classification and the tab.
 */
@Slf4j
@Singleton
public class LootModule implements IronHubModule
{
	/** How long an inventory gain counts as "just picked up" (ticks). */
	private static final int GAIN_WINDOW_TICKS = 2;
	/** How long after a loading screen despawns stay unknowable (ticks). */
	private static final int RELOAD_WINDOW_TICKS = 2;

	private final AccountState state;
	private final ItemManager itemManager;
	private final IronHubConfig config;
	private final Client client;
	private final EventBus eventBus;
	private final com.ironhub.data.DataPack dataPack;
	private LootTab tab;

	private final LootPickupTracker tracker = new LootPickupTracker();
	/** item id -> last tick the inventory GAINED some (telegrab window). */
	private final Map<Integer, Integer> gainedAtTick = new HashMap<>();
	private Map<Integer, Integer> lastInventory = new HashMap<>();
	private int tick;
	private int lastReloadTick = -RELOAD_WINDOW_TICKS;

	@Inject
	public LootModule(AccountState state, ItemManager itemManager, IronHubConfig config,
		Client client, EventBus eventBus, com.ironhub.data.DataPack dataPack)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.config = config;
		this.client = client;
		this.eventBus = eventBus;
		this.dataPack = dataPack;
	}

	@Override
	public String name()
	{
		return "Loot & supplies";
	}

	@Override
	public boolean enabled()
	{
		return config.lootSupplies();
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		tracker.clear();
		gainedAtTick.clear();
		lastInventory = new HashMap<>();
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tick++;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING
			|| event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			// the scene is going away: pending drops' fates are unknowable,
			// and despawns in this window classify UNKNOWN
			lastReloadTick = tick;
			tracker.clear();
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		String source = event.getNpc().getName();
		if (source == null || client == null)
		{
			return;
		}
		for (net.runelite.client.game.ItemStack stack : event.getItems())
		{
			WorldPoint where = WorldPoint.fromLocal(client, stack.getLocation());
			tracker.onLoot(source, stack.getId(), stack.getQuantity(), where);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		Map<Integer, Integer> now = new HashMap<>();
		for (net.runelite.api.Item item : event.getItemContainer().getItems())
		{
			if (item.getId() > 0)
			{
				now.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		for (Map.Entry<Integer, Integer> e : now.entrySet())
		{
			if (e.getValue() > lastInventory.getOrDefault(e.getKey(), 0))
			{
				gainedAtTick.put(e.getKey(), tick);
			}
		}
		lastInventory = now;
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		WorldPoint player = client == null || client.getLocalPlayer() == null
			? null : client.getLocalPlayer().getWorldLocation();
		boolean reloading = tick - lastReloadTick <= RELOAD_WINDOW_TICKS;
		Integer gained = gainedAtTick.get(event.getItem().getId());
		boolean gainedRecently = gained != null && tick - gained <= GAIN_WINDOW_TICKS;
		LootPickupTracker.Classified verdict = tracker.onDespawn(
			event.getItem().getId(), event.getTile().getWorldLocation(),
			player, reloading, gainedRecently);
		if (verdict != null && verdict.fate == LootPickupTracker.Fate.PICKED)
		{
			state.recordPickedLoot(verdict.source,
				Map.of(verdict.itemId, verdict.quantity));
		}
		// LEFT and UNKNOWN record nothing: the tab derives left-behind as
		// dropped minus confirmed-picked, and unknown stays unknown
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new LootTab(state, itemManager, dataPack, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: drop it, the next mount rebuilds. */
	@Override
	public void onThemeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}
}
