package com.ironhub.modules.diaries;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Varbits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DiariesModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void tierCounting()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		assertEquals(0, DiariesModule.totalTiersComplete(state));

		StateFixture.varbit(state, Varbits.DIARY_ARDOUGNE_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_ARDOUGNE_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_KARAMJA_EASY, 2); // Karamja reports 0–2

		assertEquals(2, DiariesModule.tiersComplete(state, DiariesModule.REGIONS[0]));
		assertEquals(3, DiariesModule.totalTiersComplete(state));
	}

	@Test
	public void tabRendersHeadless()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_EASY, 1);

		DiariesModule module = new DiariesModule(state, new IronHubConfig()
		{
		});
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 200);
		try
		{
			java.io.File out = new java.io.File("build/reports/diaries-tab.png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (java.io.IOException ignored)
		{
		}
		module.shutDown();
	}
}
