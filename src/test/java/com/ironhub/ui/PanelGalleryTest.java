package com.ironhub.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
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
	@Test
	public void dashboardRendersAtPanelWidth() throws Exception
	{
		BufferedImage image = render(new DashboardPanel(null));
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue(image.getHeight() > 300);
		write(image, "dashboard-1b.png");
	}

	@Test
	public void moduleNavRendersAtPanelWidth() throws Exception
	{
		BufferedImage image = render(new ModuleNavPanel(null));
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue(image.getHeight() > 300);
		write(image, "modules-1c.png");
	}

	static BufferedImage render(JPanel panel)
	{
		// three passes: wrap layouts need a real width before preferred
		// heights settle; invalidate clears cached BoxLayout sizes, and
		// layout runs manually — validate() is a no-op without a peer
		for (int pass = 0; pass < 3; pass++)
		{
			invalidateTree(panel);
			panel.setSize(new Dimension(UiTokens.PANEL_WIDTH, panel.getPreferredSize().height));
			layoutTree(panel);
		}

		BufferedImage image = new BufferedImage(
			panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
		panel.paint(image.getGraphics());
		return image;
	}

	private static void invalidateTree(Component c)
	{
		c.invalidate();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				invalidateTree(child);
			}
		}
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	private static void write(BufferedImage image, String name) throws Exception
	{
		File out = new File("build/reports/" + name);
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
	}
}
