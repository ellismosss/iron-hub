package com.ironhub.engine;

import com.google.gson.Gson;
import com.ironhub.data.DataPack;
import com.ironhub.data.EffectsPack;
import com.ironhub.data.GearProgressionPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.MethodsPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Experience;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GoalExpanderTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static EnginePacks packs()
	{
		DataPack dataPack = new DataPack(new Gson());
		EnginePacks packs = new EnginePacks(
			dataPack.load("quests", QuestsPack.class),
			dataPack.load("methods", MethodsPack.class),
			dataPack.load("effects", EffectsPack.class),
			dataPack.load("gear-progression", GearProgressionPack.class),
			dataPack.load("boosts", com.ironhub.data.BoostsPack.class),
			dataPack.load("diaries", com.ironhub.data.DiariesPack.class),
			dataPack.load("clog", com.ironhub.data.ClogPack.class)); // drop-rate costing
		packs.itemSources = dataPack.load("item-sources",
			com.ironhub.data.ItemSourcesPack.class);
		return packs;
	}

	private static GoalsPack.Goal goal(String id, String... reqs)
	{
		GoalsPack.Goal goal = new GoalsPack.Goal();
		goal.setId(id);
		goal.setName(id);
		List<GoalsPack.Step> steps = new ArrayList<>();
		for (String req : reqs)
		{
			GoalsPack.Step step = new GoalsPack.Step();
			step.setLabel(req);
			step.setRequirement(req);
			steps.add(step);
		}
		goal.setSteps(steps);
		return goal;
	}

	@Test
	public void mergesSharedDemandsAcrossGoals()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack.Goal bowfa = goal("bowfa", "quest:Song of the Elves");
		GoalsPack.Goal base70s = goal("base70s", "skill:Agility:70", "skill:Herblore:70");

		ActionDag dag = GoalExpander.expand(List.of(bowfa, base70s), state, packs());

		// SotE pulls its quest chain in through the pack
		Action sote = dag.get("quest:Song of the Elves");
		assertNotNull(sote);
		assertTrue(sote.dependsOn.contains("quest:Mourning's End Part II"));
		assertNotNull(dag.get("quest:Roving Elves")); // transitive chain
		// SotE's 70 Agility and base70s' 70 Agility are ONE node, tagged twice
		Action agility = dag.get("train:Agility:70");
		assertNotNull(agility);
		assertEquals(70, agility.trainToLevel);
		assertTrue(agility.neededBy.contains("bowfa"));
		assertTrue(agility.neededBy.contains("base70s"));
	}

	@Test
	public void trainLevelsChainInsteadOfOverMerging()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(
			goal("a", "skill:Fishing:70"), goal("b", "skillb:Fishing:81")), state, packs());
		// a 70 demand must not wait on the 81 demand; 81 passes through 70
		assertNotNull(dag.get("train:Fishing:70"));
		assertTrue(dag.get("train:Fishing:81").dependsOn.contains("train:Fishing:70"));
	}

	@Test
	public void metDemandsExpandToNothing()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.AGILITY, 70, Experience.getXpForLevel(70));
		StateFixture.quest(state, Quest.SONG_OF_THE_ELVES, QuestState.FINISHED);

		ActionDag dag = GoalExpander.expand(List.of(
			goal("bowfa", "quest:Song of the Elves", "skill:Agility:70")), state, packs());
		assertNull(dag.get("quest:Song of the Elves"));
		assertNull(dag.get("train:Agility:70"));
		assertEquals(0, dag.size());
	}

	@Test
	public void questPointGatesFillFromTheRouteOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(goal("ds1", "qp:32")), state, packs());
		int qp = 0;
		int quests = 0;
		for (Action node : dag.nodes())
		{
			if (node.kind == Action.Kind.QUEST)
			{
				QuestsPack.QuestEntry entry = packs().quest(node.questName);
				qp += entry == null ? 0 : entry.qp;
				quests++;
			}
		}
		assertTrue("filled to " + qp + " qp", qp >= 32);
		assertTrue(quests > 3);
	}

	@Test
	public void manualStepsBecomeTickableNodesChainedInOrder()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack.Goal bowfa = goal("bowfa",
			"quest:Song of the Elves", "150 crystal shards");
		ActionDag dag = GoalExpander.expand(List.of(bowfa), state, packs());

		Action manual = dag.get("manual:goalstep:bowfa:1");
		assertNotNull(manual);
		assertEquals("goalstep:bowfa:1", manual.unlockKey);
		// checklist order: the shard grind waits on SotE
		assertTrue(manual.dependsOn.contains("quest:Song of the Elves"));
	}

	@Test
	public void boostableGatesTrainOnlyToTheBoostedLevel()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.CONSTRUCTION, 80, Experience.getXpForLevel(80));
		// crystal saw (+3, item-gated) + spicy stew (+5, Evil Dave quest)
		StateFixture.bank(state, java.util.Map.of(9625, 1));
		StateFixture.quest(state, Quest.RECIPE_FOR_DISASTER__EVIL_DAVE, QuestState.FINISHED);

		// the ornate jewellery box case: nobody trains to 91 with saw+stew
		ActionDag dag = GoalExpander.expand(List.of(
			goal("box", "skillb:Construction:91")), state, packs());
		assertNull(dag.get("train:Construction:91"));
		Action boosted = dag.get("train:Construction:83");
		assertNotNull("expected a train-to-83 node: 91 - saw(3) - stew(5)", boosted);
		assertTrue(boosted.name.contains("boost to 91"));

		// already at 83: nothing to train at all
		StateFixture.stat(state, Skill.CONSTRUCTION, 83, Experience.getXpForLevel(83));
		ActionDag met = GoalExpander.expand(List.of(
			goal("box", "skillb:Construction:91")), state, packs());
		assertEquals(0, met.size());

		// non-boostable demands are untouched by boosts
		ActionDag strict = GoalExpander.expand(List.of(
			goal("real", "skill:Construction:91")), state, packs());
		assertNotNull(strict.get("train:Construction:91"));
	}

	@Test
	public void ownedItemsNeverBecomeObtainSteps()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// holding an Arclight: a goal requiring it must not plan obtaining it
		StateFixture.equipment(state, java.util.Map.of(19675, 1));
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "item:19675")), state, packs());
		assertEquals(0, dag.size());
	}

	@Test
	public void diaryClaimsAreDetectedNotManual()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// unclaimed: a detected diary node (no tick key), not a manual one
		ActionDag dag = GoalExpander.expand(List.of(
			goal("ring", "diary:Lumbridge & Draynor:Elite")), state, packs());
		Action node = dag.get("diarytier:Lumbridge & Draynor:Elite");
		assertNotNull(node);
		assertNull(node.unlockKey);

		// claimed: nothing to plan
		StateFixture.varbit(state, net.runelite.api.Varbits.DIARY_LUMBRIDGE_ELITE, 1);
		ActionDag claimed = GoalExpander.expand(List.of(
			goal("ring", "diary:Lumbridge & Draynor:Elite")), state, packs());
		assertEquals(0, claimed.size());
	}

	@Test
	public void diaryTierGoalsCarryAggregatedRequirements()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(
			goal("ring", "diary:Lumbridge & Draynor:Elite")), state, packs());
		Action tier = dag.get("diarytier:Lumbridge & Draynor:Elite");
		assertNotNull(tier);
		// the tier's hardest demands gate it (81 Craft w/ QP cape path skipped,
		// but 76+ skills and quest chains must appear as dependencies)
		assertFalse("tier should depend on its skill demands", tier.dependsOn.isEmpty());
		boolean hasTrain = tier.dependsOn.stream().anyMatch(d -> d.startsWith("train:"));
		assertTrue("expected TRAIN dependencies, got: " + tier.dependsOn, hasTrain);
	}

	@Test
	public void dagIsAcyclicAndFullyOrderable()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack goals = new DataPack(new Gson()).load("goals", GoalsPack.class);
		ActionDag dag = GoalExpander.expand(goals.getGoals(), state, packs());
		assertTrue(dag.size() > 20);
		assertEquals("cycles cut: " + dag.degraded, 0, dag.degraded.size());
		assertEquals(dag.size(), dag.topological().size());
	}

	@Test
	public void anyPathsPickTheCheapestDeterministically()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// Herblore 70 (~700k xp) is far cheaper than Slayer 85 (~3.3M) —
		// with Druidic Ritual done the Herblore ladder costs honestly from
		// level 3 (before the 2026-07-23 wiki-rate merge this leaned on
		// quickCost's flat pessimism for the quest-gated floor, and faster
		// wiki Slayer tiers flipped the comparison)
		StateFixture.quest(state, Quest.DRUIDIC_RITUAL, QuestState.FINISHED);
		// the quest grants the xp to level 3 — the ladder's floor; without it
		// the 0->174xp stretch has no method and Herblore costs NaN
		StateFixture.stat(state, net.runelite.api.Skill.HERBLORE, 3,
			net.runelite.api.Experience.getXpForLevel(3));
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "any:skillb:Slayer:85|skillb:Herblore:70")), state, packs());
		assertNotNull(dag.get("train:Herblore:70"));
		assertNull(dag.get("train:Slayer:85"));
	}

	/**
	 * The player's CHOSEN obtainment method wins over the engine's cheapest
	 * guess: an Amulet of glory can be crafted (Crafting 80) or hunted from
	 * dragon implings (Hunter 83), and picking Crafting must take Hunter out
	 * of the plan entirely (Luke, 2026-07-23 — "my tracker still tells me to
	 * get 78 Hunter despite me saying I want it via Crafting").
	 */
	@Test
	public void chosenObtainMethodSteersTheAnyPath()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		GoalsPack.Goal glory = goal("g", "item:1704:1:Amulet of glory");

		ActionDag free = GoalExpander.expand(List.of(glory), state, packs());
		boolean hunterByDefault = free.get("train:Hunter:83") != null;
		boolean craftingByDefault = free.get("train:Crafting:80") != null;
		assertTrue("the gear chart offers exactly one of the two paths",
			hunterByDefault ^ craftingByDefault);

		ActionDag crafted = GoalExpander.expand(List.of(glory), state, packs(),
			java.util.Map.of(1704, GoalExpander.PATH_PREF + "skillb:Crafting:80"));
		assertNotNull("the chosen path must be planned", crafted.get("train:Crafting:80"));
		assertNull("the rejected path must leave the plan", crafted.get("train:Hunter:83"));

		ActionDag hunted = GoalExpander.expand(List.of(glory), state, packs(),
			java.util.Map.of(1704, GoalExpander.PATH_PREF + "skillb:Hunter:83"));
		assertNotNull(hunted.get("train:Hunter:83"));
		assertNull(hunted.get("train:Crafting:80"));
	}

	/**
	 * An item outside the curated gear chart still gets REAL sub-steps from
	 * the knowledge base — a shop item's price becomes a tracked currency
	 * requirement instead of a dead-end "Buy: ..." line (Luke: the diary
	 * cape goal said only "Buy: Twiggy O'Korn").
	 */
	@Test
	public void kbObtainmentGivesShopItemsRealSteps()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		// Gem bag: 100 golden nuggets (an ITEM currency — countable)
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "item:12020:1:Gem bag")), state, packs());
		assertNotNull("the nugget cost must become a step", dag.get("obtain:item12012"));

		// Achievement diary cape: 99,000 coins AND the 12 elite diaries
		ActionDag cape = GoalExpander.expand(List.of(
			goal("c", "item:19476:1:Achievement diary cape")), state, packs());
		assertNotNull("the coin cost must become a step", cape.get("obtain:item995"));
		// and the coin step reads by NAME, never "Obtain item 995" (Luke)
		Action coins = cape.get("obtain:item995");
		assertFalse("obtain node must not read 'item <id>': " + coins.name,
			coins.name.matches("(?i).*item \\d+.*"));

		// Amy's saw: 500 Mahogany Homes points — a currency with NO readable
		// balance anywhere in the API. It still has to say what to go and
		// earn (a tickable manual step), never a silent dead end and never a
		// pretend progress figure.
		ActionDag saw = GoalExpander.expand(List.of(
			goal("s", "item:24880:1:Amy's saw")), state, packs());
		Action earn = saw.topological().stream()
			.filter(a -> a.name != null && a.name.startsWith("Earn 500 Mahogany Homes points"))
			.findFirst().orElse(null);
		assertNotNull("unreadable currencies still name the work: " + saw.topological(), earn);
		assertNotNull("and it is tickable", earn.unlockKey);
	}

	/**
	 * A gear item that IS a clog drop carries a phantom "kc:<source>:1" (=
	 * "drops from X"). Expanding it added a redundant "kill X once" step and
	 * the nonsense "Up to 1 kills" line (Luke, 2026-07-24: Bottomless compost
	 * bucket from Hespori). The OBTAIN is costed by the drop; no KILL node.
	 */
	@Test
	public void dropGatedGearDoesNotSpawnAPhantomKillStep()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.FARMING, 65, Experience.getXpForLevel(65));
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "item:22994:1:Bottomless compost bucket")), state, packs());
		assertNull("no phantom 'kill Hespori once' step", dag.get("kill:Hespori"));
		assertNotNull("the compost bucket is still an OBTAIN", dag.get("obtain:bottomless_compost_bucket"));

		// a REAL kc gate (Ava's assembler needs 50 Vorkath kills) survives
		ActionDag ava = GoalExpander.expand(List.of(
			goal("a", "kc:Vorkath:50")), state, packs());
		Action kill = ava.get("kill:Vorkath");
		assertNotNull("a real KC gate stays", kill);
		assertEquals(50, kill.kcTarget);
	}

	/**
	 * A readable reward-point currency renders as "Earn N X", never the raw
	 * "varbit:4893:250:Tithe Farm points" (Luke, 2026-07-24), and it is
	 * detected (no manual-tick unlock key) so it clears on its own.
	 */
	@Test
	public void varbitCurrencyReadsAsEarnNotRawString()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "varbit:4893:250:Tithe Farm points")), state, packs());
		Action earn = dag.topological().stream()
			.filter(a -> a.name != null && a.name.contains("Tithe Farm points"))
			.findFirst().orElse(null);
		assertNotNull("a step for the points must exist: " + dag.topological(), earn);
		assertEquals("Earn 250 Tithe Farm points", earn.name);
		assertFalse("must never show the raw req string", earn.name.contains("varbit:"));
		assertNull("readable currency auto-detects, no manual tick", earn.unlockKey);
	}

	/** A currency/material sub-step reads "Obtain N X" — the gem-bag step
	 *  said just "Golden nuggets", losing the count (Luke, 2026-07-24). The
	 *  gem bag buys for 100 golden nuggets; that sub-step must name the 100.*/
	@Test
	public void quantitySubStepsNameTheCount()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "item:12020:1:Gem bag")), state, packs());
		Action nuggets = dag.get("obtain:item12012");
		assertNotNull("the 100-nugget cost is a sub-step: " + dag.topological(), nuggets);
		assertEquals("Obtain 100 Golden nuggets", nuggets.name);
	}

	/**
	 * A batch recipe scales to the needed count: 75 Mithril arrows from a
	 * 15-per-batch recipe (15 headless + 15 tips) needs 5 batches = 75 + 75,
	 * not the raw 15 + 15 (Luke, 2026-07-24). The gather sub-steps must ask
	 * for the scaled quantities.
	 */
	@Test
	public void batchRecipesScaleToTheNeededCount()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.FLETCHING, 45, Experience.getXpForLevel(45));
		// choose to MAKE the arrows (shop leads by default) so the recipe
		// materials expand
		ActionDag dag = GoalExpander.expand(List.of(
			goal("g", "item:888:75:Mithril arrow")), state, packs(),
			java.util.Map.of(888, "make|15 x Headless arrow + 15 x Mithril arrowtips"));
		// headless arrow id 53, mithril arrowtips id 42 — scaled to 75 each
		Action headless = dag.get("obtain:item53");
		Action tips = dag.get("obtain:item42");
		assertNotNull("headless arrow gather step: " + dag.topological(), headless);
		assertNotNull("arrowtip gather step", tips);
		assertEquals("75 headless arrows, not 15", 75, headless.obtainQty);
		assertEquals("75 arrowtips, not 15", 75, tips.obtainQty);
	}

	/** Picking one branch of an item's any: gate via a PATH pref steers the
	 *  plan — choosing Crafting for an Amulet of glory drops Hunter (Luke,
	 *  2026-07-24: it stayed because the KB-source menu didn't map to the
	 *  gear path). Mirrors what the restricted choose-method menu now sets. */
	@Test
	public void gloryPathPrefDropsTheOtherSkill()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ActionDag crafted = GoalExpander.expand(List.of(
			goal("g", "item:1704:1:Amulet of glory")), state, packs(),
			java.util.Map.of(1704, GoalExpander.PATH_PREF + "skillb:Crafting:80"));
		assertNotNull(crafted.get("train:Crafting:80"));
		assertNull("Hunter must leave the plan", crafted.get("train:Hunter:83"));
	}
}
