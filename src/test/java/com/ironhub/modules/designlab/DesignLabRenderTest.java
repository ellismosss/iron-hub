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
}
