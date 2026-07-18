package com.ironhub.modules.quests;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import javax.swing.JComponent;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class QuestsModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private QuestsModule module(AccountState state)
	{
		return new QuestsModule(state, config, new DataPack(new Gson()), null, null);
	}

	@Test
	public void miniquestSplitAndDifficultyComeFromThePack()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		QuestsModule module = module(state);
		QuestsTab tab = (QuestsTab) module.buildTab();

		assertFalse(tab.isMiniquest(Quest.DRAGON_SLAYER_II));
		assertTrue(tab.isMiniquest(Quest.ENTER_THE_ABYSS));
		assertTrue(tab.isMiniquest(Quest.BEAR_YOUR_SOUL)); // off-route miniquest
		assertEquals("Grandmaster", tab.difficulty(Quest.DRAGON_SLAYER_II));
		assertEquals("Novice", tab.difficulty(Quest.COOKS_ASSISTANT));

		// difficulty sort: novice quests come before grandmasters
		Comparator<Quest> byDifficulty = tab.comparator("Difficulty");
		assertTrue(byDifficulty.compare(Quest.COOKS_ASSISTANT, Quest.DRAGON_SLAYER_II) < 0);
		// started sort: in-progress first
		StateFixture.quest(state, Quest.DRAGON_SLAYER_II, QuestState.IN_PROGRESS);
		Comparator<Quest> byStarted = tab.comparator("Started");
		assertTrue(byStarted.compare(Quest.DRAGON_SLAYER_II, Quest.COOKS_ASSISTANT) < 0);
		module.shutDown();
	}

	@Test
	public void questGoalLifecycle()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		QuestsModule module = module(state);

		module.addGoal("Dragon Slayer I");
		assertTrue(module.isGoal("Dragon Slayer I"));
		assertTrue(state.getSelectedGoals().contains("custom:quest:dragon-slayer-i"));
		// the custom-goal seed carries the quest: requirement the engine expands
		com.ironhub.state.PersistedState.CustomGoal seed =
			state.getCustomGoals().get("custom:quest:dragon-slayer-i");
		assertNotNull(seed);
		assertEquals("quest:Dragon Slayer I", seed.req);

		module.removeGoal("Dragon Slayer I");
		assertFalse(module.isGoal("Dragon Slayer I"));
		module.shutDown();
	}

	@Test
	public void openInQuestHelperIsInertWithoutThePlugin()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		QuestsModule module = module(state);
		assertFalse(module.openInQuestHelper("Cook's Assistant")); // headless: absent
		module.shutDown();
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		StateFixture.quest(state, Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		StateFixture.quest(state, Quest.DRAGON_SLAYER_II, QuestState.IN_PROGRESS);

		QuestsModule module = module(state);
		module.startUp();
		QuestsTab tab = (QuestsTab) module.buildTab();
		assertNotNull(tab);
		// state mutations trigger tab rebuilds; run them on the EDT or the
		// listener rebuild races the direct one and doubles every row
		// mutations on the EDT; RebuildGate queues listener rebuilds there
		// too, so an empty barrier drains them before rendering off-thread
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			module.addGoal("Dragon Slayer II");
			tab.selectSort(0); // difficulty
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		BufferedImage image = SwingRender.render(tab);
		assertTrue("main height " + image.getHeight(), image.getHeight() > 200);
		write(image, "quests-tab.png");

		javax.swing.SwingUtilities.invokeAndWait(() -> tab.selectType(1)); // miniquests
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		BufferedImage minis = SwingRender.render(tab);
		assertTrue(minis.getHeight() > 100);
		write(minis, "quests-miniquests.png");
		module.shutDown();
	}

	private static void write(BufferedImage image, String name)
	{
		try
		{
			java.io.File out = new java.io.File("build/reports/" + name);
			out.getParentFile().mkdirs();
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (java.io.IOException ignored)
		{
		}
	}
}
