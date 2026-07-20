package com.ironhub.modules.porttasks;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.PortTasksPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PortTasksModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private final PortTasksPack pack =
		new DataPack(new Gson()).load("port-tasks", PortTasksPack.class);

	private PortTasksModule module(AccountState state)
	{
		return new PortTasksModule(state, config, new DataPack(new Gson()),
			new EventBus(), null, null, null, null);
	}

	// ── pack integrity ────────────────────────────────────────────────

	@Test
	public void packIntegrity()
	{
		assertEquals(30, pack.ports.size());
		assertEquals(271, pack.rewards.size());
		assertEquals(163, pack.routes.size());
		assertEquals(211, pack.rewards.stream().filter(r -> r.type.equals("courier")).count());
		assertEquals(60, pack.rewards.stream().filter(r -> r.type.equals("bounty")).count());

		Set<Integer> dbrows = new HashSet<>();
		for (PortTasksPack.Port port : pack.ports)
		{
			assertTrue("duplicate port dbrow " + port.dbrow, dbrows.add(port.dbrow));
			assertFalse(port.name.isEmpty());
		}
		for (PortTasksPack.Route route : pack.routes)
		{
			assertTrue(dbrows.contains(route.a));
			assertTrue(dbrows.contains(route.b));
			assertTrue(route.distance > 0);
		}
		// anchors from the reference tables
		assertEquals("Port Sarim", pack.port(8587).name);
		assertEquals(155, pack.reward(8665).xp);
		assertEquals("bounty", pack.reward(13311).type);
		// the reference's own computeDistance, byte-faithful
		assertEquals(194.4, pack.distance(8593, 8595), 0.05);
		assertEquals(194.4, pack.distance(8595, 8593), 0.05); // reversed reuse
		// every port reaches every other through the route graph
		for (PortTasksPack.Port a : pack.ports)
		{
			for (PortTasksPack.Port b : pack.ports)
			{
				assertFalse(a.name + " cannot reach " + b.name,
					Double.isNaN(pack.distance(a.dbrow, b.dbrow)));
			}
		}
	}

	// ── planner math (synthetic pack: exact numbers) ──────────────────

	private static PortTasksPack miniPack()
	{
		PortTasksPack mini = new PortTasksPack();
		mini.ports = new ArrayList<>();
		for (int dbrow = 1; dbrow <= 4; dbrow++)
		{
			PortTasksPack.Port port = new PortTasksPack.Port();
			port.dbrow = dbrow;
			port.name = String.valueOf((char) ('A' + dbrow - 1));
			port.board = true;
			mini.ports.add(port);
		}
		mini.routes = new ArrayList<>();
		mini.routes.add(route(1, 2, 100));
		mini.routes.add(route(2, 3, 100));
		mini.routes.add(route(1, 3, 250)); // the chain via B is shorter
		mini.routes.add(route(3, 4, 100));
		mini.rewards = List.of();
		return mini;
	}

	private static PortTasksPack.Route route(int a, int b, double distance)
	{
		PortTasksPack.Route route = new PortTasksPack.Route();
		route.a = a;
		route.b = b;
		route.distance = distance;
		return route;
	}

	@Test
	public void distancesChainThroughTheRouteGraph()
	{
		PortTasksPack mini = miniPack();
		assertEquals(0, mini.distance(1, 1), 0.01);
		assertEquals(100, mini.distance(1, 2), 0.01);
		// Dijkstra prefers the 200 chain over the 250 direct route
		assertEquals(200, mini.distance(1, 3), 0.01);
		// no direct A-D route: chains A-B-C-D
		assertEquals(300, mini.distance(1, 4), 0.01);
		assertTrue(Double.isNaN(mini.distance(1, 99)));
	}

	@Test
	public void tourRespectsPickupBeforeDelivery()
	{
		PortTasksPack mini = miniPack();
		assertEquals(0, PortTaskPlanner.tourDistance(mini, 1, List.of()), 0.01);
		// pick up at B, deliver at C, starting at A
		assertEquals(200, PortTaskPlanner.tourDistance(mini, 1,
			List.of(new PortTaskPlanner.Job(2, 3))), 0.01);
		// two jobs interleave optimally: B pickup, C deliver, D visit
		assertEquals(300, PortTaskPlanner.tourDistance(mini, 1, List.of(
			new PortTaskPlanner.Job(2, 3),
			new PortTaskPlanner.Job(-1, 4))), 0.01);
		// pickup at C but delivery back at B forces the backtrack
		assertEquals(300, PortTaskPlanner.tourDistance(mini, 1,
			List.of(new PortTaskPlanner.Job(3, 2))), 0.01);
	}

	@Test
	public void marginalDistanceRewardsOnTheWayOffers()
	{
		PortTasksPack mini = miniPack();
		List<PortTaskPlanner.Job> active = List.of(new PortTaskPlanner.Job(2, 3));
		// an identical job rides along free
		assertEquals(0, PortTaskPlanner.marginalDistance(mini, 1, active,
			new PortTaskPlanner.Job(2, 3)), 0.01);
		// C-to-D extends the tour by one leg
		assertEquals(100, PortTaskPlanner.marginalDistance(mini, 1, active,
			new PortTaskPlanner.Job(3, 4)), 0.01);
	}

	// ── slot detection off the game's varbits ─────────────────────────

	private PortTasksModule.CourierInfo courier(int id, int dbrow, int level,
		int board, int cargo, int deliver, int amount, int xp, String name)
	{
		PortTasksModule.CourierInfo c = new PortTasksModule.CourierInfo();
		c.id = id;
		c.dbrow = dbrow;
		c.level = level;
		c.boardPort = board;
		c.cargoPort = cargo;
		c.deliverPort = deliver;
		c.cargoAmount = amount;
		c.xp = xp;
		c.name = name;
		return c;
	}

	private PortTasksModule.BountyInfo bounty(int id, int dbrow, int level,
		int port, int qty, int xp, String name)
	{
		PortTasksModule.BountyInfo b = new PortTasksModule.BountyInfo();
		b.id = id;
		b.dbrow = dbrow;
		b.level = level;
		b.port = port;
		b.qty = qty;
		b.rarity = 50;
		b.xp = xp;
		b.name = name;
		return b;
	}

	@Test
	public void slotsDeriveStatelesslyFromVarbits()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		PortTasksModule module = module(state);
		module.startUp();
		module.setCatalog(
			List.of(courier(101, 8665, 1, 8587, 8593, 8595, 3, 155, "Spice run")),
			List.of(bounty(201, 9108, 10, 8588, 5, 3465, "Bull shark cull")));

		assertTrue(module.activeTasks().isEmpty());
		assertEquals(5, module.freeSlots());

		StateFixture.varbit(state, PortTasksModule.SLOT_ID[0], 101);
		StateFixture.varbit(state, PortTasksModule.SLOT_TAKEN[0], 2);
		StateFixture.varbit(state, PortTasksModule.SLOT_ID[1], 201);
		StateFixture.varbit(state, PortTasksModule.SLOT_COUNT[1], 3); // 3 REMAINING
		StateFixture.varbit(state, PortTasksModule.SLOT_ID[2], 999); // not in catalog

		List<PortTasksModule.ActiveTask> tasks = module.activeTasks();
		assertEquals(3, tasks.size());
		assertEquals("Spice run", tasks.get(0).courier.name);
		assertEquals(2, tasks.get(0).taken);
		// the COUNT varbit stores remaining: collected = 5 - 3
		assertEquals(2, tasks.get(1).collected);
		// unknown id stays honest — no invented name
		assertEquals(999, tasks.get(2).taskId);
		assertEquals(2, module.freeSlots());

		// replaying the same varbits (login replay) never duplicates
		StateFixture.varbit(state, PortTasksModule.SLOT_ID[0], 101);
		assertEquals(3, module.activeTasks().size());
		module.shutDown();
	}

	// ── the noticeboard advisor ───────────────────────────────────────

	@Test
	public void advisorRanksByXpPerMarginalTile()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.SAILING, 30, 14_000);
		PortTasksModule module = module(state);
		module.startUp();
		// board at Port Sarim (8587): a short hop to Pandemonium (8588),
		// a long haul to Catherby->Brimhaven, a level-gated run, a bounty
		module.setCatalog(List.of(
			courier(101, 8665, 1, 8587, 8587, 8588, 3, 155, "Short hop"),
			courier(102, 8664, 1, 8587, 8593, 8595, 3, 160, "Long haul"),
			courier(103, 8666, 90, 8587, 8587, 8588, 3, 9999, "Gated run")),
			List.of(bounty(201, 9108, 10, 8587, 5, 3465, "Shark cull")));
		module.setBoard(List.of(8664, 8665, 8666, 9108), true);

		List<PortTasksModule.Advice> ranked = module.rankOffers();
		assertEquals(4, ranked.size());
		// short hop beats the long haul on xp/tile despite lower xp
		assertEquals("Short hop", ranked.get(0).label);
		assertEquals("Long haul", ranked.get(1).label);
		// the level-gated courier sinks below doable ones
		assertEquals("Gated run", ranked.get(2).label);
		assertTrue(ranked.get(2).levelGated);
		// bounties last — their kill time is never scored as travel
		assertEquals("Shark cull", ranked.get(3).label);
		assertTrue(Double.isNaN(ranked.get(3).marginalTiles));
		assertTrue(ranked.get(0).marginalTiles > 0);

		// an offer already sitting in a slot can't be taken again — it
		// sinks and flags, instead of topping the list at +0 tiles
		StateFixture.varbit(state, PortTasksModule.SLOT_ID[0], 101);
		ranked = module.rankOffers();
		assertEquals("Long haul", ranked.get(0).label);
		PortTasksModule.Advice held = ranked.stream()
			.filter(a -> "Short hop".equals(a.label)).findFirst().orElseThrow();
		assertTrue(held.alreadyTaken);
		module.shutDown();
	}

	@Test
	public void portSuggestionsFloatPreferredAndScoreDoableTasks()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.SAILING, 25, 8_000);
		PortTasksModule module = module(state);
		module.startUp();
		module.setCatalog(List.of(
			courier(101, 8665, 1, 8587, 8587, 8588, 3, 155, "Sarim spice")),
			List.of());

		List<PortTasksModule.PortSuggestion> ports = module.portSuggestions();
		// 23 ports carry noticeboards (7 of the 30 have none)
		assertEquals(23, ports.size());
		// with nothing preferred, the only scored port ranks first
		assertEquals("Port Sarim", ports.get(0).port.name);
		assertEquals("Sarim spice", ports.get(0).bestLabel);
		// locked ports sink below unlocked ones
		PortTasksModule.PortSuggestion last = ports.get(ports.size() - 1);
		assertFalse(last.unlocked);

		module.togglePreferred(8595); // Brimhaven preferred
		ports = module.portSuggestions();
		assertEquals("Brimhaven", ports.get(0).port.name);
		assertTrue(ports.get(0).preferred);
		assertTrue(state.getPreferredPorts().contains(8595));
		module.shutDown();
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, Skill.SAILING, 30, 14_000);
		PortTasksModule module = module(state);
		module.startUp();
		PortTasksTab tab = (PortTasksTab) module.buildTab();
		assertNotNull(tab);
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			module.setCatalog(List.of(
				courier(101, 8665, 1, 8587, 8587, 8588, 3, 155, "Sarim spice run"),
				courier(102, 8664, 1, 8587, 8593, 8595, 3, 160, "Catherby long haul")),
				List.of(bounty(201, 9108, 10, 8588, 5, 3465, "Bull shark cull")));
			StateFixture.varbit(state, PortTasksModule.SLOT_ID[0], 101);
			StateFixture.varbit(state, PortTasksModule.SLOT_TAKEN[0], 2);
			StateFixture.varbit(state, PortTasksModule.SLOT_ID[1], 201);
			StateFixture.varbit(state, PortTasksModule.SLOT_COUNT[1], 3);
			StateFixture.varbit(state,
				net.runelite.api.gameval.VarbitID.PORT_TASKS_COMPLETED_TODAY, 4);
			module.setBoard(List.of(8664, 8665, 9108), false);
			module.togglePreferred(8587);
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain queued rebuilds
		BufferedImage image = SwingRender.render(tab);
		assertTrue("height " + image.getHeight(), image.getHeight() > 300);
		java.io.File out = new java.io.File("build/reports/port-tasks-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
