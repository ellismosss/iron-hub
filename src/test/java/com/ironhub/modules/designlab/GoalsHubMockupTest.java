package com.ironhub.modules.designlab;

import com.ironhub.ui.SwingRender;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Renders the Goals v2 single-surface proposal (design/GOALS-V2.md §6) in
 * both themes for Luke to judge, and holds it to the client-mount label
 * rule every skinned surface obeys.
 */
public class GoalsHubMockupTest
{
	@Test
	public void mockupRendersEveryThemeAtPanelWidth() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			GoalsHubMockup mockup = new GoalsHubMockup(theme);
			BufferedImage image = SwingRender.render(mockup);
			assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the full surface should be tall", image.getHeight() > 600);

			File out = new File("build/reports/goals-hub-" + theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			ImageIO.write(image, "png", out);
		}
	}

	/** Same single-pass client mount pin as the atom gallery: no label may
	 *  collapse below its preferred height when laid out once. */
	@Test
	public void everyLabelKeepsItsHeightUnderTheClientMount()
	{
		GoalsHubMockup mockup = new GoalsHubMockup(OsrsTheme.MYSTIC);
		com.ironhub.ui.components.HubScrollPane pane =
			new com.ironhub.ui.components.HubScrollPane(mockup);
		pane.setSize(UiTokens.PANEL_WIDTH, 900);
		layoutOnce(pane);

		List<OsrsLabel> labels = new ArrayList<>();
		collect(mockup, labels);
		assertTrue("no labels found", labels.size() > 20);
		for (OsrsLabel label : labels)
		{
			assertTrue("label cut below preferred height: " + label.getBounds(),
				label.getHeight() >= label.getPreferredSize().height);
		}
	}

	private static void layoutOnce(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutOnce(child);
			}
		}
	}

	private static void collect(Container root, List<OsrsLabel> out)
	{
		for (Component child : root.getComponents())
		{
			if (child instanceof OsrsLabel)
			{
				out.add((OsrsLabel) child);
			}
			if (child instanceof Container)
			{
				collect((Container) child, out);
			}
		}
	}
}
