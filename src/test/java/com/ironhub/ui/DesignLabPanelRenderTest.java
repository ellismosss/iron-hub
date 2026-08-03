package com.ironhub.ui;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.designlab.DesignLabModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Design lab as Luke actually sees it: the REAL {@link IronHubPanel},
 * with the Settings hub open, at panel width.
 *
 * <p>This test exists because it was missing, and its absence cost three
 * rounds (2026-07-25). Every V2 render before it drew the tab standalone —
 * which cannot show the panel's own frame around the content, cannot show
 * what the hub's layout does to a control's height, and cannot show the
 * scroll pane. Luke reported a grey border wrapping the page, chips with
 * their bottoms cut off and a broken inventory frame; the first two are
 * invisible in a standalone render, so I kept "fixing" them against a
 * picture that never had them in it.
 *
 * <p><b>Any V2 change is verified against THIS render, not the gallery's.</b>
 */
public class DesignLabPanelRenderTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void theDesignLabInTheRealPanel() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 7L);
		for (OsrsTheme theme : OsrsTheme.values())
		{
			IronHubConfig config = new IronHubConfig()
			{
				@Override
				public OsrsTheme osrsTheme()
				{
					return theme;
				}
			};
			DesignLabModule lab = new DesignLabModule(config, new net.runelite.client.eventbus.EventBus(), null);
			IronHubPanel panel = new IronHubPanel(
				Set.of((IronHubModule) lab), state, config, null);
			javax.swing.SwingUtilities.invokeAndWait(() -> panel.openBlock("Settings"));
			javax.swing.SwingUtilities.invokeAndWait(() ->
			{
			});

			BufferedImage image = SwingRender.render(panel);
			assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the panel collapsed: " + image.getHeight(), image.getHeight() > 600);
			// written BEFORE the assertion: the render is the thing you look at
			// to find out WHY this failed, and withholding it on failure is
			// exactly when it is most wanted
			java.io.File out = new java.io.File("build/reports/panel-designlab-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);

			// The defect that started this test: a skinned tab painting the
			// classic grey behind itself framed the WHOLE PAGE in grey — it
			// showed at x=8 for essentially every row.
			//
			// Counted rather than asserted per-pixel, because a single pixel
			// proves nothing: Mystic's dimmed frame edge lands on exactly
			// #262626 by coincidence, so the top and bottom border rows of the
			// V2 Frame tripped the old per-pixel form (2026-07-25). A backing
			// is a band; a border is a line. This still fails hard on the
			// original defect, which greyed hundreds of rows.
			int grey = 0;
			for (int y = 0; y < image.getHeight(); y++)
			{
				if (image.getRGB(8, y) == UiTokens.PANEL_BG.getRGB())
				{
					grey++;
				}
			}
			assertTrue("classic grey behind the skin in " + theme + ": " + grey
				+ " of " + image.getHeight() + " rows at x=8", grey <= 8);
		}
	}
}
