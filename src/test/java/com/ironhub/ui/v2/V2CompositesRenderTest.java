package com.ironhub.ui.v2;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The four controls the curated set has no art for, built from curated
 * pieces (§9): field, dropdown, scrollbar, tooltip.
 */
public class V2CompositesRenderTest
{
	private static JPanel sheet(OsrsTheme theme)
	{
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.setBackground(theme.background);
		page.setBorder(new javax.swing.border.EmptyBorder(
			V2Tokens.PAD, V2Tokens.ROW, V2Tokens.PAD, V2Tokens.ROW));

		page.add(V2Label.heading("Text field"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2TextField(theme, "Search items...", null));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		V2TextField typed = new V2TextField(theme, "Search items...", null);
		typed.setText("ranarr");
		page.add(typed);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Dropdown"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2Dropdown(theme, "Value", "Attack bonus", "Defence bonus"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		V2Dropdown picked = new V2Dropdown(theme, "Value", "Attack bonus", "Defence bonus");
		picked.setSelected(1);
		page.add(picked);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Tooltip"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		V2Tooltip tip = new V2Tooltip(theme);
		tip.setTipText("Needs Farming 65 and a Magic secateurs you don't own yet.");
		tip.setSize(tip.getPreferredSize());
		JPanel tipHolder = new JPanel();
		tipHolder.setOpaque(false);
		tipHolder.setLayout(new BoxLayout(tipHolder, BoxLayout.X_AXIS));
		tipHolder.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		tipHolder.add(tip);
		tipHolder.add(Box.createHorizontalGlue());
		page.add(tipHolder);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Scrollbar"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		JPanel tall = new JPanel();
		tall.setLayout(new BoxLayout(tall, BoxLayout.Y_AXIS));
		tall.setOpaque(false);
		for (int i = 1; i <= 12; i++)
		{
			tall.add(V2Label.body("Row " + i));
		}
		JScrollPane pane = new JScrollPane(tall,
			JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		V2ScrollBarUI.install(pane, theme);
		pane.setPreferredSize(new java.awt.Dimension(V2Tokens.CONTENT_WIDTH, 80));
		pane.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 80));
		pane.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		page.add(pane);

		page.setSize(V2Tokens.PANEL_WIDTH, page.getPreferredSize().height);
		return page;
	}

	@Test
	public void compositesRenderInEveryTheme() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			JPanel built = sheet(theme);
			BufferedImage image = SwingRender.render(built);
			V2Tooltip laidOut = null;
			for (java.awt.Component k : built.getComponents())
			{
				if (k instanceof JPanel && ((JPanel) k).getComponentCount() > 0
					&& ((JPanel) k).getComponent(0) instanceof V2Tooltip)
				{
					laidOut = (V2Tooltip) ((JPanel) k).getComponent(0);
				}
			}
			assertNotNull(laidOut);
			assertEquals("a tooltip squeezed below its text clips the last line",
				laidOut.getPreferredSize().height, laidOut.getHeight());
			assertEquals(V2Tokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the sheet collapsed: " + image.getHeight(), image.getHeight() > 300);
			java.io.File out = new java.io.File("build/reports/v2-composites-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}

	@Test
	public void theDropdownReportsAndFiresItsSelection()
	{
		int[] picked = {-1};
		V2Dropdown dropdown = new V2Dropdown(OsrsTheme.STONE, "A", "B", "C")
			.onChange(i -> picked[0] = i);
		assertEquals(0, dropdown.selected());
		dropdown.setSelected(2);
		assertEquals("programmatic selection must not fire onChange", -1, picked[0]);
		dropdown.pick(1);
		assertEquals(1, picked[0]);
		assertEquals(1, dropdown.selected());
	}

	/** Every atom that can carry a tooltip must wear the V2 one — a stray
	 *  default tooltip is a yellow Swing box in the middle of the skin. */
	@Test
	public void atomsProduceV2Tooltips()
	{
		assertTrue(V2Surface.card(OsrsTheme.STONE).createToolTip() instanceof V2Tooltip);
		assertTrue(new V2Tile(OsrsTheme.STONE, null, null, 40, null)
			.createToolTip() instanceof V2Tooltip);
		assertTrue(new V2SpriteButton(OsrsTheme.STONE, V2SpriteButton.WRENCH, null)
			.createToolTip() instanceof V2Tooltip);
		assertTrue(new V2Glyph(OsrsTheme.STONE, V2Glyph.TICK)
			.createToolTip() instanceof V2Tooltip);
	}

	/** The card must be tall enough for the wrapped text, and the ONLY text
	 *  in it must be ours — the look-and-feel's tooltip painter drew a second
	 *  copy spilling out of the card until paintComponent stopped delegating. */
	@Test
	public void theTooltipSizesItselfToItsOwnWrappedText()
	{
		V2Tooltip tip = new V2Tooltip(OsrsTheme.STONE);
		tip.setTipText("Needs Farming 65 and a Magic secateurs you don't own yet.");
		java.awt.Dimension card = tip.getPreferredSize();
		java.awt.Dimension text = tip.getComponent(0).getPreferredSize();
		// a tooltip is a 1px box with 2px of padding — it covers what you are
		// pointing at, so its job is to be small (Luke, 2026-07-25)
		assertEquals(text.height + 2 * V2Tokens.TIGHT, card.height);
		assertTrue("the text must wrap to more than one line", text.height > V2Tokens.LINE_PITCH);
	}

	@Test
	public void theFieldReportsWhatWasTyped()
	{
		V2TextField field = new V2TextField(OsrsTheme.STONE, "Search...", null);
		assertEquals("", field.getText());
		field.setText("ranarr");
		assertEquals("ranarr", field.getText());
		assertNotNull(field.editor());
	}
}
