package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.gameval.VarbitID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CombatAchievementsModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void nextTierFollowsThresholds()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// thresholds unknown (all 0) — no tier claimed as next
		assertNull(CombatAchievementsModule.nextTier(state));

		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_EASY, 33);
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_MEDIUM, 115);
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_HARD, 304);

		StateFixture.varbit(state, VarbitID.CA_POINTS, 0);
		assertEquals("Easy", CombatAchievementsModule.nextTier(state).name);

		StateFixture.varbit(state, VarbitID.CA_POINTS, 214);
		assertEquals("Hard", CombatAchievementsModule.nextTier(state).name);

		StateFixture.varbit(state, VarbitID.CA_POINTS, 304);
		assertNull(CombatAchievementsModule.nextTier(state)); // past all known thresholds
	}

	@Test
	public void tabRendersHeadless()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.varbit(state, VarbitID.CA_POINTS, 214);
		StateFixture.varbit(state, VarbitID.CA_THRESHOLD_HARD, 304);

		CombatAchievementsModule module = new CombatAchievementsModule(state, new IronHubConfig()
		{
		});
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 150);
		try
		{
			java.io.File out = new java.io.File("build/reports/ca-tab.png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (java.io.IOException ignored)
		{
		}
		module.shutDown();
	}
}
