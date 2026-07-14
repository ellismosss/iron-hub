package com.ironhub.modules.quests;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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

	@Test
	public void filterMatching()
	{
		Quest quest = Quest.COOKS_ASSISTANT;
		assertTrue(QuestsTab.matches(quest, QuestState.FINISHED, "All", ""));
		assertTrue(QuestsTab.matches(quest, QuestState.FINISHED, "Done", ""));
		assertFalse(QuestsTab.matches(quest, QuestState.FINISHED, "Todo", ""));
		assertFalse(QuestsTab.matches(quest, QuestState.NOT_STARTED, "Active", ""));
		assertTrue(QuestsTab.matches(quest, QuestState.IN_PROGRESS, "Active", ""));

		assertTrue(QuestsTab.matches(quest, QuestState.FINISHED, "All", "cook"));
		assertFalse(QuestsTab.matches(quest, QuestState.FINISHED, "All", "dragon"));
	}

	@Test
	public void tabRendersHeadless()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.quest(state, Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		StateFixture.quest(state, Quest.DRAGON_SLAYER_II, QuestState.IN_PROGRESS);

		QuestsModule module = new QuestsModule(state, config);
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		BufferedImage image = SwingRender.render((javax.swing.JPanel) tab);
		assertTrue(image.getHeight() > 200); // summary + rows rendered
		write(image, "quests-tab.png");
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
