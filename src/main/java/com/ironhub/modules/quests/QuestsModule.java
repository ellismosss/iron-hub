package com.ironhub.modules.quests;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.QuestsPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Quest tracker (DESIGN.md §3.2, rebuilt 2026-07-18): quest-cape hero
 * chart, hide-completed lists split Quests/Miniquests with difficulty /
 * A-Z / started sorts (difficulty + miniquest identity from quests.json),
 * per-quest goal-planner tracking (a custom goal whose quest: requirement
 * the engine expands into the full prerequisite chain), and click-to-open
 * in Quest Helper.
 */
@Slf4j
@Singleton
public class QuestsModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final QuestsPack pack;
	private final PluginManager pluginManager; // null in unit tests
	private final ClientThread clientThread;   // null in unit tests
	private QuestsTab tab;

	@Inject
	public QuestsModule(AccountState state, IronHubConfig config, DataPack dataPack,
		PluginManager pluginManager, ClientThread clientThread,
		com.ironhub.data.UnlockIndex unlockIndex)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("quests", QuestsPack.class);
		this.pluginManager = pluginManager;
		this.clientThread = clientThread;
		this.unlockIndex = unlockIndex;
	}

	private final com.ironhub.data.UnlockIndex unlockIndex; // null in unit tests

	/** The reverse unlock index, or empty refs when absent (headless). */
	java.util.List<com.ironhub.data.UnlockIndex.Ref> questUnlocks(String questName)
	{
		return unlockIndex == null ? java.util.List.of()
			: unlockIndex.questUnlocks(questName);
	}

	@Override
	public String name()
	{
		return "Quests";
	}

	@Override
	public boolean enabled()
	{
		return config.questProgression();
	}

	@Override
	public void startUp()
	{
	}

	@Override
	public void shutDown()
	{
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new QuestsTab(state, this, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: the next buildTab dresses it fresh. */
	@Override
	public void onThemeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}

	QuestsPack pack()
	{
		return pack;
	}

	// ── goal planner ──────────────────────────────────────────────────

	static String goalId(String questName)
	{
		return "custom:quest:" + questName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
	}

	/** Track completing a quest in the Goal planner — the engine expands
	 *  the quest: requirement into its full prerequisite chain. */
	void addGoal(String questName)
	{
		state.addGoalSeed(com.ironhub.state.GoalSeeds.custom(
			goalId(questName), questName, "quest:" + questName));
	}

	void removeGoal(String questName)
	{
		com.ironhub.modules.goals.GoalPlannerModule.removeGoal(state, goalId(questName));
	}

	boolean isGoal(String questName)
	{
		return state.getSelectedGoals().contains(goalId(questName));
	}

	// ── Quest Helper launch ───────────────────────────────────────────

	/**
	 * Open a quest in the Quest Helper plugin. Quest Helper exposes no
	 * PluginMessage API, so this reflects into its loaded instance
	 * (QuestMenuHandler.startUpQuest — the same entry its own quest-list
	 * menu uses) on the client thread; inert when Quest Helper is absent.
	 * NOTE for Hub submission: reflection into another plugin may need
	 * replacing with an upstream PluginMessage once Quest Helper offers
	 * one — flagged in README/CLAUDE.md.
	 *
	 * @return false when Quest Helper is not loaded (caller may fall back)
	 */
	boolean openInQuestHelper(String questName)
	{
		if (pluginManager == null || clientThread == null)
		{
			return false;
		}
		Plugin questHelper = null;
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin.getClass().getName().equals("com.questhelper.QuestHelperPlugin")
				&& pluginManager.isPluginActive(plugin))
			{
				questHelper = plugin;
				break;
			}
		}
		if (questHelper == null)
		{
			return false;
		}
		Plugin target = questHelper;
		clientThread.invoke(() ->
		{
			try
			{
				Field handlerField = target.getClass().getDeclaredField("questMenuHandler");
				handlerField.setAccessible(true);
				Object handler = handlerField.get(target);
				Method start = handler.getClass().getMethod("startUpQuest", String.class);
				start.invoke(handler, questName);
			}
			catch (ReflectiveOperationException | RuntimeException e)
			{
				log.warn("Quest Helper launch failed for {}", questName, e);
			}
		});
		return true;
	}
}
