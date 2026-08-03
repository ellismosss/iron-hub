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

/** Status indicators, the hero block, the table and the two empty states. */
public class V2StatusRenderTest
{
	private static JPanel horizontal()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static JPanel sheet(OsrsTheme theme)
	{
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.setBackground(theme.background);
		page.setBorder(new javax.swing.border.EmptyBorder(
			V2Tokens.PAD, V2Tokens.ROW, V2Tokens.PAD, V2Tokens.ROW));

		page.add(V2Hero.build(theme, "Collections Logged", "1,482 / 1,706", 0.868,
			"as of last log open"));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Glyphs"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		JPanel glyphs = horizontal();
		for (String key : new String[]{V2Glyph.TICK, V2Glyph.TICK_LARGE, V2Glyph.CROSS,
			V2Glyph.LOCK, V2Glyph.STAR, V2Glyph.CHEVRON_CLOSED, V2Glyph.CHEVRON_OPEN,
			V2Glyph.SORT_ASCENDING, V2Glyph.SORT_DESCENDING})
		{
			glyphs.add(new V2Glyph(theme, key));
			glyphs.add(Box.createHorizontalStrut(V2Tokens.PAD));
		}
		glyphs.add(Box.createHorizontalGlue());
		page.add(glyphs);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Bars"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2ProgressBar(theme).fraction(0.35));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2ProgressBar(theme).fraction(1));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(V2Label.faint("unknown — an empty trough, never a zero"));
		page.add(new V2ProgressBar(theme));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Table"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		V2Table table = new V2Table(1);
		table.row(new V2Glyph(theme, V2Glyph.TICK), V2Label.body("Ranarr weed"),
			V2Table.right(V2Label.value("142")));
		table.row(new V2Glyph(theme, V2Glyph.CROSS), V2Label.body("Snapdragon"),
			V2Table.right(V2Label.value("0")));
		table.row(new V2Glyph(theme, V2Glyph.LOCK), V2Label.body("Torstol seed"),
			V2Table.right(V2Label.value("8")));
		table.row(V2Table.blank(), V2Label.body("Grimy toadflax with a long name"),
			V2Table.right(V2Label.value("1,204")));
		page.add(table);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Empty and unknown"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(V2EmptyState.empty(theme, "No runs configured yet."));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(V2EmptyState.unknown(theme, "We haven't seen your house yet.",
			"Enter building mode in your own house to sync what's built."));

		page.setSize(V2Tokens.PANEL_WIDTH, page.getPreferredSize().height);
		return page;
	}

	@Test
	public void statusAtomsRenderInEveryTheme() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			BufferedImage image = SwingRender.render(sheet(theme));
			assertEquals(V2Tokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the sheet collapsed: " + image.getHeight(), image.getHeight() > 400);
			java.io.File out = new java.io.File("build/reports/v2-status-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}

	/** The whole point of the table: one column model for every row. */
	@Test
	public void everyRowGetsTheSameColumnWidths()
	{
		V2Table table = new V2Table(1);
		table.row(new V2Glyph(OsrsTheme.STONE, V2Glyph.TICK), V2Label.body("short"),
			V2Table.right(V2Label.value("1")));
		table.row(new V2Glyph(OsrsTheme.STONE, V2Glyph.CROSS),
			V2Label.body("a considerably longer label"),
			V2Table.right(V2Label.value("1,204")));
		table.setSize(V2Tokens.CONTENT_WIDTH, table.getPreferredSize().height);
		table.doLayout();

		java.awt.Component[] cells = table.getComponents();
		assertEquals("the glyph column must be one width", cells[0].getWidth(), cells[3].getWidth());
		assertEquals("the name column must be one width", cells[1].getWidth(), cells[4].getWidth());
		assertEquals("the count column must be one width", cells[2].getWidth(), cells[5].getWidth());
		// and the count column is as wide as its WIDEST row, so numbers align
		assertTrue(cells[2].getWidth() >= V2Label.value("1,204").getPreferredSize().width);
	}

	@Test
	public void aRaggedTableIsRefused()
	{
		V2Table table = new V2Table(0);
		table.row(V2Label.body("a"), V2Label.body("b"));
		try
		{
			table.row(V2Label.body("only one"));
			org.junit.Assert.fail("a ragged table cannot align its columns");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains("2 cells"));
		}
	}

	@Test
	public void anUnknownBarIsEmptyRatherThanZero()
	{
		V2ProgressBar bar = new V2ProgressBar(OsrsTheme.STONE);
		assertTrue("unknown must stay unknown", Double.isNaN(bar.fraction()));
		assertEquals(0.0, bar.fraction(0).fraction(), 0.0);
	}
}
