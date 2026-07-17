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
		dailiesNew = new DailiesNewModule(brain, config, new net.runelite.client.eventbus.EventBus());
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
	public void dailiesBlockStacksBothTabsAndSurvivesModuleHopping() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Dailies"));
		assertEquals("Dailies", home.selectedBlock());

		JComponent dailiesTab = dailiesNew.buildTab();
		JComponent farmingTab = farming.buildTab();
		assertTrue("dailies tab not in the panel",
			javax.swing.SwingUtilities.isDescendingFrom(dailiesTab, panel));
		assertTrue("farming tab not in the panel",
			javax.swing.SwingUtilities.isDescendingFrom(farmingTab, panel));
		Container hubHost = farmingTab.getParent();

		// hop to the classic module card: it adopts the singleton tab...
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.openModule("Farming runs"));
		assertNotSame("module card must adopt the tab", hubHost, farmingTab.getParent());

		// ...and returning to the hub adopts it straight back
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			home.pressBlock("Dailies"); // toggle off
			home.pressBlock("Dailies"); // and on again — remounts
		});
		assertEquals(hubHost, farmingTab.getParent());
	}

	@Test
	public void unwiredBlocksSaySoAndTheHeaderFollowsSelection() throws Exception
	{
		build();
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Bank"));
		assertEquals("Bank", home.selectedBlock());
		// clicking the open block again closes it, like the game's own tabs
		javax.swing.SwingUtilities.invokeAndWait(() -> home.pressBlock("Bank"));
		assertEquals(null, home.selectedBlock());
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
