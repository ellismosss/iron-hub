package com.ironhub.modules.death;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeathRecoveryTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void deathsCaptureCarriedItemsAndSurviveRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 3L);
		StateFixture.inventory(before, Map.of(385, 5));
		StateFixture.equipment(before, Map.of(4151, 1));
		before.recordDeath(new WorldPoint(2273, 4054, 0)); // Vorkath

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 3L);
		assertEquals(1, after.getDeaths().size());
		AccountState.Death death = after.getDeaths().get(0);
		assertEquals(2273, death.where.getX());
		assertEquals(5, (int) death.carried.get(385));
		assertEquals(1, (int) death.carried.get(4151));
	}

	@Test
	public void historyIsCapped()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		for (int i = 0; i < AccountState.MAX_DEATHS + 5; i++)
		{
			state.recordDeath(new WorldPoint(3200 + i, 3200, 0));
		}
		assertEquals(AccountState.MAX_DEATHS, state.getDeaths().size());
		// oldest evicted: first remaining is death #5
		assertEquals(3205, state.getDeaths().get(0).where.getX());
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.inventory(state, Map.of(385, 3, 2434, 2));
		StateFixture.itemNames(state, Map.of(385, "Shark", 2434, "Prayer potion(4)"));
		state.recordDeath(new WorldPoint(2273, 4054, 0));

		DeathRecoveryModule module = new DeathRecoveryModule(state, null, new IronHubConfig()
		{
		}, null);
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 80);
		java.io.File out = new java.io.File("build/reports/death-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
