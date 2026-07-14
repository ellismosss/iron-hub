package com.ironhub.modules.bank;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BankTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void searchMatching()
	{
		assertTrue(BankTab.matches("Abyssal whip", ""));
		assertTrue(BankTab.matches("Abyssal whip", "whip"));
		assertTrue(BankTab.matches("Abyssal whip", "  ABYSS "));
		assertFalse(BankTab.matches("Abyssal whip", "ranarr"));
	}

	@Test
	public void relativeTimeBuckets()
	{
		assertEquals("just now", BankTab.relativeTime(30_000));
		assertEquals("5 min ago", BankTab.relativeTime(5 * 60_000));
		assertEquals("2 h ago", BankTab.relativeTime(2 * 3_600_000));
		assertEquals("3 d ago", BankTab.relativeTime(3 * 24 * 3_600_000L));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(4151, 1, 995, 2_500_000, 207, 143));
		StateFixture.itemNames(state, Map.of(
			4151, "Abyssal whip", 995, "Coins", 207, "Grimy ranarr weed"));

		BankTrackerModule module = new BankTrackerModule(state, null, new IronHubConfig()
		{
		});
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/bank-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
