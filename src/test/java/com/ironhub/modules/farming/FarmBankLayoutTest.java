package com.ironhub.modules.farming;

import com.ironhub.state.PersistedState;
import java.util.Map;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The bank layout builder (Inventory Setups port): the setup-to-bank-grid
 * position mapping, and that apply/clear drive Bank Tags to filter and
 * restore the real bank.
 */
public class FarmBankLayoutTest
{
	private static final String TAG = "_ironhubfarm_farm";

	private static PersistedState.SavedSetup setup()
	{
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("CAPE", 1052);
		setup.equipment.put("RING", 13126);
		setup.inventory = new int[]{8013, 5291, 0, 0, 5291};
		setup.inventoryQty = new int[]{1, 1, 0, 0, 1};
		return setup;
	}

	@Test
	public void positionsMirrorTheWornAndInventoryGrids()
	{
		Map<Integer, Integer> pos = FarmBankLayout.positions(setup());
		// equipment in the preset arrangement (8-wide bank grid)
		assertEquals((Integer) 1052, pos.get(8));   // CAPE
		assertEquals((Integer) 13126, pos.get(34)); // RING
		// inventory fills the right columns (4..7), row by row; the empty
		// slots at index 2,3 still advance the cursor so index 4 lands at col 0 of row 1
		assertEquals((Integer) 8013, pos.get(4));   // inv slot 0 -> col4 row0
		assertEquals((Integer) 5291, pos.get(5));   // inv slot 1 -> col5 row0
		assertEquals((Integer) 5291, pos.get(12));  // inv slot 4 -> col4 row1
		assertFalse(pos.containsKey(6));            // slot 2 empty
	}

	@Test
	public void applyFiltersAndLaysOutTheBankThenClearRestoresIt()
	{
		BankTagsService bankTags = Mockito.mock(BankTagsService.class);
		TagManager tagManager = Mockito.mock(TagManager.class);
		LayoutManager layoutManager = Mockito.mock(LayoutManager.class);
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		Mockito.when(itemManager.canonicalize(Mockito.anyInt()))
			.thenAnswer(inv -> inv.getArgument(0));

		FarmBankLayout layout = new FarmBankLayout("farm", bankTags, tagManager, layoutManager, itemManager);
		layout.apply("Herb run", setup());

		// the setup's items got tagged and pinned, the tag hidden, and the
		// bank opened onto it (so the real bank rearranges)
		Mockito.verify(tagManager).addTag(1052, TAG, false);
		Mockito.verify(tagManager).addTag(13126, TAG, false);
		Mockito.verify(tagManager).setHidden(TAG, true);
		ArgumentCaptor<Layout> saved = ArgumentCaptor.forClass(Layout.class);
		Mockito.verify(layoutManager).saveLayout(saved.capture());
		assertEquals(1052, saved.getValue().getItemAtPos(8));
		assertEquals(8013, saved.getValue().getItemAtPos(4));
		Mockito.verify(bankTags).openBankTag(TAG, BankTagsService.OPTION_HIDE_TAG_NAME);
		assertTrue(layout.isApplied());

		// re-applying the same setup does not re-tag, only re-opens
		layout.apply("Herb run", setup());
		Mockito.verify(tagManager, Mockito.times(1)).setHidden(TAG, true);
		Mockito.verify(bankTags, Mockito.times(2)).openBankTag(TAG, BankTagsService.OPTION_HIDE_TAG_NAME);

		layout.clear();
		Mockito.verify(bankTags).closeBankTag();
		// twice: the FIRST apply also removes the fixed tag unconditionally,
		// healing anything a crashed session left in Bank Tags' config
		Mockito.verify(tagManager, Mockito.times(2)).removeTag(TAG);
		Mockito.verify(layoutManager, Mockito.times(2)).removeLayout(TAG);
		assertFalse(layout.isApplied());
	}

	@Test
	public void nullServicesAreANoOp()
	{
		FarmBankLayout layout = new FarmBankLayout("farm", null, null, null, null);
		layout.apply("Herb run", setup()); // must not throw
		layout.clear();
		assertFalse(layout.isApplied());
	}
}
