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
		return newTab(state, new java.util.HashSet<>());
	}

	private static BankTab newTab(AccountState state, java.util.Set<Integer> selection)
	{
		com.ironhub.data.DataPack dataPack = new com.ironhub.data.DataPack(new com.google.gson.Gson());
		return new BankTab(state, null, null, null, selection, null,
			dataPack.load("banked-xp", com.ironhub.data.BankedXpPack.class),
			dataPack.load("xp-actions", com.ironhub.data.XpActionsPack.class),
			com.ironhub.ui.osrs.OsrsTheme.STONE);
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

	/** Each (the default chip) ranks by per-item price; Stack by HA x qty.
	 *  A quantity-1 row shows only the per-item figure (Luke, 2026-07-17). */
	@Test
	public void alchSortAndFigure()
	{
		Map<Integer, Long> prices = Map.of(1, 100L, 2, 10L);
		Map<Integer, Integer> bank = Map.of(1, 1, 2, 1_000);
		List<Integer> byStack = new java.util.ArrayList<>(List.of(1, 2));
		byStack.sort(BankTab.alchComparator(prices, bank, false));
		assertEquals("Stack ranks by total stack value", List.of(2, 1), byStack);
		List<Integer> byEach = new java.util.ArrayList<>(List.of(1, 2));
		byEach.sort(BankTab.alchComparator(prices, bank, true));
		assertEquals("Each ranks by per-item value", List.of(1, 2), byEach);

		assertEquals("941K/192 gp/ea", BankTab.alchFigure(941_000, 192, 4_901));
		assertEquals("1.2M/72.5K gp/ea", BankTab.alchFigure(1_200_000, 72_500, 17));
		assertEquals("a single item has no stack figure",
			"192 gp/ea", BankTab.alchFigure(192, 192, 1));
	}

	/** The click grammar: plain = exclusive (again = deselect), cmd/ctrl =
	 *  toggle, shift = contiguous range from the last-clicked row. */
	@Test
	public void selectionClickGrammar()
	{
		List<Integer> order = List.of(10, 20, 30, 40);
		java.util.Set<Integer> sel = new java.util.HashSet<>();

		int last = BankTab.applyClick(sel, order, 1, false, false, -1);
		assertEquals(java.util.Set.of(20), sel);
		last = BankTab.applyClick(sel, order, 3, false, false, last);
		assertEquals("plain click clears other selections", java.util.Set.of(40), sel);
		last = BankTab.applyClick(sel, order, 3, false, false, last);
		assertTrue("plain click on the only-selected row deselects", sel.isEmpty());

		last = BankTab.applyClick(sel, order, 0, false, false, last);
		last = BankTab.applyClick(sel, order, 2, true, false, last);
		assertEquals("cmd/ctrl adds without clearing", java.util.Set.of(10, 30), sel);
		last = BankTab.applyClick(sel, order, 2, true, false, last);
		assertEquals("cmd/ctrl on a selected row removes it", java.util.Set.of(10), sel);

		last = BankTab.applyClick(sel, order, 0, false, false, last);
		last = BankTab.applyClick(sel, order, 2, false, true, last);
		assertEquals("shift selects the contiguous range",
			java.util.Set.of(10, 20, 30), sel);
		assertEquals(2, last);

		// shift with no valid anchor falls back to a plain exclusive select
		sel.clear();
		last = BankTab.applyClick(sel, order, 1, false, true, -1);
		assertEquals(java.util.Set.of(20), sel);
		assertEquals(1, last);
	}

	/** Exclusion memory: excluded at quantity 1 auto-returns once the
	 *  player owns more (and the auto-return clears the entry, so a fresh
	 *  exclusion at >1 is permanent); legacy set entries migrate as
	 *  excluded-at-many (permanent). */
	@Test
	public void alchExclusionMemory() throws Exception
	{
		// a pre-map profile on disk: the old Set<Integer> shape only
		java.io.File dir = new java.io.File(temp.getRoot(), "7");
		dir.mkdirs();
		java.nio.file.Files.write(new java.io.File(dir, "state.json").toPath(),
			"{\"alchExcluded\":[4151],\"alchExcludedAtQty\":{\"536\":1}}".getBytes(
				java.nio.charset.StandardCharsets.UTF_8));
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 7L);

		assertTrue("legacy exclusion is permanent", state.isAlchExcluded(4151, 500));
		assertTrue("qty-1 exclusion hides while still 1", state.isAlchExcluded(536, 1));
		assertFalse("qty-1 exclusion shows again at >1", state.isAlchExcluded(536, 2));

		// the auto-return pass REMOVES the outgrown entry...
		state.autoReturnAlchExclusions(Map.of(536, 3, 4151, 500));
		assertEquals(java.util.Set.of(4151), state.getAlchExcluded());
		// ...so excluding again at >1 sticks even when the stack grows
		state.setAlchExcluded(536, 3);
		assertTrue(state.isAlchExcluded(536, 3));
		assertTrue("re-exclusion at >1 is permanent", state.isAlchExcluded(536, 400));
		state.autoReturnAlchExclusions(Map.of(536, 400));
		assertTrue(state.getAlchExcluded().contains(536));

		// both shapes round-trip: a reload sees the same picture
		state.persistNow();
		AccountState reloaded = StateFixture.state(temp.getRoot());
		StateFixture.profile(reloaded, 7L);
		assertTrue(reloaded.isAlchExcluded(4151, 500));
		assertTrue(reloaded.isAlchExcluded(536, 400));
		reloaded.clearAlchExclusions();
		assertTrue(reloaded.getAlchExcluded().isEmpty());
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

	/** The Banked Experience interface: header format (bold name — level,
	 *  banked level in green), the orange Next-level line, and the gilded
	 *  altar modifier reshaping Prayer totals (3.5x, as the upstream states). */
	@Test
	public void skillViewShowsBankedLevelsAndModifiers() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, net.runelite.api.Skill.PRAYER, 43, 50_339);
		StateFixture.bank(state, Map.of(536, 1_000)); // dragon bones
		StateFixture.itemNames(state, Map.of(536, "Dragon bones"));
		BankTab tab = newTab(state);
		tab.showSkillView(net.runelite.api.Skill.PRAYER);

		// header: three labels — "Prayer" (bold) + " — 43" + " (51 banked)"
		// (50,339 + 72,000 = 122,339 xp = level 51)
		List<String> texts = componentTexts(tab);
		assertTrue("bold skill name missing: " + texts, texts.contains("Prayer"));
		assertTrue("level segment missing", texts.contains(" — 43"));
		assertTrue("banked level segment missing", texts.contains(" (51 banked)"));
		assertFalse("the old 'level banked' wording must be gone",
			texts.stream().anyMatch(t -> t.contains("level banked")));

		// 1,000 dragon bones at base 72 = 72,000 banked xp
		String base = net.runelite.client.util.QuantityFormatter.quantityToRSDecimalStack(72_000, true);
		assertTrue("base banked xp missing",
			texts.stream().anyMatch(t -> t.equals("Banked: " + base + " xp")));
		tab.toggleModifier("Lit Gilded Altar (350% xp)");
		// x3.5 = 252,000
		String altar = net.runelite.client.util.QuantityFormatter.quantityToRSDecimalStack(252_000, true);
		assertTrue("gilded altar must reshape the total",
			componentTexts(tab).stream().anyMatch(t -> t.equals("Banked: " + altar + " xp")));

		java.awt.image.BufferedImage image = com.ironhub.ui.SwingRender.render(tab);
		java.io.File out = new java.io.File("build/reports/bank-skill-view.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		tab.dispose();
	}

	/** Prayer altar consumers are mutually exclusive (upstream
	 *  compatibleWith, carried as the pack's exclusiveGroup): ticking
	 *  Ectofuntus unticks the gilded altar; Demonic Offering (ashes) is
	 *  independent and never disturbs the bone total. */
	@Test
	public void altarModifiersAreMutuallyExclusive() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, net.runelite.api.Skill.PRAYER, 43, 50_339);
		StateFixture.bank(state, Map.of(536, 1_000)); // dragon bones, base 72
		StateFixture.itemNames(state, Map.of(536, "Dragon bones"));
		BankTab tab = newTab(state);
		tab.showSkillView(net.runelite.api.Skill.PRAYER);

		tab.toggleModifier("Lit Gilded Altar (350% xp)");
		tab.toggleModifier("Ectofuntus (400% xp)");
		// 4x only — were both active the total would read 1M (72K x 14)
		assertTrue("Ectofuntus must untick the gilded altar",
			componentTexts(tab).stream().anyMatch(t -> t.equals("Banked: 288K xp")));
		tab.toggleModifier("Demonic Offering (300% xp)"); // ashes — compatible
		assertTrue("an ashes modifier must not untick a bone altar",
			componentTexts(tab).stream().anyMatch(t -> t.equals("Banked: 288K xp")));
		tab.dispose();
	}

	/** The visible Secondaries section aggregates chosen entries' needs:
	 *  100 guam leaf as Guam tar want 1,500 swamp tar against 200 owned.
	 *  Selecting the one item surfaces its activity detail card under the
	 *  results (the old value-click expansion was invisible — Luke). */
	@Test
	public void skillViewAggregatesSecondaries() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, net.runelite.api.Skill.HERBLORE, 19,
			net.runelite.api.Experience.getXpForLevel(19));
		StateFixture.bank(state, Map.of(249, 100, 1939, 200));
		StateFixture.itemNames(state, Map.of(249, "Guam leaf", 1939, "Swamp tar"));
		java.util.Set<Integer> selection = new java.util.HashSet<>();
		BankTab tab = newTab(state, selection);
		tab.showSkillView(net.runelite.api.Skill.HERBLORE);

		List<String> texts = componentTexts(tab);
		assertTrue("Secondaries header missing: " + texts,
			texts.stream().anyMatch(t -> t.equals("Secondaries")));
		assertTrue("aggregated have/need missing: " + texts,
			texts.stream().anyMatch(t -> t.equals("200/1,500")));
		assertFalse("no selection = no detail card",
			texts.stream().anyMatch(t -> t.contains(" xp each · ")));

		selection.add(249);
		tab.showSkillView(net.runelite.api.Skill.HERBLORE); // re-render
		texts = componentTexts(tab);
		assertTrue("single selection must surface the activity detail: " + texts,
			texts.stream().anyMatch(t -> t.contains(" xp each · ")));
		tab.dispose();
	}

	/** Selected items grow a stats card between the strip and the results.
	 *  Headless there is no client: name and count only, never invented
	 *  stats — and a count of 1 never renders (item 22). */
	@Test
	public void itemStatsCardRenders() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(536, 120, 4151, 1));
		StateFixture.itemNames(state, Map.of(536, "Dragon bones", 4151, "Abyssal whip"));
		java.util.Set<Integer> selection = new java.util.HashSet<>(List.of(536));
		BankTab tab = newTab(state, selection);

		List<String> texts = componentTexts(tab);
		assertTrue("card must name the selected item: " + texts,
			texts.contains("Dragon bones"));
		assertTrue("card shows the banked count", texts.contains("×120"));

		selection.add(4151); // whip, quantity 1
		tab.showRunecraftView(); // any rebuild path refreshes the card
		tab.showRunecraftView(); // back to search mode (toggle off)
		texts = componentTexts(tab);
		assertTrue(texts.contains("Abyssal whip"));
		assertFalse("x1 never renders", texts.stream().anyMatch(t -> t.equals("×1")));

		java.awt.image.BufferedImage image = com.ironhub.ui.SwingRender.render(tab);
		java.io.File out = new java.io.File("build/reports/bank-item-stats.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		tab.dispose();
	}

	/** The persisted target level: the meter is flanked by the current
	 *  level (left) and target (right), with the centered banked-xp line
	 *  beneath; the old "Banked reaches level…" line is gone. */
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

		List<String> texts = componentTexts(tab);
		assertTrue("current level must flank the meter", texts.contains("43"));
		assertTrue("target level must flank the meter", texts.contains("61"));
		assertTrue("centered banked total missing: " + texts, texts.contains("72K xp banked"));
		assertFalse("the reach line was deleted",
			texts.stream().anyMatch(t -> t.contains("Banked reaches level")));
		tab.dispose();
	}

	/** No persisted target: the field auto-fills with the first unbanked
	 *  level (banked level + 1) so the bar shows immediately — and the auto
	 *  value never persists. */
	@Test
	public void targetAutoFillsWithFirstUnbankedLevel() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, net.runelite.api.Skill.PRAYER, 43, 50_339);
		StateFixture.bank(state, Map.of(536, 1_000)); // banked level 51
		StateFixture.itemNames(state, Map.of(536, "Dragon bones"));
		BankTab tab = newTab(state);
		tab.showSkillView(net.runelite.api.Skill.PRAYER);

		assertEquals("52", tab.targetFieldText());
		assertEquals("the auto value must not persist", 0, state.getBankSkillTarget("Prayer"));
		List<String> texts = componentTexts(tab);
		assertTrue("the bar shows against the auto target", texts.contains("52"));
		assertTrue("centered banked total missing", texts.contains("72K xp banked"));
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
		// shared header grammar + level-flanked meter
		assertTrue("bold skill name missing", texts.contains("Runecraft"));
		assertTrue("level segment missing", texts.contains(" — 44"));
		assertTrue("target level must flank the meter", texts.contains("50"));

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
