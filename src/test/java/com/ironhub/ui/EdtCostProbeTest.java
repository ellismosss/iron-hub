package com.ironhub.ui;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.IronHubPlugin;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.modules.bank.BankTrackerModule;
import com.ironhub.modules.ca.CombatAchievementsModule;
import com.ironhub.modules.clues.ClueStashModule;
import com.ironhub.modules.collectionlog.CollectionLogModule;
import com.ironhub.modules.dailies.DailiesModule;
import com.ironhub.modules.dashboard.DashboardModule;
import com.ironhub.modules.death.DeathRecoveryModule;
import com.ironhub.modules.diaries.DiariesModule;
import com.ironhub.modules.farming.FarmingRunModule;
import com.ironhub.modules.gear.GearProgressionModule;
import com.ironhub.modules.goals.GoalPlannerModule;
import com.ironhub.modules.loot.LootModule;
import com.ironhub.modules.qol.QolModule;
import com.ironhub.modules.quests.QuestsModule;
import com.ironhub.modules.slayer.SlayerOptimizerModule;
import com.ironhub.modules.supplies.SuppliesRunwayModule;
import com.ironhub.modules.sync.ExternalSyncModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * EDT cost probe for the freeze reports (2026-07-17): builds the full
 * module set over a realistically sized account, opens every nav block,
 * then measures how long the EDT takes to drain after state notifications
 * with every hub's tabs alive. Prints a timing table; the assertions only
 * catch pathological regressions (an order of magnitude, not noise).
 */
public class EdtCostProbeTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void edtCostsStaySane() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		seed(state);

		IronHubConfig config = new IronHubConfig()
		{
		};
		Set<IronHubModule> modules = buildModules(state, config);
		for (IronHubModule module : modules)
		{
			if (!(module instanceof com.ironhub.modules.loadoutlab.LoadoutLabModule))
			{
				module.startUp();
			}
		}

		IronHubPanel[] panel = new IronHubPanel[1];
		long t0 = System.nanoTime();
		SwingUtilities.invokeAndWait(() ->
			panel[0] = new IronHubPanel(modules, state, new DataPack(new Gson()), config));
		System.out.printf("panel + home build: %d ms%n", ms(t0));

		// open every block once — the tab-construction cost a first click pays
		for (String block : IronHubPanel.blockContents().keySet())
		{
			long t = System.nanoTime();
			SwingUtilities.invokeAndWait(() -> panel[0].openBlock(block));
			SwingUtilities.invokeAndWait(() -> layout(panel[0]));
			System.out.printf("open %-12s %d ms%n", block, ms(t));
		}

		// second visit: tabs cached, only re-mount + layout
		long tRe = System.nanoTime();
		SwingUtilities.invokeAndWait(() -> panel[0].openBlock("Bank"));
		SwingUtilities.invokeAndWait(() -> layout(panel[0]));
		long reopenMs = ms(tRe);
		System.out.printf("re-open Bank: %d ms%n", reopenMs);

		// notification storm: every alive tab reacts to each state change.
		// setUnlocked fires listeners synchronously here (test thread stands
		// in for the client thread), tabs invokeLater their rebuilds.
		int storms = 20;
		long tStorm = System.nanoTime();
		for (int i = 0; i < storms; i++)
		{
			state.setUnlocked("perf_probe_" + i, true);
		}
		SwingUtilities.invokeAndWait(() -> layout(panel[0])); // drain the queue
		long stormMs = ms(tStorm);
		System.out.printf("%d notifies with all hubs alive: %d ms (%.1f ms each)%n",
			storms, stormMs, stormMs / (double) storms);

		// ballpark guards only — CI machines vary wildly
		org.junit.Assert.assertTrue("EDT drain after " + storms + " notifies took " + stormMs
			+ " ms — rebuild storm regression", stormMs < 20_000);
	}

	private static void layout(IronHubPanel panel)
	{
		panel.setSize(UiTokens.PANEL_WIDTH, 4000);
		layoutTree(panel);
	}

	private static void layoutTree(java.awt.Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	private static long ms(long fromNanos)
	{
		return (System.nanoTime() - fromNanos) / 1_000_000;
	}

	/** A mature account: 1,200 banked stacks, all quests, mid-high stats. */
	private static void seed(AccountState state)
	{
		for (Skill skill : Skill.values())
		{
			StateFixture.stat(state, skill, 75, 1_300_000);
		}
		Quest[] quests = Quest.values();
		for (int i = 0; i < quests.length; i++)
		{
			StateFixture.quest(state, quests[i],
				i % 3 == 0 ? QuestState.NOT_STARTED : QuestState.FINISHED);
		}
		Map<Integer, Integer> bank = new HashMap<>();
		Map<Integer, String> names = new HashMap<>();
		for (int id = 2; id < 1_202; id++)
		{
			bank.put(id, 1 + (id % 500));
			names.put(id, "Item number " + id);
		}
		StateFixture.itemNames(state, names);
		StateFixture.bank(state, bank);
		Map<Integer, Integer> inv = new HashMap<>();
		for (int id = 2; id < 30; id++)
		{
			inv.put(id, 1);
		}
		StateFixture.inventory(state, inv);
		state.recordDeath(new net.runelite.api.coords.WorldPoint(3200, 3200, 0));
	}

	private static Set<IronHubModule> buildModules(AccountState state, IronHubConfig config)
	{
		return Set.of(
			new GearProgressionModule(state, config, new DataPack(new Gson()), null, null),
			new QuestsModule(state, config, new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null),
			new DiariesModule(state, config, new DataPack(new Gson())),
			new CombatAchievementsModule(state, config, null,
				new net.runelite.client.eventbus.EventBus(), new DataPack(new Gson()), null),
			new QolModule(state, config, new DataPack(new Gson())),
			new LootModule(state, null, config),
			new BankTrackerModule(state, null, null, null, null, null, null, null, null, null, config, new DataPack(new Gson()), null),
			new FarmingRunModule(state, null, new net.runelite.client.eventbus.EventBus(),
				null, null, null, config, null, new DataPack(new Gson()),
				null, null, null, null, null, null, null),
			new DailiesModule(state, null, config, new DataPack(new Gson()),
				null, null, null, null, null, null, null, null, null, null, null),
			new GoalPlannerModule(state, config, new DataPack(new Gson()), null),
			new ClueStashModule(state, config, new DataPack(new Gson()),
				new net.runelite.client.eventbus.EventBus(), null),
			new SlayerOptimizerModule(state, null, null, config, null, null,
				new net.runelite.client.eventbus.EventBus(), null, null, null,
				new com.ironhub.data.DataPack(new com.google.gson.Gson()), null, null, null, null, null, null),
			new SuppliesRunwayModule(state, null, config),
			new CollectionLogModule(state, null, null,
				new net.runelite.client.eventbus.EventBus(), config, new DataPack(new Gson()), null),
			new ExternalSyncModule(state, null, new net.runelite.client.eventbus.EventBus(),
				config, null, new Gson()),
			new DashboardModule(),
			new DeathRecoveryModule(state, null, config, null),
			new com.ironhub.modules.loadoutlab.LoadoutLabModule(
				new com.loadoutlab.LoadoutLabPlugin(), new net.runelite.client.eventbus.EventBus(),
				config, state, null, null, null, new Gson(), null, null, null, null),
			new com.ironhub.modules.designlab.DesignLabModule(config,
				new net.runelite.client.eventbus.EventBus()),
			new com.ironhub.modules.dailies.DailiesNewModule(
				new DailiesModule(state, null, config, new DataPack(new Gson()),
					null, null, null, null, null, null, null, null, null, null, null),
				config),
			new com.ironhub.modules.poh.PohModule(state, config, new DataPack(new Gson()),
				new net.runelite.client.eventbus.EventBus(), null));
	}
}
