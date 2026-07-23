package com.ironhub.modules.clues;

import com.ironhub.IronHubConfig;
import com.ironhub.data.ClueStepsPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Clues & STASH (DESIGN.md §3.15, rebuilt 2026-07-18): STASH units
 * built/filled per tier with ready-to-fill counts, and emote clue-step
 * doability against owned items via the requirement graph — with a `+`
 * that adds unlocking a step to Goals.
 *
 * <p>STASH detection ports the STASH Tracker plugin (BSD-2, Nearvaas,
 * github.com/Nearvaas/S.T.A.S.H-Toolkit): a built STASH's game object
 * only renders for the player who built it, so seeing it spawn proves
 * "built"; deposit/withdraw chat messages — attributed to the STASH the
 * player just clicked, else the nearest unit — flip "filled". A manual
 * toggle in the tab covers STASHes filled before the plugin existed.</p>
 */
@Slf4j
@Singleton
public class ClueStashModule implements IronHubModule
{
	/** Attribute a chat message to the clicked STASH for this long. */
	private static final long INTERACTION_WINDOW_MS = 5000L;
	/** Fallback: attribute by proximity within this many tiles. */
	private static final int PROXIMITY_TILES = 5;

	private final AccountState state;
	private final IronHubConfig config;
	private final ClueStepsPack pack;
	private final com.ironhub.data.ItemSourcesPack itemSources;
	private final EventBus eventBus; // null in unit tests
	private final Client client;     // null in unit tests

	private final Runnable listener = this::onStateChanged;
	private CluesTab tab;

	private ClueStepsPack.Stash pendingStash;
	private long pendingStashTime;

	@Inject
	public ClueStashModule(AccountState state, IronHubConfig config, DataPack dataPack,
		EventBus eventBus, Client client)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("clue-steps", ClueStepsPack.class);
		this.itemSources = dataPack == null ? null
			: dataPack.load("item-sources", com.ironhub.data.ItemSourcesPack.class);
		this.eventBus = eventBus;
		this.client = client;
	}

	@Override
	public String name()
	{
		return "Clues & STASH";
	}

	@Override
	public boolean enabled()
	{
		return config.clueStash();
	}

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		state.addListener(listener);
	}

	@Override
	public void shutDown()
	{
		state.removeListener(listener);
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		pendingStash = null;
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
			tab = new CluesTab(state, this, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip drops the tab; the next buildTab re-clothes it. */
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

	ClueStepsPack pack()
	{
		return pack;
	}

	// ── clue-step doability (the requirement graph) ───────────────────

	static Requirement requirement(ClueStepsPack.Clue clue)
	{
		return Requirements.allOf(clue.reqs.stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}

	static boolean doable(ClueStepsPack.Clue clue, AccountState state)
	{
		return requirement(clue).isMet(state);
	}

	/** "needs: <first missing item>" line, or null when doable. */
	static String blocking(ClueStepsPack.Clue clue, AccountState state)
	{
		List<Requirement> missing = requirement(clue).missing(state);
		return missing.isEmpty() ? null : missing.get(0).describe();
	}

	/** Where the blocking item comes from (the KB projection), or null. */
	String blockingSource(ClueStepsPack.Clue clue)
	{
		if (itemSources == null)
		{
			return null;
		}
		for (Requirement leaf : requirement(clue).missing(state))
		{
			Integer itemId = leaf.itemId();
			String line = itemId == null ? null
				: itemSources.sourceLine(itemId, state, state.getItemSourcePref(itemId));
			if (line != null)
			{
				return line;
			}
		}
		return null;
	}

	static long doableCount(List<ClueStepsPack.Clue> clues, AccountState state)
	{
		return clues.stream().filter(c -> doable(c, state)).count();
	}

	// ── goal planner integration ──────────────────────────────────────

	/** Add unlocking this clue step to Goals; a step already
	 *  doable lands achieved immediately. */
	void addGoal(ClueStepsPack.Clue clue)
	{
		state.addGoalSeed(com.ironhub.state.GoalSeeds.clue(clue.id, clue.text, clue.tier, clue.reqs));
		markProofs();
	}

	void removeGoal(ClueStepsPack.Clue clue)
	{
		state.removeGoalSeed("clue:" + clue.id);
	}

	boolean isGoal(ClueStepsPack.Clue clue)
	{
		return state.getGoalSeeds().containsKey("clue:" + clue.id);
	}

	/** Mark cluestep_<id> unlocks for goal'd steps whose reqs are now met —
	 *  the goals' achieved proof (never flashes on goal removal). */
	private void markProofs()
	{
		if (pack == null)
		{
			return;
		}
		List<String> newlyDone = null;
		for (String id : state.goalSeedIds("clue"))
		{
			ClueStepsPack.Clue clue = pack.clue(id);
			if (clue != null && !state.isUnlocked("cluestep_" + id)
				&& doable(clue, state))
			{
				if (newlyDone == null)
				{
					newlyDone = new ArrayList<>();
				}
				newlyDone.add("cluestep_" + id);
			}
		}
		if (newlyDone != null)
		{
			state.setUnlockedBulk(newlyDone);
		}
	}

	private void onStateChanged()
	{
		markProofs();
	}

	// ── STASH detection (STASH Tracker port) ──────────────────────────

	/** A built STASH's object only renders for the player who built it. */
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (pack == null)
		{
			return;
		}
		ClueStepsPack.Stash unit = pack.stashByObjectId(event.getGameObject().getId());
		if (unit != null)
		{
			state.setStashBuilt(unit.objectId, true);
		}
	}

	/** Remember the clicked STASH so the next message attributes to it. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (pack == null)
		{
			return;
		}
		ClueStepsPack.Stash unit = pack.stashByObjectId(event.getId());
		if (unit != null)
		{
			pendingStash = unit;
			pendingStashTime = System.currentTimeMillis();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
			&& type != ChatMessageType.MESBOX)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage()).toLowerCase(Locale.ROOT);
		if (!message.contains("stash"))
		{
			return;
		}
		int change = classify(message);
		if (change == NO_CHANGE)
		{
			return;
		}
		ClueStepsPack.Stash unit = resolveStash();
		if (unit == null)
		{
			return;
		}
		switch (change)
		{
			case FILLED:
				state.setStashFilled(unit.objectId, true); // filling implies built
				break;
			case EMPTIED:
				state.setStashBuilt(unit.objectId, true);
				state.setStashFilled(unit.objectId, false);
				break;
			case REMOVED:
				state.setStashFilled(unit.objectId, false);
				state.setStashBuilt(unit.objectId, false);
				break;
			case BUILT:
				state.setStashBuilt(unit.objectId, true);
				break;
			default:
		}
		pendingStash = null;
	}

	static final int NO_CHANGE = 0;
	static final int FILLED = 1;
	static final int EMPTIED = 2;
	static final int REMOVED = 3;
	static final int BUILT = 4;

	/** Loose keyword classification (STASH Tracker parity — wording
	 *  tweaks don't break detection). Message must already contain "stash". */
	static int classify(String message)
	{
		if (message.contains("deposit") || message.contains("store") || message.contains("fill"))
		{
			return FILLED;
		}
		if (message.contains("withdraw") || message.contains("take") || message.contains("took")
			|| message.contains("retrieve") || message.contains("empt"))
		{
			return EMPTIED;
		}
		if (message.contains("remove") || message.contains("dismantle"))
		{
			return REMOVED;
		}
		if (message.contains("build") || message.contains("construct"))
		{
			return BUILT;
		}
		return NO_CHANGE;
	}

	/** The clicked STASH within the interaction window, else the nearest
	 *  unit to the player (instances lack the click identification). */
	private ClueStepsPack.Stash resolveStash()
	{
		if (pendingStash != null
			&& System.currentTimeMillis() - pendingStashTime <= INTERACTION_WINDOW_MS)
		{
			return pendingStash;
		}
		if (client == null || client.getLocalPlayer() == null)
		{
			return null;
		}
		return nearest(client.getLocalPlayer().getWorldLocation());
	}

	private ClueStepsPack.Stash nearest(WorldPoint from)
	{
		ClueStepsPack.Stash best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ClueStepsPack.Stash unit : pack.stash)
		{
			if (unit.plane != from.getPlane())
			{
				continue;
			}
			int distance = unit.worldPoint().distanceTo2D(from);
			if (distance <= PROXIMITY_TILES && distance < bestDistance)
			{
				best = unit;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Manual filled toggle from the tab — the escape hatch for STASHes
	 *  filled before the plugin existed (filling implies built). */
	void toggleFilled(ClueStepsPack.Stash unit)
	{
		state.setStashFilled(unit.objectId, !state.isStashFilled(unit.objectId));
	}

	/** An unfilled unit whose outfit the player fully owns (bank + carried;
	 *  POH costume storage is not readable). */
	boolean readyToFill(ClueStepsPack.Stash unit)
	{
		if (state.isStashFilled(unit.objectId) || unit.clueId == null || pack == null)
		{
			return false;
		}
		ClueStepsPack.Clue clue = pack.clue(unit.clueId);
		return clue != null && !clue.reqs.isEmpty() && doable(clue, state);
	}
}
