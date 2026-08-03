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

	/**
	 * DR1 2026-08-03: the grave-fee estimate follows the wiki's verified
	 * bands (free under 100k, then 1k / 10k / 100k per item, 500k cap, irons
	 * half), assumes the three highest-value unstackables are kept, and
	 * answers -1 — unknown, never a guess — for a valuable stackable whose
	 * band basis the wiki does not document.
	 */
	@Test
	public void graveFeeBandsKeptItemsAndHonestUnknowns()
	{
		java.util.List<long[]> items = new java.util.ArrayList<>(java.util.List.of(
			new long[]{5_000_000, 1, 0},  // kept (1st)
			new long[]{2_000_000, 1, 0},  // kept (2nd)
			new long[]{500_000, 1, 0},    // kept (3rd)
			new long[]{200_000, 1, 0},    // 100k..1m band: 1,000
			new long[]{50_000, 5, 0}));   // under 100k: free
		assertEquals(1_000, AccountState.graveFeeEstimate(items, false));
		assertEquals(500, AccountState.graveFeeEstimate(items, true));

		// a cheap stack is provably free either way the bands are read
		items.add(new long[]{10, 500, 1});
		assertEquals(1_000, AccountState.graveFeeEstimate(items, false));

		// a valuable stack has no documented band basis: unknown, never a guess
		items.add(new long[]{60, 2_000, 1});
		assertEquals(-1, AccountState.graveFeeEstimate(items, false));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.inventory(state, Map.of(385, 3, 2434, 2));
		StateFixture.itemNames(state, Map.of(385, "Shark", 2434, "Prayer potion(4)"));
		state.recordDeath(new WorldPoint(3288, 3886, 0)); // older death
		state.recordDeath(new WorldPoint(2273, 4054, 0));

		DeathRecoveryModule module = new DeathRecoveryModule(state, null, new IronHubConfig()
		{
			@Override
			public com.ironhub.ui.osrs.OsrsTheme osrsTheme()
			{
				// Vanilla: osrsTheme() defaults to MYSTIC, and the renders
				// exist to be judged against the Vanilla design system
				return com.ironhub.ui.osrs.OsrsTheme.STONE;
			}
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
