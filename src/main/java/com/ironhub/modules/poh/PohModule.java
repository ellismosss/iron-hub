package com.ironhub.modules.poh;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.PohPack;
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
import net.runelite.api.GameState;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * House (Progression hub, "Build" tile): the complete POH catalog — every
 * room, its hotspots and each hotspot's furniture ladder, shown as
 * room → hotspot → tier tiles.
 *
 * <p>Built detection sweeps the house scene and marks what it finds, gated
 * ONLY on building mode ({@code VarbitID.POH_BUILDING_MODE}): you can enter
 * it just in your OWN house, so it proves ownership, which nothing else
 * available does — not the client jar, not the wiki, not RuneLite's own
 * PohPlugin. It replaced a gate that compared chat against a
 * "Welcome to your house." message I had invented; that string does not exist
 * in OSRS, and an equality check that misses is invisible, so detection
 * silently marked nothing at all. A POH region check was tried alongside and
 * REMOVED: building mode already implies being in your house, so the region
 * list was an unverified assumption that could only ever block detection.</p>
 *
 * <p>Sweeping beats listening to spawns: it finds furniture that loaded
 * before the module started (you were already inside) and furniture the game
 * does not place as a GameObject at all — rugs are ground objects, mounted
 * heads and wall charts are wall/decorative ones. Spawn events of all four
 * kinds only queue a sweep, and while build mode is on one sweep per scene is
 * guaranteed so no event is needed to get started. The gate is checked BEFORE
 * the queue flag is consumed — consuming first discarded the request whenever
 * a tick landed outside build mode.</p>
 *
 * <p>While nothing is marked the tab reports what detection can see (build
 * mode, sweeps, tiles, furniture found, matched). Three rounds were lost
 * guessing which link was broken; a detector that cannot report itself is
 * indistinguishable from an empty grid.</p>
 *
 * <p>The game builds the IDENTICAL object for the same furniture in
 * different rooms — one brown rug serves the parlour, bedroom, chapel and
 * portal nexus hotspots — so an object id alone cannot say which hotspot
 * was built. Spawns are therefore buffered per house ROOM (a POH room is
 * one 8x8 chunk, so co-located objects share a room) and a room is
 * identified by the furniture in it that IS unambiguous; shared furniture
 * is then attributed to that room. A room with nothing unambiguous in it
 * marks nothing rather than guessing. A manual mark on every tier row is
 * the escape hatch for that, and for houses built before Iron Hub.</p>
 */
@Slf4j
@Singleton
public class PohModule implements IronHubModule
{
	/**
	 * {@code VarbitID.POH_BUILDING_MODE} — the ownership proof, per Luke's
	 * call: you can only enter building mode in your OWN house, so nothing is
	 * ever marked from a house you are merely visiting. Read as non-zero
	 * rather than {@code == 1}: the constant's NAME comes from the game's own
	 * symbols and is authoritative, but nothing available here confirms which
	 * truthy value it uses, and guessing a specific one is how the last gate
	 * failed.
	 */
	private static final int POH_BUILDING_MODE = 2176;

	private final AccountState state;
	private final IronHubConfig config;
	private final PohPack pack;
	private final com.ironhub.data.BoostsPack boostsPack;
	private final com.ironhub.data.ItemSourcesPack itemSources;
	private final EventBus eventBus; // null in unit tests
	private final net.runelite.api.Client client; // null in unit tests — the scene sweep is skipped
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private volatile PohTab tab; // written on the EDT, read by publishDiagnostics on the client thread

	/** Furniture found by the last sweep, grouped by the house room it sits
	 *  in. Client thread, except the tab's Reset (EDT) — synchronizedMap
	 *  keeps the clear atomic against a concurrent sweep. */
	private final Map<Long, Set<Integer>> pendingByRoom =
		java.util.Collections.synchronizedMap(new HashMap<>());
	/** A sweep is due; drained on the next tick so a scene load costs one.
	 *  Volatile with its siblings: the tab's Reset writes them off-thread. */
	private volatile boolean sweepQueued;
	/** Whether this scene has been swept to a useful result (reset on load). */
	private volatile boolean sweptThisScene;
	/** Consecutive sweeps that saw nothing, so an empty house stops re-reading
	 *  ~43k tiles every tick while a still-loading scene still gets retried. */
	private volatile int emptySweeps;
	private static final int MAX_EMPTY_SWEEPS = 5;
	/** Whether the last sweep saw the game's unbuilt-hotspot markers — the
	 *  scene's own corroboration that the house is being EDITED. */
	private boolean sawHotspot;

	// Diagnostics, written on the client thread and shown in the tab while
	// nothing is marked. Three rounds of "it still doesn't work" were spent
	// guessing which link was broken; this puts the answer on screen instead.
	private volatile boolean diagBuildingMode;
	private volatile int diagSweeps;
	private volatile int diagTiles;
	private volatile int diagObjects;
	private volatile int diagFurniture;
	private volatile int diagMarked;
	private volatile boolean diagSawHotspot;
	private volatile int diagVarbit;
	private final Runnable goalProofListener = this::onStateChange;
	/** Seeds are per-profile — re-derive them when the profile switches
	 *  (the profileGeneration seam every module-local cache obeys). */
	private int seedProfileGeneration = -1;

	private void onStateChange()
	{
		int generation = state.profileGeneration();
		if (generation != seedProfileGeneration)
		{
			seedProfileGeneration = generation;
			refreshPohSeeds();
		}
		markPohGoalProofs();
	}
	private volatile boolean markingProofs;

	@Inject
	public PohModule(AccountState state, IronHubConfig config, DataPack dataPack,
		EventBus eventBus, net.runelite.api.Client client,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.client = client;
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("poh", PohPack.class);
		this.boostsPack = dataPack == null ? null
			: dataPack.load("boosts", com.ironhub.data.BoostsPack.class);
		this.itemSources = dataPack == null ? null
			: dataPack.load("item-sources", com.ironhub.data.ItemSourcesPack.class);
		this.eventBus = eventBus;
		this.itemManager = itemManager;
	}

	@Override
	public String name()
	{
		return "House";
	}

	@Override
	public boolean enabled()
	{
		return config.pohProgression();
	}

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		refreshPohSeeds();
		implyLowerTiers();
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
		pendingByRoom.clear();
		sweepQueued = false;
		sweptThisScene = false;
		emptySweeps = 0;
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
			tab = new PohTab(state, this, config.osrsTheme(), itemManager);
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

	PohPack pack()
	{
		return pack;
	}

	com.ironhub.data.BoostsPack boostsPack()
	{
		return boostsPack;
	}

	com.ironhub.data.ItemSourcesPack itemSources()
	{
		return itemSources;
	}

	// ── detection ─────────────────────────────────────────────────────

	/** A scene load invalidates what we buffered; re-sweep once it settles. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING
			|| event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			pendingByRoom.clear();
			sweptThisScene = false;
			emptySweeps = 0;
		}
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			sweepQueued = true;   // covers walking in, and already being inside
		}
	}

	/**
	 * Any POH furniture appearing is the trigger to re-sweep. The four object
	 * kinds all matter: rugs arrive as ground objects, mounted heads and wall
	 * charts as decorative/wall ones, so a GameObject-only reader is blind to
	 * them. The sweep itself is what reads the house, so these only set a flag
	 * — a scene load fires hundreds of them.
	 */
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		queueIfFurniture(event.getGameObject().getId());
	}

	@Subscribe
	public void onGroundObjectSpawned(net.runelite.api.events.GroundObjectSpawned event)
	{
		queueIfFurniture(event.getGroundObject().getId());
	}

	@Subscribe
	public void onWallObjectSpawned(net.runelite.api.events.WallObjectSpawned event)
	{
		queueIfFurniture(event.getWallObject().getId());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(net.runelite.api.events.DecorativeObjectSpawned event)
	{
		queueIfFurniture(event.getDecorativeObject().getId());
	}

	/**
	 * Queue a sweep for any POH object — furniture OR one of the game's
	 * unbuilt-hotspot markers. The markers matter as much as the furniture:
	 * they appear the moment the player enters building mode, and since a
	 * scene is swept only once, without them nothing would prompt the re-sweep
	 * that turns "standing in the house" into "editing it".
	 */
	private void queueIfFurniture(int objectId)
	{
		if (pack == null)
		{
			return;
		}
		if (pack.isBuildModeMarker(objectId) || !pack.placementsByObjectId(objectId).isEmpty())
		{
			sweepQueued = true;
		}
	}

	/**
	 * Sweeps are coalesced — a scene load spawns hundreds of objects and each
	 * sweep reads the whole scene. The gate is checked BEFORE the queue flag is
	 * consumed: consuming it first threw the request away whenever a tick landed
	 * outside building mode, so a player already in build mode when the module
	 * started never swept at all. While build mode is on we also guarantee one
	 * sweep per scene, so no event is needed to get started.
	 */
	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		diagVarbit = client == null ? -1 : client.getVarbitValue(POH_BUILDING_MODE);
		boolean building = buildingMode();
		if (building != diagBuildingMode)
		{
			// Entering building mode is itself a reason to sweep. Whether
			// toggling it reloads the scene (which would reset sweptThisScene)
			// is not something this can verify, so it must not depend on it.
			sweptThisScene = !building && sweptThisScene;
			emptySweeps = 0;
			diagBuildingMode = building;
			publishDiagnostics();
		}
		if (!building)
		{
			return;
		}
		if (!sweepQueued && sweptThisScene)
		{
			return;
		}
		sweepQueued = false;
		scanHouse();
		diagSawHotspot = sawHotspot;
		commitPending();
		// Only stop re-sweeping once a sweep has actually SEEN something. The
		// first tick after a scene load can land before the scene is
		// populated, and latching on that leaves an empty house until some
		// spawn happens to queue another sweep. Bounded, because a sweep reads
		// the whole scene and a genuinely empty one must not do that forever.
		if (diagObjects > 0 || ++emptySweeps >= MAX_EMPTY_SWEEPS)
		{
			sweptThisScene = true;
		}
		publishDiagnostics();
	}

	/**
	 * Whether the player is in building mode, which can only be entered in
	 * your OWN house — so this is what keeps a house you are visiting from
	 * marking anything.
	 */
	boolean buildingMode()
	{
		return client != null && client.getVarbitValue(POH_BUILDING_MODE) > 0;
	}

	/**
	 * Sweep every tile of the house and buffer the POH furniture on it. Two
	 * things this catches that {@link #onGameObjectSpawned} cannot: furniture
	 * that loaded before the module was listening (you were already inside),
	 * and furniture the game does not place as a GameObject at all — rugs are
	 * ground objects, mounted heads and wall charts are wall/decorative ones.
	 */
	private void scanHouse()
	{
		if (client == null)
		{
			return;
		}
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		net.runelite.api.Scene scene = view == null ? null : view.getScene();
		if (scene == null || scene.getTiles() == null)
		{
			return;
		}
		diagSweeps++;
		diagTiles = 0;
		diagObjects = 0;
		diagFurniture = 0;
		sawHotspot = false;
		for (net.runelite.api.Tile[][] plane : scene.getTiles())
		{
			if (plane == null)
			{
				continue;
			}
			for (net.runelite.api.Tile[] col : plane)
			{
				if (col == null)
				{
					continue;
				}
				for (net.runelite.api.Tile tile : col)
				{
					if (tile != null)
					{
						diagTiles++;
						scanTile(tile);
					}
				}
			}
		}
	}

	private void scanTile(net.runelite.api.Tile tile)
	{
		net.runelite.api.coords.WorldPoint point = tile.getWorldLocation();
		if (tile.getGameObjects() != null)
		{
			for (net.runelite.api.GameObject object : tile.getGameObjects())
			{
				if (object != null)
				{
					buffer(object.getId(), point);
				}
			}
		}
		if (tile.getGroundObject() != null)          // rugs
		{
			buffer(tile.getGroundObject().getId(), point);
		}
		if (tile.getWallObject() != null)            // windows, some fireplaces
		{
			buffer(tile.getWallObject().getId(), point);
		}
		if (tile.getDecorativeObject() != null)      // mounted heads, wall charts
		{
			buffer(tile.getDecorativeObject().getId(), point);
		}
	}

	/** Buffer one furniture object against the house room it sits in. */
	private void buffer(int objectId, net.runelite.api.coords.WorldPoint point)
	{
		diagObjects++;
		if (pack.isBuildModeMarker(objectId))
		{
			sawHotspot = true;   // the scene's own proof the house is being edited
			return;
		}
		if (pack.placementsByObjectId(objectId).isEmpty())
		{
			return;
		}
		if (pendingByRoom.computeIfAbsent(roomKey(point), k -> new HashSet<>()).add(objectId))
		{
			diagFurniture++;
		}
	}

	/**
	 * Which house room an object sits in. A POH room is one 8x8-tile chunk, so
	 * objects sharing a chunk share a room — that is what tells a Parlour rug
	 * from a Bedroom rug when the game uses the identical object for both.
	 * Objects with no readable location share one bucket (attribution then
	 * only resolves the unambiguous ones).
	 */
	private static long roomKey(net.runelite.api.coords.WorldPoint point)
	{
		if (point == null)
		{
			return -1L;
		}
		return ((long) (point.getX() >> 3) & 0xFFFF) << 20
			| ((long) (point.getY() >> 3) & 0xFFFF) << 4
			| (point.getPlane() & 0xF);
	}

	/**
	 * Resolve the buffered spawns to tier ids and mark them built. Per room:
	 * furniture whose object id has ONE placement identifies the room, and
	 * anything ambiguous (the same rug in six rooms) is then attributed to
	 * that room. A room identified by nothing unambiguous leaves its shared
	 * furniture unmarked rather than guessing — the manual mark covers it.
	 */
	private void commitPending()
	{
		if (pendingByRoom.isEmpty() || !sawHotspot)
		{
			return;
		}
		Set<String> built = new HashSet<>();
		List<Set<Integer>> rooms; // snapshot: iteration vs the tab Reset's clear
		synchronized (pendingByRoom)
		{
			rooms = new ArrayList<>(pendingByRoom.values());
		}
		for (Set<Integer> objectIds : rooms)
		{
			// 1. the room this chunk is, voted for by unambiguous furniture
			Map<String, Integer> votes = new HashMap<>();
			for (Integer objectId : objectIds)
			{
				List<PohPack.Placement> places = pack.placementsByObjectId(objectId);
				if (places.size() == 1 && places.get(0).space.room != null)
				{
					votes.merge(places.get(0).space.room, 1, Integer::sum);
				}
			}
			String room = null;
			int best = 0;
			for (Map.Entry<String, Integer> vote : votes.entrySet())
			{
				// deterministic: highest count, ties broken by name
				if (vote.getValue() > best
					|| (vote.getValue() == best && room != null && vote.getKey().compareTo(room) < 0))
				{
					room = vote.getKey();
					best = vote.getValue();
				}
			}
			// 2. mark what we can attribute — a standing tier subsumes every
			// tier below it (Luke, 2026-07-29: a Gilded altar means the
			// cheaper altars are "built" too, progression-wise)
			for (Integer objectId : objectIds)
			{
				List<PohPack.Placement> places = pack.placementsByObjectId(objectId);
				for (PohPack.Placement place : places)
				{
					if (places.size() == 1 || place.space.room == null
						|| place.space.room.equals(room))
					{
						for (PohPack.Tier tier : place.space.tiers)
						{
							built.add(tier.id);
							if (tier == place.tier)
							{
								break;
							}
						}
					}
				}
			}
		}
		diagMarked = built.size();
		built.removeIf(state::isPohBuilt);
		if (!built.isEmpty())
		{
			state.setPohBuiltBulk(new ArrayList<>(built));
		}
	}

	/**
	 * Forget every built mark and re-sweep — the tab's Reset, so detection can
	 * be watched from a blank slate rather than reading marks a previous
	 * session already persisted.
	 */
	void resetDetection()
	{
		state.clearPohBuilt();
		pendingByRoom.clear();
		sweptThisScene = false;
		emptySweeps = 0;
		sweepQueued = true;
	}

	/** Programmatic built toggle — a test/fixture seam only since the UI's
	 *  manual mark-as-built click was removed (Luke, 2026-07-28). */
	void toggleBuilt(PohPack.Tier tier)
	{
		state.setPohBuilt(tier.id, !state.isPohBuilt(tier.id));
	}

	// ── goal planner integration ──────────────────────────────────────

	/** Add building this tier to Goals ({@code poh:<id>}); a tier
	 *  already built lands its proof immediately. */
	void toggleGoal(PohPack.Tier tier)
	{
		String goalId = "poh:" + tier.id;
		if (state.getGoalSeeds().containsKey(goalId))
		{
			state.removeGoalSeed(goalId);
			return;
		}
		java.util.List<String> materialReqs = new java.util.ArrayList<>();
		for (PohPack.Material m : tier.materials)
		{
			materialReqs.add("item:" + m.itemId + ":" + m.qty + ":" + m.name);
		}
		state.addGoalSeed(com.ironhub.state.GoalSeeds.poh(tier.id, tier.name,
			tier.icon == null ? 0 : tier.icon, tier.reqs, materialReqs));
		if (state.isPohBuilt(tier.id))
		{
			// already built: prove now
			state.setUnlocked(com.ironhub.state.GoalSeeds.pohProofKey(tier.id), true);
		}
	}

	boolean isGoal(PohPack.Tier tier)
	{
		return state.getGoalSeeds().containsKey("poh:" + tier.id);
	}

	/**
	 * Prove goal-planner PoH goals: mark the {@code pohtier_<id>} unlock (the
	 * goal's achieved proof) for every goal-added tier now built — built
	 * state lives in a dedicated set the requirement graph can't read, so the
	 * unlock flag is the bridge. Bulk persist; re-entrant notify from
	 * setUnlockedBulk finds nothing new and stops.
	 */
	/** Re-derive tracked PoH goals from today's pack — a goal added before
	 *  poh.json carried build materials kept its bare "Build X" step
	 *  forever (Luke, 2026-07-23). No-op when nothing changed. */
	void refreshPohSeeds()
	{
		if (pack == null)
		{
			return;
		}
		for (String tierId : state.goalSeedIds("poh"))
		{
			PohPack.Tier tier = tierById(tierId);
			if (tier == null)
			{
				continue; // a tier the pack no longer curates: leave it alone
			}
			java.util.List<String> materialReqs = new java.util.ArrayList<>();
			for (PohPack.Material m : tier.materials)
			{
				materialReqs.add("item:" + m.itemId + ":" + m.qty + ":" + m.name);
			}
			state.refreshGoalSeed(com.ironhub.state.GoalSeeds.poh(tier.id, tier.name,
				tier.icon == null ? 0 : tier.icon, tier.reqs, materialReqs));
		}
	}

	private PohPack.Tier tierById(String tierId)
	{
		for (PohPack.Space space : pack.spaces)
		{
			for (PohPack.Tier tier : space.tiers)
			{
				if (tier.id.equals(tierId))
				{
					return tier;
				}
			}
		}
		return null;
	}

	void markPohGoalProofs()
	{
		Set<String> goalTiers = state.goalSeedIds("poh");
		if (goalTiers.isEmpty() || markingProofs)
		{
			return;
		}
		List<String> newlyDone = new ArrayList<>();
		for (String tierId : goalTiers)
		{
			String key = com.ironhub.state.GoalSeeds.pohProofKey(tierId);
			if (state.isPohBuilt(tierId) && !state.isUnlocked(key))
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

	/**
	 * The next UPGRADE for a hotspot — the tier above the highest one built,
	 * or the first tier when nothing is built. Null once the top tier is up.
	 *
	 * <p>A hotspot holds ONE piece of furniture: a Gilded altar REPLACES the
	 * Oak altar, it does not stack on it, so the ladder is a list of
	 * alternatives and only the highest built one exists in the house. Asking
	 * for "the first unbuilt tier from the bottom" therefore answered "Oak
	 * altar" for a player with a Gilded altar, which made every hotspot read
	 * as incomplete forever — the tab showed 0/137 and no green ticks even
	 * when detection had marked everything correctly.</p>
	 */
	PohPack.Tier nextTier(PohPack.Space space)
	{
		int highest = -1;
		for (int i = 0; i < space.tiers.size(); i++)
		{
			if (state.isPohBuilt(space.tiers.get(i).id))
			{
				highest = i;
			}
		}
		return highest + 1 < space.tiers.size() ? space.tiers.get(highest + 1) : null;
	}

	/** Whether anything at all stands at this hotspot — what a player means
	 *  by "built", and what the tab counts. */
	boolean isBuilt(PohPack.Space space)
	{
		return builtTier(space) != null;
	}

	/** The hotspot's TOP tier stands — the tile's green tick (Luke,
	 *  2026-07-29: any-tier-built read as "done" on maxable hotspots). */
	boolean fullyBuilt(PohPack.Space space)
	{
		return !space.tiers.isEmpty()
			&& state.isPohBuilt(space.tiers.get(space.tiers.size() - 1).id);
	}

	/** Normalize persisted marks to the subsume rule: earlier sessions
	 *  marked only the standing tier, so a Gilded altar left the cheaper
	 *  altars unmarked (Luke, 2026-07-29). */
	private void implyLowerTiers()
	{
		if (pack == null)
		{
			return;
		}
		List<String> add = new ArrayList<>();
		for (PohPack.Space space : pack.spaces)
		{
			int highest = -1;
			for (int i = 0; i < space.tiers.size(); i++)
			{
				if (state.isPohBuilt(space.tiers.get(i).id))
				{
					highest = i;
				}
			}
			for (int i = 0; i < highest; i++)
			{
				if (!state.isPohBuilt(space.tiers.get(i).id))
				{
					add.add(space.tiers.get(i).id);
				}
			}
		}
		if (!add.isEmpty())
		{
			state.setPohBuiltBulk(add);
		}
	}

	/** Highest built tier of a space, or null. */
	PohPack.Tier builtTier(PohPack.Space space)
	{
		PohPack.Tier best = null;
		for (PohPack.Tier tier : space.tiers)
		{
			if (state.isPohBuilt(tier.id))
			{
				best = tier;
			}
		}
		return best;
	}

	/** Redraw the tab when what detection can see changes — nothing else would
	 *  refresh it, since a failing detector never changes account state. */
	private void publishDiagnostics()
	{
		String line = diagnostics();
		if (line.equals(lastDiagnostics))
		{
			return;
		}
		lastDiagnostics = line;
		PohTab open = tab;
		if (open != null)
		{
			// the same coalesced, visibility-gated path state changes take —
			// a bare invokeLater(rebuild) rebuilt hidden tabs per diagnostic
			open.refresh();
		}
	}

	private volatile String lastDiagnostics = "";

	/** The last published diagnostics line — a tab fingerprint term. */
	String lastDiagnostics()
	{
		return lastDiagnostics;
	}

	/**
	 * A one-line account of what detection can actually see, shown in the tab
	 * while nothing is marked. Every value is written on the client thread.
	 */
	String diagnostics()
	{
		if (client == null)
		{
			return "No game client attached.";
		}
		return "Build mode " + (diagBuildingMode ? "on" : "off") + " (" + diagVarbit + ")"
			+ " · sweeps " + diagSweeps
			+ " · tiles " + diagTiles
			+ " · objects " + diagObjects
			+ " · editing " + (diagSawHotspot ? "yes" : "no")
			+ " · furniture found " + diagFurniture
			+ " · matched " + diagMarked;
	}

	/** Test seam: read one tile exactly as the sweep does. */
	void scanTileForTest(net.runelite.api.Tile tile)
	{
		scanTile(tile);
	}

	/** Test seam: attribute and commit what the sweep found. */
	void commitForTest()
	{
		commitPending();
	}

	/** Test seam: buffered object ids awaiting the own-house confirmation. */
	List<Integer> pendingObjects()
	{
		List<Integer> out = new ArrayList<>();
		synchronized (pendingByRoom)
		{
			for (Set<Integer> ids : pendingByRoom.values())
			{
				out.addAll(ids);
			}
		}
		return out;
	}
}
