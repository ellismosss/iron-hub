package com.ironhub.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless render of every shared atom — fails on layout/paint exceptions
 * and writes build/reports/atom-gallery.png for mockup side-by-sides.
 */
public class AtomsSmokeTest
{
	@Test
	public void galleryRendersAtPanelWidth() throws Exception
	{
		BufferedImage image = AtomGallery.render();
		assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
		assertTrue("gallery rendered empty", image.getHeight() > 100);

		File out = new File("build/reports/atom-gallery.png");
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
	}
}
