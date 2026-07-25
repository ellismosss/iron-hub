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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The action atoms, and the rule that a state must exist in the art. */
public class V2ActionsRenderTest
{
	private static JPanel row(OsrsTheme theme, String... keys)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String key : keys)
		{
			row.add(new V2SpriteButton(theme, key, null));
			row.add(Box.createHorizontalStrut(V2Tokens.PAD));
		}
		row.add(Box.createHorizontalGlue());
		return row;
	}

	private static JPanel sheet(OsrsTheme theme)
	{
		JPanel page = new JPanel();
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.setBackground(theme.background);
		page.setBorder(new javax.swing.border.EmptyBorder(
			V2Tokens.PAD, V2Tokens.ROW, V2Tokens.PAD, V2Tokens.ROW));

		page.add(V2Label.heading("Button"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2Button(theme, "Save setup", null));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(new V2Button(theme, "Saved", null).labelColor(V2Tokens.DONE));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Utility"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(row(theme, V2SpriteButton.WRENCH, V2SpriteButton.HELP, V2SpriteButton.MENU,
			V2SpriteButton.CLOSE, V2SpriteButton.CANCEL));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Steppers"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(row(theme, V2SpriteButton.INCREMENT, V2SpriteButton.DECREMENT,
			V2SpriteButton.PLUS, V2SpriteButton.MINUS,
			V2SpriteButton.PLUS_LARGE, V2SpriteButton.MINUS_LARGE));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Arrows"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(row(theme, V2SpriteButton.ARROW_UP, V2SpriteButton.ARROW_DOWN,
			V2SpriteButton.ARROW_LEFT, V2SpriteButton.ARROW_RIGHT, V2SpriteButton.BACK));
		page.add(Box.createVerticalStrut(V2Tokens.SECTION));

		page.add(V2Label.heading("Squares and wiki"));
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(row(theme, V2SpriteButton.SQUARE, V2SpriteButton.SQUARE_SMALL,
			V2SpriteButton.SQUARE_LARGE, V2SpriteButton.WIKI));

		JPanel selected = new JPanel();
		selected.setLayout(new BoxLayout(selected, BoxLayout.X_AXIS));
		selected.setOpaque(false);
		selected.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (String key : new String[]{V2SpriteButton.SQUARE_SMALL,
			V2SpriteButton.SQUARE_LARGE, V2SpriteButton.WIKI, V2SpriteButton.MENU})
		{
			V2SpriteButton button = new V2SpriteButton(theme, key, null);
			button.setSelected(true);
			selected.add(button);
			selected.add(Box.createHorizontalStrut(V2Tokens.PAD));
		}
		selected.add(Box.createHorizontalGlue());
		page.add(Box.createVerticalStrut(V2Tokens.ROW));
		page.add(V2Label.faint("selected states"));
		page.add(selected);

		page.setSize(V2Tokens.PANEL_WIDTH, page.getPreferredSize().height);
		return page;
	}

	@Test
	public void actionsRenderInEveryTheme() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			BufferedImage image = SwingRender.render(sheet(theme));
			assertEquals(V2Tokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the sheet collapsed: " + image.getHeight(), image.getHeight() > 250);
			java.io.File out = new java.io.File("build/reports/v2-actions-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}

	/** §8, both directions: the art decides which states exist. */
	@Test
	public void statesAreOfferedOnlyWhereTheArtHasThem()
	{
		assertTrue(new V2SpriteButton(OsrsTheme.STONE, V2SpriteButton.WRENCH, null).hasHover());
		assertFalse("the game drew no hovered increment button",
			new V2SpriteButton(OsrsTheme.STONE, V2SpriteButton.INCREMENT, null).hasHover());
		assertTrue(new V2SpriteButton(OsrsTheme.STONE, V2SpriteButton.WIKI, null).canSelect());
		V2SpriteButton wrench = new V2SpriteButton(OsrsTheme.STONE, V2SpriteButton.WRENCH, null);
		assertFalse(wrench.canSelect());
		try
		{
			wrench.setSelected(true);
			fail("a toggle must not be built on art that cannot show selection");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(expected.getMessage().contains("selected state"));
		}
	}

	@Test
	public void everyNamedSpriteExists()
	{
		// declared only — JComponent contributes public String constants of
		// its own (TOOL_TIP_TEXT_KEY), which are not sprite keys
		for (java.lang.reflect.Field field : V2SpriteButton.class.getDeclaredFields())
		{
			if (field.getType() == String.class
				&& java.lang.reflect.Modifier.isPublic(field.getModifiers()))
			{
				try
				{
					String key = (String) field.get(null);
					assertTrue(field.getName() + " -> " + key, V2Sprites.has(key));
				}
				catch (IllegalAccessException e)
				{
					fail(e.getMessage());
				}
			}
		}
	}
}
