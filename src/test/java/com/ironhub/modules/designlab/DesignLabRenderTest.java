package com.ironhub.modules.designlab;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless renders of the OSRS-skin gallery — one per theme — for
 * side-by-side comparison with the source art (wiki 1x Character Summary and
 * Settings interfaces; Mystic pack sprites), plus the invariants that in-client
 * rounds have already caught once each.
 */
public class DesignLabRenderTest
{
	@Test
	public void galleryRendersEveryThemeAtPanelWidth() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			DesignLabTab tab = new DesignLabTab(theme);
			BufferedImage image = SwingRender.render(tab);
			assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
			assertTrue(image.getHeight() > 400);

			File out = new File("build/reports/designlab-" + theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			ImageIO.write(image, "png", out);

			// stone-box anatomy, checked at a real box's own painted bounds:
			// the corner notch shows the theme's backing through the box,
			// with the engraved dark/light pair below it
			Rectangle box = firstStatBox(tab);
			assertEquals("corner notch shows the backing",
				theme.background.getRGB(), image.getRGB(box.x, box.y));
			int midX = box.x + box.width / 2;
			assertEquals(theme.edgeDark.getRGB(), image.getRGB(midX, box.y));
			assertEquals(theme.edgeLight.getRGB(), image.getRGB(midX, box.y + 1));
			assertEquals(theme.boxFill.getRGB(), image.getRGB(midX, box.y + 2));
		}
	}

	/** Bounds of the first StatBox, in the tab's own coordinates. */
	private static Rectangle firstStatBox(DesignLabTab tab)
	{
		com.ironhub.ui.osrs.StatBox box = find(tab, com.ironhub.ui.osrs.StatBox.class);
		assertTrue("no StatBox in the gallery", box != null);
		java.awt.Point origin = javax.swing.SwingUtilities.convertPoint(box.getParent(), box.getLocation(), tab);
		return new Rectangle(origin.x, origin.y, box.getWidth(), box.getHeight());
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

	/** Every theme must dress every atom — no half-clothed surface. */
	@Test
	public void bothThemesDefineEveryToken()
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			assertNotEquals("hover must differ from rest", theme.boxFill, theme.hoverFill);
			assertNotEquals("press must differ from rest", theme.boxFill, theme.pressFill);
			assertNotEquals("selected must differ from rest", theme.boxFill, theme.selectFill);
			assertNotEquals("selected bevel must differ", theme.edgeLight, theme.selectEdge);
			assertTrue("corner stamp mirrors a square", theme.cornerStamp.length > 0
				&& theme.cornerStamp.length == theme.cornerStamp[0].length());
			assertTrue("hover lifts the fill", brightness(theme.hoverFill) > brightness(theme.boxFill));
			assertTrue("press sinks the fill", brightness(theme.pressFill) < brightness(theme.boxFill));
		}
	}

	@Test
	public void buttonsAnswerThePointer()
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			StoneButton button = new StoneButton(theme, "Start all runs", null);
			assertEquals(theme.boxFill, button.fillFor(false, false));
			assertEquals(theme.hoverFill, button.fillFor(true, false));
			assertEquals(theme.pressFill, button.fillFor(true, true));
		}
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
		DesignLabTab tab = new DesignLabTab(OsrsTheme.MYSTIC);
		com.ironhub.ui.components.HubScrollPane pane = new com.ironhub.ui.components.HubScrollPane(tab);
		pane.setSize(UiTokens.PANEL_WIDTH, 900);
		layoutOnce(pane);

		List<OsrsLabel> labels = new ArrayList<>();
		collect(tab, labels);
		assertTrue("no labels found", labels.size() > 10);
		for (OsrsLabel label : labels)
		{
			assertTrue("label cut below preferred height: " + label.getBounds(),
				label.getHeight() >= label.getPreferredSize().height);
		}
	}

	/**
	 * The RuneScape TTF draws its ink floating high in the em box (caps span
	 * baseline-12..baseline-2), and the game centers the visible INK — box,
	 * icon and text centers are all equal in the wiki 1x screenshot. Pins
	 * the measured-ink placement so a caps/digits label reads centered
	 * (Luke, in-client 2026-07-16: text sat high beside a centered icon)
	 * and descender ink is never clipped away.
	 */
	@Test
	public void labelInkCentersAndDescendersSurvive()
	{
		OsrsLabel caps = OsrsLabel.label("Total XP: 47,702,858");
		Rectangle ink = paintInk(caps);
		double inkCenter = ink.y + (ink.height - 1) / 2.0;
		double boxCenter = (caps.getHeight() - 1) / 2.0;
		assertEquals("caps ink off-center: ink=" + ink + " in h=" + caps.getHeight(),
			boxCenter, inkCenter, 0.01);

		OsrsLabel descenders = OsrsLabel.label("gjpqy");
		Rectangle dInk = paintInk(descenders);
		assertTrue("descender ink clipped: " + dInk + " in h=" + descenders.getHeight(),
			dInk.y + dInk.height <= descenders.getHeight());
	}

	private static double brightness(java.awt.Color c)
	{
		return c.getRed() + c.getGreen() + c.getBlue();
	}

	private static void layoutOnce(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutOnce(child);
			}
		}
	}

	private static void collect(Container root, List<OsrsLabel> out)
	{
		for (Component child : root.getComponents())
		{
			if (child instanceof OsrsLabel)
			{
				out.add((OsrsLabel) child);
			}
			if (child instanceof Container)
			{
				collect((Container) child, out);
			}
		}
	}

	/**
	 * Paint at preferred size into an oversized image (direct paint() is
	 * unclipped, so overdraw past the component is visible) and bound the
	 * COLORED ink rows — the game centers ink, shadows excluded.
	 */
	private static Rectangle paintInk(OsrsLabel label)
	{
		java.awt.Dimension pref = label.getPreferredSize();
		label.setSize(pref);
		BufferedImage image = new BufferedImage(pref.width + 4, pref.height + 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(OsrsTheme.STONE.boxFill);
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
		return new Rectangle(0, minY, pref.width, maxY - minY + 1);
	}
}
