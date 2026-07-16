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
import javax.swing.JComponent;
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

	/**
	 * The tick read low in-client (Luke, 2026-07-16) because it was placed by
	 * hand-picked offsets copied off a 17px source into a smaller box. It is
	 * now centered from its own measured bounds — pinned here by measuring
	 * the painted pixels, in both themes.
	 */
	@Test
	public void checkboxTickCentersInItsBox()
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			com.ironhub.ui.osrs.StoneCheckbox box = new com.ironhub.ui.osrs.StoneCheckbox(theme, true);
			Rectangle ink = paintedInk(box, theme.checkMark.getRGB(), theme.recess);
			int size = box.getPreferredSize().height;
			assertEquals("tick sits off-center vertically: " + ink + " in " + size,
				(size - 1) / 2.0, ink.y + (ink.height - 1) / 2.0, 0.5);
			assertEquals("tick sits off-center horizontally: " + ink + " in " + size,
				(size - 1) / 2.0, ink.x + (ink.width - 1) / 2.0, 0.5);
		}
	}

	/**
	 * Same defect, same fix, third surface: bar text is placed from the small
	 * font's measured ink, so it centers in the trough instead of riding high.
	 */
	@Test
	public void progressBarTextCentersInTheTrough()
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			com.ironhub.ui.osrs.StoneProgressBar bar =
				new com.ironhub.ui.osrs.StoneProgressBar(theme, theme.recess, 0).labels("", "30.59%", "");
			bar.setSize(120, bar.getPreferredSize().height);
			BufferedImage image = new BufferedImage(120, bar.getHeight(), BufferedImage.TYPE_INT_RGB);
			bar.paint(image.createGraphics());
			Rectangle ink = bounds(image, OsrsSkin.BAR_TEXT.getRGB());
			assertEquals("bar text off-center: " + ink + " in h=" + bar.getHeight(),
				(bar.getHeight() - 1) / 2.0, ink.y + (ink.height - 1) / 2.0, 0.5);
		}
	}

	/**
	 * The highlight band wraps the checkbox with a 1px gap on every side and
	 * mirrors that gap at the right — Luke walked this one to the pixel: the
	 * stat-box padding left an ugly gap, full-bleed stretched too far.
	 */
	@Test
	public void checklistHighlightKeepsAOnePixelGapAroundTheCheckbox()
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			com.ironhub.ui.osrs.StoneChecklist list = new com.ironhub.ui.osrs.StoneChecklist(theme)
				.row("Ardougne cloak 4", false)
				.row("Graceful outfit", true);
			list.setSize(213, list.getPreferredSize().height);
			layoutOnce(list);

			Component row = list.getComponent(0);
			com.ironhub.ui.osrs.StoneCheckbox box = find((Container) row, com.ironhub.ui.osrs.StoneCheckbox.class);
			assertEquals("gap left of the checkbox", 1, box.getX());
			assertEquals("gap above the checkbox", 1, box.getY());
			assertEquals("gap below the checkbox", 1, row.getHeight() - box.getHeight() - box.getY());

			// and the band itself sits 1px inside the engraved edge, mirrored
			java.awt.Insets in = list.getInsets();
			assertEquals("band not inset from the left edge", 3, in.left);
			assertEquals("right gap does not mirror the left", in.left, in.right);
			assertEquals("band does not span the frame's inner width",
				list.getWidth() - in.left - in.right, row.getWidth());
		}
	}

	/**
	 * Typed text reads as body copy, matching the section captions (Luke) —
	 * and stays CRISP. Swing re-applies the look-and-feel's antialias
	 * setting over the Graphics hint, which smeared the pixel font with LCD
	 * subpixel fringes until OsrsSkin.crisp() set the per-component property;
	 * a blend would show up here as a colour outside the field's palette.
	 */
	@Test
	public void fieldTextIsBodyColouredAndCrisp()
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			com.ironhub.ui.osrs.StoneTextField field =
				new com.ironhub.ui.osrs.StoneTextField(theme, "Search modules…");
			field.setText("Ardougne cloak");
			field.setSize(180, field.getPreferredSize().height);
			BufferedImage image = new BufferedImage(180, field.getHeight(), BufferedImage.TYPE_INT_RGB);
			field.paint(image.createGraphics());

			java.util.Set<Integer> allowed = new java.util.HashSet<>(java.util.List.of(
				theme.fieldFill.getRGB() & 0xFFFFFF,
				theme.edgeDark.getRGB() & 0xFFFFFF,
				theme.fieldEdge.getRGB() & 0xFFFFFF,
				theme.selectFill.getRGB() & 0xFFFFFF,
				OsrsSkin.MUTED.getRGB() & 0xFFFFFF));
			int typed = 0;
			for (int y = 0; y < image.getHeight(); y++)
			{
				for (int x = 0; x < image.getWidth(); x++)
				{
					int rgb = image.getRGB(x, y) & 0xFFFFFF;
					assertTrue(String.format("antialiased pixel #%06X at %d,%d — the pixel font is smeared", rgb, x, y),
						allowed.contains(rgb));
					if (rgb == (OsrsSkin.MUTED.getRGB() & 0xFFFFFF))
					{
						typed++;
					}
				}
			}
			assertTrue("typed text is not the caption colour", typed > 100);
		}
	}

	/**
	 * Typed text, its placeholder and a dropdown's value all centre their
	 * INK in the control. Swing centres by the font's EM BOX, and the
	 * RuneScape font's ink floats high inside it, so every em-centred
	 * control read a pixel or two high until the padding paid for it (Luke,
	 * in-client 2026-07-16 — the same defect as the stat boxes and the bars,
	 * now on the Swing-laid-out surfaces).
	 */
	@Test
	public void fieldAndDropdownTextCentresItsInk()
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			com.ironhub.ui.osrs.StoneTextField typed =
				new com.ironhub.ui.osrs.StoneTextField(theme, "Search modules…");
			typed.setText("Ardoune"); // caps + x-height, no descender to skew the bounds
			assertInkCentred(typed, 180, OsrsSkin.MUTED, "typed text");

			com.ironhub.ui.osrs.StoneTextField empty =
				new com.ironhub.ui.osrs.StoneTextField(theme, "Search modules");
			assertInkCentred(empty, 180, OsrsSkin.FAINT, "placeholder");

			javax.swing.JComboBox<String> combo = com.ironhub.ui.osrs.StoneComboBoxUI.skin(
				new javax.swing.JComboBox<>(new String[]{"All tiers"}), theme);
			combo.setSize(140, 22);
			combo.doLayout();
			assertInkCentred(combo, 140, OsrsSkin.LABEL, "dropdown value");
		}
	}

	private static void assertInkCentred(java.awt.Component c, int width, java.awt.Color ink, String what)
	{
		int height = c.getHeight() > 0 ? c.getHeight() : c.getPreferredSize().height;
		c.setSize(width, height);
		if (c instanceof Container)
		{
			((Container) c).doLayout();
		}
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		c.paint(image.createGraphics());
		Rectangle bounds = bounds(image, ink.getRGB());
		double inkCentre = bounds.y + (bounds.height - 1) / 2.0;
		assertEquals(what + " does not centre its ink: " + bounds + " in h=" + height,
			(height - 1) / 2.0, inkCentre, 0.01);
	}

	/** Ink bounds of one colour, painted over the given backdrop. */
	private static Rectangle paintedInk(JComponent c, int rgb, java.awt.Color backdrop)
	{
		java.awt.Dimension pref = c.getPreferredSize();
		c.setSize(pref);
		BufferedImage image = new BufferedImage(pref.width, pref.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(backdrop);
		g.fillRect(0, 0, pref.width, pref.height);
		c.paint(g);
		return bounds(image, rgb);
	}

	private static Rectangle bounds(BufferedImage image, int rgb)
	{
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) & 0xFFFFFF) == (rgb & 0xFFFFFF))
				{
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		assertTrue("nothing painted in that colour", maxY >= 0);
		return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
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
