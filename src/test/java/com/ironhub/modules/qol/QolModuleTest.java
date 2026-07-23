package com.ironhub.modules.qol;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.QolPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import com.ironhub.ui.components.Status;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class QolModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final QolPack pack = new DataPack(new Gson()).load("qol", QolPack.class);

	private QolPack.Unlock byId(String id)
	{
		return pack.getUnlocks().stream().filter(u -> u.getId().equals(id)).findFirst().orElseThrow();
	}

	@Test
	public void ownedAvailableLocked()
	{
		AccountState state = StateFixture.state(temp.getRoot());

		// locked: 60/60 attack/defence not met — blocking line names the leaf
		assertEquals(Status.LOCKED, QolModule.status(state, byId("dragon_defender")));
		assertEquals("60 Attack", QolModule.blockingLine(state, byId("dragon_defender")));

		// available: requirements met, item not owned
		StateFixture.stat(state, Skill.ATTACK, 60, 0);
		StateFixture.stat(state, Skill.DEFENCE, 60, 0);
		assertEquals(Status.AVAILABLE, QolModule.status(state, byId("dragon_defender")));

		// owned: item in bank wins regardless of requirements
		StateFixture.bank(state, Map.of(12954, 1));
		assertEquals(Status.OWNED, QolModule.status(state, byId("dragon_defender")));

		// quest requirement drives availability
		assertEquals(Status.LOCKED, QolModule.status(state, byId("ava_assembler")));
		StateFixture.quest(state, Quest.DRAGON_SLAYER_II, QuestState.FINISHED);
		assertEquals(Status.AVAILABLE, QolModule.status(state, byId("ava_assembler")));

		// manual text requirements never auto-complete
		assertEquals(Status.LOCKED, QolModule.status(state, byId("herb_sack")));
		assertEquals("250 Tithe Farm points", QolModule.blockingLine(state, byId("herb_sack")));
	}

	/** Owning a HIGHER diary-reward tier proves the lower — an Ardougne
	 *  cloak 3 covers everything cloak 2 does (Luke's report: cloak 2 read
	 *  "not obtained" beside an owned cloak 3). The generator bakes the
	 *  higher tiers' ids into each lower tier's ownership list. */
	@Test
	public void higherTierProvesLower()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		assertEquals(Status.AVAILABLE, QolModule.status(state, byId("ardougne_cloak_2")));
		StateFixture.bank(state, Map.of(13123, 1)); // Ardougne cloak 3
		assertEquals(Status.OWNED, QolModule.status(state, byId("ardougne_cloak_2")));
		assertEquals(Status.OWNED, QolModule.status(state, byId("ardougne_cloak_1")));
		// never the other way: cloak 4 stays unowned
		assertEquals(Status.AVAILABLE, QolModule.status(state, byId("ardougne_cloak_4")));
	}

	/** A QoL goal is achieved by OWNING the unlock (the module's OWNED
	 *  signal), with the prerequisites as steps — not merely "reqs met". */
	@Test
	public void goalTrackingOwnershipProof()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		QolPack.Unlock pouch = byId("rune_pouch"); // item 12791, prose req
		com.ironhub.data.GoalsPack.Goal goal = com.ironhub.modules.goals.GoalPlannerModule.toGoal(
			com.ironhub.state.GoalSeeds.qol(pouch.getId(), pouch.getName(),
				pouch.getItemIds(), pouch.getRequirements()));

		assertEquals("qol:rune_pouch", goal.getId());
		// prerequisite step + an "Obtain" step proven by ownership
		assertEquals(pouch.getRequirements().size() + 1, goal.getSteps().size());
		assertEquals("item:12791", goal.getAchieved().get(0));
		assertFalse("not owned yet", com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, state));

		StateFixture.bank(state, Map.of(12791, 1));
		assertTrue("owning it completes the goal",
			com.ironhub.modules.goals.GoalPlannerModule.isAchieved(goal, state));
	}

	@Test
	public void goalGlyphRoundTripsThroughState()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		QolPack.Unlock pouch = byId("rune_pouch");
		state.addGoalSeed(com.ironhub.state.GoalSeeds.qol(pouch.getId(), pouch.getName(),
			pouch.getItemIds(), pouch.getRequirements()));
		assertTrue(state.getGoalSeeds().containsKey("qol:rune_pouch"));
		assertTrue(state.getSelectedGoals().contains("qol:rune_pouch"));
		state.removeGoalSeed("qol:rune_pouch");
		assertTrue(state.getGoalSeeds().isEmpty());
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(12791, 1)); // rune pouch owned
		StateFixture.stat(state, Skill.ATTACK, 70, 0);
		StateFixture.stat(state, Skill.DEFENCE, 70, 0);

		// track one unlock so a × goal glyph renders
		state.addGoalSeed(com.ironhub.state.GoalSeeds.qol("herb_sack", "Herb sack",
			byId("herb_sack").getItemIds(), byId("herb_sack").getRequirements()));

		QolModule module = new QolModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null);
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/qol-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
