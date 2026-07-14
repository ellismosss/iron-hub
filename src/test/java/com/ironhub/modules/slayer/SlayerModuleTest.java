package com.ironhub.modules.slayer;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.VarPlayer;
import net.runelite.api.gameval.VarbitID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SlayerModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	@Test
	public void readsTaskStateFromVarps()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		SlayerOptimizerModule module = new SlayerOptimizerModule(state, null, null, config, null, null);
		module.startUp();

		assertEquals(0, module.remaining()); // no task
		StateFixture.varp(state, VarPlayer.SLAYER_TASK_SIZE, 87);
		StateFixture.varbit(state, VarbitID.SLAYER_POINTS, 1240);
		StateFixture.varbit(state, VarbitID.SLAYER_TASKS_COMPLETED, 43);

		assertEquals(87, module.remaining());
		assertEquals(1240, module.points());
		assertEquals(43, module.streak());
		module.shutDown();
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.varp(state, VarPlayer.SLAYER_TASK_SIZE, 87);
		StateFixture.varbit(state, VarbitID.SLAYER_POINTS, 1240);
		StateFixture.varbit(state, VarbitID.SLAYER_TASKS_COMPLETED, 43);

		SlayerOptimizerModule module = new SlayerOptimizerModule(state, null, null, config, null, null);
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 80);
		java.io.File out = new java.io.File("build/reports/slayer-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
