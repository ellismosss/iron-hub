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

	/** The reworked OSRS-skin home (2026-07-16): name, summary, 6 nav blocks. */
	@Test
	public void homeRendersInBothThemes() throws Exception
	{
		com.ironhub.state.AccountState state =
			com.ironhub.state.StateFixture.state(temp.getRoot());
		com.ironhub.state.StateFixture.playerName(state, "Iron Luke");
		for (com.ironhub.ui.osrs.OsrsTheme theme : com.ironhub.ui.osrs.OsrsTheme.values())
		{
			HomePanel home = new HomePanel(state, null, theme, name -> {});
			BufferedImage image = SwingRender.render(home);
			assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
			assertTrue(image.getHeight() > 150);
			write(image, "home-" + theme.name().toLowerCase() + ".png");
			home.dispose();
		}
	}

	private static void write(BufferedImage image, String name) throws Exception
	{
		File out = new File("build/reports/" + name);
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
	}
}
