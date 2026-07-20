package com.ironhub.modules.hunter;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.HunterRumoursPack;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HunterRumoursModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private final HunterRumoursPack pack = new DataPack(new Gson()).load("hunter-rumours", HunterRumoursPack.class);

	private HunterRumoursModule module(AccountState state)
	{
		return new HunterRumoursModule(state, config, new DataPack(new Gson()),
			null, null, new EventBus(), null, null, null, null, null, null, null, null);
	}

	@Test
	public void packIntegrity()
	{
		assertEquals(6, pack.hunters.size());
		assertTrue(pack.rumours.size() >= 28);
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (HunterRumoursPack.Rumour rumour : pack.rumours)
		{
			assertTrue("duplicate rumour id " + rumour.id, ids.add(rumour.id));
			for (String req : rumour.reqs)
			{
				assertFalse(rumour.id + " req is manual: " + req,
					Requirements.isManual(Requirements.parse(req)));
			}
			assertTrue(rumour.id + " has neither xp drops nor a range",
				(rumour.xpDrops != null && !rumour.xpDrops.isEmpty())
					|| (rumour.xpRange != null && rumour.xpRange.size() == 2));
			assertTrue(rumour.id + " pity ordering", rumour.pityWithOutfit <= rumour.pity);
		}
		// pity interpolation anchors (butterfly net: 150 → 142)
		HunterRumoursPack.Rumour sapphire = pack.rumour("sapphire_glacialis");
		assertEquals(150, sapphire.pity);
		assertEquals(150, sapphire.pityFor(0));
		assertEquals(142, sapphire.pityFor(4));
		// herbiboar tracks via an xp RANGE
		assertTrue(pack.rumour("herbiboar").matchesCatchXp(2000));
		assertFalse(pack.rumour("herbiboar").matchesCatchXp(10));
	}

	@Test
	public void assignmentAndCatchCounting()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		module.startUp();

		HunterRumoursPack.Rumour swampLizard = pack.rumour("swamp_lizard");
		HunterRumoursPack.Hunter gilman = pack.hunterByNpcName("Guild Hunter Gilman");
		module.assignRumour(swampLizard, gilman);

		PersistedState.RumourRecord active = module.active();
		assertNotNull(active);
		assertEquals("swamp_lizard", active.rumourId);
		assertEquals("novice_gilman", active.hunterId);
		assertEquals(0, active.caught);

		// swamp lizard catch xp = 152 (from Creature.java)
		module.feedHunterXp(1000); // seeds the baseline
		module.feedHunterXp(1152); // +152 = a catch
		assertEquals(1, module.active().caught);
		module.feedHunterXp(1204); // +52 = not a lizard catch
		assertEquals(1, module.active().caught);
		module.feedHunterXp(1356); // +152 = a catch
		assertEquals(2, module.active().caught);

		// persistence round-trip
		assertEquals(2, state.getRumourRecords().get(0).caught);
		module.shutDown();
	}

	@Test
	public void pieceMessageStopsCounting()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		module.startUp();
		module.assignRumour(pack.rumour("swamp_lizard"),
			pack.hunterByNpcName("Guild Hunter Gilman"));
		module.feedHunterXp(1000);

		module.onChatMessage(gameMessage(
			"You find a rare piece of the creature! You should take it back to the Hunter Guild."));
		assertTrue(module.active().pieceFound);

		int before = module.active().caught;
		module.feedHunterXp(1152); // a catch after the piece — not counted
		assertEquals(before, module.active().caught);
		module.shutDown();
	}

	@Test
	public void assignmentChangeClosesTheOldRecord()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		module.startUp();

		module.assignRumour(pack.rumour("swamp_lizard"), null);
		module.assignRumour(pack.rumour("spined_larupia"), null);

		List<PersistedState.RumourRecord> records = module.records();
		assertEquals(2, records.size());
		assertTrue(records.get(0).end > 0);
		assertEquals("spined_larupia", records.get(1).rumourId);
		assertEquals(0, records.get(1).end);
		module.shutDown();
	}

	@Test
	public void profileSwitchDropsCachedRecords()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		module.startUp();
		module.assignRumour(pack.rumour("swamp_lizard"), null);
		assertEquals(1, module.records().size());

		// mid-session switch to profile B: the cached records must reload,
		// never push A's history into B's persistence (2026-07-20 audit)
		StateFixture.profile(state, 43L);
		assertTrue(module.records().isEmpty());
		assertTrue(state.getRumourRecords().isEmpty());
		module.shutDown();
	}

	@Test
	public void preferredLocationPersists()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		HunterRumoursPack.Rumour swampLizard = pack.rumour("swamp_lizard");
		List<HunterRumoursPack.Location> areas = pack.locationsFor("SWAMP_LIZARD");
		assertTrue(areas.size() >= 2);

		// default: the first area
		assertEquals(areas.get(0).name, module.preferredLocation(swampLizard).name);
		module.setPreferredLocation("SWAMP_LIZARD", areas.get(1).name);
		assertEquals(areas.get(1).name, module.preferredLocation(swampLizard).name);
		module.shutDown();
	}

	@Test
	public void outfitLowersPityRate()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		HunterRumoursModule module = module(state);
		assertEquals(0, module.outfitPieces());
		// two outfit pieces in the bank
		StateFixture.bank(state, Map.of(pack.outfit.get(0), 1, pack.outfit.get(1), 1));
		assertEquals(2, module.outfitPieces());
		module.shutDown();
	}

	@Test
	public void tabRendersBothViewsHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.stat(state, net.runelite.api.Skill.HUNTER, 80, 2_000_000);
		HunterRumoursModule module = module(state);
		module.startUp();
		HunterRumoursTab tab = (HunterRumoursTab) module.buildTab();
		assertNotNull(tab);

		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			module.assignRumour(pack.rumour("swamp_lizard"),
				pack.hunterByNpcName("Guild Hunter Gilman"));
			module.feedHunterXp(1000);
			module.feedHunterXp(1152); // one catch
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		BufferedImage rumour = SwingRender.render((JPanel) tab);
		assertTrue("rumour height " + rumour.getHeight(), rumour.getHeight() > 150);
		write(rumour, "hunter-rumours-tab.png");

		javax.swing.SwingUtilities.invokeAndWait(() -> tab.selectView(1));
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		BufferedImage history = SwingRender.render((JPanel) tab);
		assertTrue(history.getHeight() > 80);
		write(history, "hunter-rumours-history.png");
		module.shutDown();
	}

	@Test
	public void setupsShareTheLoadoutKeySpacePerTrap()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		module.startUp();

		// no rumour: no setup surface
		assertNull(module.rumourSetup());
		module.assignRumour(pack.rumour("swamp_lizard"), null);
		assertEquals("Hunter: Net trap", HunterRumoursModule.setupKey(pack.rumour("swamp_lizard")));
		assertNull(module.rumourSetup());
		module.saveRumourSetup();
		assertNotNull(module.rumourSetup());
		// the same trap's setup serves other net-trap rumours (Loadout key space)
		assertNotNull(state.savedSetup("Hunter: Net trap"));
		module.assignRumour(pack.rumour("orange_salamander"), null); // also net trap
		assertNotNull(module.rumourSetup());

		assertFalse(module.bankShowArmed());
		module.setBankShow(true);
		assertTrue(module.bankShowArmed());
		module.shutDown();
	}

	@Test
	public void fairyRingCodeFollowsThePreferredLocation()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		module.startUp();

		assertNull(module.fairyRingCode()); // no rumour
		module.assignRumour(pack.rumour("sapphire_glacialis"), null);
		// first area = Rellekka Hunter area, DKS
		assertEquals("DKS", module.fairyRingCode());
		module.setPreferredLocation("SAPPHIRE_GLACIALIS", "Farming Guild");
		assertEquals("CIR", module.fairyRingCode());
		module.shutDown();
	}

	@Test
	public void overlayRendersWithinBudget() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		HunterRumoursModule module = module(state);
		module.startUp();
		module.assignRumour(pack.rumour("swamp_lizard"),
			pack.hunterByNpcName("Guild Hunter Gilman"));
		module.feedHunterXp(1000);
		module.feedHunterXp(1152);

		HunterRumoursOverlay overlay = new HunterRumoursOverlay(module, config, null);
		BufferedImage canvas = new BufferedImage(300, 260, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = canvas.createGraphics();
		g.setColor(new java.awt.Color(58, 66, 48));
		g.fillRect(0, 0, 300, 260);
		g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		java.awt.Dimension size = overlay.render(g);
		g.dispose();
		assertNotNull(size);
		assertTrue("overlay width " + size.width, size.width <= 250);
		assertTrue("overlay height " + size.height, size.height <= 200);
		write(canvas, "hunter-rumours-overlay.png");

		// piece found flips the overlay to the return banner
		module.onChatMessage(gameMessage(
			"You find a rare piece of the creature! You should take it back to the Hunter Guild."));
		java.awt.Graphics2D g2 = canvas.createGraphics();
		g2.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		assertNotNull(overlay.render(g2));
		g2.dispose();
		module.shutDown();
	}

	private static net.runelite.api.events.ChatMessage gameMessage(String text)
	{
		return new net.runelite.api.events.ChatMessage(null,
			net.runelite.api.ChatMessageType.GAMEMESSAGE, "", text, "", 0);
	}

	private static void write(BufferedImage image, String name)
	{
		try
		{
			java.io.File out = new java.io.File("build/reports/" + name);
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (java.io.IOException ignored)
		{
		}
	}
}
