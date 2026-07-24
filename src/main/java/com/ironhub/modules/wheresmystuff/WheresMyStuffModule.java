package com.ironhub.modules.wheresmystuff;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.StorageLocationsPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Where's my stuff (Bank hub): a storage-location tracker that remembers what
 * you keep in every place beyond bank / inventory / worn, so the Gear library
 * (and this tab) can say "You own this · Fancy dress box (PoH)" for an item
 * that lives only in a POH costume storage. Ported from the "Dude, Where's My
 * Stuff?" hub plugin (github.com/Thource/dude-wheres-my-stuff @ d032272,
 * BSD-2, (c) 2022 Thource — licenses/dude-wheres-my-stuff-LICENSE).
 *
 * <p>Detection is byte-faithful to the reference: it reads a storage's backing
 * container when the player opens it and persists the last-seen contents via
 * {@link AccountState#putStorageContents}. Nothing polls; an unseen storage is
 * silent, never "empty".</p>
 *
 * <p>This slice ships the PlayerOwnedHouse costume room — the whole room hangs
 * off one container ({@link InventoryID#POH_COSTUMES}) and its sub-storages are
 * told apart purely by the item-id allow-lists in the storage-locations pack.
 * Only your OWN house counts (the reference gates on the POH region); the same
 * "can't verify it's your house" caveat the reference documents applies.</p>
 */
@Slf4j
@Singleton
public class WheresMyStuffModule implements IronHubModule
{
	/** POH region ids, byte-faithful to the reference's Region.REGION_POH. */
	private static final Set<Integer> POH_REGIONS =
		Set.of(7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);

	private final AccountState state;
	private final IronHubConfig config;
	private final StorageLocationsPack pack;   // null if the pack is unavailable
	private final EventBus eventBus;           // null in unit tests
	private final Client client;               // null in unit tests
	private final ItemManager itemManager;     // null in unit tests

	private String familyLabel(String family)
	{
		String suffix = pack == null || pack.familyLabels == null
			? null : pack.familyLabels.get(family);
		return suffix == null ? "" : suffix;
	}

	private WheresMyStuffTab tab;

	@Inject
	public WheresMyStuffModule(AccountState state, IronHubConfig config, DataPack dataPack,
		EventBus eventBus, Client client, ItemManager itemManager)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null
			: dataPack.load("storage-locations", StorageLocationsPack.class);
		this.eventBus = eventBus;
		this.client = client;
		this.itemManager = itemManager;
	}

	@Override
	public String name()
	{
		return "Where's my stuff";
	}

	@Override
	public boolean enabled()
	{
		return config.wheresMyStuff();
	}

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
		}
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
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
			tab = new WheresMyStuffTab(state, this, config.osrsTheme(), itemManager);
		}
		return tab;
	}

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

	StorageLocationsPack pack()
	{
		return pack;
	}

	// ── POH costume-room detection ────────────────────────────────────

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (pack == null || client == null)
		{
			return;
		}
		int containerId = event.getContainerId();
		if (containerId > 0x8000)   // the reference's mask
		{
			containerId -= 0x8000;
		}
		long now = System.currentTimeMillis();

		if (containerId == InventoryID.POH_COSTUMES)
		{
			if (!inOwnHouse())
			{
				return;
			}
			Map<Integer, Integer> contents = readContainer(event.getItemContainer());
			commitPoh(contents, resolveNames(contents.keySet()), now);
			return;
		}

		// a plain container-backed storage (carryable sub-container, boat hold,
		// Death's office): the whole container IS the storage's contents
		StorageLocationsPack.Storage def = containerStorage(containerId);
		if (def != null)
		{
			Map<Integer, Integer> contents = readContainer(event.getItemContainer());
			if (contents.isEmpty() && !state.getStorageContents().containsKey(def.id))
			{
				return; // never-seen + empty stays silent
			}
			state.putStorageContents(def.id, def.name, def.family, label(def),
				contents, resolveNames(contents.keySet()), now);
		}
	}

	/** The container-mode storage backed by this container id, or null. */
	private StorageLocationsPack.Storage containerStorage(int containerId)
	{
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			if ("container".equals(s.mode) && s.containerId == containerId)
			{
				return s;
			}
		}
		return null;
	}

	// ── object-mount detection (POH cape hanger) ──────────────────────

	@Subscribe
	public void onGameObjectSpawned(net.runelite.api.events.GameObjectSpawned event)
	{
		if (pack == null || client == null || !inOwnHouse())
		{
			return;
		}
		int objectId = event.getGameObject().getId();
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			if (!"objectmount".equals(s.mode))
			{
				continue;
			}
			List<Integer> stored = mountItems(s, objectId);
			if (stored == null)
			{
				continue; // this object isn't a mount or clear for this storage
			}
			// a mount replaces the storage's contents (one cape at a time); the
			// empty-hanger object clears it — but a never-seen empty stays silent
			if (stored.isEmpty() && !state.getStorageContents().containsKey(s.id))
			{
				return;
			}
			Map<Integer, Integer> items = new HashMap<>();
			for (int id : stored)
			{
				items.put(id, 1);
			}
			state.putStorageContents(s.id, s.name, s.family, label(s),
				items, resolveNames(items.keySet()), System.currentTimeMillis());
			return;
		}
	}

	/** For an object-mount storage: the items a spawned object id means are
	 *  stored (empty list = a clear object), or null if the id is neither. */
	static List<Integer> mountItems(StorageLocationsPack.Storage s, int objectId)
	{
		if (s.mounts != null)
		{
			for (StorageLocationsPack.Mount m : s.mounts)
			{
				if (m.object == objectId)
				{
					return m.items;
				}
			}
		}
		if (s.clearObjects != null && s.clearObjects.contains(objectId))
		{
			return java.util.Collections.emptyList();
		}
		return null;
	}

	/** Item id -> display name, resolved on the client thread (this handler
	 *  runs on it), baked into the snapshot so the tab renders offline. */
	private Map<Integer, String> resolveNames(Set<Integer> ids)
	{
		Map<Integer, String> names = new HashMap<>();
		if (itemManager == null)
		{
			return names;
		}
		for (int id : ids)
		{
			names.put(id, itemManager.getItemComposition(id).getName());
		}
		return names;
	}

	private boolean inOwnHouse()
	{
		if (client.getLocalPlayer() == null)
		{
			return false;
		}
		WorldPoint wp = WorldPoint.fromLocalInstance(client,
			client.getLocalPlayer().getLocalLocation());
		return wp != null && POH_REGIONS.contains(wp.getRegionID());
	}

	/** Real items only (skip empty slots + placeholders), id -> summed qty. */
	private Map<Integer, Integer> readContainer(ItemContainer container)
	{
		Map<Integer, Integer> out = new HashMap<>();
		if (container == null)
		{
			return out;
		}
		for (Item item : container.getItems())
		{
			int id = item.getId();
			if (id <= 0)
			{
				continue;
			}
			if (itemManager != null
				&& itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1)
			{
				continue; // a placeholder, not a stored item
			}
			out.merge(id, item.getQuantity(), Integer::sum);
		}
		return out;
	}

	/**
	 * Attribute a POH_COSTUMES container read to its sub-storages and commit
	 * each. A storage is (re)written when it has matching items OR already has
	 * a tracked snapshot (so withdrawing everything reflects as empty, never
	 * stale) — but a never-seen empty storage stays silent.
	 */
	private void commitPoh(Map<Integer, Integer> contents, Map<Integer, String> names, long now)
	{
		Map<String, Map<Integer, Integer>> byStorage = attributePoh(pack, contents);
		Map<String, StorageLocationsPack.Storage> defs = new HashMap<>();
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			defs.put(s.id, s);
		}
		Set<String> tracked = state.getStorageContents().keySet();
		for (Map.Entry<String, Map<Integer, Integer>> e : byStorage.entrySet())
		{
			Map<Integer, Integer> items = e.getValue();
			if (items.isEmpty() && !tracked.contains(e.getKey()))
			{
				continue;
			}
			Map<Integer, String> subNames = new HashMap<>();
			for (int id : items.keySet())
			{
				String n = names.get(id);
				if (n != null)
				{
					subNames.put(id, n);
				}
			}
			StorageLocationsPack.Storage def = defs.get(e.getKey());
			state.putStorageContents(e.getKey(), def.name, def.family,
				label(def), items, subNames, now);
		}
	}

	String label(StorageLocationsPack.Storage def)
	{
		String suffix = familyLabel(def.family);
		return suffix.isEmpty() ? def.name : def.name + " (" + suffix + ")";
	}

	/**
	 * Pure attribution: split a POH_COSTUMES container read across the costume
	 * storages by their allow-lists; Uncategorised (the null-list catch-all)
	 * gets whatever no other allow-list claims. Returns a map only for the
	 * POH_COSTUMES storages (id -> qty each), byte-faithful to the reference.
	 */
	static Map<String, Map<Integer, Integer>> attributePoh(
		StorageLocationsPack pack, Map<Integer, Integer> contents)
	{
		List<StorageLocationsPack.Storage> costume = new ArrayList<>();
		Set<Integer> claimed = new HashSet<>();
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			if ("POH_COSTUMES".equals(s.container))
			{
				costume.add(s);
				if (s.items != null)
				{
					claimed.addAll(s.items);
				}
			}
		}
		Map<String, Map<Integer, Integer>> out = new HashMap<>();
		for (StorageLocationsPack.Storage s : costume)
		{
			Map<Integer, Integer> mine = new HashMap<>();
			for (Map.Entry<Integer, Integer> e : contents.entrySet())
			{
				boolean take = s.items == null
					? !claimed.contains(e.getKey())      // Uncategorised: the leftovers
					: s.items.contains(e.getKey());
				if (take)
				{
					mine.put(e.getKey(), e.getValue());
				}
			}
			out.put(s.id, mine);
		}
		return out;
	}
}
