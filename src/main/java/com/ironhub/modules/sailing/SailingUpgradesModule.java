package com.ironhub.modules.sailing;

import com.ironhub.IronHubConfig;
import com.ironhub.data.BoatUpgradesPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.GoalSeeds;
import com.ironhub.state.PersistedState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Sailing upgrades (Progression hub): every boat the player has been seen
 * captaining, with its available upgrades, materials owned/missing, the
 * next locked tier per part and the part's benefit — ported from the Boat
 * Upgrades hub plugin (IEarnSolo, BSD-2, pinned in gen_boat_upgrades.py).
 *
 * <p>Detection follows the reference: the {@code SAILING_BOARDED_BOAT} /
 * {@code SAILING_SIDEPANEL_SHIPYARD_MODE} varbits schedule a scan two
 * ticks later (the boat scene needs a beat to load); core part tiers come
 * from the sidepanel facility varbits, facility tiers from sweeping the
 * player-owned boat WorldEntity's scene for the pack's built-facility
 * object ids. Only the local player's own boat counts — the sidepanel
 * captain varcstr must match. Snapshots persist per boat type so the tab
 * renders offline ("as of last boarding" honesty); tiers only ever rise.
 * The ten LOST_SCHEMATIC_* varbits mirror into
 * {@code unlock:schematic_<slug>} flags the requirement graph gates on.</p>
 */
@Slf4j
@Singleton
public class SailingUpgradesModule implements IronHubModule
{
	static final String[] BOAT_NAMES = {"Raft", "Skiff", "Sloop"};

	private final AccountState state;
	private final IronHubConfig config;
	private final BoatUpgradesPack pack;
	private final EventBus eventBus;   // null in unit tests
	private final Client client;       // null in unit tests
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private SailingUpgradesTab tab;

	private int pendingScanTicks = -1;
	private Map<Integer, BoatUpgradesPack.Schematic> schematicByVarbit;
	private Map<String, BoatUpgradesPack.Upgrade> rowById;
	private final Runnable goalProofListener = this::markBoatGoalProofs;
	private volatile boolean markingProofs;

	@Inject
	public SailingUpgradesModule(AccountState state, IronHubConfig config,
		DataPack dataPack, EventBus eventBus, Client client,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("boat-upgrades", BoatUpgradesPack.class);
		this.eventBus = eventBus;
		this.client = client;
		this.itemManager = itemManager;
	}

	@Override
	public String name()
	{
		return "Sailing upgrades";
	}

	@Override
	public boolean enabled()
	{
		return config.boatUpgrades();
	}

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		state.addListener(goalProofListener);
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		state.removeListener(goalProofListener);
		pendingScanTicks = -1;
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
			tab = new SailingUpgradesTab(state, this, config.osrsTheme(), itemManager);
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

	BoatUpgradesPack pack()
	{
		return pack;
	}

	// ── detection ─────────────────────────────────────────────────────

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (pack == null)
		{
			return;
		}
		// learned schematics mirror into unlock flags (idempotent; the
		// requirement graph and goal steps gate on them)
		BoatUpgradesPack.Schematic schematic = schematicByVarbit().get(event.getVarbitId());
		if (schematic != null && event.getValue() == 1)
		{
			state.setUnlocked("schematic_" + schematic.slug, true);
			return;
		}
		int id = event.getVarbitId();
		if (id != VarbitID.SAILING_BOARDED_BOAT
			&& id != VarbitID.SAILING_SIDEPANEL_SHIPYARD_MODE)
		{
			return;
		}
		if (client == null)
		{
			return;
		}
		// reference semantics: a boarded-boat flip while in the shipyard is
		// the shipyard's own board/unboard churn — ignore it
		int shipyard = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_SHIPYARD_MODE);
		if (id == VarbitID.SAILING_BOARDED_BOAT && shipyard != 0)
		{
			return;
		}
		boolean active = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT) != 0
			|| shipyard != 0;
		pendingScanTicks = active ? 2 : -1; // scene needs a beat to load
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (pendingScanTicks < 0)
		{
			return;
		}
		if (--pendingScanTicks == 0)
		{
			pendingScanTicks = -1;
			scan();
		}
	}

	/** Shipyard swaps rebuild the boat objects — coalesce the spawn burst
	 *  into one scan next tick (the reference rescans per spawn). */
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (client != null && pendingScanTicks == -1
			&& client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_SHIPYARD_MODE) != 0)
		{
			pendingScanTicks = 1;
		}
	}

	private void scan()
	{
		if (client == null || pack == null)
		{
			return;
		}
		int boarded = client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT);
		int shipyard = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_SHIPYARD_MODE);
		if (boarded == 0 && shipyard == 0)
		{
			return;
		}
		// your own boat only: the sidepanel names the captain
		String captain = normalizeName(client.getVarcStrValue(
			VarClientID.SAILING_SIDEPANEL_CAPTAIN_NAME));
		Player local = client.getLocalPlayer();
		String localName = local == null ? null : local.getName();
		if (localName == null || !localName.equalsIgnoreCase(captain))
		{
			return;
		}
		int boatType = boarded != 0
			? client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT_TYPE)
			: client.getVarbitValue(VarbitID.SAILING_PREVIOUS_BOAT_TYPE_ID);
		if (boatType < 0 || boatType > 2)
		{
			return;
		}
		Map<String, Integer> tiers = new HashMap<>();
		int hull = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_FACILITY_HULL);
		tiers.put("Hull", hull);
		tiers.put("Base", hull); // the raft's hull slot is the Base part
		tiers.put("Sails", client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_FACILITY_SAIL));
		tiers.put("Helm", client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_FACILITY_HELM));
		tiers.put("Keel", client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_FACILITY_KEEL));
		scanFacilities(tiers);
		// only parts this boat type can carry (a raft has no Hull rows, a
		// skiff no Base rows — and no keel varbit claim on a raft)
		tiers.keySet().removeIf(part -> pack.rowsFor(part, boatType).isEmpty());
		state.putSailingBoat(boatType, tiers, System.currentTimeMillis());
	}

	/** Sweep the player-owned boat WorldEntity's scene for built-facility
	 *  objects (the reference's scanBoatWorldEntity, byte-faithful). */
	private void scanFacilities(Map<String, Integer> tiers)
	{
		WorldView top = client.getTopLevelWorldView();
		if (top == null || top.worldEntities() == null)
		{
			return;
		}
		for (WorldEntity entity : top.worldEntities())
		{
			if (entity.getOwnerType() != WorldEntity.OWNER_TYPE_SELF_PLAYER)
			{
				continue;
			}
			WorldView boatView = entity.getWorldView();
			Scene scene = boatView == null ? null : boatView.getScene();
			if (scene == null || scene.getTiles() == null)
			{
				continue;
			}
			for (Tile[][] plane : scene.getTiles())
			{
				if (plane == null)
				{
					continue;
				}
				for (Tile[] col : plane)
				{
					if (col == null)
					{
						continue;
					}
					for (Tile tile : col)
					{
						if (tile == null)
						{
							continue;
						}
						for (GameObject go : tile.getGameObjects())
						{
							if (go == null)
							{
								continue;
							}
							BoatUpgradesPack.FacilityHit hit =
								pack.facilityByObjectId(go.getId());
							if (hit != null)
							{
								tiers.merge(hit.part, hit.tier, Math::max);
							}
						}
					}
				}
			}
		}
	}

	private static String normalizeName(String name)
	{
		return name == null ? "" : name.replace('\u00A0', ' ').trim();
	}

	private Map<Integer, BoatUpgradesPack.Schematic> schematicByVarbit()
	{
		if (schematicByVarbit == null)
		{
			Map<Integer, BoatUpgradesPack.Schematic> m = new HashMap<>();
			if (pack != null)
			{
				for (BoatUpgradesPack.Schematic s : pack.detection.schematics)
				{
					m.put(s.varbit, s);
				}
			}
			schematicByVarbit = m;
		}
		return schematicByVarbit;
	}

	// ── snapshot queries (the tab's brain) ────────────────────────────

	/** Boat types seen captained, ascending (raft, skiff, sloop). */
	List<Integer> knownBoats()
	{
		Set<Integer> types = new TreeSet<>();
		for (String key : state.getSailingBoats().keySet())
		{
			types.add(Integer.parseInt(key));
		}
		return new ArrayList<>(types);
	}

	long boatLastSeen(int boatType)
	{
		PersistedState.BoatSnapshot snap =
			state.getSailingBoats().get(String.valueOf(boatType));
		return snap == null ? 0 : snap.lastSeen;
	}

	/** Highest tier seen for the part on this boat, or -1 (not seen). */
	int detectedTier(int boatType, String part)
	{
		PersistedState.BoatSnapshot snap =
			state.getSailingBoats().get(String.valueOf(boatType));
		Integer tier = snap == null ? null : snap.partTiers.get(part);
		return tier == null ? -1 : tier;
	}

	/** The parts this boat type can carry, in pack order. */
	List<BoatUpgradesPack.Part> partsFor(int boatType)
	{
		List<BoatUpgradesPack.Part> out = new ArrayList<>();
		for (BoatUpgradesPack.Part part : pack.parts)
		{
			if (boatType == 0 && part.raftExcluded)
			{
				continue;
			}
			if (!pack.rowsFor(part.key, boatType).isEmpty())
			{
				out.add(part);
			}
		}
		return out;
	}

	/** The next upgrade for the part on this boat (lowest tier above the
	 *  detected one), or null when the ladder is done. */
	BoatUpgradesPack.Upgrade nextRow(int boatType, String part)
	{
		int current = detectedTier(boatType, part);
		for (BoatUpgradesPack.Upgrade row : pack.rowsFor(part, boatType))
		{
			if (row.tier > current)
			{
				return row;
			}
		}
		return null;
	}

	/** The detected tier's own row (for "X built" copy), or null. */
	BoatUpgradesPack.Upgrade currentRow(int boatType, String part)
	{
		int current = detectedTier(boatType, part);
		BoatUpgradesPack.Upgrade best = null;
		for (BoatUpgradesPack.Upgrade row : pack.rowsFor(part, boatType))
		{
			if (row.tier <= current)
			{
				best = row;
			}
		}
		return best;
	}

	/** True when any known boat the row fits carries the part at or above
	 *  the row's tier. */
	boolean isBuilt(BoatUpgradesPack.Upgrade row)
	{
		for (int boatType : knownBoats())
		{
			if (row.boatType != -1 && row.boatType != boatType)
			{
				continue;
			}
			if (detectedTier(boatType, row.part) >= row.tier)
			{
				return true;
			}
		}
		return false;
	}

	static String boatLabel(int boatType)
	{
		return boatType >= 0 && boatType < BOAT_NAMES.length
			? BOAT_NAMES[boatType] : "any boat";
	}

	// ── goal planner integration ──────────────────────────────────────

	/** Toggle the {@code boat:<rowId>} goal; an already-built upgrade lands
	 *  its proof immediately. */
	void toggleGoal(BoatUpgradesPack.Upgrade row)
	{
		String goalId = "boat:" + row.id;
		if (state.getGoalSeeds().containsKey(goalId))
		{
			state.removeGoalSeed(goalId);
			return;
		}
		List<String> materialReqs = new ArrayList<>();
		for (BoatUpgradesPack.Material m : row.materials)
		{
			materialReqs.add("item:" + m.itemId + ":" + m.qty + ":" + m.name);
		}
		state.addGoalSeed(GoalSeeds.boatUpgrade(row.id, row.name,
			boatLabel(row.boatType), row.materials.get(0).itemId,
			row.reqs, materialReqs));
		if (isBuilt(row))
		{
			state.setUnlocked(GoalSeeds.boatProofKey(row.id), true);
		}
	}

	boolean isGoal(BoatUpgradesPack.Upgrade row)
	{
		return state.getGoalSeeds().containsKey("boat:" + row.id);
	}

	/** Prove goal-added upgrades the boat scans have since seen built —
	 *  the {@code boatbuilt_<id>} unlock bridges the snapshot (which the
	 *  requirement graph can't read) to the goal's achieved proof. */
	void markBoatGoalProofs()
	{
		Set<String> goalRows = state.goalSeedIds("boat");
		if (goalRows.isEmpty() || markingProofs || pack == null)
		{
			return;
		}
		List<String> newlyDone = new ArrayList<>();
		for (String rowId : goalRows)
		{
			BoatUpgradesPack.Upgrade row = rowById().get(rowId);
			String key = GoalSeeds.boatProofKey(rowId);
			if (row != null && isBuilt(row) && !state.isUnlocked(key))
			{
				newlyDone.add(key);
			}
		}
		if (!newlyDone.isEmpty())
		{
			markingProofs = true;
			try
			{
				state.setUnlockedBulk(newlyDone);
			}
			finally
			{
				markingProofs = false;
			}
		}
	}

	private Map<String, BoatUpgradesPack.Upgrade> rowById()
	{
		if (rowById == null)
		{
			Map<String, BoatUpgradesPack.Upgrade> m = new HashMap<>();
			if (pack != null)
			{
				for (BoatUpgradesPack.Upgrade row : pack.upgrades)
				{
					m.put(row.id, row);
				}
			}
			rowById = m;
		}
		return rowById;
	}

	/** Test seam: the pending-scan countdown. */
	int pendingScanTicks()
	{
		return pendingScanTicks;
	}
}
