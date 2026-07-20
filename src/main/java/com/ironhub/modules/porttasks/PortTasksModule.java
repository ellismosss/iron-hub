package com.ironhub.modules.porttasks;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.PortTasksPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Port tasks (Dailies hub): Sailing courier and bounty tasks — the five
 * accepted slots with live progress, a ranked port-suggestion list, and
 * the noticeboard advisor that scores a board's offers by Sailing XP per
 * tile added to your current route (exact tour math over your active
 * tasks — see {@link PortTaskPlanner}). Detection ported from the Port
 * Tasks hub plugin (nucleon + Cooper Morris, BSD-2, pinned in
 * gen_port_tasks.py).
 *
 * <p>The task catalog is NOT static: names, ports, cargo and bounty
 * targets come from cache DBTable 197 at runtime (loaded once per
 * session on the client thread); per-task XP, ports and sailing
 * distances ride in the pack. Slot state is stateless-derived from the
 * game's own varbits on every read, so login replay can never duplicate
 * a task (the reference's known bug). The bounty COUNT varbits store
 * items REMAINING — collected = required − remaining.</p>
 */
@Slf4j
@Singleton
public class PortTasksModule implements IronHubModule
{
	/** Per-slot varbits (reference PortTaskTrigger, verbatim). */
	static final int[] SLOT_ID = {
		VarbitID.PORT_TASK_SLOT_0_ID, VarbitID.PORT_TASK_SLOT_1_ID,
		VarbitID.PORT_TASK_SLOT_2_ID, VarbitID.PORT_TASK_SLOT_3_ID,
		VarbitID.PORT_TASK_SLOT_4_ID};
	static final int[] SLOT_TAKEN = {
		VarbitID.PORT_TASK_SLOT_0_CARGO_TAKEN, VarbitID.PORT_TASK_SLOT_1_CARGO_TAKEN,
		VarbitID.PORT_TASK_SLOT_2_CARGO_TAKEN, VarbitID.PORT_TASK_SLOT_3_CARGO_TAKEN,
		VarbitID.PORT_TASK_SLOT_4_CARGO_TAKEN};
	static final int[] SLOT_DELIVERED = {
		VarbitID.PORT_TASK_SLOT_0_CARGO_DELIVERED, VarbitID.PORT_TASK_SLOT_1_CARGO_DELIVERED,
		VarbitID.PORT_TASK_SLOT_2_CARGO_DELIVERED, VarbitID.PORT_TASK_SLOT_3_CARGO_DELIVERED,
		VarbitID.PORT_TASK_SLOT_4_CARGO_DELIVERED};
	/** Bounty items-REMAINING varbits — raw ids, no gameval names exist
	 *  (raw in the reference too), interleaved out of numeric order. */
	static final int[] SLOT_COUNT = {14662, 14663, 14819, 15370, 15397};

	/** One runtime-catalog courier task (DBTable 197 + pack XP). */
	public static final class CourierInfo
	{
		public int id;          // the slot-varbit value (COL_TASK_ID)
		public int dbrow;       // the DBRow (noticeboard + rewards key)
		public int level;
		public int boardPort;   // port dbrows
		public int cargoPort;
		public int deliverPort;
		public int cargoItem;
		public int cargoAmount;
		public int xp;          // 0 = unknown (not in the pack)
		public String name;
	}

	/** One runtime-catalog bounty task. */
	public static final class BountyInfo
	{
		public int id;
		public int dbrow;
		public int level;
		public int port;
		public int itemId;
		public int qty;
		public int rarity;      // 1 in N
		public int xp;
		public String name;
	}

	/** A filled task slot, derived fresh from varbits on every read. */
	public static final class ActiveTask
	{
		public int slot;
		public int taskId;
		public CourierInfo courier; // one of these two is set when the
		public BountyInfo bounty;   // catalog has loaded; both null = honest "Task #id"
		public int taken;
		public int delivered;
		public int collected;
	}

	/** One ranked board offer. */
	public static final class Advice
	{
		public int dbrow;
		public CourierInfo courier; // null for bounty offers
		public BountyInfo bounty;
		public String label;
		public int xp;
		public double marginalTiles; // NaN = unroutable/unknown
		public double score;         // xp per marginal tile
		public boolean levelGated;
		public boolean alreadyTaken; // the offer sits in one of your slots
	}

	private final AccountState state;
	private final IronHubConfig config;
	private final PortTasksPack pack;
	private final EventBus eventBus;         // null in unit tests
	private final Client client;             // null in unit tests
	private final ClientThread clientThread; // null in unit tests
	private final OverlayManager overlayManager; // null in unit tests
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private PortTasksTab tab;
	private PortTaskBoardOverlay overlay;

	/** Immutable catalog snapshot, published atomically — the client thread
	 *  rebuilds it while the EDT tab iterates (2026-07-20 audit: clear+put
	 *  on shared HashMaps raced the readers). */
	private volatile Catalog catalog = new Catalog(Map.of(), Map.of(), Map.of(), Map.of());

	private static final class Catalog
	{
		final Map<Integer, CourierInfo> courierById;
		final Map<Integer, CourierInfo> courierByDbrow;
		final Map<Integer, BountyInfo> bountyById;
		final Map<Integer, BountyInfo> bountyByDbrow;

		Catalog(Map<Integer, CourierInfo> courierById, Map<Integer, CourierInfo> courierByDbrow,
			Map<Integer, BountyInfo> bountyById, Map<Integer, BountyInfo> bountyByDbrow)
		{
			this.courierById = courierById;
			this.courierByDbrow = courierByDbrow;
			this.bountyById = bountyById;
			this.bountyByDbrow = bountyByDbrow;
		}
	}
	private volatile boolean catalogLoaded;

	private volatile boolean boardOpen;
	private List<Integer> lastOffers = List.of(); // board DBRows, scan order

	@Inject
	public PortTasksModule(AccountState state, IronHubConfig config, DataPack dataPack,
		EventBus eventBus, Client client, ClientThread clientThread,
		OverlayManager overlayManager, net.runelite.client.game.ItemManager itemManager)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("port-tasks", PortTasksPack.class);
		this.eventBus = eventBus;
		this.client = client;
		this.clientThread = clientThread;
		this.overlayManager = overlayManager;
		this.itemManager = itemManager;
	}

	@Override
	public String name()
	{
		return "Port tasks";
	}

	@Override
	public boolean enabled()
	{
		return config.portTasks();
	}

	@Override
	public void startUp()
	{
		List<Integer> watch = new ArrayList<>();
		for (int slot = 0; slot < 5; slot++)
		{
			watch.add(SLOT_ID[slot]);
			watch.add(SLOT_TAKEN[slot]);
			watch.add(SLOT_DELIVERED[slot]);
			watch.add(SLOT_COUNT[slot]);
		}
		watch.add(VarbitID.PORT_TASKS_COMPLETED_TODAY);
		watch.add(VarbitID.PORT_TASK_EXTRA_SLOTS_UNLOCKED);
		state.watchVarbits(watch.stream().mapToInt(Integer::intValue).toArray());
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		if (overlayManager != null)
		{
			overlay = new PortTaskBoardOverlay(this, config);
			overlayManager.add(overlay);
		}
		loadCatalog();
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		if (overlayManager != null && overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		boardOpen = false;
		lastOffers = List.of();
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
			tab = new PortTasksTab(state, this, config.osrsTheme(), itemManager);
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

	PortTasksPack pack()
	{
		return pack;
	}

	boolean catalogLoaded()
	{
		return catalogLoaded;
	}

	// ── runtime catalog (cache DBTable 197, reference semantics) ──────

	private void loadCatalog()
	{
		if (client == null || clientThread == null || pack == null)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
			{
				return false; // retry until the cache is readable
			}
			if (!catalogLoaded)
			{
				readCatalog();
			}
			return true;
		});
	}

	private void readCatalog()
	{
		List<Integer> rows = client.getDBTableRows(DBTableID.PortTask.ID);
		if (rows == null || rows.isEmpty())
		{
			return;
		}
		List<CourierInfo> couriers = new ArrayList<>();
		List<BountyInfo> bounties = new ArrayList<>();
		for (int dbrow : rows)
		{
			Integer id = intField(dbrow, DBTableID.PortTask.COL_TASK_ID, 0);
			Integer board = intField(dbrow, DBTableID.PortTask.COL_STARTING_PORT, 0);
			Integer cargoPort = intField(dbrow, DBTableID.PortTask.COL_CARGO_PORT, 0);
			Integer deliverPort = intField(dbrow, DBTableID.PortTask.COL_ENDING_PORT, 0);
			Integer level = intField(dbrow, DBTableID.PortTask.COL_LEVEL_REQUIRED, 0);
			if (id == null || board == null || cargoPort == null || deliverPort == null
				|| level == null)
			{
				continue;
			}
			PortTasksPack.Reward reward = pack.reward(dbrow);
			if (cargoPort.equals(deliverPort))
			{
				// bounty row (cargo == destination port)
				if (pack.port(board) == null)
				{
					continue; // quest rows
				}
				BountyInfo b = new BountyInfo();
				b.id = id;
				b.dbrow = dbrow;
				b.level = level;
				b.port = board;
				b.name = stringField(dbrow, DBTableID.PortTask.COL_NAME);
				Integer item = intField(dbrow, DBTableID.PortTask.COL_BOUNTY_OBJECT, 0);
				Integer qty = intField(dbrow, DBTableID.PortTask.COL_BOUNTY_OBJECT_AMOUNT, 0);
				Integer rarity = intField(dbrow, DBTableID.PortTask.COL_BOUNTY_OBJECT_RARITY, 0);
				b.itemId = item == null ? 0 : item;
				b.qty = qty == null ? 0 : qty;
				b.rarity = rarity == null ? 0 : rarity;
				b.xp = reward == null ? 0 : reward.xp;
				bounties.add(b);
			}
			else
			{
				if (pack.port(cargoPort) == null || pack.port(deliverPort) == null)
				{
					continue; // quest rows use out-of-catalog ports
				}
				CourierInfo c = new CourierInfo();
				c.id = id;
				c.dbrow = dbrow;
				c.level = level;
				c.boardPort = board;
				c.cargoPort = cargoPort;
				c.deliverPort = deliverPort;
				c.name = stringField(dbrow, DBTableID.PortTask.COL_NAME);
				Integer item = intField(dbrow, DBTableID.PortTask.COL_CARGO, 0);
				Integer amount = intField(dbrow, DBTableID.PortTask.COL_CARGO, 1);
				c.cargoItem = item == null ? 0 : item;
				c.cargoAmount = amount == null ? 0 : amount;
				c.xp = reward == null ? 0 : reward.xp;
				couriers.add(c);
			}
		}
		setCatalog(couriers, bounties);
		log.debug("port-task catalog: {} couriers, {} bounties", couriers.size(), bounties.size());
	}

	private Integer intField(int dbrow, int column, int tupleIndex)
	{
		Object[] field = client.getDBTableField(dbrow, column, tupleIndex);
		if (field == null || field.length == 0 || !(field[0] instanceof Integer))
		{
			return null;
		}
		return (Integer) field[0];
	}

	private String stringField(int dbrow, int column)
	{
		Object[] field = client.getDBTableField(dbrow, column, 0);
		return field != null && field.length > 0 && field[0] instanceof String
			? (String) field[0] : null;
	}

	/** Test seam / catalog commit. */
	void setCatalog(List<CourierInfo> couriers, List<BountyInfo> bounties)
	{
		Map<Integer, CourierInfo> courierById = new HashMap<>();
		Map<Integer, CourierInfo> courierByDbrow = new HashMap<>();
		Map<Integer, BountyInfo> bountyById = new HashMap<>();
		Map<Integer, BountyInfo> bountyByDbrow = new HashMap<>();
		for (CourierInfo c : couriers)
		{
			courierById.put(c.id, c);
			courierByDbrow.put(c.dbrow, c);
		}
		for (BountyInfo b : bounties)
		{
			bountyById.put(b.id, b);
			bountyByDbrow.put(b.dbrow, b);
		}
		catalog = new Catalog(courierById, courierByDbrow, bountyById, bountyByDbrow);
		catalogLoaded = !courierById.isEmpty() || !bountyById.isEmpty();
		refreshTab();
	}

	/** Module-local changes (catalog, board) bypass AccountState — poke
	 *  the tab directly (the hunter-module idiom; these are rare events). */
	private void refreshTab()
	{
		if (tab != null)
		{
			javax.swing.SwingUtilities.invokeLater(tab::rebuild);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN && !catalogLoaded)
		{
			loadCatalog();
		}
	}

	// ── accepted slots (stateless off the watched varbits) ────────────

	public List<ActiveTask> activeTasks()
	{
		List<ActiveTask> out = new ArrayList<>();
		for (int slot = 0; slot < 5; slot++)
		{
			int id = state.getVarbit(SLOT_ID[slot]);
			if (id == 0)
			{
				continue;
			}
			ActiveTask task = new ActiveTask();
			task.slot = slot;
			task.taskId = id;
			Catalog cat = catalog;
			task.courier = cat.courierById.get(id);
			task.bounty = task.courier == null ? cat.bountyById.get(id) : null;
			task.taken = state.getVarbit(SLOT_TAKEN[slot]);
			task.delivered = state.getVarbit(SLOT_DELIVERED[slot]);
			if (task.bounty != null)
			{
				// the COUNT varbit stores items REMAINING
				int remaining = state.getVarbit(SLOT_COUNT[slot]);
				task.collected = Math.max(0, Math.min(task.bounty.qty,
					task.bounty.qty - remaining));
			}
			out.add(task);
		}
		return out;
	}

	int completedToday()
	{
		return state.getVarbit(VarbitID.PORT_TASKS_COMPLETED_TODAY);
	}

	int freeSlots()
	{
		int free = 0;
		for (int slot = 0; slot < 5; slot++)
		{
			if (state.getVarbit(SLOT_ID[slot]) == 0)
			{
				free++;
			}
		}
		return free;
	}

	// ── noticeboard scan + advisor ────────────────────────────────────

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != InterfaceID.PORT_TASK_BOARD || clientThread == null)
		{
			return;
		}
		clientThread.invokeLater(this::scanBoard);
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.PORT_TASK_BOARD)
		{
			boardOpen = false; // lastOffers stay for the tab
		}
	}

	private void scanBoard()
	{
		if (client == null)
		{
			return;
		}
		Widget container = client.getWidget(InterfaceID.PortTaskBoard.CONTAINER);
		if (container == null)
		{
			return;
		}
		List<Integer> offers = new ArrayList<>();
		Widget[] children = container.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				// the widget op-listener's 4th arg is the offer's DBRow
				Object[] ops = child.getOnOpListener();
				if (ops != null && ops.length >= 4 && ops[3] instanceof Integer)
				{
					offers.add((Integer) ops[3]);
				}
			}
		}
		if (!catalogLoaded)
		{
			readCatalog(); // already on the client thread
		}
		setBoard(offers, true);
	}

	/** Test seam / board commit. */
	void setBoard(List<Integer> offerDbrows, boolean open)
	{
		lastOffers = List.copyOf(offerDbrows);
		boardOpen = open;
		refreshTab();
	}

	boolean boardOpen()
	{
		return boardOpen;
	}

	List<Integer> lastOffers()
	{
		return lastOffers;
	}

	/** The active courier slots as planner jobs (pickups still owed keep
	 *  their cargo-port stop). */
	List<PortTaskPlanner.Job> activeJobs()
	{
		List<PortTaskPlanner.Job> jobs = new ArrayList<>();
		for (ActiveTask task : activeTasks())
		{
			CourierInfo c = task.courier;
			if (c == null || task.delivered >= c.cargoAmount)
			{
				continue;
			}
			jobs.add(new PortTaskPlanner.Job(
				task.taken >= c.cargoAmount ? -1 : c.cargoPort, c.deliverPort));
		}
		return jobs;
	}

	/**
	 * Rank the last-seen board's offers: couriers by Sailing XP per tile
	 * added to the optimal tour over your active tasks (best first,
	 * level-gated offers sunk), then bounties by XP (their kill time is
	 * unknowable — never scored as travel).
	 */
	List<Advice> rankOffers()
	{
		if (pack == null || lastOffers.isEmpty())
		{
			return List.of();
		}
		int sailing = state.getRealLevel(Skill.SAILING);
		int from = boardPortOf(lastOffers);
		List<PortTaskPlanner.Job> active = activeJobs();
		double baseTour = from == 0 ? Double.NaN
			: PortTaskPlanner.tourDistance(pack, from, active);
		java.util.Set<Integer> heldIds = new java.util.HashSet<>();
		for (ActiveTask task : activeTasks())
		{
			heldIds.add(task.taskId);
		}
		Catalog cat = catalog;
		List<Advice> couriers = new ArrayList<>();
		List<Advice> bounties = new ArrayList<>();
		for (int dbrow : lastOffers)
		{
			CourierInfo c = cat.courierByDbrow.get(dbrow);
			BountyInfo b = c == null ? cat.bountyByDbrow.get(dbrow) : null;
			PortTasksPack.Reward reward = pack.reward(dbrow);
			Advice advice = new Advice();
			advice.dbrow = dbrow;
			if (c != null)
			{
				advice.courier = c;
				advice.label = c.name != null ? c.name
					: reward != null ? reward.label : "Task #" + dbrow;
				advice.xp = c.xp;
				advice.levelGated = sailing < c.level;
				advice.alreadyTaken = heldIds.contains(c.id);
				advice.marginalTiles = from == 0 ? Double.NaN
					: PortTaskPlanner.marginalDistance(pack, from, active, baseTour,
						new PortTaskPlanner.Job(c.cargoPort, c.deliverPort));
				advice.score = advice.xp > 0 && advice.marginalTiles > 0
					? advice.xp / advice.marginalTiles
					: advice.xp > 0 && advice.marginalTiles == 0 ? Double.MAX_VALUE : 0;
				couriers.add(advice);
			}
			else if (b != null)
			{
				advice.bounty = b;
				advice.label = b.name != null ? b.name
					: reward != null ? reward.label : "Task #" + dbrow;
				advice.xp = b.xp;
				advice.levelGated = sailing < b.level;
				advice.alreadyTaken = heldIds.contains(b.id);
				advice.marginalTiles = Double.NaN;
				advice.score = 0;
				bounties.add(advice);
			}
			// unknown dbrow (catalog not loaded): skip — never invent
		}
		// held offers sink (you can't take them again), then level gates,
		// then value
		couriers.sort(Comparator
			.comparing((Advice a) -> a.alreadyTaken)
			.thenComparing(a -> a.levelGated)
			.thenComparing(a -> -a.score)
			.thenComparing(a -> -a.xp));
		bounties.sort(Comparator
			.comparing((Advice a) -> a.alreadyTaken)
			.thenComparing(a -> a.levelGated)
			.thenComparing(a -> -a.xp));
		List<Advice> out = new ArrayList<>(couriers);
		out.addAll(bounties);
		return out;
	}

	/**
	 * Cheap identity of everything rankOffers reads — board offers, slot
	 * varbits, Sailing level. The board overlay recomputes the Held-Karp
	 * ranking only when this changes (2026-07-20 audit: it ran the full
	 * exact-tour ranking per rendered frame while the board was open).
	 */
	Object adviceKey()
	{
		List<Object> key = new ArrayList<>();
		key.add(lastOffers);
		key.add(state.getRealLevel(Skill.SAILING));
		for (int slot = 0; slot < SLOT_ID.length; slot++)
		{
			key.add(state.getVarbit(SLOT_ID[slot]));
			key.add(state.getVarbit(SLOT_TAKEN[slot]));
			key.add(state.getVarbit(SLOT_DELIVERED[slot]));
			key.add(state.getVarbit(SLOT_COUNT[slot]));
		}
		return key;
	}

	/** The port a set of offers is posted at (their shared board port). */
	private int boardPortOf(List<Integer> offerDbrows)
	{
		Catalog cat = catalog;
		for (int dbrow : offerDbrows)
		{
			CourierInfo c = cat.courierByDbrow.get(dbrow);
			if (c != null)
			{
				return c.boardPort;
			}
			BountyInfo b = cat.bountyByDbrow.get(dbrow);
			if (b != null)
			{
				return b.port;
			}
		}
		return 0;
	}

	// ── port suggestions (feature: best boards for your level) ────────

	/** One suggested noticeboard port. */
	public static final class PortSuggestion
	{
		public PortTasksPack.Port port;
		public boolean unlocked;
		public boolean preferred;
		public double bestScore;   // best xp/tile among its doable tasks; 0 = none known
		public String bestLabel;
	}

	/**
	 * Every noticeboard port, preferred first, then by the best courier
	 * task (xp per tile of board->cargo->delivery) it can offer at your
	 * level. Scores need the runtime catalog; without it the ports still
	 * list with their level gates.
	 */
	List<PortSuggestion> portSuggestions()
	{
		if (pack == null)
		{
			return List.of();
		}
		int sailing = state.getRealLevel(Skill.SAILING);
		java.util.Set<Integer> preferred = state.getPreferredPorts();
		List<PortSuggestion> out = new ArrayList<>();
		for (PortTasksPack.Port port : pack.ports)
		{
			if (!port.board)
			{
				continue;
			}
			PortSuggestion s = new PortSuggestion();
			s.port = port;
			s.unlocked = port.level == null || sailing >= port.level;
			s.preferred = preferred.contains(port.dbrow);
			for (CourierInfo c : catalog.courierByDbrow.values())
			{
				if (c.boardPort != port.dbrow || sailing < c.level || c.xp <= 0)
				{
					continue;
				}
				double tiles = pack.distance(port.dbrow, c.cargoPort)
					+ pack.distance(c.cargoPort, c.deliverPort);
				if (Double.isNaN(tiles) || tiles <= 0)
				{
					continue;
				}
				double score = c.xp / tiles;
				if (score > s.bestScore)
				{
					s.bestScore = score;
					s.bestLabel = c.name;
				}
			}
			out.add(s);
		}
		out.sort(Comparator
			.comparing((PortSuggestion s) -> !s.preferred)
			.thenComparing(s -> !s.unlocked)
			.thenComparing(s -> -s.bestScore));
		return out;
	}

	void togglePreferred(int portDbrow)
	{
		state.togglePreferredPort(portDbrow);
	}
}
