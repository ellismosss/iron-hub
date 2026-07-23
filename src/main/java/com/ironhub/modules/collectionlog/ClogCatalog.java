package com.ironhub.modules.collectionlog;

import com.ironhub.state.PersistedState;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;

/**
 * The collection log's own structure, read from the game cache: five tabs,
 * each an ordered list of pages, each page an ordered list of slot item ids
 * — exactly what the log's interface draws, so it can never drift from the
 * game the way a hand-maintained table would.
 *
 * <p>Decoded from the log's cs2 (RuneStar/cs2-scripts, [proc,collection_*]):
 * {@code collection_draw_tabs_all} names the five tab structs, and per tab
 * {@code collection_draw_list} walks {@code param_683} (an enum of page
 * structs) reading {@code param_689} for the page name, while
 * {@code collection_draw_log} walks the page's {@code param_690} (an enum of
 * item ids). Full decode in DOMAIN-NOTES "Collection log detection".
 *
 * <p>Cache-layout drift is honest: a read that finds no pages returns empty
 * and the caller keeps the snapshot it already had.
 */
@Slf4j
final class ClogCatalog
{
	/** The five tab structs, in the order the interface draws them. */
	private static final int[] TAB_STRUCTS = {471, 472, 473, 474, 475};
	private static final int PARAM_TAB_NAME = 682;
	private static final int PARAM_TAB_PAGES = 683;
	private static final int PARAM_PAGE_NAME = 689;
	private static final int PARAM_PAGE_ITEMS = 690;
	/** The log has ~1,900 slots; a fraction of that means the layout moved. */
	private static final int SLOT_FLOOR = 1_000;

	private ClogCatalog()
	{
	}

	/** Read the whole structure (client thread only); empty on drift. */
	static List<PersistedState.ClogTab> load(Client client)
	{
		List<PersistedState.ClogTab> tabs = new ArrayList<>();
		int slots = 0;
		for (int structId : TAB_STRUCTS)
		{
			StructComposition tabStruct = client.getStructComposition(structId);
			if (tabStruct == null)
			{
				continue;
			}
			EnumComposition pageList = client.getEnum(tabStruct.getIntValue(PARAM_TAB_PAGES));
			if (pageList == null)
			{
				continue;
			}
			PersistedState.ClogTab tab = new PersistedState.ClogTab();
			String name = tabStruct.getStringValue(PARAM_TAB_NAME);
			tab.name = name == null || name.isEmpty() ? "Tab " + structId : name;
			for (int pageStructId : pageList.getIntVals())
			{
				StructComposition pageStruct = client.getStructComposition(pageStructId);
				if (pageStruct == null)
				{
					continue;
				}
				EnumComposition itemList = client.getEnum(pageStruct.getIntValue(PARAM_PAGE_ITEMS));
				if (itemList == null)
				{
					continue;
				}
				PersistedState.ClogPage page = new PersistedState.ClogPage();
				String pageName = pageStruct.getStringValue(PARAM_PAGE_NAME);
				if (pageName == null || pageName.isEmpty())
				{
					continue; // a nameless page is nothing we can show honestly
				}
				page.name = pageName;
				page.items = itemList.getIntVals().clone();
				slots += page.items.length;
				tab.pages.add(page);
			}
			if (!tab.pages.isEmpty())
			{
				tabs.add(tab);
			}
		}
		if (tabs.size() < TAB_STRUCTS.length || slots < SLOT_FLOOR)
		{
			log.warn("collection log catalog read {} tabs / {} slots - cache layout may have "
				+ "changed", tabs.size(), slots);
			return List.of();
		}
		return tabs;
	}
}
