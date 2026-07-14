package com.ironhub.modules.suggest;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.BankedXpPack;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.HerbPatchesPack;
import com.ironhub.modules.suggest.WhatNowModule.Suggestion;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WhatNowTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final DataPack packs = new DataPack(new Gson());
	private final WhatNowModule.Packs all = new WhatNowModule.Packs(
		packs.load("dailies", DailiesPack.class),
		packs.load("herb-patches", HerbPatchesPack.class),
		packs.load("banked-xp", BankedXpPack.class),
		packs.load("goals", GoalsPack.class),
		packs.load("gear-progression", com.ironhub.data.GearProgressionPack.class));

	@Test
	public void suggestionsComposeAcrossModules()
	{
		AccountState state = StateFixture.state(temp.getRoot());

		// ready patches + a slayer task + banked xp + an active goal
		state.recordHerbPatch("falador", "HARVESTABLE", "Ranarr", 0);
		state.recordHerbPatch("catherby", "HARVESTABLE", "Ranarr", 0);
		StateFixture.varp(state, VarPlayer.SLAYER_TASK_SIZE, 40);
		StateFixture.bank(state, Map.of(536, 500)); // dragon bones
		state.selectGoal("dragon_defender", true);

		List<Suggestion> suggestions = WhatNowModule.suggest(state, all, 60);
		assertTrue(suggestions.size() >= 4);
		// herb run outranks everything at high impact + ready urgency
		assertEquals("Herb run", suggestions.get(0).title);
		// every suggestion explains itself
		suggestions.forEach(s -> assertTrue(s.title, !s.why.isEmpty()));
	}

	@Test
	public void timeBudgetDemotesLongActivities()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.varp(state, VarPlayer.SLAYER_TASK_SIZE, 200); // ~100 min task
		state.recordHerbPatch("falador", "HARVESTABLE", "Ranarr", 0);

		List<Suggestion> five = WhatNowModule.suggest(state, all, 5);
		assertEquals("Herb run", five.get(0).title); // 6 min fits-ish; slayer demoted
		assertTrue(five.get(five.size() - 1).title.contains("slayer"));
	}

	@Test
	public void freshAccountOnlySuggestsTheRequirementFreeDaily()
	{
		// zaff_battlestaves has no requirements, so even a fresh account
		// has one legitimately available daily — nothing else
		AccountState state = StateFixture.state(temp.getRoot());
		List<Suggestion> suggestions = WhatNowModule.suggest(state, all, 60);
		assertEquals(1, suggestions.size());
		assertEquals("Do your dailies", suggestions.get(0).title);
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		state.recordHerbPatch("falador", "HARVESTABLE", "Ranarr", 0);
		StateFixture.varp(state, VarPlayer.SLAYER_TASK_SIZE, 40);
		StateFixture.stat(state, Skill.ATTACK, 50, 0);
		state.selectGoal("dragon_defender", true);

		WhatNowModule module = new WhatNowModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()));
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/whatnow-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
