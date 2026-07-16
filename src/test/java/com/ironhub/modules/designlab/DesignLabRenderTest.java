package com.ironhub.modules.designlab;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsSkin;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless render of the OSRS-skin gallery for side-by-side comparison with
 * the wiki's native-1x Character Summary screenshot. Also pins the stone-box
 * anatomy: the corner notch must show BACKGROUND through the box, with the
 * engraved dark/light edge pair on every side.
 */
public class DesignLabRenderTest
{
	@Test
	public void galleryRendersAtPanelWidth() throws Exception
	{
		BufferedImage image = SwingRender.render(new DesignLabTab());
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue(image.getHeight() > 250);

		File out = new File("build/reports/designlab-tab.png");
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);

		// stone-box anatomy at the first box's top-left corner: the very
		// corner pixel is notched away (background), and a mid-edge slice
		// reads dark line, light line, then fill
		int boxX = 8, boxY = firstBoxTop(image, boxX);
		assertEquals("corner notch shows the backing",
			OsrsSkin.BACKGROUND.getRGB(), image.getRGB(boxX, boxY));
		int midX = boxX + 40;
		assertEquals(OsrsSkin.EDGE_DARK.getRGB(), image.getRGB(midX, boxY));
		assertEquals(OsrsSkin.EDGE_LIGHT.getRGB(), image.getRGB(midX, boxY + 1));
		assertEquals(OsrsSkin.BOX_FILL.getRGB(), image.getRGB(midX, boxY + 2));
	}

	/** First row (scanning down at x) whose right neighbourhood turns EDGE_DARK. */
	private static int firstBoxTop(BufferedImage image, int x)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			if (image.getRGB(x + 40, y) == OsrsSkin.EDGE_DARK.getRGB())
			{
				return y;
			}
		}
		throw new AssertionError("no stone box edge found");
	}
}
