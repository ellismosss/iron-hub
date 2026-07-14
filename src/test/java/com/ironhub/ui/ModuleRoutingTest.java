package com.ironhub.ui;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.loadout.LoadoutModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
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
	public void loadoutOpensACard()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		IronHubConfig config = new IronHubConfig()
		{
		};
		LoadoutModule loadout = new LoadoutModule(state, null, null, config, new DataPack(new Gson()), new Gson(), null);
		assertTrue(loadout.enabled());
		assertNotNull(loadout.buildTab());

		IronHubPanel panel = new IronHubPanel(Set.of((IronHubModule) loadout), state, new DataPack(new Gson()));
		int before = componentCount(panel);
		panel.openModule("Loadout");
		assertTrue("openModule added no card", componentCount(panel) > before);
	}

	private static int componentCount(IronHubPanel panel)
	{
		return ((javax.swing.JPanel) panel.getComponent(0)).getComponentCount();
	}
}
