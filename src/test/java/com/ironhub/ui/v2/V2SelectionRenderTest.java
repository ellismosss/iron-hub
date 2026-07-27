package com.ironhub.ui.v2;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.image.BufferedImage;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The selection atoms: checkbox, chips, tabs, tiles, item slots. */
public class V2SelectionRenderTest
{
	private static JPanel horizontal()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static BufferedImage emblem(OsrsTheme theme, String key)
	{
		return V2Sprites.get(theme, key);
	}

	private static JPanel sheet(OsrsTheme theme)
	{
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.setBackground(theme.background);
		page.setBorder(new javax.swing.border.EmptyBorder(
			V2Tokens.PAD, V2Tokens.ROW, V2Tokens.PAD, V2Tokens.ROW));

		page.add(V2Label.heading("Checkbox"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2Checkbox(theme, "Herb run", true, null));
		page.add(new V2Checkbox(theme, "Tree run", false, null));
		page.add(new V2Checkbox(theme, "Hardwood run", false, null)
			.state(V2Checkbox.State.LOCKED));
		page.add(new V2Checkbox(theme, "Fruit tree run", true, null)
			.state(V2Checkbox.State.DISABLED_ON));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Chips"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2ChipRow(theme, true, "Today", "Route", "Goals"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		V2ChipRow narrow = new V2ChipRow(theme, false, "All", "Owned");
		narrow.setSelected(1);
		page.add(narrow);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Tiles"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		JPanel tiles = horizontal();
		String[] emblems = {"icons/skills/farming", "icons/skills/slayer",
			"icons/skills/construction", "icons/skills/hunter"};
		for (int i = 0; i < emblems.length; i++)
		{
			V2Tile tile = new V2Tile(theme, emblem(theme, emblems[i]),
				null, 50, null);
			tile.selected(i == 1).owned(i == 0);
			tiles.add(tile);
			tiles.add(Box.createHorizontalStrut(V2Tokens.ROW));
		}
		tiles.add(Box.createHorizontalGlue());
		page.add(tiles);
		page.add(Box.createVerticalStrut(V2Tokens.ROW));

		JPanel captioned = horizontal();
		String[] names = {"Herbs", "Trees", "Seeds"};
		for (int i = 0; i < names.length; i++)
		{
			V2Tile tile = new V2Tile(theme, emblem(theme, "icons/skills/farming"),
				names[i], 60, null);
			tile.selected(i == 0);
			captioned.add(tile);
			captioned.add(Box.createHorizontalStrut(V2Tokens.ROW));
		}
		captioned.add(Box.createHorizontalGlue());
		page.add(captioned);
		page.add(Box.createVerticalStrut(V2Tokens.ROW));

		// captionInside + corner: the clog page grid's shape — bold caption
		// ON the art, detail count top-right (Luke, 2026-07-27)
		JPanel inside = horizontal();
		String[] insideNames = {"Abyssal Sire", "Barrows Chests", "Wintertodt"};
		String[] counts = {"7/33", "24/24", "0/12"};
		for (int i = 0; i < insideNames.length; i++)
		{
			V2Tile tile = new V2Tile(theme, emblem(theme, "icons/skills/slayer"),
				insideNames[i], 56, null)
				.width(68).captionLines(2).captionInside().corner(counts[i])
				.captionStatus(i == 1 ? V2Tokens.DONE : V2Tokens.ACTION);
			if (i == 0)
			{
				tile.status(V2Tile.Status.READY).progress(7 / 33.0);
			}
			else if (i == 1)
			{
				tile.status(V2Tile.Status.DONE);
			}
			inside.add(tile);
			inside.add(Box.createHorizontalStrut(V2Tokens.ROW));
		}
		inside.add(Box.createHorizontalGlue());
		page.add(inside);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Tabs and slots"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		JPanel tabs = horizontal();
		for (int i = 0; i < 3; i++)
		{
			tabs.add(new V2Tab(theme, emblem(theme, "icons/skills/mining"), null)
				.active(i == 1));
		}
		tabs.add(Box.createHorizontalGlue());
		page.add(tabs);
		page.add(Box.createVerticalStrut(V2Tokens.ROW));

		JPanel slots = horizontal();
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.HEAD, null));
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.CAPE, null));
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.WEAPON, null)
			.item(emblem(theme, "icons/skills/attack")));
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.SHIELD, null).selected(true));
		slots.add(new V2ItemSlot(theme, null));
		slots.add(Box.createHorizontalGlue());
		page.add(slots);

		page.setSize(V2Tokens.PANEL_WIDTH, page.getPreferredSize().height);
		return page;
	}

	@Test
	public void selectionAtomsRenderInEveryTheme() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			BufferedImage image = SwingRender.render(sheet(theme));
			assertEquals(V2Tokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the sheet collapsed: " + image.getHeight(), image.getHeight() > 350);
			java.io.File out = new java.io.File("build/reports/v2-selection-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}

	/** A locked or disabled row is not a control — pressing it does nothing. */
	@Test
	public void lockedAndDisabledRowsDoNotToggle()
	{
		int[] fired = {0};
		V2Checkbox box = new V2Checkbox(OsrsTheme.STONE, "Gated", false, () -> fired[0]++);
		press(box);
		assertEquals(1, fired[0]);
		box.state(V2Checkbox.State.LOCKED);
		press(box);
		assertEquals("a locked row must not toggle", 1, fired[0]);
		box.state(V2Checkbox.State.DISABLED);
		press(box);
		assertEquals(1, fired[0]);
		box.state(V2Checkbox.State.OFF);
		press(box);
		assertEquals(2, fired[0]);
	}

	@Test
	public void chipsReportTheirSelectionAndFireOnce()
	{
		int[] picked = {-1};
		V2ChipRow chips = new V2ChipRow(OsrsTheme.STONE, true, "A", "B", "C")
			.onChange(i -> picked[0] = i);
		assertEquals(0, chips.selected());
		chips.setSelected(2);
		assertEquals("programmatic selection must not fire onChange", -1, picked[0]);
		assertEquals(2, chips.selected());
		chips.pick(1);
		assertEquals(1, picked[0]);
		assertEquals(1, chips.selected());
	}

	private static void press(Component c)
	{
		for (java.awt.event.MouseListener l : c.getMouseListeners())
		{
			l.mousePressed(new java.awt.event.MouseEvent(c,
				java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false));
		}
	}
}
