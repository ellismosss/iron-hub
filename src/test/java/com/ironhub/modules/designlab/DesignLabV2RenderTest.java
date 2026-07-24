package com.ironhub.modules.designlab;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Design lab V2 in both themes — the sign-off surface. Migration is gated on
 * Luke being happy with these, so the renders are the deliverable and the
 * assertions only guard against a collapsed or clipped page.
 */
public class DesignLabV2RenderTest
{
	@Test
	public void theV2GalleryRendersInBothThemes() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			DesignLabV2Tab tab = new DesignLabV2Tab(theme);
			tab.setSize(V2Tokens.PANEL_WIDTH, tab.getPreferredSize().height);
			BufferedImage image = SwingRender.render(tab);
			assertEquals(V2Tokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the gallery collapsed: " + image.getHeight(), image.getHeight() > 1200);
			java.io.File out = new java.io.File("build/reports/designlab-v2-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}

	/** The Design lab shows V2 first and can switch back to the V1 atoms —
	 *  the two systems are judged side by side, not one from memory. */
	@Test
	public void theDesignLabSwitchesBetweenGalleries() throws Exception
	{
		DesignLabTab lab = new DesignLabTab(OsrsTheme.STONE);
		assertTrue("V2 must lead", contains(lab, DesignLabV2Tab.class));
		lab.showGallery(false);
		assertTrue("the V1 gallery must still be reachable",
			!contains(lab, DesignLabV2Tab.class));
		lab.showGallery(true);
		assertTrue(contains(lab, DesignLabV2Tab.class));
	}

	private static boolean contains(java.awt.Container root, Class<?> type)
	{
		for (java.awt.Component child : root.getComponents())
		{
			if (type.isInstance(child)
				|| (child instanceof java.awt.Container && contains((java.awt.Container) child, type)))
			{
				return true;
			}
		}
		return false;
	}
}
