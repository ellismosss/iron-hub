package com.ironhub.modules.bank;

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
 * in list order (the sidebar's ranking order).
 *
 * <p>All calls mutate bank/tag state and MUST run on the client thread.
 * Null services (headless tests) make every method a no-op.
 */
class BankCollectView
{
	private static final String TAG = "_ironhubbank_";
	private static final int COLUMNS = 8;

	private final BankTagsService bankTagsService;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final ItemManager itemManager;

	private boolean applied;
	private int appliedSignature;

	BankCollectView(BankTagsService bankTagsService, TagManager tagManager,
		LayoutManager layoutManager, ItemManager itemManager)
	{
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
	void apply(List<Integer> itemIds)
	{
		if (!available() || itemIds.isEmpty())
		{
			return;
		}
		int signature = itemIds.hashCode();
		if (!applied || signature != appliedSignature)
		{
			removeApplied();
			Layout layout = new Layout(TAG);
			int pos = 0;
			for (int itemId : itemIds)
			{
				int canonical = itemManager.canonicalize(itemId);
				layout.setItemAtPos(canonical, pos);
				tagManager.addTag(canonical, TAG, false);
				pos++;
				if (pos >= COLUMNS * 6)
				{
					break; // one screen of collected items is the point
				}
			}
			layoutManager.saveLayout(layout);
			tagManager.setHidden(TAG, true); // never clutters the tag bar
			applied = true;
			appliedSignature = signature;
		}
		bankTagsService.openBankTag(TAG, BankTagsService.OPTION_HIDE_TAG_NAME);
	}

	/** Close the collected view and remove the hidden tag + layout so
	 *  nothing is left behind in the player's bank. Client thread. */
	void clear()
	{
		if (!available() || !applied)
		{
			return;
		}
		bankTagsService.closeBankTag();
		removeApplied();
	}

	boolean isApplied()
	{
		return applied;
	}

	private void removeApplied()
	{
		if (applied)
		{
			tagManager.removeTag(TAG);
			layoutManager.removeLayout(TAG);
			applied = false;
			appliedSignature = 0;
		}
	}
}
