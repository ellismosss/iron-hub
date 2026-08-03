package com.ironhub.ui.osrs;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * G1 (2026-08-03): the quests/achievements stat icons shipped with the
 * game interface's brown backing baked into the PNG — on the Goals hub's
 * stat slabs it read as a subtle highlight plate behind the icon. Every
 * stat icon must be transparent outside its own ink, like its siblings
 * (combat_level, total_level, total_xp) always were.
 */
public class OsrsIconsTest
{
	@Test
	public void statIconsCarryNoBakedBacking() throws IOException
	{
		for (String name : new String[]{"quests", "achievements", "combat_level",
			"total_level", "total_xp", "combat_tasks", "collections_logged"})
		{
			BufferedImage image = ImageIO.read(
				OsrsIconsTest.class.getResourceAsStream("/data/icons/osrs/" + name + ".png"));
			assertNotNull(name + ".png missing", image);
			// the four corners are outside any 18x18 stat icon's ink — an
			// opaque corner means a baked interface backing
			int w = image.getWidth() - 1;
			int h = image.getHeight() - 1;
			for (int[] corner : new int[][]{{0, 0}, {w, 0}, {0, h}, {w, h}})
			{
				int alpha = (image.getRGB(corner[0], corner[1]) >>> 24);
				assertTrue(name + ".png has an opaque corner — baked backing",
					alpha == 0);
			}
		}
	}
}
