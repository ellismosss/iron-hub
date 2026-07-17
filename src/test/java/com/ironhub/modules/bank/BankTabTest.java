package com.ironhub.modules.bank;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BankTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void searchMatching()
	{
		assertTrue(BankTab.matches("Abyssal whip", ""));
		assertTrue(BankTab.matches("Abyssal whip", "whip"));
		assertTrue(BankTab.matches("Abyssal whip", "  ABYSS "));
		assertFalse(BankTab.matches("Abyssal whip", "ranarr"));
	}

	/** Nothing lists until the player types or filters (Luke, 2026-07-17). */
	@Test
	public void idleBankListsNothing() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(4151, 1, 995, 2_500_000));
		StateFixture.itemNames(state, Map.of(4151, "Abyssal whip", 995, "Coins"));
		BankTab tab = new BankTab(state, null, null, null, new java.util.HashSet<>(), null, new com.ironhub.data.DataPack(
			new com.google.gson.Gson()).load("banked-xp", com.ironhub.data.BankedXpPack.class),
			true, v -> {}, com.ironhub.ui.osrs.OsrsTheme.STONE);
		assertFalse("no rows may render before a search or filter",
			componentTexts(tab).stream().anyMatch(t -> t.contains("Abyssal whip")));
		assertTrue("the idle state must say how to start",
			componentTexts(tab).stream().anyMatch(t -> t.contains("Type to search")));
	}

	/** The dropdown's stat indices map to the right equipment fields. */
	@Test
	public void statExtraction()
	{
		net.runelite.client.game.ItemEquipmentStats stats =
			net.runelite.client.game.ItemEquipmentStats.builder()
				.astab(1).aslash(2).acrush(3).amagic(4).arange(5)
				.str(6).rstr(7).mdmg(8f).prayer(9)
				.dstab(10).dslash(11).dcrush(12).dmagic(13).drange(14)
				.build();
		for (int stat = 1; stat <= 14; stat++)
		{
			assertEquals(stat, BankTab.statOf(stats, stat));
		}
	}

	private static java.util.List<String> componentTexts(java.awt.Container root)
	{
		java.util.List<String> texts = new java.util.ArrayList<>();
		for (java.awt.Component child : root.getComponents())
		{
			if (child instanceof com.ironhub.ui.osrs.OsrsLabel)
			{
				texts.add(((com.ironhub.ui.osrs.OsrsLabel) child).text());
			}
			if (child instanceof java.awt.Container)
			{
				texts.addAll(componentTexts((java.awt.Container) child));
			}
		}
		return texts;
	}

	/** The Banked Experience interface: header maths + the gilded altar
	 *  modifier reshaping Prayer totals (3.5x, as the upstream states). */
	@Test
	public void skillViewShowsBankedLevelsAndModifiers() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, net.runelite.api.Skill.PRAYER, 43, 50_339);
		StateFixture.bank(state, Map.of(536, 1_000)); // dragon bones
		StateFixture.itemNames(state, Map.of(536, "Dragon bones"));
		BankTab tab = new BankTab(state, null, null, null, new java.util.HashSet<>(), null,
			new com.ironhub.data.DataPack(new com.google.gson.Gson())
				.load("banked-xp", com.ironhub.data.BankedXpPack.class),
			true, v -> {}, com.ironhub.ui.osrs.OsrsTheme.STONE);
		tab.showSkillView(net.runelite.api.Skill.PRAYER);

		// 1,000 dragon bones at base 72 = 72,000 banked xp
		String base = net.runelite.client.util.QuantityFormatter.quantityToRSDecimalStack(72_000, true);
		assertTrue("base banked xp missing",
			componentTexts(tab).stream().anyMatch(t -> t.contains("Banked: " + base + " xp")));
		tab.toggleModifier("Lit Gilded Altar (350% xp)");
		// x3.5 = 252,000
		String altar = net.runelite.client.util.QuantityFormatter.quantityToRSDecimalStack(252_000, true);
		assertTrue("gilded altar must reshape the total",
			componentTexts(tab).stream().anyMatch(t -> t.contains("Banked: " + altar + " xp")));

		java.awt.image.BufferedImage image = com.ironhub.ui.SwingRender.render(tab);
		java.io.File out = new java.io.File("build/reports/bank-skill-view.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		tab.dispose();
	}

	@Test
	public void relativeTimeBuckets()
	{
		assertEquals("just now", BankTab.relativeTime(30_000));
		assertEquals("5 min ago", BankTab.relativeTime(5 * 60_000));
		assertEquals("2 h ago", BankTab.relativeTime(2 * 3_600_000));
		assertEquals("3 d ago", BankTab.relativeTime(3 * 24 * 3_600_000L));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(4151, 1, 995, 2_500_000, 207, 143, 536, 120));
		StateFixture.itemNames(state, Map.of(
			4151, "Abyssal whip", 995, "Coins", 207, "Grimy ranarr weed", 536, "Dragon bones"));

		BankTrackerModule module = new BankTrackerModule(state, null, null,
			new net.runelite.client.game.SkillIconManager(), null, null, null, null, null, null, new IronHubConfig()
		{
		}, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null);
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/bank-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
