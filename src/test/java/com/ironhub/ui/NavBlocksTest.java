package com.ironhub.ui;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.dailies.DailiesModule;
import com.ironhub.modules.dailies.DailiesNewModule;
import com.ironhub.modules.farming.FarmingRunModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * The wired nav blocks (2026-07-17, Luke's spec): the home is persistent, a
 * block click opens its hub page beneath it, and the Dailies hub stacks the
 * Dailies (New) and Farm runs tabs together. Module tabs are singletons, so
 * the hub and the classic module cards ADOPT the tab whenever they are shown
 * — the reparenting is the mechanic most worth pinning.
 */
public class NavBlocksTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private IronHubPanel panel;
	private DailiesNewModule dailiesNew;
	private FarmingRunModule farming;
	private HomePanel home;
	private net.runelite.client.config.ConfigManager configManager;

	private void build() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		IronHubConfig config = new IronHubConfig()
		{
		};
		DailiesModule brain = new DailiesModule(state, null, config, new DataPack(new Gson()),
			null, null, null, null, null, null, null, null, null, null, null);
		brain.startUp();
		dailiesNew = new DailiesNewModule(brain, config);
		farming = new FarmingRunModule(state, null, new net.runelite.client.eventbus.EventBus(),
			null, null, null, config, null, new DataPack(new Gson()),
			null, null, null, null, null, null, null);
		farming.startUp();
		configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		panel = new IronHubPanel(Set.of((IronHubModule) dailiesNew, farming), state, config,
			configManager);
		home = find(panel, HomePanel.class);
		assertNotNull("the home must be persistent in the panel", home);
	}

	@Test
	public void dailiesBlockShowsOneModuleAtATimeAndSurvivesAThemeSwap() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Dailies"));
		assertEquals("Dailies", home.selectedBlock());

		// exclusive sections (Luke, 2026-07-17): the first module opens by
		// default; expanding another collapses it — never both at once.
		// Farm runs LEADS the block since D1 (Luke, 2026-08-03), so it is
		// the default-open module now — this assertion is the ordering pin.
		JComponent farmingTab = farming.buildTab();
		assertTrue("Farm runs must lead the Dailies block and open by default (D1)",
			javax.swing.SwingUtilities.isDescendingFrom(farmingTab, panel));
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.toggleModule("Dailies", "Dailies"));
		JComponent dailiesTab = dailiesNew.buildTab();
		assertTrue("expanded module's tab not in the panel",
			javax.swing.SwingUtilities.isDescendingFrom(dailiesTab, panel));
		assertTrue("collapsing must unmount the other module's tab",
			!javax.swing.SwingUtilities.isDescendingFrom(farmingTab, panel));
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.toggleModule("Dailies", "Farm runs"));
		assertTrue("re-expanding Farm runs must remount its tab",
			javax.swing.SwingUtilities.isDescendingFrom(farmingTab, panel));
		Container hubHost = farmingTab.getParent();

		// a theme swap rebuilds the home and drops every cached hub page: the
		// freshly built slots must ADOPT the singleton tab of the module that
		// was open (the expansion choice survives the swap)
		panel.themeChanged();
		javax.swing.SwingUtilities.invokeAndWait(() -> {}); // flush the queued rebuild
		home = find(panel, HomePanel.class);
		assertNotNull("theme swap must rebuild the home", home);
		// the swap REOPENS the block the player was in (the skin switcher
		// lives in a hub page, so dumping them at home each flip is no good)
		assertEquals("Dailies", home.selectedBlock());
		assertNotSame("fresh hub slots must adopt the tab", hubHost, farmingTab.getParent());
		assertTrue("farming tab lost in the theme swap",
			javax.swing.SwingUtilities.isDescendingFrom(farmingTab, panel));
	}

	/** The Gear & Combat hub expands independently (Luke, 2026-07-21): the
	 *  gear viewer and the Slayer tab read together — never forced exclusive.
	 *  No combat modules are registered in this fixture, so the honest
	 *  "Enable the X module" notes are the observable for mounted slots. */
	@Test
	public void gearAndCombatHubExpandsModulesIndependently() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Gear & Combat"));
		assertEquals(java.util.List.of("Gear & Combat"), enableNotes());
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.toggleModule("Gear & Combat", "Slayer"));
		assertEquals("expanding Slayer must keep Gear & Combat open",
			java.util.List.of("Gear & Combat", "Slayer"), enableNotes());
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.toggleModule("Gear & Combat", "Gear & Combat"));
		assertEquals(java.util.List.of("Slayer"), enableNotes());
	}

	/**
	 * The Settings hub carries the skin switcher (Luke, 2026-07-24: the
	 * osrsTheme setting was only reachable from the RuneLite config panel).
	 * It must show the live theme and WRITE the setting — holding its own
	 * state would leave the two switches disagreeing.
	 */
	@Test
	public void theSettingsHubSwitchesTheSkin() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Settings"));
		com.ironhub.ui.osrs.StoneChipRow chips =
			find(panel, com.ironhub.ui.osrs.StoneChipRow.class);
		assertNotNull("no skin switcher in the Settings hub", chips);
		assertEquals("the switcher must show the live theme",
			com.ironhub.ui.osrs.OsrsTheme.MYSTIC.ordinal(), chips.getSelected());

		javax.swing.SwingUtilities.invokeAndWait(
			() -> chips.pick(com.ironhub.ui.osrs.OsrsTheme.STONE.ordinal()));
		org.mockito.Mockito.verify(configManager).setConfiguration(
			com.ironhub.IronHubConfig.GROUP, "osrsTheme",
			com.ironhub.ui.osrs.OsrsTheme.STONE);

		java.awt.image.BufferedImage image = SwingRender.render(panel);
		java.io.File out = new java.io.File("build/reports/home-settings-hub.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}

	private java.util.List<String> enableNotes()
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		collectEnableNotes(panel, out);
		return out;
	}

	private static void collectEnableNotes(Container root, java.util.List<String> out)
	{
		for (Component child : root.getComponents())
		{
			if (child instanceof javax.swing.JLabel)
			{
				String t = ((javax.swing.JLabel) child).getText();
				if (t != null && t.startsWith("Enable the ") && t.endsWith(" module"))
				{
					out.add(t.substring("Enable the ".length(),
						t.length() - " module".length()));
				}
			}
			if (child instanceof Container)
			{
				collectEnableNotes((Container) child, out);
			}
		}
	}

	/**
	 * The Progression hub is a tile grid (Luke, 2026-07-24), so every module
	 * the block lists must sit on exactly one tile — a module missing from
	 * TILED would simply be unreachable, with nothing to click.
	 */
	@Test
	public void everyTiledHubCoversItsModulesExactlyOnce()
	{
		for (java.util.Map.Entry<String, java.util.List<IronHubPanel.Section>> hub
			: IronHubPanel.TILED.entrySet())
		{
			java.util.List<String> tiled = new java.util.ArrayList<>();
			for (IronHubPanel.Section section : hub.getValue())
			{
				tiled.addAll(section.modules);
			}
			assertEquals("a module appears on two tiles in " + hub.getKey(),
				tiled.size(), new java.util.HashSet<>(tiled).size());
			assertEquals("tiles and hub contents disagree in " + hub.getKey(),
				new java.util.HashSet<>(IronHubPanel.blockContents().get(hub.getKey())),
				new java.util.HashSet<>(tiled));
		}
		assertEquals("Luke's grid is 4x2", 8, IronHubPanel.TILED.get("Progression").size());
	}

	/**
	 * Tiles SELECT (they never collapse to an empty page under a grid), and
	 * the two-module tile's chip row switches inside the section — one slot
	 * serves the whole hub, so only the chosen module is ever mounted.
	 */
	@Test
	public void progressionTilesSelectSectionsAndTheirChips() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Progression"));
		assertEquals("the first tile opens by default",
			java.util.List.of("Collection log"), enableNotes());

		javax.swing.SwingUtilities.invokeAndWait(() -> panel.toggleModule("Progression", "House"));
		assertEquals(java.util.List.of("House"), enableNotes());
		// the chip row inside the Build tile switches to its second module
		com.ironhub.ui.osrs.StoneChipRow chips =
			find(panel, com.ironhub.ui.osrs.StoneChipRow.class);
		assertNotNull("a two-module tile must offer chips", chips);
		javax.swing.SwingUtilities.invokeAndWait(() -> chips.pick(1));
		assertEquals(java.util.List.of("Boats"), enableNotes());

		// pressing the open tile again keeps it open (no empty page)
		javax.swing.SwingUtilities.invokeAndWait(() ->
			panel.toggleModule("Progression", "Boats"));
		assertEquals(java.util.List.of("Boats"), enableNotes());
	}

	@Test
	public void blocksToggleLikeTheGamesOwnTabs() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Bank"));
		assertEquals("Bank", home.selectedBlock());
		// clicking the open block again closes it, like the game's own tabs
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Bank"));
		assertEquals(null, home.selectedBlock());
	}

	/** Every stone routes somewhere — a NAV name missing from BLOCKS is an
	 *  honest-but-dead "not built yet" page nobody intends any more. */
	@Test
	public void everyNavBlockHasAHubPage()
	{
		for (String name : HomePanel.blockNames())
		{
			assertTrue("nav stone without a hub page: " + name,
				IronHubPanel.blockContents().containsKey(name));
		}
	}

	/**
	 * The home view's scrollbar takes no pixels but must stay wheel-capable:
	 * Swing's wheel handler only scrolls when the vertical bar isVisible(),
	 * so the NEVER policy silently killed the wheel (Luke, in-client
	 * 2026-07-17). Zero width + visible is the contract.
	 */
	@Test
	public void homeScrollsByWheelWithoutShowingABar() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Dailies"));
		javax.swing.JScrollPane pane = find(panel, javax.swing.JScrollPane.class);
		assertNotNull(pane);
		// content far taller than the viewport — the bar must engage
		pane.setSize(UiTokens.PANEL_WIDTH, 400);
		pane.doLayout();
		pane.getViewport().doLayout();
		javax.swing.JScrollBar bar = pane.getVerticalScrollBar();
		assertTrue("wheel needs a visible bar", bar.isVisible());
		assertEquals("the bar must take no pixels", 0, bar.getWidth());
		assertEquals("content must span the full panel width",
			UiTokens.PANEL_WIDTH, pane.getViewport().getWidth());
	}

	@Test
	public void wiredPanelRendersWithTheDailiesHubOpen() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Dailies"));
		BufferedImage image = SwingRender.render(panel);
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue(image.getHeight() > 400);
		File out = new File("build/reports/home-dailies-hub.png");
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
	}

	@SuppressWarnings("unchecked")
	private static <T> T find(Container root, Class<T> type)
	{
		for (Component child : root.getComponents())
		{
			if (type.isInstance(child))
			{
				return (T) child;
			}
			if (child instanceof Container)
			{
				T hit = find((Container) child, type);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		return null;
	}
}
