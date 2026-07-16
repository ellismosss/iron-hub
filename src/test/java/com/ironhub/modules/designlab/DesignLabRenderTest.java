package com.ironhub.modules.designlab;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless render of the OSRS-skin gallery for side-by-side comparison with
 * the source art (wiki 1x Character Summary; Mystic pack sprites). Pins the
 * stone-box anatomy for BOTH themes: the corner notch must show the theme's
 * backing through the box, with the engraved dark/light edge pair below.
 */
public class DesignLabRenderTest
{
	@Test
	public void galleryRendersAtPanelWidth() throws Exception
	{
		BufferedImage image = SwingRender.render(new DesignLabTab());
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue(image.getHeight() > 500);

		File out = new File("build/reports/designlab-tab.png");
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);

		for (OsrsTheme theme : new OsrsTheme[]{OsrsSkin.STONE, OsrsSkin.MYSTIC})
		{
			int boxX = 8, boxY = firstBoxTop(image, boxX, theme);
			assertEquals("corner notch shows the backing",
				theme.background.getRGB(), image.getRGB(boxX, boxY));
			int midX = boxX + 40;
			assertEquals(theme.edgeDark.getRGB(), image.getRGB(midX, boxY));
			assertEquals(theme.edgeLight.getRGB(), image.getRGB(midX, boxY + 1));
			assertEquals(theme.boxFill.getRGB(), image.getRGB(midX, boxY + 2));
		}
	}

	/** First row (scanning down at x) whose right neighbourhood turns the theme's dark edge. */
	private static int firstBoxTop(BufferedImage image, int x, OsrsTheme theme)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			if (image.getRGB(x + 40, y) == theme.edgeDark.getRGB())
			{
				return y;
			}
		}
		throw new AssertionError("no stone box edge found for theme");
	}

	/**
	 * Mount exactly like IronHubPanel.wrap (HubScrollPane viewport) and lay
	 * out ONCE — the client does a single pass, while SwingRender's repeated
	 * passes let stale current-sizes leak into BoxLayout minimums and heal
	 * the very bug this pins: a UI-less label with a 0x0 default minimum
	 * degenerates its row's alignment and gets cut to half height (proven
	 * in-client 2026-07-16: halved captions, off-center XP text).
	 */
	@Test
	public void everyLabelKeepsItsHeightUnderTheClientMount()
	{
		DesignLabTab tab = new DesignLabTab();
		com.ironhub.ui.components.HubScrollPane pane = new com.ironhub.ui.components.HubScrollPane(tab);
		pane.setSize(UiTokens.PANEL_WIDTH, 600);
		layoutOnce(pane);

		java.util.List<com.ironhub.ui.osrs.OsrsLabel> labels = new java.util.ArrayList<>();
		collect(tab, labels);
		assertTrue("no labels found", labels.size() > 10);
		for (com.ironhub.ui.osrs.OsrsLabel label : labels)
		{
			assertTrue("label cut below preferred height: " + label.getBounds(),
				label.getHeight() >= label.getPreferredSize().height);
		}
	}

	private static void layoutOnce(java.awt.Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) c).getComponents())
			{
				layoutOnce(child);
			}
		}
	}

	private static void collect(java.awt.Container root, java.util.List<com.ironhub.ui.osrs.OsrsLabel> out)
	{
		for (java.awt.Component child : root.getComponents())
		{
			if (child instanceof com.ironhub.ui.osrs.OsrsLabel)
			{
				out.add((com.ironhub.ui.osrs.OsrsLabel) child);
			}
			if (child instanceof java.awt.Container)
			{
				collect((java.awt.Container) child, out);
			}
		}
	}

	/**
	 * The RuneScape TTF draws its ink floating high in the em box (caps span
	 * baseline-12..baseline-2), and the game centers the visible INK — box,
	 * icon and text centers are all equal in the wiki 1x screenshot. Pins
	 * the measured-ink placement so a caps/digits label reads centered
	 * (Luke, in-client 2026-07-16: text sat 2px high beside a centered icon)
	 * and descender ink is never clipped away.
	 */
	@Test
	public void labelInkCentersAndDescendersSurvive()
	{
		com.ironhub.ui.osrs.OsrsLabel caps = com.ironhub.ui.osrs.OsrsLabel.label("Total XP: 47,702,858");
		java.awt.Rectangle ink = paintInk(caps);
		double inkCenter = ink.y + (ink.height - 1) / 2.0;
		double boxCenter = (caps.getHeight() - 1) / 2.0;
		assertTrue("caps ink off-center: ink=" + ink + " in h=" + caps.getHeight(),
			Math.abs(inkCenter - boxCenter) <= 1.0);

		com.ironhub.ui.osrs.OsrsLabel descenders = com.ironhub.ui.osrs.OsrsLabel.label("gjpqy");
		java.awt.Rectangle dInk = paintInk(descenders);
		assertTrue("descender ink clipped: " + dInk + " in h=" + descenders.getHeight(),
			dInk.y + dInk.height <= descenders.getHeight());
	}

	/**
	 * Paint at preferred size into an oversized image (direct paint() is
	 * unclipped, so overdraw past the component is visible) and bound the
	 * COLORED ink rows — the game centers ink, shadows excluded.
	 */
	private static java.awt.Rectangle paintInk(com.ironhub.ui.osrs.OsrsLabel label)
	{
		java.awt.Dimension pref = label.getPreferredSize();
		label.setSize(pref);
		BufferedImage image = new BufferedImage(pref.width + 4, pref.height + 4, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setColor(OsrsSkin.BOX_FILL);
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		label.paint(g);
		int minY = Integer.MAX_VALUE, maxY = -1;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (image.getRGB(x, y) == OsrsSkin.LABEL.getRGB())
				{
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		assertTrue("label painted nothing", maxY >= 0);
		return new java.awt.Rectangle(0, minY, pref.width, maxY - minY + 1);
	}
}
