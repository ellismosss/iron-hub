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
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * House (Progression hub, "Build" tile): the complete POH catalog — every
 * room, its hotspots and each hotspot's furniture ladder, shown as
 * room → hotspot → tier tiles.
 *
 * <p>Built detection: house furniture object ids from the pack, gated on
 * the game's own "Welcome to your house." message so a friend's house
 * never marks anything. Spawns buffer from the scene load and commit once
 * the welcome message confirms the house is the player's own (spawns and
 * the message race, so neither order is trusted alone).</p>
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
	private static final String OWN_HOUSE_MESSAGE = "Welcome to your house.";

	private final AccountState state;
	private final IronHubConfig config;
	private final PohPack pack;
	private final com.ironhub.data.BoostsPack boostsPack;
	private final com.ironhub.data.ItemSourcesPack itemSources;
	private final EventBus eventBus; // null in unit tests
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private PohTab tab;

	/** Furniture object ids spotted since the last scene load, grouped by the
	 *  house room they sit in, awaiting the own-house confirmation. */
	private final Map<Long, Set<Integer>> pendingByRoom = new HashMap<>();
	private boolean inOwnHouse;
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
		EventBus eventBus, net.runelite.client.game.ItemManager itemManager)
	{
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
		inOwnHouse = false;
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

	/** Every scene load resets the own-house confirmation and the buffer. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOADING only: the scene's furniture spawns DURING the load and
		// LOGGED_IN fires right AFTER it — clearing there wiped the whole
		// buffer moments before the welcome message could commit it (the
		// "detection never marks anything" bug, fixed 2026-07-23)
		if (event.getGameState() == GameState.LOADING
			|| event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			inOwnHouse = false;
			pendingByRoom.clear();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		if (OWN_HOUSE_MESSAGE.equals(Text.removeTags(event.getMessage())))
		{
			inOwnHouse = true;
			commitPending();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (pack == null)
		{
			return;
		}
		int objectId = event.getGameObject().getId();
		if (pack.placementsByObjectId(objectId).isEmpty())
		{
			return;
		}
		pendingByRoom.computeIfAbsent(roomKey(event.getGameObject()), k -> new HashSet<>())
			.add(objectId);
		if (inOwnHouse)
		{
			commitPending(); // building-mode swaps commit live
		}
	}

	/**
	 * Which house room an object sits in. A POH room is one 8x8-tile chunk, so
	 * objects sharing a chunk share a room — that is what tells a Parlour rug
	 * from a Bedroom rug when the game uses the identical object for both.
	 * Objects with no readable location share one bucket (attribution then
	 * only resolves the unambiguous ones).
	 */
	private static long roomKey(net.runelite.api.GameObject object)
	{
		net.runelite.api.coords.WorldPoint point = object.getWorldLocation();
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
		if (pendingByRoom.isEmpty())
		{
			return;
		}
		Set<String> built = new HashSet<>();
		for (Set<Integer> objectIds : pendingByRoom.values())
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
			// 2. mark what we can attribute
			for (Integer objectId : objectIds)
			{
				List<PohPack.Placement> places = pack.placementsByObjectId(objectId);
				for (PohPack.Placement place : places)
				{
					if (places.size() == 1 || place.space.room == null
						|| place.space.room.equals(room))
					{
						built.add(place.tier.id);
					}
				}
			}
		}
		built.removeIf(state::isPohBuilt);
		if (!built.isEmpty())
		{
			state.setPohBuiltBulk(new ArrayList<>(built));
		}
	}

	/** Manual mark toggle — the escape hatch for houses built before the
	 *  plugin (there is no readable POH layout outside the house). */
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

	/** The first unbuilt tier of a space, or null when the ladder is done. */
	PohPack.Tier nextTier(PohPack.Space space)
	{
		for (PohPack.Tier tier : space.tiers)
		{
			if (!state.isPohBuilt(tier.id))
			{
				return tier;
			}
		}
		return null;
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

	/** Test seam: buffered object ids awaiting the own-house confirmation. */
	List<Integer> pendingObjects()
	{
		List<Integer> out = new ArrayList<>();
		for (Set<Integer> ids : pendingByRoom.values())
		{
			out.addAll(ids);
		}
		return out;
	}
}
