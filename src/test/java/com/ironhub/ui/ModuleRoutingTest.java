package com.ironhub.ui;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.qol.QolModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Opening a nav block must mount the module tabs it lists. */
public class ModuleRoutingTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void qolChecklistMountsInTheProgressionBlock() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		IronHubConfig config = new IronHubConfig()
		{
		};
		QolModule qol = new QolModule(state, config, new DataPack(new Gson()));
		assertTrue(qol.enabled());
		assertNotNull(qol.buildTab());

		IronHubPanel panel = new IronHubPanel(Set.of((IronHubModule) qol), state, new DataPack(new Gson()), config);
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.openBlock("Progression"));
		assertTrue("QoL tab not mounted by its block",
			javax.swing.SwingUtilities.isDescendingFrom(qol.buildTab(), panel));

		// the header-plate grammar across a many-module hub page
		java.awt.image.BufferedImage image = SwingRender.render(panel);
		java.io.File out = new java.io.File("build/reports/home-progression-hub.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}
}
