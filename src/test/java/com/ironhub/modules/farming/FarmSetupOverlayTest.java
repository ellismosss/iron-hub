package com.ironhub.modules.farming;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.state.StateFixture;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The bank companion overlay (ported from Inventory Setups): gated on
 * config + an active run with a saved setup + the bank being open, sized
 * within the overlay budget, and rendered for a side-by-side.
 */
public class FarmSetupOverlayTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private FarmingRunModule startedRun(AccountState state)
	{
		FarmingRunModule module = new FarmingRunModule(state, null, new EventBus(),
			null, null, null, config, null, new DataPack(new Gson()), null,
			TimetrackingFixture.configManager(), null);
		module.startUp();
		module.startTemplate("Herb run");
		return module;
	}

	private static ItemManager itemManager()
	{
		AsyncBufferedImage sprite = new AsyncBufferedImage(null, 36, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = sprite.createGraphics();
		sg.setColor(new Color(0xC8, 0xA0, 0x50));
		sg.fillRect(2, 2, 30, 28);
		sg.dispose();
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		Mockito.when(itemManager.getImage(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
			.thenReturn(sprite);
		return itemManager;
	}

	private static Client bankOpenClient(boolean open)
	{
		Client client = Mockito.mock(Client.class);
		Widget bank = Mockito.mock(Widget.class);
		Mockito.when(bank.isHidden()).thenReturn(!open);
		Mockito.when(client.getWidget(InterfaceID.Bankmain.ITEMS)).thenReturn(open ? bank : null);
		return client;
	}

	private static PersistedState.SavedSetup setup()
	{
		PersistedState.SavedSetup setup = new PersistedState.SavedSetup();
		setup.equipment.put("CAPE", 1052);
		setup.equipment.put("RING", 13126);
		setup.inventory = new int[28];
		setup.inventoryQty = new int[28];
		int[] items = {8013, 8013, 5291, 5291, 5291, 21622};
		for (int i = 0; i < items.length; i++)
		{
			setup.inventory[i] = items[i];
			setup.inventoryQty[i] = 1;
		}
		return setup;
	}

	@Test
	public void rendersTheSetupOverTheBankWithinBudget() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 7L);
		// carry one of the setup's items so a mix of have (neutral) and
		// missing (red) slots renders
		StateFixture.inventorySlots(state, new int[]{8013});
		StateFixture.inventory(state, Map.of(8013, 1));
		state.saveFarmRunSetup("Herb run", setup());

		FarmingRunModule module = startedRun(state);
		FarmSetupOverlay overlay = new FarmSetupOverlay(module, state, config,
			itemManager(), bankOpenClient(true));

		BufferedImage canvas = new BufferedImage(280, 240, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		g.setColor(new Color(0x2E, 0x2A, 0x22));
		g.fillRect(0, 0, 280, 240);
		g.setFont(FontManager.getRunescapeSmallFont());
		Dimension size = overlay.render(g);
		g.dispose();

		assertNotNull(size);
		assertTrue("width " + size.width, size.width <= 250);
		assertTrue("height " + size.height, size.height <= 200);
		java.io.File out = new java.io.File("build/reports/farming-bank-setup.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(canvas, "png", out);
		module.shutDown();
	}

	@Test
	public void hiddenWhenBankClosedOrNoSetupOrNotRunning() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 7L);
		Graphics2D g = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics();
		g.setFont(FontManager.getRunescapeSmallFont());

		// running + bank open but NO saved setup for this run
		FarmingRunModule module = startedRun(state);
		FarmSetupOverlay overlay = new FarmSetupOverlay(module, state, config,
			itemManager(), bankOpenClient(true));
		assertNull(overlay.render(g));

		// setup saved, but the bank is closed
		state.saveFarmRunSetup("Herb run", setup());
		assertNull(new FarmSetupOverlay(module, state, config, itemManager(), bankOpenClient(false))
			.render(g));

		// setup + bank open, but no run active
		module.endRun(false);
		assertNull(new FarmSetupOverlay(module, state, config, itemManager(), bankOpenClient(true))
			.render(g));
		module.shutDown();
	}
}
