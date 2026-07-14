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
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;

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
	private DailiesTab tab;

	@Inject
	public DailiesModule(AccountState state, IronHubConfig config, DataPack dataPack)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
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

	static Requirement requirement(DailiesPack.Daily daily)
	{
		return Requirements.allOf(daily.getRequirements().stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}
}
