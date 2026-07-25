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
			DesignLabModule lab = new DesignLabModule(config, new net.runelite.client.eventbus.EventBus());
			IronHubPanel panel = new IronHubPanel(
				Set.of((IronHubModule) lab), state, config, null);
			javax.swing.SwingUtilities.invokeAndWait(() -> panel.openBlock("Settings"));
			javax.swing.SwingUtilities.invokeAndWait(() ->
			{
			});

			BufferedImage image = SwingRender.render(panel);
			assertEquals(UiTokens.PANEL_WIDTH, image.getWidth());
			assertTrue("the panel collapsed: " + image.getHeight(), image.getHeight() > 600);
			// the defect that started this test: a skinned tab painting the
			// classic grey behind itself framed the whole page in grey
			for (int y = 0; y < image.getHeight(); y++)
			{
				assertTrue("classic grey behind the skin at y=" + y,
					image.getRGB(8, y) != UiTokens.PANEL_BG.getRGB());
			}
			java.io.File out = new java.io.File("build/reports/panel-designlab-"
				+ theme.name().toLowerCase() + ".png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
	}
}
