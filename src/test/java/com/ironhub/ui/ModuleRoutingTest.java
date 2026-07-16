package com.ironhub.ui;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.qol.QolModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.awt.Component;
import java.awt.Container;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Clicking a nav row must open a card for every module with a tab. */
public class ModuleRoutingTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void qolChecklistOpensACard()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		IronHubConfig config = new IronHubConfig()
		{
		};
		QolModule qol = new QolModule(state, config, new DataPack(new Gson()));
		assertTrue(qol.enabled());
		assertNotNull(qol.buildTab());

		IronHubPanel panel = new IronHubPanel(Set.of((IronHubModule) qol), state, new DataPack(new Gson()), config);
		int before = componentCount(panel);
		panel.openModule("QoL checklist");
		assertTrue("openModule added no card", componentCount(panel) > before);
	}

	private static int componentCount(Container root)
	{
		int count = 0;
		for (Component child : root.getComponents())
		{
			count++;
			if (child instanceof Container)
			{
				count += componentCount((Container) child);
			}
		}
		return count;
	}
}
