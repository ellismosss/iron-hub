package com.ironhub.modules.designlab;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Goals hub rebuilt from V2 atoms — the system's first real screen, and
 * the surface the atoms get judged against from here on. The render is the
 * deliverable; the assertions only guard against a collapsed page.
 */
public class GoalsV2RenderTest
{
	@Test
	public void theGoalsHubRendersInEveryTheme() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			GoalsV2View view = new GoalsV2View(theme);
			view.setSize(V2Tokens.PANEL_WIDTH, view.getPreferredSize().height);
			BufferedImage image = SwingRender.render(view);
			assertEquals(V2Tokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the hub collapsed: " + image.getHeight(), image.getHeight() > 500);
			java.io.File out = new java.io.File("build/reports/goals-v2-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}

	/** The lab offers it beside the atoms it is made of. */
	@Test
	public void theDesignLabCanShowIt()
	{
		DesignLabTab lab = new DesignLabTab(OsrsTheme.STONE);
		lab.showView(1);
		assertTrue(contains(lab, GoalsV2View.class));
		lab.showView(0);
		assertTrue(!contains(lab, GoalsV2View.class));
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
