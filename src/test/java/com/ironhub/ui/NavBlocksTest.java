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
		panel = new IronHubPanel(Set.of((IronHubModule) dailiesNew, farming), state,
			new DataPack(new Gson()), config);
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
		// default; expanding another collapses it — never both at once
		JComponent dailiesTab = dailiesNew.buildTab();
		assertTrue("first module's tab must open by default",
			javax.swing.SwingUtilities.isDescendingFrom(dailiesTab, panel));
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.toggleModule("Dailies", "Farm runs"));
		JComponent farmingTab = farming.buildTab();
		assertTrue("expanded module's tab not in the panel",
			javax.swing.SwingUtilities.isDescendingFrom(farmingTab, panel));
		assertTrue("collapsing must unmount the other module's tab",
			!javax.swing.SwingUtilities.isDescendingFrom(dailiesTab, panel));
		Container hubHost = farmingTab.getParent();

		// a theme swap rebuilds the home and drops every cached hub page: the
		// freshly built slots must ADOPT the singleton tab of the module that
		// was open (the expansion choice survives the swap)
		panel.themeChanged();
		javax.swing.SwingUtilities.invokeAndWait(() -> {}); // flush the queued rebuild
		home = find(panel, HomePanel.class);
		assertNotNull("theme swap must rebuild the home", home);
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Dailies"));
		assertNotSame("fresh hub slots must adopt the tab", hubHost, farmingTab.getParent());
		assertTrue("farming tab lost in the theme swap",
			javax.swing.SwingUtilities.isDescendingFrom(farmingTab, panel));
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
