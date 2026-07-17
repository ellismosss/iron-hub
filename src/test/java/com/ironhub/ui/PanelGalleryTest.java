package com.ironhub.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless renders of the dashboard (frame 1b) and module navigation
 * (frame 1c) for mockup side-by-sides; fails on layout/paint exceptions.
 * PNGs land in build/reports/.
 */
public class PanelGalleryTest
{
	@org.junit.Rule
	public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder();

	@Test
	public void dashboardRendersAtPanelWidth() throws Exception
	{
		com.ironhub.state.AccountState state =
			com.ironhub.state.StateFixture.state(temp.getRoot());
		BufferedImage image = SwingRender.render(new DashboardPanel(state,
			new com.ironhub.data.DataPack(new com.google.gson.Gson()), name -> {}, null));
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue(image.getHeight() > 300);
		write(image, "dashboard-1b.png");
	}

	@Test
	public void moduleNavRendersAtPanelWidth() throws Exception
	{
		BufferedImage image = SwingRender.render(new ModuleNavPanel(null, name -> {}));
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue(image.getHeight() > 300);
		write(image, "modules-1c.png");
	}

	/** The reworked OSRS-skin home (2026-07-16): name, summary, 7 nav blocks. */
	@Test
	public void homeRendersInBothThemes() throws Exception
	{
		com.ironhub.state.AccountState state =
			com.ironhub.state.StateFixture.state(temp.getRoot());
		for (com.ironhub.ui.osrs.OsrsTheme theme : com.ironhub.ui.osrs.OsrsTheme.values())
		{
			HomePanel home = new HomePanel(state, theme, () -> {}, name -> {});
			BufferedImage image = SwingRender.render(home);
			assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
			assertTrue(image.getHeight() > 150);
			write(image, "home-" + theme.name().toLowerCase() + ".png");
			home.dispose();
		}
	}

	@Test
	public void navRowsExistForImplementedModules()
	{
		// nav rows route to modules by exact name — a mismatch is an inert row
		for (String name : new String[]{"Quests", "Achievement diaries", "Combat achievements", "QoL checklist", "Bank & banked XP", "Dailies", "Gear progression", "Loot & supplies", "Slayer", "Farming runs", "Death recovery", "Goal planner", "Supplies runway", "Clues & STASH", "Collection log", "Design lab", "Dailies (New)"})
		{
			assertTrue("nav row missing for module: " + name,
				ModuleNavPanel.moduleNames().contains(name));
		}
	}

	private static void write(BufferedImage image, String name) throws Exception
	{
		File out = new File("build/reports/" + name);
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
	}
}
