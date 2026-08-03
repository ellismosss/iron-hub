package com.ironhub.modules.supplies;

import com.ironhub.IronHubConfig;
import com.ironhub.data.SuppliesPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Supplies runway (DESIGN.md §3.17, rebuilt 2026-07-24 per Luke): a
 * threshold watchlist of the consumables and resources an ironman stocks,
 * grouped into category tiles. Each category shows a curated top-20 by
 * default; the player searches to add anything from the catalog, removes
 * what they do not care about, sets a red-highlight threshold per item, and
 * can track a restock goal.
 *
 * <p>Superseded the old runway-hours estimate (built from the trip-diff
 * consumption log, which surfaced dropped weapons as "supplies"). The
 * catalog is a generated pack ({@link SuppliesPack}) so nothing but real
 * supplies can appear.
 */
@Slf4j
@Singleton
public class SuppliesRunwayModule implements IronHubModule
{
	private final AccountState state;
	private final ItemManager itemManager;
	private final IronHubConfig config;
	private final com.ironhub.data.DataPack dataPack;
	private RunwayTab tab;

	@Inject
	public SuppliesRunwayModule(AccountState state, ItemManager itemManager, IronHubConfig config,
		com.ironhub.data.DataPack dataPack)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.config = config;
		this.dataPack = dataPack;
	}

	/** The supplies catalog; null in headless tests constructed without a DataPack. */
	SuppliesPack pack()
	{
		return dataPack == null ? null : dataPack.load("supplies", SuppliesPack.class);
	}

	/** Where-from lines for restock hovers (design/KB-RUNTIME.md); null headless. */
	com.ironhub.data.ItemSourcesPack itemSources()
	{
		return dataPack == null ? null
			: dataPack.load("item-sources", com.ironhub.data.ItemSourcesPack.class);
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
			tab = new RunwayTab(state, itemManager, this, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: drop it, the next mount rebuilds. */
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

	/**
	 * The items the player is watching in a category, in pack order (curated
	 * defaults first, then anything they added) — the pack's defaults minus
	 * removals, plus additions.
	 */
	List<SuppliesPack.Item> watchlist(String categoryKey)
	{
		SuppliesPack pack = pack();
		List<SuppliesPack.Item> out = new ArrayList<>();
		if (pack == null)
		{
			return out;
		}
		for (SuppliesPack.Item item : pack.inCategory(categoryKey))
		{
			if (state.isSupplyTracked(item.id, Boolean.TRUE.equals(item.isDefault)))
			{
				out.add(item);
			}
		}
		return out;
	}
}
