package com.ironhub.modules.loot;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LootTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void perKillFormatting()
	{
		assertEquals("—", LootTab.perKillText(10, 0));
		assertEquals("1.2/kill", LootTab.perKillText(120, 100));
		assertEquals("0.0/kill", LootTab.perKillText(1, 250));
		assertEquals("150/kill", LootTab.perKillText(15_000, 100));
	}

	@Test
	public void lootAggregatesAndSurvivesRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 77L);
		before.incrementKillCount("Zulrah");
		before.incrementKillCount("Zulrah");
		before.ingestLoot("Zulrah", Map.of(12934, 100)); // scales
		before.ingestLoot("Zulrah", Map.of(12934, 150, 2402, 1));

		assertEquals(250, (int) before.lootFor("Zulrah").get(12934));
		assertTrue(before.lootSources().contains("Zulrah"));

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 77L);
		assertEquals(250, (int) after.lootFor("Zulrah").get(12934));
		assertEquals(2, after.getKillCount("Zulrah"));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		state.incrementKillCount("Zulrah");
		state.ingestLoot("Zulrah", Map.of(12934, 100, 2402, 1));
		StateFixture.itemNames(state, Map.of(12934, "Zulrah's scales", 2402, "Magic fang"));

		LootModule module = new LootModule(state, null, new IronHubConfig()
		{
		});
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/loot-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
