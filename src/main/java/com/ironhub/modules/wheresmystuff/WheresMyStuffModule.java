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
	private final com.ironhub.data.ClueStepsPack clues; // for STASH contents

	// STASH: an AccountState-driven sync (Iron Hub already detects fills), so
	// this mirrors the filled set into per-unit snapshots. Cached to avoid
	// redundant work on unrelated state changes.
	private final Runnable stashListener = this::onStateChanged;
	/** Re-derive seam: detection baselines are per-account. */
	private int seenGeneration = -1;

	private void onStateChanged()
	{
		int generation = state.profileGeneration();
		if (generation != seenGeneration)
		{
			// profile switch: drop the old account's detection baselines or
			// they leak into the new one's snapshots
			seenGeneration = generation;
			lastStashFilled = java.util.Collections.emptySet();
			pendingSlots.clear();
			lastSlotItems.clear();
			if (scrapers != null)
			{
				scrapers.profileChanged();
			}
		}
		syncStash();
	}
	private java.util.Set<Integer> lastStashFilled = java.util.Collections.emptySet();

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
		this.clues = dataPack == null ? null
			: dataPack.load("clue-steps", com.ironhub.data.ClueStepsPack.class);
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

	/** How the widget/chat/inventory-diff scrapers (isolated in
	 *  {@link WheresMyStuffScrapers}) hand a storage's contents back: names may
	 *  be null (resolved from the item id) or supplied (point-icon labels). */
	@FunctionalInterface
	public interface Sink
	{
		void commit(String storageId, Map<Integer, Integer> items,
			Map<Integer, String> names, long now);
	}

	private WheresMyStuffScrapers scrapers;

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
			if (client != null && pack != null)
			{
				scrapers = new WheresMyStuffScrapers(client, itemManager, state, pack, this::sink);
				eventBus.register(scrapers);
			}
		}
		seenGeneration = state.profileGeneration(); // fresh baselines already
		state.addListener(stashListener);
		syncStash();
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
			if (scrapers != null)
			{
				eventBus.unregister(scrapers);
				scrapers = null;
			}
		}
		state.removeListener(stashListener);
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	/** The scrapers' commit path: resolve the registry def, honour supplied
	 *  names, and apply the shared never-seen-empty-stays-silent rule. */
	private void sink(String storageId, Map<Integer, Integer> items,
		Map<Integer, String> names, long now)
	{
		StorageLocationsPack.Storage def = storageById(storageId);
		if (def != null)
		{
			commit(def, items, names, now);
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
			commit(def, readContainer(event.getItemContainer()), now);
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

	// ── chat-driven bespoke storages (raw patterns from the reference) ─

	private static final java.util.regex.Pattern EYATLALLI_LOGIN =
		java.util.regex.Pattern.compile("Eyatlalli is holding onto your ([^.]+)\\.");

	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (pack == null || client == null)
		{
			return;
		}
		net.runelite.api.ChatMessageType type = event.getType();
		if (type != net.runelite.api.ChatMessageType.GAMEMESSAGE
			&& type != net.runelite.api.ChatMessageType.SPAM)
		{
			return;
		}
		String raw = event.getMessage();
		String msg = net.runelite.client.util.Text.removeTags(raw);
		long now = System.currentTimeMillis();

		// Nulodion — a decayed cannon can be reclaimed (4 parts), a new one clears
		if (msg.startsWith("Your cannon has decayed"))
		{
			commitFixed("world:nulodion", cannonParts(1), now);
		}
		else if (msg.startsWith("The dwarf gives you a new cannon"))
		{
			commitFixed("world:nulodion", cannonParts(0), now);
		}
		// Eyatlalli — cold-storage weapon (worn slot 3 at the freeze)
		else if (raw.contains("Your weapon freezes over."))
		{
			net.runelite.api.ItemContainer worn = client.getItemContainer(InventoryID.WORN);
			Map<Integer, Integer> items = new HashMap<>();
			if (worn != null && worn.getItems().length > 3)
			{
				Item weapon = worn.getItems()[3];
				if (weapon.getId() > 0)
				{
					items.put(weapon.getId(), Math.max(1, weapon.getQuantity()));
				}
			}
			commitFixed("world:eyatlalli", items, now);
		}
		else if (msg.startsWith("Eyatlalli returns your lost weapon")
			|| msg.startsWith("Eyatlalli retrieves your weapon")
			|| msg.startsWith("You retrieve your weapon")
			|| msg.startsWith("As the icicle bursts, your weapon"))
		{
			commitFixed("world:eyatlalli", new HashMap<>(), now); // retrieved -> empty
		}
		else
		{
			java.util.regex.Matcher m = EYATLALLI_LOGIN.matcher(msg);
			if (m.find() && itemManager != null)
			{
				String name = m.group(1);
				itemManager.search(name).stream()
					.filter(p -> p.getName().equals(name)).findFirst()
					.ifPresent(p ->
					{
						Map<Integer, Integer> items = new HashMap<>();
						items.put(p.getId(), 1);
						commitFixed("world:eyatlalli", items, now);
					});
			}
		}
	}

	private Map<Integer, Integer> cannonParts(int qty)
	{
		Map<Integer, Integer> items = new HashMap<>();
		if (qty > 0)
		{
			for (int id : new int[]{net.runelite.api.gameval.ItemID.TWPART1,
				net.runelite.api.gameval.ItemID.TWPART2, net.runelite.api.gameval.ItemID.TWPART3,
				net.runelite.api.gameval.ItemID.TWPART4})
			{
				items.put(id, qty);
			}
		}
		return items;
	}

	private void commitFixed(String id, Map<Integer, Integer> items, long now)
	{
		StorageLocationsPack.Storage def = storageById(id);
		if (def != null)
		{
			commit(def, items, now);
		}
	}

	private StorageLocationsPack.Storage storageById(String id)
	{
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			if (s.id.equals(id))
			{
				return s;
			}
		}
		return null;
	}

	// ── STASH (derived from Iron Hub's own fill detection) ────────────

	/** Iron Hub already tracks which STASH units are filled; mirror that into
	 *  per-unit snapshots so "where is my clue item?" can point at a STASH.
	 *  A filled unit stores its emote clue's required items. Runs on any state
	 *  change but no-ops unless the filled set moved. */
	private void syncStash()
	{
		if (clues == null)
		{
			return;
		}
		java.util.Set<Integer> filled = state.getStashFilled();
		if (filled.equals(lastStashFilled))
		{
			return;
		}
		// swap the baseline BEFORE committing: each commit notifies state
		// listeners synchronously, re-entering this method — with the old
		// baseline still in place that re-walked and re-committed every
		// unit per unit (O(N^2) commits per stash change)
		java.util.Set<Integer> previous = lastStashFilled;
		lastStashFilled = filled;
		long now = System.currentTimeMillis();
		// units that turned OFF since last sync -> record an honest empty
		for (int objectId : previous)
		{
			if (!filled.contains(objectId) && state.getStorageContents().containsKey(stashId(objectId)))
			{
				commitStash(objectId, false, now);
			}
		}
		for (int objectId : filled)
		{
			commitStash(objectId, true, now);
		}
	}

	private static String stashId(int objectId)
	{
		return "stash:" + objectId;
	}

	private void commitStash(int objectId, boolean filled, long now)
	{
		com.ironhub.data.ClueStepsPack.Stash unit = clues.stashByObjectId(objectId);
		if (unit == null)
		{
			return;
		}
		Map<Integer, String> names = filled ? stashNames(objectId) : new HashMap<>();
		Map<Integer, Integer> items = new HashMap<>();
		names.keySet().forEach(id -> items.put(id, 1));
		if (items.isEmpty() && !state.getStorageContents().containsKey(stashId(objectId)))
		{
			return; // never-seen + empty stays silent
		}
		String label = unit.name + " (" + familyLabel("stash") + ")";
		state.putStorageContents(stashId(objectId), unit.name, "stash", label, items, names, now);
	}

	/** item id -> name for a STASH unit, parsed from its clue's item: reqs. */
	private Map<Integer, String> stashNames(int objectId)
	{
		Map<Integer, String> out = new HashMap<>();
		com.ironhub.data.ClueStepsPack.Stash unit = clues.stashByObjectId(objectId);
		if (unit == null || unit.clueId == null)
		{
			return out;
		}
		for (com.ironhub.data.ClueStepsPack.Clue clue : clues.clues)
		{
			if (!unit.clueId.equals(clue.id) || clue.reqs == null)
			{
				continue;
			}
			for (String req : clue.reqs)
			{
				// "item:<id>:<qty>:<Name>"
				String[] parts = req.split(":", 4);
				if (parts.length == 4 && "item".equals(parts[0]))
				{
					try
					{
						out.put(Integer.parseInt(parts[1]), parts[3]);
					}
					catch (NumberFormatException ignored)
					{
						// a non-item req (skill:/unlock:/...) — not stored
					}
				}
			}
			break;
		}
		return out;
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
			// empty-hanger object clears it
			Map<Integer, Integer> items = new HashMap<>();
			for (int id : stored)
			{
				items.put(id, 1);
			}
			commit(s, items, System.currentTimeMillis());
			return;
		}
	}

	// ── varbit-driven detection (varbit-static + varbit-index) ────────

	/** varbit/varp id -> the storages watching it. Built once from the
	 *  immutable pack — the old handler walked all ~90 storages' watch
	 *  lists on EVERY VarbitChanged. */
	private volatile Map<Integer, List<StorageLocationsPack.Storage>> varbitWatchers;
	private volatile Map<Integer, List<StorageLocationsPack.Storage>> varpWatchers;

	/** Slot-mode storages (rune pouch, quiver) move an amount varbit per
	 *  cast/shot; committing each change persisted + broadcast per cast — a
	 *  sustained replan/rebuild storm in combat (AccountState rides its
	 *  inventory throttle for the very same pouch varbits). Quantity-only
	 *  movement coalesces to that ~6s cadence; an item-set change (a rune
	 *  swapped in) flushes on the next tick. Client thread only. */
	private final java.util.Set<StorageLocationsPack.Storage> pendingSlots = new java.util.HashSet<>();
	private final Map<String, java.util.Set<Integer>> lastSlotItems = new HashMap<>();
	private int tick;
	private static final int SLOT_FLUSH_TICKS = 10;

	private void buildWatcherIndex()
	{
		Map<Integer, List<StorageLocationsPack.Storage>> byVarbit = new HashMap<>();
		Map<Integer, List<StorageLocationsPack.Storage>> byVarp = new HashMap<>();
		for (StorageLocationsPack.Storage s : pack.storages)
		{
			Map<Integer, List<StorageLocationsPack.Storage>> into = s.varp ? byVarp : byVarbit;
			for (int id : watchedIds(s))
			{
				into.computeIfAbsent(id, k -> new ArrayList<>()).add(s);
			}
		}
		varpWatchers = byVarp;
		varbitWatchers = byVarbit;
	}

	private static java.util.Set<Integer> watchedIds(StorageLocationsPack.Storage s)
	{
		java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
		if ("varbits".equals(s.mode) && s.varbitItems != null)
		{
			for (StorageLocationsPack.VarbitItem vi : s.varbitItems)
			{
				ids.add(vi.varbit);
			}
		}
		else if ("varbitindex".equals(s.mode))
		{
			ids.add(s.indexVarbit);
		}
		else if ("slots".equals(s.mode) && s.slots != null)
		{
			for (StorageLocationsPack.Slot slot : s.slots)
			{
				ids.add(slot.typeVarbit);
				ids.add(slot.countVarbit);
			}
		}
		else if ("compute".equals(s.mode) && s.computeItems != null)
		{
			for (StorageLocationsPack.Compute c : s.computeItems)
			{
				ids.add(c.variantVarbit);
				ids.add(c.indexVarbit);
				ids.add(c.typeVarbit);
				if (c.terms != null)
				{
					for (StorageLocationsPack.Term t : c.terms)
					{
						ids.add(t.varbit);
					}
				}
			}
		}
		ids.remove(0); // unset optional fields
		return ids;
	}

	@Subscribe
	public void onVarbitChanged(net.runelite.api.events.VarbitChanged event)
	{
		if (pack == null || client == null)
		{
			return;
		}
		if (varbitWatchers == null)
		{
			buildWatcherIndex();
		}
		List<StorageLocationsPack.Storage> candidates = new ArrayList<>();
		List<StorageLocationsPack.Storage> hits = varbitWatchers.get(event.getVarbitId());
		if (hits != null)
		{
			candidates.addAll(hits);
		}
		hits = varpWatchers.get(event.getVarpId());
		if (hits != null)
		{
			candidates.addAll(hits);
		}
		long now = System.currentTimeMillis();
		for (StorageLocationsPack.Storage s : candidates)
		{
			int changed = s.varp ? event.getVarpId() : event.getVarbitId();
			if ("varbits".equals(s.mode) && touchesVarbits(s, changed))
			{
				Map<Integer, Integer> items = new HashMap<>();
				Map<Integer, String> overrides = new HashMap<>();
				for (StorageLocationsPack.VarbitItem vi : s.varbitItems)
				{
					int mult = vi.multiplier == 0 ? 1 : vi.multiplier;
					int raw = s.varp ? client.getVarpValue(vi.varbit) : client.getVarbitValue(vi.varbit);
					int qty = raw * mult;
					if (qty > 0)
					{
						items.merge(vi.itemId, qty, Integer::sum);
						if (vi.name != null)
						{
							overrides.put(vi.itemId, vi.name);
						}
					}
				}
				commit(s, items, overrides, now); // commit resolves the rest
			}
			else if ("varbitindex".equals(s.mode) && s.indexVarbit == changed)
			{
				Map<Integer, Integer> items = new HashMap<>();
				int idx = client.getVarbitValue(s.indexVarbit);
				if (idx > 0 && idx < s.indexItems.size())
				{
					int itemId = s.indexItems.get(idx);
					if (itemId > 0)
					{
						items.put(itemId, 1);
					}
				}
				commit(s, items, now);
			}
			else if ("slots".equals(s.mode) && touchesSlots(s, event))
			{
				pendingSlots.add(s); // coalesced — drained on the tick
			}
			else if ("compute".equals(s.mode) && touchesCompute(s, changed))
			{
				commit(s, readCompute(s), now);
			}
		}
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		tick++;
		if (pendingSlots.isEmpty() || pack == null || client == null)
		{
			return;
		}
		boolean flushAll = tick % SLOT_FLUSH_TICKS == 0;
		long now = System.currentTimeMillis();
		// drain a snapshot: commit's notify re-enters this module (state
		// listener), and a profile-generation reset clears pendingSlots
		List<StorageLocationsPack.Storage> drain = new ArrayList<>(pendingSlots);
		pendingSlots.clear();
		for (StorageLocationsPack.Storage s : drain)
		{
			Map<Integer, Integer> items = readSlots(s);
			java.util.Set<Integer> known = lastSlotItems.get(s.id);
			if (flushAll || known == null || !known.equals(items.keySet()))
			{
				lastSlotItems.put(s.id, new java.util.HashSet<>(items.keySet()));
				commit(s, items, now);
			}
			else
			{
				pendingSlots.add(s); // still riding to the flush tick
			}
		}
	}

	private static boolean touchesCompute(StorageLocationsPack.Storage s, int varbit)
	{
		for (StorageLocationsPack.Compute c : s.computeItems)
		{
			if (c.variantVarbit == varbit || c.indexVarbit == varbit || c.typeVarbit == varbit)
			{
				return true;
			}
			if (c.terms != null)
			{
				for (StorageLocationsPack.Term t : c.terms)
				{
					if (t.varbit == varbit)
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Derived-formula storages (Tool Leprechaun, Elnock Inquisitor). */
	private Map<Integer, Integer> readCompute(StorageLocationsPack.Storage s)
	{
		Map<Integer, Integer> items = new HashMap<>();
		for (StorageLocationsPack.Compute c : s.computeItems)
		{
			int id;
			int qty;
			switch (c.kind == null ? "" : c.kind)
			{
				case "sum":
				case "variant":
					qty = sumTerms(c);
					id = "variant".equals(c.kind) && client.getVarbitValue(c.variantVarbit) == 1
						? c.variantItemId : c.itemId;
					break;
				case "index":
					int v = client.getVarbitValue(c.indexVarbit);
					id = v >= 0 && v < c.indexArray.size() ? c.indexArray.get(v) : -1;
					qty = id > 0 ? 1 : 0;
					break;
				case "type":
					int t = client.getVarbitValue(c.typeVarbit);
					id = t <= 1 ? c.emptyId : c.filledId;
					qty = t == 0 ? 0 : 1;
					break;
				default:
					id = -1;
					qty = 0;
			}
			if (id > 0 && qty > 0)
			{
				items.merge(id, qty, Integer::sum);
			}
		}
		return items;
	}

	private int sumTerms(StorageLocationsPack.Compute c)
	{
		int sum = 0;
		for (StorageLocationsPack.Term t : c.terms)
		{
			sum += client.getVarbitValue(t.varbit) * Math.max(1, t.mult);
		}
		return sum;
	}

	private static boolean touchesSlots(StorageLocationsPack.Storage s, net.runelite.api.events.VarbitChanged e)
	{
		int id = s.varp ? e.getVarpId() : e.getVarbitId();
		for (StorageLocationsPack.Slot slot : s.slots)
		{
			if (slot.typeVarbit == id || slot.countVarbit == id)
			{
				return true;
			}
		}
		return false;
	}

	/** Read a slot-based storage (rune/bolt pouch, quiver): per slot, the type
	 *  value resolves to an item id, the count value to a quantity. */
	private Map<Integer, Integer> readSlots(StorageLocationsPack.Storage s)
	{
		Map<Integer, Integer> items = new HashMap<>();
		for (StorageLocationsPack.Slot slot : s.slots)
		{
			int type = s.varp ? client.getVarpValue(slot.typeVarbit)
				: client.getVarbitValue(slot.typeVarbit);
			if (type <= 0)
			{
				continue;
			}
			int itemId;
			if ("array".equals(s.typeKind))
			{
				itemId = type < s.typeArray.size() ? s.typeArray.get(type) : -1;
			}
			else if ("enum".equals(s.typeKind))
			{
				itemId = client.getEnum(s.typeEnum).getIntValue(type);
			}
			else
			{
				itemId = type; // the type value IS the item id (Dizana's quiver)
			}
			int qty = s.varp ? client.getVarpValue(slot.countVarbit)
				: client.getVarbitValue(slot.countVarbit);
			if (itemId > 0 && qty > 0)
			{
				items.merge(itemId, qty, Integer::sum);
			}
		}
		return items;
	}

	private static boolean touchesVarbits(StorageLocationsPack.Storage s, int varbit)
	{
		for (StorageLocationsPack.VarbitItem vi : s.varbitItems)
		{
			if (vi.varbit == varbit)
			{
				return true;
			}
		}
		return false;
	}

	/** Shared commit: never-seen + empty stays silent, otherwise write the
	 *  snapshot with client-thread-resolved names. */
	private void commit(StorageLocationsPack.Storage def, Map<Integer, Integer> items, long now)
	{
		commit(def, items, null, now);
	}

	/** As above, but with explicit item names (a caller with display-name
	 *  overrides, e.g. minigame points); ids the overrides miss still
	 *  resolve — a partial map used to leave the storage's other items
	 *  nameless. */
	private void commit(StorageLocationsPack.Storage def, Map<Integer, Integer> items,
		Map<Integer, String> names, long now)
	{
		if (items.isEmpty() && !state.getStorageContents().containsKey(def.id))
		{
			return;
		}
		Map<Integer, String> resolved = resolveNames(items.keySet());
		if (names != null)
		{
			resolved.putAll(names);
		}
		state.putStorageContents(def.id, def.name, def.family, label(def),
			items, resolved, now);
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
