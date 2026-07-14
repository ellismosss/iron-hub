package com.ironhub.modules.dailies;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DailiesModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	// Thu 2026-01-01 12:00 UTC
	private static final long NOW = 1_767_268_800_000L;

	@Test
	public void resetWindows()
	{
		long dailyReset = DailiesModule.lastReset("daily", NOW);
		assertEquals(NOW - 12 * 3_600_000L, dailyReset); // 00:00 UTC same day

		long weeklyReset = DailiesModule.lastReset("weekly", NOW);
		assertEquals(dailyReset - 24 * 3_600_000L, weeklyReset); // previous Wednesday

		assertEquals(0, DailiesModule.lastReset("growth", NOW));
	}

	@Test
	public void manualTickExpiresAtReset()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		DailiesPack pack = new DataPack(new Gson()).load("dailies", DailiesPack.class);
		DailiesPack.Daily first = pack.getDailies().get(0);

		assertFalse(DailiesModule.isDone(state, first, System.currentTimeMillis()));
		state.markDaily(first.getId(), true);
		assertTrue(DailiesModule.isDone(state, first, System.currentTimeMillis()));
		// tomorrow it is outstanding again
		assertFalse(DailiesModule.isDone(state, first, System.currentTimeMillis() + 24 * 3_600_000L));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		DailiesModule module = new DailiesModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()));
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/dailies-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
