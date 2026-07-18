package com.ironhub.modules.poh;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.PohPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * POH progression (Progression hub, 2026-07-18): the useful house builds
 * per the wiki's POH progression guide as a tile grid — built / next-tier
 * requirements / locked per space.
 *
 * <p>Built detection: house furniture object ids from the pack, gated on
 * the game's own "Welcome to your house." message so a friend's house
 * never marks anything. Spawns buffer from the scene load and commit once
 * the welcome message confirms the house is the player's own (spawns and
 * the message race, so neither order is trusted alone). A manual mark on
 * every tier row covers houses built before Iron Hub.</p>
 */
@Slf4j
@Singleton
public class PohModule implements IronHubModule
{
	private static final String OWN_HOUSE_MESSAGE = "Welcome to your house.";

	private final AccountState state;
	private final IronHubConfig config;
	private final PohPack pack;
	private final EventBus eventBus; // null in unit tests
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private PohTab tab;

	/** Tier ids spotted since the last scene load, awaiting confirmation. */
	private final Set<String> pendingTiers = new HashSet<>();
	private boolean inOwnHouse;

	@Inject
	public PohModule(AccountState state, IronHubConfig config, DataPack dataPack,
		EventBus eventBus, net.runelite.client.game.ItemManager itemManager)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("poh", PohPack.class);
		this.eventBus = eventBus;
		this.itemManager = itemManager;
	}

	@Override
	public String name()
	{
		return "PoH";
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
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		pendingTiers.clear();
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

	// ── detection ─────────────────────────────────────────────────────

	/** Every scene load resets the own-house confirmation and the buffer. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING
			|| event.getGameState() == GameState.LOGGED_IN)
		{
			inOwnHouse = false;
			pendingTiers.clear();
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
			if (!pendingTiers.isEmpty())
			{
				state.setPohBuiltBulk(new ArrayList<>(pendingTiers));
				pendingTiers.clear();
			}
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (pack == null)
		{
			return;
		}
		PohPack.Tier tier = pack.tierByObjectId(event.getGameObject().getId());
		if (tier == null || state.isPohBuilt(tier.id))
		{
			return;
		}
		if (inOwnHouse)
		{
			state.setPohBuilt(tier.id, true); // building mode swaps commit live
		}
		else
		{
			pendingTiers.add(tier.id); // awaits the own-house welcome message
		}
	}

	/** Manual mark toggle — the escape hatch for houses built before the
	 *  plugin (there is no readable POH layout outside the house). */
	void toggleBuilt(PohPack.Tier tier)
	{
		state.setPohBuilt(tier.id, !state.isPohBuilt(tier.id));
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

	/** Test seam: pending-buffer contents. */
	List<String> pendingTiers()
	{
		return new ArrayList<>(pendingTiers);
	}
}
