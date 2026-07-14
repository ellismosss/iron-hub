package com.ironhub.modules.diaries;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.DiariesPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DiariesModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private DiariesModule module(AccountState state)
	{
		DiariesModule module = new DiariesModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()));
		module.startUp();
		return module;
	}

	@Test
	public void tierCounting()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		assertEquals(0, DiariesModule.totalTiersComplete(state));

		StateFixture.varbit(state, Varbits.DIARY_ARDOUGNE_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_ARDOUGNE_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_KARAMJA_EASY, 2); // Karamja reports 0–2

		assertEquals(2, DiariesModule.tiersComplete(state, DiariesModule.REGIONS[0]));
		assertEquals(3, DiariesModule.totalTiersComplete(state));
	}

	@Test
	public void packCoversAllRegionsAndParses()
	{
		DiariesPack pack = new DataPack(new Gson()).load("diaries", DiariesPack.class);
		assertEquals(12, pack.regions.size());
		int total = 0;
		for (DiariesPack.Region region : pack.regions)
		{
			assertNotNull("unknown region: " + region.name, DiariesModule.regionMeta(region.name));
			assertEquals(4, region.tiers.size());
			for (DiariesPack.Tier tier : region.tiers)
			{
				total += tier.tasks.size();
				assertFalse(region.name + " " + tier.tier + " has no rewards",
					tier.rewards.isEmpty());
				for (DiariesPack.Task task : tier.tasks)
				{
					assertTrue("task without completion flag: " + task.task,
						(task.varp != null && task.bit != null) || task.varbit != null);
				}
			}
		}
		assertEquals(492, total);
	}

	@Test
	public void everyPackRequirementResolvesInTheGraph()
	{
		DiariesPack pack = new DataPack(new Gson()).load("diaries", DiariesPack.class);
		List<String> broken = new java.util.ArrayList<>();
		for (DiariesPack.Region region : pack.regions)
		{
			for (DiariesPack.Tier tier : region.tiers)
			{
				for (DiariesPack.Task task : tier.tasks)
				{
					for (DiariesPack.Req req : task.reqs)
					{
						if (req.req == null)
						{
							continue;
						}
						Requirement parsed = Requirements.parse(req.req);
						if (Requirements.isManual(parsed))
						{
							broken.add(region.name + " " + tier.tier + ": " + req.req);
						}
					}
				}
			}
		}
		assertTrue("unresolvable requirement strings:\n" + String.join("\n", broken),
			broken.isEmpty());
	}

	@Test
	public void taskCompletionDecodesVarpBitsAndKaramjaVarbits()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		DiariesModule module = module(state);
		DiariesPack pack = module.pack();

		// Ardougne easy task 1 = bit 0 of the Ardougne diary varp
		DiariesPack.Task essMine = pack.regions.get(0).tiers.get(0).tasks.get(0);
		assertEquals((Integer) VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY, essMine.varp);
		assertFalse(module.taskComplete(essMine));
		StateFixture.varp(state, essMine.varp, 1 << essMine.bit);
		assertTrue(module.taskComplete(essMine));

		// Karamja easy task 1 = pick 5 bananas, varbit counts to 5
		DiariesPack.Region karamja = pack.regions.stream()
			.filter(r -> r.name.equals("Karamja")).findFirst().orElseThrow(AssertionError::new);
		DiariesPack.Task bananas = karamja.tiers.get(0).tasks.get(0);
		assertEquals((Integer) VarbitID.ATJUN_EASY_BANANA, bananas.varbit);
		StateFixture.varbit(state, bananas.varbit, 4);
		assertFalse(module.taskComplete(bananas));
		StateFixture.varbit(state, bananas.varbit, 5);
		assertTrue(module.taskComplete(bananas));
	}

	@Test
	public void doableHighlightsFollowRequirements()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		DiariesModule module = module(state);
		DiariesPack pack = module.pack();

		// Ardougne easy: task 1 needs Rune Mysteries, task 2 needs 5 Thieving
		DiariesPack.Task essMine = pack.regions.get(0).tiers.get(0).tasks.get(0);
		DiariesPack.Task stealCake = pack.regions.get(0).tiers.get(0).tasks.get(1);
		assertFalse(module.taskDoable(essMine));
		assertFalse(module.taskDoable(stealCake));

		StateFixture.quest(state, Quest.RUNE_MYSTERIES, QuestState.FINISHED);
		StateFixture.stat(state, Skill.THIEVING, 5, 400);
		assertTrue(module.taskDoable(essMine));
		assertTrue(module.taskDoable(stealCake));

		// completing a task removes the highlight
		StateFixture.varp(state, essMine.varp, 1 << essMine.bit);
		assertFalse(module.taskDoable(essMine));
	}

	@Test
	public void tabRendersHeadless()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_EASY, 1);
		StateFixture.quest(state, Quest.RUNE_MYSTERIES, QuestState.FINISHED);
		StateFixture.stat(state, Skill.THIEVING, 38, 32000);
		StateFixture.varp(state, VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY, 0b111);

		DiariesModule module = module(state);
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		((DiariesTab) tab).expandForTest("Ardougne");
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 200);
		try
		{
			java.io.File out = new java.io.File("build/reports/diaries-tab.png");
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (java.io.IOException ignored)
		{
		}
		module.shutDown();
	}
}
