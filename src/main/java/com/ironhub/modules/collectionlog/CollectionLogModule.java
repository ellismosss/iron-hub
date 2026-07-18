package com.ironhub.modules.collectionlog;

import com.ironhub.IronHubConfig;
import com.ironhub.data.ClogPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Collection log (DESIGN.md §3.18, rebuilt around Log Adviser's
 * Time-To-Next-Slot ranking — see ClogRanker): every remaining activity
 * ranked by expected hours to its next unique slot, with per-slot goal
 * planner assignment ({@code clog:<itemId>} goals proven by the
 * {@code clogitem_<id>} unlock this module marks).
 *
 * The sync brain is ported from Log Adviser's CollectionLogTracker
 * (BSD-2): live drops arrive as "New item added to your collection log"
 * chat messages; browsing the log passively harvests the per-item
 * callback script (4100); and a one-click "Log Sync" header button
 * presses the game's own Search and runs the enumerate script (2240),
 * which re-fires 4100 for every obtained slot. Drift is detected by
 * comparing the player's true COLLECTION_COUNT varp against a baseline
 * pinned at the last full sync. The log window title is still parsed on
 * open for the game-truth slot total.
 */
@Slf4j
@Singleton
public class CollectionLogModule implements IronHubModule
{
	static final Pattern TITLE = Pattern.compile("Collection Log - ([\\d,]+)/([\\d,]+)");
	static final String NEW_ITEM_PREFIX = "New item added to your collection log:";

	// Fires once when the collection log interface is built — attach the button.
	private static final int SCRIPT_COLLECTION_SETUP = 7797;
	// Per-item callback the enumerate script fires for every OBTAINED slot.
	private static final int SCRIPT_COLLECTION_ITEM = 4100;
	// The cs2 proc that walks the whole log and re-fires 4100 for all obtained items.
	private static final int SCRIPT_ENUMERATE_LOG = 2240;
	// Large logs keep firing 4100 for a few ticks; settle before consuming.
	private static final int SYNC_SETTLE_TICKS = 3;

	private final AccountState state;
	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final ItemManager itemManager; // null in unit tests
	private ClogPack pack;
	private CollectionLogTab tab;

	private final LogSyncButton syncButton = new LogSyncButton();
	// Full-sync harvest state (client thread only).
	private final Set<Integer> harvest = new HashSet<>();
	private Integer syncAtTick;
	// True only between a Sync click and the harvest being consumed — gates
	// the "Syncing..." label and the baseline re-pin (passive 4100s from
	// just browsing still merge, but must not claim a full sync).
	private boolean fullSyncRequested;

	@Inject
	public CollectionLogModule(AccountState state, Client client, ClientThread clientThread,
		EventBus eventBus, IronHubConfig config, DataPack dataPack, ItemManager itemManager)
	{
		this.state = state;
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.config = config;
		this.dataPack = dataPack;
		this.itemManager = itemManager;
	}

	@Override
	public String name()
	{
		return "Collection log";
	}

	@Override
	public boolean enabled()
	{
		return config.collectionLog();
	}

	@Override
	public void startUp()
	{
		pack = dataPack.load("clog", ClogPack.class);
		state.watchVarps(VarPlayerID.COLLECTION_COUNT);
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		syncButton.reset();
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
			if (pack == null)
			{
				startUp();
			}
			tab = new CollectionLogTab(this, state, itemManager, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: the next buildTab dresses it fresh. */
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

	ClogPack pack()
	{
		return pack;
	}

	/**
	 * True when our obtained data is believed complete: the player's true
	 * slot count (COLLECTION_COUNT varp) hasn't moved past the baseline
	 * pinned at the last full sync. A never-synced account with any slots
	 * reads out-of-sync until the first Log Sync.
	 */
	boolean inSync()
	{
		int playerCount = state.getVarp(VarPlayerID.COLLECTION_COUNT);
		if (playerCount <= 0)
		{
			// Varp not delivered yet, or a fresh account — don't nag.
			return true;
		}
		int baseline = state.getClogBaseline();
		return baseline >= 0 && playerCount <= baseline;
	}

	// ── live drops (chat) ──────────────────────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		if (!message.startsWith(NEW_ITEM_PREFIX))
		{
			return;
		}
		String itemName = message.substring(NEW_ITEM_PREFIX.length()).trim();
		Integer id = pack.itemIdByName(itemName);
		if (id == null)
		{
			log.debug("could not resolve collection log item name: {}", itemName);
			return;
		}
		// The unlock advanced the player's true count by one — keep the
		// baseline in lockstep so this doesn't read as drift.
		state.bumpClogBaseline();
		markObtained(List.of(pack.canonical(id)));
	}

	// ── widget harvest (browsing + the Log Sync button) ───────────────

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != SCRIPT_COLLECTION_ITEM)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 2)
		{
			return;
		}
		int itemId = (int) args[1];
		if (itemId > 0)
		{
			harvest.add(pack.canonical(itemId));
		}
		// Push the consume out to a few ticks after the LAST item so a
		// large log finishes streaming before we read it.
		syncAtTick = client.getTickCount() + SYNC_SETTLE_TICKS;
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == SCRIPT_COLLECTION_SETUP)
		{
			// The interface was (re)built — its dynamic children were wiped.
			syncButton.reset();
			syncButton.attach(client, this::triggerFullSync);
			// Another plugin handling 7797 may deleteAllChildren() on this
			// container AFTER us; re-attach once more on the next client
			// cycle so we survive the wipe regardless of EventBus ordering.
			clientThread.invokeLater(() ->
			{
				syncButton.attach(client, this::triggerFullSync);
				return true;
			});
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Self-heal: re-create the button if another plugin wiped it.
		// attach() no-ops when the log is closed or the button is present.
		syncButton.attach(client, this::triggerFullSync);
		if (fullSyncRequested)
		{
			// A redraw can rebuild the button and revert its label;
			// re-pin the busy state each tick until we consume.
			syncButton.setBusy(true);
		}
		if (syncAtTick != null && client.getTickCount() >= syncAtTick)
		{
			consumeHarvest();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			syncButton.reset();
		}
	}

	/** Press the game's own Search and run the enumerate script; the
	 *  per-item 4100 callbacks are harvested in onScriptPreFired.
	 *  Package-private for tests. */
	void triggerFullSync()
	{
		harvest.clear();
		fullSyncRequested = true;
		// Guarantee a consume even if the log is empty (no 4100 fires) so
		// the button never sticks on "Syncing...".
		syncAtTick = client.getTickCount() + SYNC_SETTLE_TICKS;
		syncButton.setBusy(true);
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1,
			"Search", null);
		client.runScript(SCRIPT_ENUMERATE_LOG);
	}

	/** Merge the harvest into the obtained set; a full sync also re-pins
	 *  the baseline to the player's true count. Client thread. */
	private void consumeHarvest()
	{
		List<Integer> ids = new ArrayList<>(harvest);
		harvest.clear();
		syncAtTick = null;
		markObtained(ids);
		if (fullSyncRequested)
		{
			fullSyncRequested = false;
			syncButton.setBusy(false);
			int playerCount = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
			if (playerCount > 0)
			{
				state.recordClogFullSync(playerCount);
			}
		}
	}

	/**
	 * Record obtained slots and prove any goal-planner clog goals they
	 * complete: the {@code clogitem_<id>} unlock is each goal's achieved
	 * proof. One bulk unlock persist.
	 */
	void markObtained(Collection<Integer> canonicalIds)
	{
		if (canonicalIds.isEmpty())
		{
			return;
		}
		state.markClogObtained(canonicalIds);
		List<String> newlyDone = new ArrayList<>();
		for (int id : canonicalIds)
		{
			if (state.getGoalSeeds().containsKey("clog:" + id)
				&& !state.isUnlocked("clogitem_" + id))
			{
				newlyDone.add("clogitem_" + id);
			}
		}
		if (!newlyDone.isEmpty())
		{
			state.setUnlockedBulk(newlyDone);
		}
	}

	// ── log window title (game-truth slot total) ──────────────────────

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.COLLECTION && clientThread != null)
		{
			// the title text populates after the interface's scripts run
			clientThread.invokeLater(this::readLogTitle);
		}
	}

	private void readLogTitle()
	{
		Widget frame = client.getWidget(InterfaceID.Collection.FRAME);
		if (frame == null)
		{
			return;
		}
		if (!scanForTitle(frame))
		{
			log.debug("collection log title not found on open");
		}
	}

	private boolean scanForTitle(Widget widget)
	{
		int[] parsed = parseTitle(widget.getText());
		if (parsed != null)
		{
			state.recordCollectionLog(parsed[0], parsed[1]);
			return true;
		}
		for (Widget[] children : new Widget[][]{
			widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null && scanForTitle(child))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/** "[slots, total]" from a log title, or null. Static for tests. */
	static int[] parseTitle(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher matcher = TITLE.matcher(text);
		if (!matcher.find())
		{
			return null;
		}
		return new int[]{
			Integer.parseInt(matcher.group(1).replace(",", "")),
			Integer.parseInt(matcher.group(2).replace(",", ""))};
	}
}
