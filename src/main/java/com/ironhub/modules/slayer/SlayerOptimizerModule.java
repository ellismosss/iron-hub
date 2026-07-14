package com.ironhub.modules.slayer;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarPlayer;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

/**
 * Slayer basics (DESIGN.md §3.16): current task (name, remaining, streak,
 * points) from documented varps/varbits, plus the task infobox (frame 3f,
 * visible while on task). Point planning, block/skip advice and per-task
 * readiness arrive with the task-rates data pack.
 */
@Slf4j
@Singleton
public class SlayerOptimizerModule implements IronHubModule
{
	/** SLAYER_TARGET value meaning "boss task" — resolve via the boss table. */
	private static final int CREATURE_BOSS = 98;
	// cache DB tables the client resolves task names through; ids mirror
	// core's SlayerPlugin.updateTask (table 113 = tasks, 116 = boss rows)
	private static final int TABLE_TASKS = 113;
	private static final int TABLE_BOSSES = 116;
	private static final int COLUMN_TASK_NAME = 10;
	private static final int COLUMN_BOSS_TASK_ROW = 4;

	private final AccountState state;
	private final Client client;
	private final ClientThread clientThread;
	private final IronHubConfig config;
	private final InfoBoxManager infoBoxManager;     // null in unit tests
	private final Provider<? extends Plugin> plugin; // Provider breaks the DI cycle

	private final Runnable listener = this::onStateChanged;
	private SlayerTab tab;
	private SlayerInfoBox infoBox;
	private volatile String taskName = "";
	private volatile int resolvedCreature = -1;
	private volatile int resolvedBossId = -1;

	@Inject
	public SlayerOptimizerModule(AccountState state, Client client, ClientThread clientThread,
		IronHubConfig config, InfoBoxManager infoBoxManager, Provider<com.ironhub.IronHubPlugin> plugin)
	{
		this.state = state;
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.infoBoxManager = infoBoxManager;
		this.plugin = plugin;
	}

	@Override
	public String name()
	{
		return "Slayer";
	}

	@Override
	public boolean enabled()
	{
		return config.slayerOptimizer();
	}

	@Override
	public void startUp()
	{
		state.watchVarps(VarPlayer.SLAYER_TASK_SIZE, VarPlayer.SLAYER_TASK_CREATURE);
		state.watchVarbits(VarbitID.SLAYER_POINTS, VarbitID.SLAYER_TASKS_COMPLETED,
			VarbitID.SLAYER_TARGET_BOSSID);
		state.addListener(listener);

		if (infoBoxManager != null)
		{
			BufferedImage icon = ImageUtil.loadImageResource(com.ironhub.IronHubPlugin.class, "/icon.png");
			infoBox = new SlayerInfoBox(icon, plugin.get(), this);
			infoBoxManager.addInfoBox(infoBox);
		}
	}

	@Override
	public void shutDown()
	{
		state.removeListener(listener);
		if (infoBox != null)
		{
			infoBoxManager.removeInfoBox(infoBox);
			infoBox = null;
		}
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
			tab = new SlayerTab(state, this);
		}
		return tab;
	}

	// ── reads for tab + infobox ───────────────────────────────────────

	int remaining()
	{
		return state.getVarp(VarPlayer.SLAYER_TASK_SIZE);
	}

	int points()
	{
		return state.getVarbit(VarbitID.SLAYER_POINTS);
	}

	int streak()
	{
		return state.getVarbit(VarbitID.SLAYER_TASKS_COMPLETED);
	}

	String taskName()
	{
		return taskName;
	}

	/** Resolve the creature name on the client thread when the task changes. */
	private void onStateChanged()
	{
		int creature = state.getVarp(VarPlayer.SLAYER_TASK_CREATURE);
		int bossId = state.getVarbit(VarbitID.SLAYER_TARGET_BOSSID);
		if (creature == resolvedCreature && bossId == resolvedBossId)
		{
			return;
		}
		resolvedCreature = creature;
		resolvedBossId = bossId;
		if (creature <= 0 || client == null || clientThread == null)
		{
			taskName = "";
			state.setSlayerTask("");
			return;
		}
		clientThread.invoke(() ->
		{
			taskName = resolveTaskName(creature, bossId);
			state.setSlayerTask(taskName); // shared: the loadout tab keys strategies off it
			if (tab != null)
			{
				SwingUtilities.invokeLater(tab::rebuild);
			}
		});
	}

	private String resolveTaskName(int creature, int bossId)
	{
		try
		{
			int taskRow;
			if (creature == CREATURE_BOSS)
			{
				var bossRows = client.getDBRowsByValue(TABLE_BOSSES, 1, 0, bossId);
				if (bossRows.isEmpty())
				{
					return "Boss task";
				}
				taskRow = (Integer) client.getDBTableField(bossRows.get(0), COLUMN_BOSS_TASK_ROW, 0)[0];
			}
			else
			{
				var rows = client.getDBRowsByValue(TABLE_TASKS, 0, 0, creature);
				if (rows.isEmpty())
				{
					return "";
				}
				taskRow = rows.get(0);
			}
			return (String) client.getDBTableField(taskRow, COLUMN_TASK_NAME, 0)[0];
		}
		catch (RuntimeException e)
		{
			log.warn("failed to resolve slayer task name", e);
			return "";
		}
	}
}
