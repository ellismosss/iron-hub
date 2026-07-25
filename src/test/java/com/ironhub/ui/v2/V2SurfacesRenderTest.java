package com.ironhub.ui.v2;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The surface and text atoms, drawn at panel width in both themes. */
public class V2SurfacesRenderTest
{
	private static JPanel sheet(OsrsTheme theme)
	{
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.setBackground(theme.background);
		page.setBorder(new javax.swing.border.EmptyBorder(
			V2Tokens.PAD, V2Tokens.ROW, V2Tokens.PAD, V2Tokens.ROW));

		page.add(V2Label.heading("Surfaces"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));

		V2Surface card = V2Surface.card(theme);
		card.stack(V2Label.heading("Card"), V2Tokens.ROW);
		card.stack(V2Label.body("The filled surface. Sections, tiles."), V2Tokens.ROW);
		card.add(V2Label.faint("as of last log open"));
		page.add(card);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		V2Surface well = V2Surface.well(theme);
		well.stack(V2Label.heading("Well"), V2Tokens.ROW);
		well.stack(V2Label.body("Border only. Framed lists, fields."), V2Tokens.ROW);
		well.add(V2Label.value("1,482"));
		page.add(well);
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(new V2Divider(theme));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Text roles"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(V2Label.body("Body — the default"));
		page.add(V2Label.value("Value — 99 Slayer"));
		page.add(V2Label.detail("Detail — a second line"));
		page.add(V2Label.faint("Faint — provenance"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(V2Label.status("Done", V2Tokens.DONE));
		page.add(V2Label.status("Actionable now", V2Tokens.ACTION));
		page.add(V2Label.status("Blocked", V2Tokens.BLOCKED));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(V2Label.wrapped("Wrapped body text measured with the pixel font, "
			+ "never html, so it breaks between words instead of mid-glyph.",
			V2Tokens.CONTENT_WIDTH - 2 * V2Tokens.ROW));

		page.add(Box.createVerticalStrut(V2Tokens.SECTION));
		V2Surface frame = V2Surface.inventoryFrame(theme);
		frame.add(V2Label.body("Frame — the outer panel border"));
		page.add(frame);

		page.setSize(V2Tokens.PANEL_WIDTH, page.getPreferredSize().height);
		return page;
	}

	@Test
	public void surfacesRenderInEveryTheme() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			BufferedImage image = SwingRender.render(sheet(theme));
			assertEquals(V2Tokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the sheet collapsed: " + image.getHeight(), image.getHeight() > 300);
			java.io.File out = new java.io.File("build/reports/v2-surfaces-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}

	/**
	 * Every surface clears its OWN art by the same rule — the art's inset
	 * plus PAD — and is square about it. The insets differ between families
	 * because the art does (the card's bevel is 9px, the well's cap is 4),
	 * but two cards anywhere in the plugin still have identical geometry.
	 */
	@Test
	public void everySurfaceInsetsItsContentSquarely()
	{
		java.awt.Insets card = V2Surface.card(OsrsTheme.STONE).getInsets();
		assertEquals(V2Tokens.SLICE_INSET + V2Tokens.PAD, card.left);
		java.awt.Insets well = V2Surface.well(OsrsTheme.STONE).getInsets();
		assertEquals(com.ironhub.ui.v2.V2Well.CAP + V2Tokens.PAD, well.left);
		for (java.awt.Insets insets : new java.awt.Insets[]{card, well})
		{
			assertEquals(insets.left, insets.top);
			assertEquals(insets.left, insets.right);
			assertEquals(insets.left, insets.bottom);
		}
	}

	@Test
	public void statusTextRefusesAColourThatIsNotAStatus()
	{
		try
		{
			V2Label.status("nearly there", java.awt.Color.CYAN);
			org.junit.Assert.fail("colour is never decoration");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage().contains("BLOCKED"));
		}
	}
}
