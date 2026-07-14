package com.ironhub.modules.supplies;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Supplies Runway (DESIGN.md §3.17): hours of stock left per consumable,
 * from rolling usage rates over the consumption log and current owned
 * stock. Restock guidance and planning mode arrive later.
 */
@Slf4j
@Singleton
public class SuppliesRunwayModule implements IronHubModule
{
	/** Need at least this many minutes of observed usage to rate an item. */
	static final long MIN_SPAN_MS = 10 * 60_000;

	private final AccountState state;
	private final ItemManager itemManager;
	private final IronHubConfig config;
	private RunwayTab tab;

	@Inject
	public SuppliesRunwayModule(AccountState state, ItemManager itemManager, IronHubConfig config)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Supplies runway";
	}

	@Override
	public boolean enabled()
	{
		return config.suppliesRunway();
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
			tab = new RunwayTab(state, itemManager, this);
		}
		return tab;
	}

	int warningHours()
	{
		return config.runwayWarningHours();
	}

	/** A consumable with an observed usage rate. */
	public static class Runway
	{
		public final int itemId;
		public final double perHour;
		public final int stock;

		Runway(int itemId, double perHour, int stock)
		{
			this.itemId = itemId;
			this.perHour = perHour;
			this.stock = stock;
		}

		public double hoursLeft()
		{
			return perHour <= 0 ? Double.POSITIVE_INFINITY : stock / perHour;
		}
	}

	/**
	 * Usage rates from the event log: per item, total consumed over the
	 * span between its first and last events. Items with a single event
	 * or a tiny span are skipped (not enough signal).
	 * ponytail: spans include logged-out time, so rates skew low after
	 * breaks; refine with session windows when planning mode lands.
	 */
	public static Map<Integer, Runway> compute(AccountState state)
	{
		List<AccountState.Consumption> log = state.getConsumptionLog();
		Map<Integer, long[]> spans = new HashMap<>();   // id -> [first, last]
		Map<Integer, Integer> totals = new HashMap<>();
		for (AccountState.Consumption event : log)
		{
			totals.merge(event.itemId, event.quantity, Integer::sum);
			spans.compute(event.itemId, (id, span) -> span == null
				? new long[]{event.timeMs, event.timeMs}
				: new long[]{span[0], event.timeMs});
		}

		Map<Integer, Runway> result = new LinkedHashMap<>();
		totals.entrySet().stream()
			.filter(e -> spans.get(e.getKey())[1] - spans.get(e.getKey())[0] >= MIN_SPAN_MS)
			.map(e ->
			{
				long[] span = spans.get(e.getKey());
				double hours = (span[1] - span[0]) / 3_600_000.0;
				return new Runway(e.getKey(), e.getValue() / hours, state.canonicalStock(e.getKey()));
			})
			.sorted(Comparator.comparingDouble(Runway::hoursLeft))
			.forEach(r -> result.put(r.itemId, r));
		return result;
	}

	/** "14 h" / "45 min" / "-" for unknown. */
	public static String formatHours(double hours)
	{
		if (Double.isInfinite(hours))
		{
			return "-";
		}
		if (hours < 1)
		{
			return Math.max(1, Math.round(hours * 60)) + " min";
		}
		return Math.round(hours) + " h";
	}
}
