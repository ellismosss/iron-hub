package com.ironhub.modules.dailies;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Provider;
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
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.Notifier;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/**
 * Dailies (DESIGN.md §3.12), rebuilt post-M8 in the Farm runs shape: the
 * repeatable events of the OSRS wiki's Repeatable events page, detected from
 * the game's own claim varbits (see {@link DailyTracker}), plus a guided run
 * that routes you through the ones you can actually do right now.
 *
 * <p>Like a farm run, the run is culled at the start — an event you have
 * deselected, cannot access, or have already claimed today is not a stop —
 * and a stop advances off a live game signal (its claim varbit flipping),
 * never on arrival. Events with no claim flag at all (Miscellania) advance
 * on the sidebar's Skip.
 */
@Slf4j
@Singleton
public class DailiesModule implements IronHubModule
{
	private final AccountState state;
	private final Client client;                   // null in unit tests
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final InfoBoxManager infoBoxManager;   // null in unit tests
	private final OverlayManager overlayManager;   // null in unit tests
	private final Provider<? extends Plugin> plugin; // Provider breaks the DI cycle
	private final EventBus eventBus;               // null in unit tests
	private final Notifier notifier;               // null in unit tests
	private final ShortestPathBridge pathBridge;   // null in unit tests
	private final ItemManager itemManager;         // null in unit tests
	private final net.runelite.client.callback.ClientThread clientThread; // null in unit tests
	private final net.runelite.client.plugins.banktags.BankTagsService bankTagsService; // null in tests
	private final net.runelite.client.plugins.banktags.TagManager tagManager; // null in tests
	private final net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager; // null in tests
	private com.ironhub.modules.farming.FarmBankLayout bankLayout;
	private com.loadoutlab.ui.BankHighlightOverlay bankHighlight;

	private DailiesTab tab;
	private DailiesInfoBox infoBox;
	private DailyRunInfoBox runInfoBox;
	private DailiesRunOverlay overlay;
	private DailiesPack pack;

	// run state — written on the client thread, read from the EDT/overlay
	private volatile long runStartMs;
	private volatile List<DailiesPack.Daily> stops = List.of();
	private final Set<String> visited = ConcurrentHashMap.newKeySet();

	/**
	 * The UTC day the claim varbits were last known fresh — they only refresh
	 * from the server on login, so once the clock rolls past 00:00 UTC while
	 * you stay logged in they may still read "claimed" for an event the server
	 * has already reissued. This is core's {@code dailyReset} escape hatch
	 * (see {@link DailyTracker}); 0 until we have seen a login.
	 */
	private volatile long varbitsFreshDay;

	/** Reset crossing → notify once, and never on a login replay. */
	private long notifiedForDay;

	/** The run's name — also the key its saved bank setup is stored under, and
	 *  the title the bank wears while it is laid out. */
	static final String RUN_NAME = "Daily run";

	/** ~1 min at 0.6s/tick — the reset we watch for happens once a day. */
	private static final int TICKS_PER_RESET_CHECK = 100;
	private int resetTick;

	/** The claim varbits we registered — everything else is someone else's. */
	private Set<Integer> watchedVarbits = Set.of();

	@Inject
	public DailiesModule(AccountState state, Client client, IronHubConfig config, DataPack dataPack,
		InfoBoxManager infoBoxManager, Provider<com.ironhub.IronHubPlugin> plugin,
		EventBus eventBus, Notifier notifier, OverlayManager overlayManager,
		ShortestPathBridge pathBridge, ItemManager itemManager,
		net.runelite.client.callback.ClientThread clientThread,
		net.runelite.client.plugins.banktags.BankTagsService bankTagsService,
		net.runelite.client.plugins.banktags.TagManager tagManager,
		net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.bankTagsService = bankTagsService;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.eventBus = eventBus;
		this.notifier = notifier;
		this.state = state;
		this.client = client;
		this.config = config;
		this.dataPack = dataPack;
		this.infoBoxManager = infoBoxManager;
		this.overlayManager = overlayManager;
		this.pathBridge = pathBridge;
		this.plugin = plugin;
	}

	@Override
	public String name()
	{
		return "Dailies";
	}

	@Override
	public boolean enabled()
	{
		return config.dailies();
	}

	@Override
	public void startUp()
	{
		pack = dataPack.load("dailies", DailiesPack.class);
		// Every detection varbit, so AccountState keeps them live for us.
		int[] watched = pack.dailies.stream()
			.filter(d -> d.detection != null && d.detection.varbit > 0)
			.mapToInt(d -> d.detection.varbit)
			.toArray();
		state.watchVarbits(watched);
		watchedVarbits = java.util.Arrays.stream(watched).boxed()
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		notifiedForDay = DailyTracker.startOfUtcDay(System.currentTimeMillis());

		if (eventBus != null)
		{
			eventBus.register(this);
		}
		if (infoBoxManager != null)
		{
			BufferedImage icon = ImageUtil.loadImageResource(com.ironhub.IronHubPlugin.class, "/icon.png");
			infoBox = new DailiesInfoBox(icon, plugin.get(), this);
			infoBoxManager.addInfoBox(infoBox);
			runInfoBox = new DailyRunInfoBox(icon, plugin.get(), this);
			infoBoxManager.addInfoBox(runInfoBox);
		}
		bankLayout = new com.ironhub.modules.farming.FarmBankLayout(
			bankTagsService, tagManager, layoutManager, itemManager);
		if (overlayManager != null)
		{
			overlay = new DailiesRunOverlay(this);
			overlayManager.add(overlay);
			// the same green glow the farm run uses for what is still to withdraw
			bankHighlight = new com.loadoutlab.ui.BankHighlightOverlay(this::bankHighlight);
			overlayManager.add(bankHighlight);
		}
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		if (infoBox != null)
		{
			infoBoxManager.removeInfoBox(infoBox);
			infoBox = null;
		}
		if (runInfoBox != null)
		{
			infoBoxManager.removeInfoBox(runInfoBox);
			runInfoBox = null;
		}
		if (overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		if (bankHighlight != null)
		{
			overlayManager.remove(bankHighlight);
			bankHighlight = null;
		}
		if (bankLayout != null)
		{
			bankLayout.clear(); // shutDown runs on the client thread
			bankLayout = null;
		}
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
		endRun(false);
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new DailiesTab(this);
		}
		return tab;
	}

	/** Login refreshes every claim varbit from the server — from here until
	 *  the next 00:00 UTC we can trust them exactly. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			varbitsFreshDay = DailyTracker.startOfUtcDay(System.currentTimeMillis());
			notifiedForDay = varbitsFreshDay; // never notify on a login replay
		}
	}

	/**
	 * Only our own claim varbits matter here — the game changes hundreds of
	 * others, and a login floods this handler, so re-evaluating every stop on
	 * each one would be pure waste.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (pack == null || !watchedVarbits.contains(event.getVarbitId()))
		{
			return;
		}
		stampTearsVisit(event);
		checkAdvance();
	}

	/**
	 * The reset is a wall-clock event, not a game event: an idle player must
	 * still be told their dailies came back, so this cannot hang off a varbit
	 * change. Throttled — a reset crossing is a once-a-day thing.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (++resetTick % TICKS_PER_RESET_CHECK == 0)
		{
			notifyReset(System.currentTimeMillis());
		}
	}

	/**
	 * The game announcing an event is claimable — today only Juna's opt-in
	 * Tears of Guthix reminder, which she repeats daily for as long as you
	 * stay eligible. Believed over any timer we could keep ourselves.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (pack == null || (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM))
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		for (DailiesPack.Daily daily : pack.dailies)
		{
			if (daily.detection != null && daily.detection.chat != null
				&& message.contains(daily.detection.chat))
			{
				state.setUnlocked(DailyTracker.eligibleKey(daily), true);
			}
		}
	}

	/** Tears of Guthix: 1 while you are collecting, 0 when the time is up. */
	private boolean collectingTears;

	/**
	 * Tears of Guthix has no cooldown varbit, so a visit is only known by
	 * watching one happen — and the visit is the minigame ENDING, not starting.
	 * Walking in and catching your first tear is not a week's worth of XP, so
	 * the stop waits for the collecting flag to go back down (1 -> 0), which is
	 * when the tears are drunk and the XP lands.
	 */
	private void stampTearsVisit(VarbitChanged event)
	{
		for (DailiesPack.Daily daily : pack.dailies)
		{
			if (daily.detection == null || !"rolling7".equals(daily.detection.mode)
				|| event.getVarbitId() != daily.detection.varbit)
			{
				continue;
			}
			boolean collecting = event.getValue() > 0;
			if (collectingTears && !collecting)
			{
				state.markDaily(daily.id, true);
				state.setUnlocked(DailyTracker.eligibleKey(daily), false);
			}
			collectingTears = collecting;
		}
	}

	/**
	 * The bank is building: lay the daily run's saved setup over it, exactly as
	 * a farm run does. Cleared whenever the bank opens without a run.
	 */
	@Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_INIT || bankLayout == null)
		{
			return;
		}
		if (config.farmBankSetup() && running())
		{
			com.ironhub.state.PersistedState.SavedSetup setup = state.getFarmRunSetup(RUN_NAME);
			if (setup != null)
			{
				bankLayout.apply(RUN_NAME, setup);
				return;
			}
		}
		bankLayout.clear();
	}

	/** Bank Tags titles the window with the raw hidden tag; say the run instead. */
	@Subscribe
	public void onScriptPostFired(net.runelite.api.events.ScriptPostFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_FINISHBUILDING
			|| client == null || bankLayout == null || !bankLayout.isApplied())
		{
			return;
		}
		net.runelite.api.widgets.Widget title = client.getWidget(
			net.runelite.api.gameval.InterfaceID.Bankmain.TITLE);
		if (title != null)
		{
			title.setText(RUN_NAME);
		}
	}

	/** Setup items you have not picked up yet — the green bank glow. */
	java.util.Set<Integer> bankHighlight()
	{
		if (!running() || !config.farmBankSetup())
		{
			return java.util.Set.of();
		}
		com.ironhub.state.PersistedState.SavedSetup setup = state.getFarmRunSetup(RUN_NAME);
		return setup == null ? java.util.Set.of() : state.setupItemsToWithdraw(setup);
	}

	/** At 00:00 UTC the outstanding set refills — say so once, and never as a
	 *  replay of a reset that happened before you logged in. */
	void notifyReset(long now)
	{
		long today = DailyTracker.startOfUtcDay(now);
		if (today == notifiedForDay)
		{
			return;
		}
		notifiedForDay = today;
		int outstanding = outstanding();
		if (outstanding > 0 && notifier != null && config.notifyDailyReset())
		{
			notifier.notify(outstanding + (outstanding == 1
				? " daily is available again" : " dailies are available again"));
		}
	}

	// ── detection ────────────────────────────────────────────────────

	/** True once the clock has passed 00:00 UTC since the varbits were last
	 *  refreshed from the server, i.e. they may be stale. */
	boolean crossedReset()
	{
		return varbitsFreshDay > 0
			&& DailyTracker.startOfUtcDay(System.currentTimeMillis()) != varbitsFreshDay;
	}

	DailyTracker.State stateOf(DailiesPack.Daily daily)
	{
		return DailyTracker.stateOf(state, daily, crossedReset(), System.currentTimeMillis());
	}

	/** Whether this event is in the player's routine — their explicit choice,
	 *  else the pack's default (the Wilderness ones start out). */
	boolean selected(DailiesPack.Daily daily)
	{
		return state.isDailySelected(daily.id, !daily.optOut);
	}

	/** What you still need to bring before this one is doable. */
	java.util.List<String> missing(DailiesPack.Daily daily)
	{
		return DailyTracker.missing(state, daily);
	}

	/** Claimable now and selected for the run. UNKNOWN (Tears of Guthix before
	 *  we have seen a visit) does not count — we never guess it either way. */
	int outstanding()
	{
		return outstanding(state, pack);
	}

	/** How many you get from this event at your current diary tier. */
	int quantity(DailiesPack.Daily daily)
	{
		return DailyTracker.quantity(state, daily);
	}

	/** "13 bones" / "91,000 coins" — what to bring, scaled to your tier.
	 *  Empty when the pack lists nothing to bring. */
	String bringLine(DailiesPack.Daily daily)
	{
		if (daily.bring == null || daily.bring.isEmpty())
		{
			return "";
		}
		int qty = Math.max(1, quantity(daily));
		StringBuilder out = new StringBuilder();
		for (DailiesPack.Bring bring : daily.bring)
		{
			if (out.length() > 0)
			{
				out.append(" · ");
			}
			out.append(String.format(Locale.ROOT, "%,d", (long) bring.per * qty))
				.append(' ').append(bring.label);
		}
		return out.toString();
	}

	/**
	 * The awkward truths about a stop, for the overlay: the pack's note, and
	 * how many trips it takes at your tier when an inventory cannot hold the
	 * lot (Robin returns two unnoted items per bone, so 14 bones is a trip).
	 */
	java.util.List<String> stopAdvice(DailiesPack.Daily daily)
	{
		java.util.List<String> out = new ArrayList<>();
		if (daily.perTrip == null)
		{
			return out;
		}
		if (daily.note != null)
		{
			out.add(daily.note);
		}
		int trips = DailyTracker.trips(state, daily);
		int units = Math.max(1, quantity(daily));
		out.add(trips > 1
			? units + " to collect = " + trips + " trips of " + daily.perTrip
			: "Withdraw " + Math.min(units, daily.perTrip) + " to collect " + units);
		return out;
	}

	// ── run engine ───────────────────────────────────────────────────

	/**
	 * The stops worth doing right now: selected, accessible, and not already
	 * claimed this reset. An event whose state we genuinely do not know
	 * (Tears of Guthix, before we have watched a visit) IS included — going to
	 * look is the only way to find out, and silently dropping it would hide it
	 * forever.
	 */
	List<DailiesPack.Daily> cull()
	{
		List<DailiesPack.Daily> out = new ArrayList<>();
		for (DailiesPack.Daily daily : pack.dailies)
		{
			if (!selected(daily))
			{
				continue; // the player's own checklist wins
			}
			DailyTracker.State current = stateOf(daily);
			// SHORT too: a stop you cannot supply is a wasted trip, so it is no
			// more a stop than a locked one is.
			if (current == DailyTracker.State.LOCKED || current == DailyTracker.State.DONE
				|| current == DailyTracker.State.SHORT)
			{
				continue;
			}
			out.add(daily);
		}
		return out;
	}

	void startRun()
	{
		stops = List.copyOf(cull());
		visited.clear();
		runStartMs = System.currentTimeMillis();
		// Switch the sidebar to the active run NOW, queued before routeToNext:
		// a path-bridge hiccup must not leave the picker showing (the farm-run
		// lesson — that post can throw and skip the caller's rebuild).
		rebuildTab();
		routeToNext();
	}

	/**
	 * Advance past the current stop once the game says you claimed it. Not on
	 * arrival: standing next to Zaff is not the same as buying the staves, and
	 * a proximity advance would desync the overlay from Shortest Path (the bug
	 * that cost the farm runs a round).
	 */
	void checkAdvance()
	{
		if (!running())
		{
			return;
		}
		DailiesPack.Daily next = nextStop();
		if (next == null || stateOf(next) != DailyTracker.State.DONE)
		{
			return;
		}
		advance(next.id);
	}

	private void advance(String dailyId)
	{
		visited.add(dailyId);
		if (visited.size() == stops.size())
		{
			endRun(true);
		}
		else
		{
			routeToNext(); // keep the path bridge on the true next stop
		}
		rebuildTab();
	}

	/**
	 * Manual advance from the sidebar: mark this stop and everything before it
	 * done ("I'm past here"). The escape hatch for a stop you skip, and the
	 * only way to advance Miscellania, which has no claim flag to watch.
	 */
	void markThrough(String dailyId)
	{
		boolean changed = false;
		for (DailiesPack.Daily daily : stops)
		{
			changed |= visited.add(daily.id);
			if (daily.id.equals(dailyId))
			{
				break;
			}
		}
		if (!changed)
		{
			return;
		}
		if (visited.size() == stops.size())
		{
			endRun(true);
		}
		else
		{
			routeToNext();
		}
		rebuildTab();
	}

	void endRun(boolean complete)
	{
		boolean wasRunning = running();
		if (wasRunning && bankLayout != null && bankLayout.isApplied() && clientThread != null)
		{
			clientThread.invoke(bankLayout::clear); // give the bank back
		}
		runStartMs = 0;
		stops = List.of();
		visited.clear();
		if (wasRunning && !complete)
		{
			rebuildTab();
		}
	}

	/** Send the path bridge toward the next unvisited stop. */
	/**
	 * Best-effort, and it swallows: the Shortest Path post is a foreign plugin
	 * on the EDT and it can throw. It used to take its caller with it — Skip
	 * updated the run and then never reached rebuildTab(), so the overlay moved
	 * on while the sidebar sat there. Nothing about routing is worth losing a
	 * repaint over.
	 */
	private void routeToNext()
	{
		try
		{
			postRoute();
		}
		catch (RuntimeException e)
		{
			log.debug("routeToNext failed", e);
		}
	}

	private void postRoute()
	{
		DailiesPack.Daily next = nextStop();
		if (next != null && pathBridge != null)
		{
			pathBridge.pathTo(next.worldPoint());
		}
	}

	private void rebuildTab()
	{
		if (tab != null)
		{
			SwingUtilities.invokeLater(tab::rebuild);
		}
	}

	// ── run reads ────────────────────────────────────────────────────

	boolean running()
	{
		return runStartMs > 0;
	}

	long elapsedMs()
	{
		return running() ? System.currentTimeMillis() - runStartMs : 0;
	}

	boolean isVisited(String dailyId)
	{
		return visited.contains(dailyId);
	}

	int visitedCount()
	{
		return visited.size();
	}

	/** First unclaimed stop in route order, or null. */
	DailiesPack.Daily nextStop()
	{
		return stops.stream().filter(d -> !visited.contains(d.id)).findFirst().orElse(null);
	}

	List<DailiesPack.Daily> stops()
	{
		return stops;
	}

	DailiesPack pack()
	{
		return pack;
	}

	AccountState state()
	{
		return state;
	}

	/** Null in headless tests — the tab renders its tiles without sprites. */
	ItemManager itemManager()
	{
		return itemManager;
	}

	boolean hasSetup()
	{
		return state.getFarmRunSetup(RUN_NAME) != null;
	}

	void saveSetup()
	{
		state.saveFarmRunSetup(RUN_NAME, state.captureSetup());
	}

	void clearSetup()
	{
		state.saveFarmRunSetup(RUN_NAME, null);
	}

	/** mm:ss / h:mm:ss, matching the farm run timer. */
	static String formatDuration(long ms)
	{
		long seconds = ms / 1000;
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		return hours > 0
			? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
			: String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60);
	}

	/**
	 * Cross-module read (dashboard, WhatNow): dailies claimable now. Called
	 * without the module's login bookkeeping, so it trusts the varbits as they
	 * read rather than assuming a stale-reset window.
	 */
	public static int outstanding(AccountState state, DailiesPack pack)
	{
		long now = System.currentTimeMillis();
		return (int) pack.dailies.stream()
			.filter(d -> state.isDailySelected(d.id, !d.optOut))
			.filter(d -> DailyTracker.stateOf(state, d, false, now) == DailyTracker.State.AVAILABLE)
			.count();
	}
}
