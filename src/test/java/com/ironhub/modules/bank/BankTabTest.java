package com.ironhub.modules.bank;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
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

	private static BankTab newTab(AccountState state)
	{
		com.ironhub.data.DataPack dataPack = new com.ironhub.data.DataPack(new com.google.gson.Gson());
		return new BankTab(state, null, null, null, new java.util.HashSet<>(), null,
			dataPack.load("banked-xp", com.ironhub.data.BankedXpPack.class),
			dataPack.load("xp-actions", com.ironhub.data.XpActionsPack.class),
			true, v -> {}, com.ironhub.ui.osrs.OsrsTheme.STONE);
	}

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
		BankTab tab = newTab(state);
		assertFalse("no rows may render before a search or filter",
			componentTexts(tab).stream().anyMatch(t -> t.contains("Abyssal whip")));
		assertTrue("the idle state must say how to start",
			componentTexts(tab).stream().anyMatch(t -> t.contains("Type to search")));
		tab.dispose();
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

	/** The strip's stat groups take each item's best bonus in the group. */
	@Test
	public void statGroupExtraction()
	{
		net.runelite.client.game.ItemEquipmentStats stats =
			net.runelite.client.game.ItemEquipmentStats.builder()
				.astab(1).aslash(2).acrush(3).amagic(4).arange(5)
				.str(6).rstr(7).mdmg(8.6f).prayer(9)
				.dstab(10).dslash(11).dcrush(12).dmagic(13).drange(14)
				.build();
		assertEquals(3, BankTab.groupValue(stats, BankTab.StatGroup.ATTACK));
		assertEquals(6, BankTab.groupValue(stats, BankTab.StatGroup.STRENGTH));
		assertEquals(14, BankTab.groupValue(stats, BankTab.StatGroup.DEFENCE));
		assertEquals(7, BankTab.groupValue(stats, BankTab.StatGroup.RANGED));
		assertEquals(9, BankTab.groupValue(stats, BankTab.StatGroup.MAGIC)); // round(8.6)
	}

	/** Stack sorts by HA x quantity; Each by the per-item price alone. */
	@Test
	public void alchSortAndFigure()
	{
		Map<Integer, Long> prices = Map.of(1, 100L, 2, 10L);
		Map<Integer, Integer> bank = Map.of(1, 1, 2, 1_000);
		List<Integer> byStack = new java.util.ArrayList<>(List.of(1, 2));
		byStack.sort(BankTab.alchComparator(prices, bank, false));
		assertEquals("total stack value ranks first by default", List.of(2, 1), byStack);
		List<Integer> byEach = new java.util.ArrayList<>(List.of(1, 2));
		byEach.sort(BankTab.alchComparator(prices, bank, true));
		assertEquals("Each ranks by per-item value", List.of(1, 2), byEach);

		assertEquals("941K/192 gp/ea", BankTab.alchFigure(941_000, 192));
		assertEquals("1.2M/72.5K gp/ea", BankTab.alchFigure(1_200_000, 72_500));
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
		BankTab tab = newTab(state);
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

	/** The visible Secondaries section aggregates chosen entries' needs:
	 *  100 guam leaf as Guam tar want 1,500 swamp tar against 200 owned. */
	@Test
	public void skillViewAggregatesSecondaries() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, net.runelite.api.Skill.HERBLORE, 19,
			net.runelite.api.Experience.getXpForLevel(19));
		StateFixture.bank(state, Map.of(249, 100, 1939, 200));
		StateFixture.itemNames(state, Map.of(249, "Guam leaf", 1939, "Swamp tar"));
		BankTab tab = newTab(state);
		tab.showSkillView(net.runelite.api.Skill.HERBLORE);

		List<String> texts = componentTexts(tab);
		assertTrue("Secondaries header missing: " + texts,
			texts.stream().anyMatch(t -> t.equals("Secondaries")));
		assertTrue("aggregated have/need missing: " + texts,
			texts.stream().anyMatch(t -> t.equals("200/1,500")));
		tab.dispose();
	}

	/** The persisted target level: meter + reach line from the REAL xp
	 *  table (level 61 needs 302,288 xp, so 122,339 banked reaches 51). */
	@Test
	public void targetLevelProgress() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, net.runelite.api.Skill.PRAYER, 43, 50_339);
		StateFixture.bank(state, Map.of(536, 1_000)); // 72,000 banked prayer xp
		StateFixture.itemNames(state, Map.of(536, "Dragon bones"));
		state.setBankSkillTarget("Prayer", 61);
		assertEquals(61, state.getBankSkillTarget("Prayer"));
		BankTab tab = newTab(state);
		tab.showSkillView(net.runelite.api.Skill.PRAYER);

		int reaches = net.runelite.api.Experience.getLevelForXp(50_339 + 72_000);
		assertEquals("xp-table sanity: 122,339 xp is level 51", 51, reaches);
		assertTrue("reach line missing",
			componentTexts(tab).stream().anyMatch(
				t -> t.contains("Banked reaches level " + reaches + " of target 61")));
		tab.dispose();
	}

	/** The essence ids in code must be the wiki-named items — pinned against
	 *  the LEGACY-constant name index (an independent source from gameval). */
	@Test
	public void essenceIdsMatchTheNameIndex()
	{
		com.ironhub.data.ItemNameIndex names =
			new com.ironhub.data.ItemNameIndex(new com.google.gson.Gson());
		assertEquals((Integer) BankTab.PURE_ESSENCE, names.idOf("Pure essence"));
		assertEquals((Integer) BankTab.RUNE_ESSENCE, names.idOf("Rune essence"));
		assertEquals((Integer) BankTab.DAEYALT_ESSENCE, names.idOf("Daeyalt essence"));
	}

	/** The Runecrafting view: essence counts, the best method the level
	 *  allows, banked xp = pure x method xp with Daeyalt as its own line. */
	@Test
	public void runecraftViewSmoke() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, net.runelite.api.Skill.RUNECRAFT, 44,
			net.runelite.api.Experience.getXpForLevel(44));
		StateFixture.bank(state, Map.of(
			BankTab.PURE_ESSENCE, 10_000, BankTab.DAEYALT_ESSENCE, 1_000));
		StateFixture.itemNames(state, Map.of(
			BankTab.PURE_ESSENCE, "Pure essence", BankTab.DAEYALT_ESSENCE, "Daeyalt essence"));
		state.setBankSkillTarget("Runecraft", 50);
		BankTab tab = newTab(state);
		tab.showRunecraftView();

		List<String> texts = componentTexts(tab);
		assertTrue("pure essence count missing",
			texts.stream().anyMatch(t -> t.contains("Pure essence")));
		assertTrue("absent rune essence must show as 0, never invented",
			texts.stream().anyMatch(t -> t.contains("Rune essence")));
		// best Regular method at 44 = Nature rune, 9 xp: 10,000 x 9 = 90,000
		assertTrue("banked rc xp missing: " + texts,
			texts.stream().anyMatch(t -> t.contains("Banked: 90K xp")));
		// daeyalt twin: 1,000 x 13.5 = 13,500 on its own line
		assertTrue("daeyalt line missing: " + texts,
			texts.stream().anyMatch(t -> t.contains("Daeyalt: 13.5K xp")));
		assertTrue("target reach line missing",
			texts.stream().anyMatch(t -> t.contains("of target 50")));

		java.awt.image.BufferedImage image = com.ironhub.ui.SwingRender.render(tab);
		java.io.File out = new java.io.File("build/reports/bank-rc-view.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		tab.dispose();
	}

	@Test
	public void relativeTimeBuckets()
	{
		assertEquals("just now", com.ironhub.ui.Format.relativeTime(30_000));
		assertEquals("5 min ago", com.ironhub.ui.Format.relativeTime(5 * 60_000));
		assertEquals("2 h ago", com.ironhub.ui.Format.relativeTime(2 * 3_600_000));
		assertEquals("3 d ago", com.ironhub.ui.Format.relativeTime(3 * 24 * 3_600_000L));
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
