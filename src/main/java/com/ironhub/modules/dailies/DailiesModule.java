package com.ironhub.modules.dailies;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.Notifier;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

/**
 * Dailies & recurring reminders (DESIGN.md §3.12): reset-aware manual
 * ticks over the dailies data pack. Varbit/chat auto-detection comes
 * per-daily as mappings are confirmed; manual ticks persist per profile
 * and expire at the activity's reset.
 */
@Slf4j
@Singleton
public class DailiesModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final InfoBoxManager infoBoxManager;   // null in unit tests
	private final Provider<? extends Plugin> plugin; // Provider breaks the DI cycle
	private final EventBus eventBus;               // null in unit tests
	private final Notifier notifier;               // null in unit tests
	private DailiesTab tab;
	private DailiesInfoBox infoBox;
	private DailiesPack pack;
	private long lastResetKey;
	private int notifyTick;

	@Inject
	public DailiesModule(AccountState state, IronHubConfig config, DataPack dataPack,
		InfoBoxManager infoBoxManager, Provider<com.ironhub.IronHubPlugin> plugin,
		EventBus eventBus, Notifier notifier)
	{
		this.eventBus = eventBus;
		this.notifier = notifier;
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
		this.infoBoxManager = infoBoxManager;
		this.plugin = plugin;
	}

	@Override
	public String name()
	{
		return "Dailies";
	}

	@Override
	public boolean enabled()
	{
		return config.dailies();
	}

	@Override
	public void startUp()
	{
		pack = dataPack.load("dailies", DailiesPack.class);
		lastResetKey = lastReset("daily", System.currentTimeMillis());
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		if (infoBoxManager != null)
		{
			BufferedImage icon = ImageUtil.loadImageResource(com.ironhub.IronHubPlugin.class, "/icon.png");
			infoBox = new DailiesInfoBox(icon, plugin.get(), () -> outstanding(state, pack));
			infoBoxManager.addInfoBox(infoBox);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (++notifyTick % 100 != 0) // ~1 min — reset crossings are daily
		{
			return;
		}
		long key = lastReset("daily", System.currentTimeMillis());
		if (key != lastResetKey)
		{
			lastResetKey = key;
			int outstanding = outstanding(state, pack);
			if (outstanding > 0 && notifier != null && config.notifyDailyReset())
			{
				notifier.notify(outstanding + (outstanding == 1
					? " daily is available again" : " dailies are available again"));
			}
		}
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
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
			tab = new DailiesTab(state, dataPack.load("dailies", DailiesPack.class));
		}
		return tab;
	}

	/**
	 * Epoch millis of the most recent reset for a reset type, relative to
	 * {@code now}: daily = 00:00 UTC today, weekly = Wednesday 00:00 UTC
	 * (the game's weekly reset), growth = never (manual toggle only).
	 */
	static long lastReset(String reset, long now)
	{
		ZonedDateTime utcNow = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC);
		switch (reset)
		{
			case "daily":
				return utcNow.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
			case "weekly":
				return utcNow.toLocalDate()
					.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
					.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
			default: // growth — Hespori-style, no timed reset
				return 0;
		}
	}

	/** Manually ticked and still inside the current reset window. */
	static boolean isDone(AccountState state, DailiesPack.Daily daily, long now)
	{
		long doneAt = state.dailyDoneAt(daily.getId());
		return doneAt > 0 && doneAt >= lastReset(daily.getReset(), now);
	}

	/** Available (requirements met) and not ticked this reset. */
	public static int outstanding(AccountState state, DailiesPack pack)
	{
		long now = System.currentTimeMillis();
		return (int) pack.getDailies().stream()
			.filter(d -> !isDone(state, d, now) && requirement(d).isMet(state))
			.count();
	}

	static Requirement requirement(DailiesPack.Daily daily)
	{
		return Requirements.allOf(daily.getRequirements().stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}
}
