package com.ironhub.ui.components;

import java.util.List;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;

/**
 * Collects a list of items together in the REAL bank, the way Inventory
 * Setups presents a setup (and our farm-run bank layout does): a hidden
 * Bank Tag with a {@link Layout} pinning each item to a grid position,
 * opened via {@link BankTagsService} so the bank itself filters and
 * rearranges — not an overlay. Items lay out row-major from the top left
 * in list order (the caller's ranking order).
 *
 * <p>Shared between the bank module and the Loadout module; each owner
 * passes its OWN tag name so their hidden tags never collide. If two
 * owners apply at once, the later {@code openBankTag} simply wins (each
 * tag's items stay under its own name, so nothing corrupts) — last-wins
 * is acceptable because only one sidebar surface drives the bank at a
 * time in practice.
 *
 * <p>All calls mutate bank/tag state and MUST run on the client thread.
 * Null services (headless tests) make every method a no-op.
 */
public class BankCollectView
{
	private static final int COLUMNS = 8;

	private final String tag;
	private final BankTagsService bankTagsService;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final ItemManager itemManager;

	private boolean applied;
	private int appliedSignature;

	public BankCollectView(String tag, BankTagsService bankTagsService, TagManager tagManager,
		LayoutManager layoutManager, ItemManager itemManager)
	{
		this.tag = tag;
		this.bankTagsService = bankTagsService;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.itemManager = itemManager;
	}

	private boolean available()
	{
		return bankTagsService != null && tagManager != null
			&& layoutManager != null && itemManager != null;
	}

	/** Filter and lay the bank out to these items, in order. Client thread. */
	public void apply(List<Integer> itemIds)
	{
		if (!available() || itemIds.isEmpty())
		{
			return;
		}
		int signature = itemIds.hashCode();
		if (applied && signature == appliedSignature
			&& tag.equals(bankTagsService.getActiveTag()))
		{
			// already showing exactly this — every openBankTag call forces a
			// full bank relayout (bankSearch.reset), and Bank Tags itself
			// keeps its active tag across bank rebuilds, so a redundant
			// re-open is pure relayout churn (2026-07-18 bank-open freeze)
			return;
		}
		if (!applied || signature != appliedSignature)
		{
			removeApplied();
			Layout layout = new Layout(tag);
			int pos = 0;
			for (int itemId : itemIds)
			{
				int canonical = itemManager.canonicalize(itemId);
				layout.setItemAtPos(canonical, pos);
				tagManager.addTag(canonical, tag, false);
				pos++;
				if (pos >= COLUMNS * 6)
				{
					break; // one screen of collected items is the point
				}
			}
			layoutManager.saveLayout(layout);
			tagManager.setHidden(tag, true); // never clutters the tag bar
			applied = true;
			appliedSignature = signature;
		}
		bankTagsService.openBankTag(tag, BankTagsService.OPTION_HIDE_TAG_NAME);
	}

	/** Close the collected view and remove the hidden tag + layout so
	 *  nothing is left behind in the player's bank. Client thread. */
	public void clear()
	{
		if (!available() || !applied)
		{
			return;
		}
		bankTagsService.closeBankTag();
		removeApplied();
	}

	public boolean isApplied()
	{
		return applied;
	}

	private void removeApplied()
	{
		if (applied)
		{
			tagManager.removeTag(tag);
			layoutManager.removeLayout(tag);
			applied = false;
			appliedSignature = 0;
		}
	}
}
