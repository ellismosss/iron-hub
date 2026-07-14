package com.ironhub.modules.collectionlog;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CollectionLogTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void titleParsing()
	{
		assertArrayEquals(new int[]{246, 1568},
			CollectionLogModule.parseTitle("Collection Log - 246/1,568"));
		assertArrayEquals(new int[]{1568, 1568},
			CollectionLogModule.parseTitle("Collection Log - 1,568/1,568"));
		assertNull(CollectionLogModule.parseTitle("Bank of Gielinor"));
		assertNull(CollectionLogModule.parseTitle(null));
	}

	@Test
	public void progressPersistsAcrossRestart()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 21L);
		before.recordCollectionLog(246, 1568);

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 21L);
		assertEquals(246, after.getCollectionLogSlots());
		assertEquals(1568, after.getCollectionLogTotal());
		assertTrue(after.getCollectionLogSeenMs() > 0);
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		state.recordCollectionLog(246, 1568);
		state.setKillCount("Zulrah", 234);
		state.setKillCount("Vorkath", 58);

		CollectionLogModule module = new CollectionLogModule(state, null, null,
			new EventBus(), new IronHubConfig()
		{
		});
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 150);
		java.io.File out = new java.io.File("build/reports/collectionlog-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
