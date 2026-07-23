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
		DiariesPack.Region ardougne = pack.regions.get(0);
		DiariesPack.Task essMine = ardougne.tiers.get(0).tasks.get(0);
		assertEquals((Integer) VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY, essMine.varp);
		assertFalse(module.taskComplete(ardougne, 0, essMine));
		StateFixture.varp(state, essMine.varp, 1 << essMine.bit);
		assertTrue(module.taskComplete(ardougne, 0, essMine));

		// Karamja easy task 1 = pick 5 bananas, varbit counts to 5
		DiariesPack.Region karamja = pack.regions.stream()
			.filter(r -> r.name.equals("Karamja")).findFirst().orElseThrow(AssertionError::new);
		DiariesPack.Task bananas = karamja.tiers.get(0).tasks.get(0);
		assertEquals((Integer) VarbitID.ATJUN_EASY_BANANA, bananas.varbit);
		StateFixture.varbit(state, bananas.varbit, 4);
		assertFalse(module.taskComplete(karamja, 0, bananas));
		StateFixture.varbit(state, bananas.varbit, 5);
		assertTrue(module.taskComplete(karamja, 0, bananas));
	}

	@Test
	public void doableHighlightsFollowRequirements()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		DiariesModule module = module(state);
		DiariesPack pack = module.pack();

		// Ardougne easy: task 1 needs Rune Mysteries, task 2 needs 5 Thieving
		DiariesPack.Region ardougne = pack.regions.get(0);
		DiariesPack.Task essMine = ardougne.tiers.get(0).tasks.get(0);
		DiariesPack.Task stealCake = ardougne.tiers.get(0).tasks.get(1);
		assertFalse(module.taskDoable(ardougne, 0, essMine));
		assertFalse(module.taskDoable(ardougne, 0, stealCake));

		StateFixture.quest(state, Quest.RUNE_MYSTERIES, QuestState.FINISHED);
		StateFixture.stat(state, Skill.THIEVING, 5, 400);
		assertTrue(module.taskDoable(ardougne, 0, essMine));
		assertTrue(module.taskDoable(ardougne, 0, stealCake));

		// completing a task removes the highlight
		StateFixture.varp(state, essMine.varp, 1 << essMine.bit);
		assertFalse(module.taskDoable(ardougne, 0, essMine));
	}

	/** A whole-tier goal shares the per-task proof keys — the module's
	 *  marker serves both families (2026-07-23, Luke's tier-goal ask). */
	@Test
	public void tierGoalsShareTaskProofs()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		DiariesModule module = module(state);
		DiariesPack pack = module.pack();
		DiariesPack.Region ardougne = pack.regions.get(0);
		DiariesPack.Tier easy = ardougne.tiers.get(0);
		DiariesPack.Task essMine = easy.tasks.get(0);

		java.util.List<String> slugs = new java.util.ArrayList<>();
		java.util.List<String> texts = new java.util.ArrayList<>();
		for (DiariesPack.Task t : easy.tasks)
		{
			slugs.add(DiariesModule.slug(t));
			texts.add(t.task);
		}
		state.addGoalSeed(com.ironhub.state.GoalSeeds.diaryTier(
			ardougne.name, easy.tier, slugs, texts));

		String proof = "diarytask_" + DiariesModule.slug(essMine);
		assertFalse(state.isUnlocked(proof));
		StateFixture.varp(state, essMine.varp, 1 << essMine.bit);
		module.markDiaryGoalProofs();
		assertTrue("a tier goal's step must prove like a task goal's",
			state.isUnlocked(proof));
	}

	/** Boostable gates count usable temporary boosts (2026-07-23): a level
	 *  just short reads doable through the boost-aware overload while the
	 *  plain check stays honest, and a boost never revives a quest gate. */
	@Test
	public void boostAwareDoableClosesSkillGapsOnly()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		DiariesModule module = module(state);
		DiariesPack pack = module.pack();

		// Ardougne easy task 2 needs 5 Thieving (skillb: — an action gate)
		DiariesPack.Region ardougne = pack.regions.get(0);
		DiariesPack.Task stealCake = ardougne.tiers.get(0).tasks.get(1);
		StateFixture.stat(state, Skill.THIEVING, 3, 200);
		assertFalse(module.taskDoable(ardougne, 0, stealCake));
		assertFalse(module.taskDoable(ardougne, 0, stealCake, java.util.Map.of()));
		assertTrue(module.taskDoable(ardougne, 0, stealCake,
			java.util.Map.of(Skill.THIEVING, 2)));

		// a quest gate is never boostable
		DiariesPack.Task essMine = ardougne.tiers.get(0).tasks.get(0);
		assertFalse(module.taskDoable(ardougne, 0, essMine,
			java.util.Map.of(Skill.THIEVING, 99)));
	}

	@Test
	public void tierCountAndClaimVarbitsCoverIronmanAlternateFlags()
	{
		// Desert Medium's Pollnivneach task has an ironman-only alternate
		// flag the client can't see: the game's own count/claim varbits
		// must prove it (the in-client 11/12-but-complete bug).
		AccountState state = StateFixture.state(temp.getRoot());
		DiariesModule module = module(state);
		DiariesPack.Region desert = module.pack().regions.stream()
			.filter(r -> r.name.equals("Desert")).findFirst().orElseThrow(AssertionError::new);
		DiariesPack.Tier medium = desert.tiers.get(1);
		DiariesPack.Task pollnivneach = medium.tasks.get(10);

		// 11 of 12 bits set — the Pollnivneach bit (22) missing
		int elevenBits = 0;
		for (DiariesPack.Task task : medium.tasks)
		{
			if (task != pollnivneach)
			{
				elevenBits |= 1 << task.bit;
			}
		}
		StateFixture.varp(state, VarPlayerID.DESERT_ACHIEVEMENT_DIARY, elevenBits);
		assertEquals(11, module.tierDone(desert, 1));
		assertFalse(module.taskComplete(desert, 1, pollnivneach));

		// the game's completed count says 12/12 -> task and tier complete
		StateFixture.varbit(state, VarbitID.DESERT_MED_COUNT, 12);
		assertEquals(12, module.tierDone(desert, 1));
		assertTrue(module.taskComplete(desert, 1, pollnivneach));
		assertTrue(module.tierAllDone(desert, 1));

		// a claimed tier proves its tasks too (independent of the count)
		StateFixture.varbit(state, VarbitID.DESERT_MED_COUNT, 0);
		StateFixture.varbit(state, Varbits.DIARY_DESERT_MEDIUM, 1);
		assertTrue(module.taskComplete(desert, 1, pollnivneach));
	}

	@Test
	public void diaryGoalsPersistAndCompileInThePlanner()
	{
		AccountState before = StateFixture.state(temp.getRoot());
		StateFixture.profile(before, 7L);
		DiariesModule beforeModule = module(before);
		DiariesPack.Region ardougne = beforeModule.pack().regions.get(0);
		DiariesPack.Task essMine = ardougne.tiers.get(0).tasks.get(0);
		String slug = DiariesModule.slug(essMine);
		assertEquals("1196_0", slug);

		before.addGoalSeed(com.ironhub.state.GoalSeeds.diary(slug, essMine.task, ardougne.name, "Easy"));
		before.addGoalSeed(com.ironhub.state.GoalSeeds.diary("vb9999", "Removed", "Karamja", "Easy"));
		before.removeGoalSeed("diary:vb9999");

		AccountState after = StateFixture.state(temp.getRoot());
		StateFixture.profile(after, 7L);
		assertEquals(java.util.Set.of("diary:" + slug), after.getSelectedGoals());
		assertEquals(java.util.Set.of(slug), after.goalSeedIds("diary"));

		// the seed compiles into a planner goal: one step, unlock-flag proof
		com.ironhub.data.GoalsPack.Goal goal = com.ironhub.modules.goals.GoalPlannerModule
			.toGoal(after.getGoalSeeds().get("diary:" + slug));
		assertEquals("diary:" + slug, goal.getId());
		assertEquals(essMine.task, goal.getName());
		assertEquals(1, goal.getSteps().size());
		assertFalse(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, after));

		// completing the task in-game: the module marks the proof on state
		// change (the fixture's ingest doesn't notify, so invoke the
		// listener's target directly)
		DiariesModule afterModule = module(after);
		StateFixture.varp(after, VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY, 1 << essMine.bit);
		afterModule.markDiaryGoalProofs();
		assertTrue(after.isUnlocked("diarytask_" + slug));
		assertTrue(com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, after));
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
