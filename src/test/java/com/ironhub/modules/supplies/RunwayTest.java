package com.ironhub.modules.supplies;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.SuppliesPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.GoalSeeds;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The rebuilt Supplies runway: a categorised threshold watchlist, not a
 * consumption-rate estimate. The load-bearing behaviours are the watchlist
 * diffing (defaults minus removals, plus additions) and the no-weapon
 * guarantee that fixes the reported bug.
 */
public class RunwayTest
{
	private static final int SHARK = 385;
	private static final int STEEL_CANNONBALL = 2;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private SuppliesRunwayModule module(AccountState state)
	{
		return new SuppliesRunwayModule(state, null, config, new DataPack(new Gson()));
	}

	@Test
	public void watchlistIsTheDefaultsUntilThePlayerEditsIt()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		SuppliesRunwayModule module = module(state);

		List<SuppliesPack.Item> food = module.watchlist("food");
		assertTrue("food starts with its curated defaults", food.size() >= 10);
		assertTrue("shark is a default food",
			food.stream().anyMatch(i -> i.id == SHARK));

		// remove a default -> it leaves the list
		state.untrackSupply(SHARK, true);
		assertFalse(module.watchlist("food").stream().anyMatch(i -> i.id == SHARK));

		// add a non-default (a steel cannonball is not a default) -> it joins its category
		state.trackSupply(STEEL_CANNONBALL, false);
		assertTrue(module.watchlist("ammo").stream().anyMatch(i -> i.id == STEEL_CANNONBALL)
			|| module.watchlist("materials").stream().anyMatch(i -> i.id == STEEL_CANNONBALL));
	}

	@Test
	public void noWeaponAppearsInAnyWatchlistOrSearch()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		SuppliesRunwayModule module = module(state);
		assertTrue("Rune longsword must not be searchable",
			module.pack().search("longsword").isEmpty());
		for (SuppliesPack.Category c : module.pack().categories)
		{
			for (SuppliesPack.Item item : module.watchlist(c.key))
			{
				assertFalse(item.name, item.name.toLowerCase().endsWith("longsword"));
			}
		}
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// the initial view is the first category (Potions); seed below-target
		// (red) and above-target (light) rows there
		int superCombat = 12695, prayerPot = 2434, superRestore = 3024;
		StateFixture.bank(state, Map.of(superCombat, 500, prayerPot, 10, superRestore, 3));
		state.setSupplyThreshold(superCombat, 100);    // 500 >= 100 -> light
		state.setSupplyThreshold(prayerPot, 50);       // 10 < 50 -> red
		state.setSupplyThreshold(superRestore, 20);    // 3 < 20 -> red
		state.addGoalSeed(GoalSeeds.supply(prayerPot, "Prayer potion(4)", 50)); // × glyph

		SuppliesRunwayModule module = module(state);
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);

		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 80);
		write(image, "supplies-runway-tab.png");

		// the search view
		SwingUtilities.invokeAndWait(() -> ((RunwayTab) tab).searchForTest("potion"));
		SwingUtilities.invokeAndWait(() -> { });   // drain the queued rebuild
		write(SwingRender.render((JPanel) tab), "supplies-runway-search.png");

		module.shutDown();
	}

	private static void write(java.awt.image.BufferedImage image, String name) throws Exception
	{
		java.io.File out = new java.io.File("build/reports/" + name);
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
	}
}
