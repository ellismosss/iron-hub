package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.data.CaCompletionPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Combat achievements (DESIGN.md §3.10, rebuilt to full parity with the
 * Combat Achievements Tracker hub plugin per user direction): the complete
 * task catalog read live from the game cache (CaCatalog), searchable and
 * filterable, with per-task tracking, a boss drill-down, tier goals
 * (auto-advancing or fixed via config), community completion rates from
 * the bundled wiki snapshot, and goal-progress chat messages on each
 * completed task.
 */
@Slf4j
@Singleton
public class CombatAchievementsModule implements IronHubModule
{
	static final CaTier[] TIERS = CaTier.values();

	private final AccountState state;
	private final IronHubConfig config;
	private final Client client;               // null in unit tests
	private final EventBus eventBus;
	private final DataPack dataPack;
	private final ChatMessageManager chatMessageManager; // null in unit tests

	private CombatAchievementsTab tab;
	private volatile List<CaTask> tasks = List.of();
	private Map<Integer, Double> pctById = Map.of();
	private Map<String, Double> pctByName = Map.of();
	private boolean loadedThisSession;
	private boolean reloadRequested;
	/** A combat task completed this tick: reload, then chat the goal progress. */
	private boolean announceAfterReload;
	private Runnable tasksListener;

	@Inject
	public CombatAchievementsModule(AccountState state, IronHubConfig config, Client client,
		EventBus eventBus, DataPack dataPack, ChatMessageManager chatMessageManager)
	{
		this.state = state;
		this.config = config;
		this.client = client;
		this.eventBus = eventBus;
		this.dataPack = dataPack;
		this.chatMessageManager = chatMessageManager;
	}

	@Override
	public String name()
	{
		return "Combat achievements";
	}

	@Override
	public boolean enabled()
	{
		return config.combatAchievements();
	}

	@Override
	public void startUp()
	{
		CaCompletionPack pack = dataPack.load("ca-completion", CaCompletionPack.class);
		pctById = pack.byId();
		pctByName = pack.byName();
		state.watchVarbits(VarbitID.CA_POINTS);
		for (CaTier tier : TIERS)
		{
			state.watchVarbits(tier.thresholdVarbit, tier.completedCountVarbit, tier.statusVarbit);
		}
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		loadedThisSession = false;
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
			tab = new CombatAchievementsTab(this, state, config, config.osrsTheme());
			tasksListener = tab::onTasksUpdated;
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: the next buildTab dresses it fresh. */
	@Override
	public void onThemeChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
				tasksListener = null;
			}
		});
	}

	/** The loaded catalog (immutable snapshot; empty until first load). */
	List<CaTask> tasks()
	{
		return tasks;
	}

	/** Re-read the catalog from the client on the next game tick. */
	void requestReload()
	{
		reloadRequested = true;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			loadedThisSession = false;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// covers plugin start while already logged in (no login event fires)
		if (!loadedThisSession || reloadRequested)
		{
			loadedThisSession = true;
			reloadRequested = false;
			loadCatalog();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = event.getMessage();
		if (message.contains("Congratulations, you've completed") && message.contains("combat task"))
		{
			reloadRequested = true;
			announceAfterReload = config.caGoalMessages();
		}
	}

	/** Runs on the client thread (GameTick dispatch). */
	private void loadCatalog()
	{
		List<CaTask> loaded = CaCatalog.load(client);
		for (CaTask task : loaded)
		{
			Double pct = pctById.get(task.id);
			if (pct == null)
			{
				pct = pctByName.get(CaCompletionPack.normalize(task.name));
			}
			task.communityPct = pct;
		}
		tasks = List.copyOf(loaded);
		log.debug("CA catalog loaded: {} tasks", loaded.size());
		markCompletedGoalTasks(loaded);
		if (announceAfterReload)
		{
			announceAfterReload = false;
			announceGoalProgress();
		}
		if (tasksListener != null)
		{
			SwingUtilities.invokeLater(tasksListener);
		}
	}

	/**
	 * Prove goal-planner CA goals: for every task added as a goal that the
	 * catalog now shows completed, mark its {@code catask_<id>} unlock flag
	 * (the goal's achieved proof) — one bulk persist.
	 */
	private void markCompletedGoalTasks(List<CaTask> loaded)
	{
		var goalIds = state.getCaGoals().keySet();
		List<String> newlyDone = new java.util.ArrayList<>();
		for (CaTask task : loaded)
		{
			if (task.completed && goalIds.contains(String.valueOf(task.id))
				&& !state.isUnlocked("catask_" + task.id))
			{
				newlyDone.add("catask_" + task.id);
			}
		}
		if (!newlyDone.isEmpty())
		{
			state.setUnlockedBulk(newlyDone);
		}
	}

	// ── tier goal math (thresholds always from the game's varbits) ────

	int points()
	{
		return state.getVarbit(VarbitID.CA_POINTS);
	}

	/**
	 * The tier being worked toward: the configured tier, or with AUTO the
	 * first tier whose threshold is not yet reached (null = all complete).
	 */
	CaTier goalTier()
	{
		IronHubConfig.CaTierGoal goal = config.caTierGoal();
		if (goal != IronHubConfig.CaTierGoal.AUTO)
		{
			return CaTier.valueOf(goal.name());
		}
		return nextTier(state);
	}

	/** Points needed for the goal tier (0 when thresholds are unseen). */
	int goalThreshold()
	{
		CaTier goal = goalTier();
		return goal == null ? 0 : state.getVarbit(goal.thresholdVarbit);
	}

	/**
	 * The next tier still in progress: the first whose points threshold is
	 * above the current points, or null when all thresholds are passed.
	 */
	static CaTier nextTier(AccountState state)
	{
		int points = state.getVarbit(VarbitID.CA_POINTS);
		for (CaTier tier : TIERS)
		{
			int threshold = state.getVarbit(tier.thresholdVarbit);
			if (threshold > 0 && points < threshold)
			{
				return tier;
			}
		}
		return null;
	}

	/** Goal-progress chatbox line after each completed combat task. */
	private void announceGoalProgress()
	{
		if (chatMessageManager == null)
		{
			return;
		}
		int points = points();
		CaTier goal = goalTier();
		int threshold = goalThreshold();
		ChatMessageBuilder message = new ChatMessageBuilder()
			.append(Color.CYAN, "[Iron Hub] ");
		if (goal == null || threshold <= 0 || points >= threshold)
		{
			message.append(Color.GREEN, (goal == null ? "All CA tiers" : goal.display + " tier") + " complete! ")
				.append(Color.WHITE, "(" + points + " points)");
		}
		else
		{
			message.append(Color.WHITE, "CA progress: " + points + "/" + threshold + " points ")
				.append(Color.YELLOW, "(" + (threshold - points) + " to " + goal.display + ")");
		}
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message.build())
			.build());
	}
}
