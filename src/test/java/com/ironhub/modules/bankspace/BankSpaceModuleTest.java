package com.ironhub.modules.bankspace;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.BankStoragePack;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BankSpaceModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private final BankStoragePack pack =
		new DataPack(new Gson()).load("bank-storage", BankStoragePack.class);

	private BankSpaceModule module(AccountState state)
	{
		return new BankSpaceModule(state, config, new DataPack(new Gson()),
			new EventBus(), null, null, null);
	}

	private BankStoragePack.Location location(String id)
	{
		return pack.locations.stream().filter(l -> l.id.equals(id))
			.findFirst().orElseThrow();
	}

	@Test
	public void packIntegrity()
	{
		assertEquals(21, pack.locations.size());
		int total = 0;
		int bis = 0;
		Set<String> bisLocations = new HashSet<>();
		for (BankStoragePack.Location location : pack.locations)
		{
			assertFalse(location.name.isEmpty());
			Set<Integer> seen = new HashSet<>();
			for (BankStoragePack.Entry entry : location.items)
			{
				assertTrue(location.id + " bad id", entry.id > 0);
				assertFalse(location.id + " unnamed item " + entry.id,
					entry.name == null || entry.name.isEmpty());
				assertTrue(location.id + " duplicate id " + entry.id, seen.add(entry.id));
				total++;
				if (entry.bis)
				{
					bis++;
					bisLocations.add(location.id);
				}
			}
		}
		assertTrue("total " + total, total >= 2200);
		// the reference only reads bis flags for these three POH storages
		assertEquals(Set.of("armour_case", "cape_rack", "magic_wardrobe"), bisLocations);
		assertTrue("bis " + bis, bis >= 100);
		// anchors
		assertEquals("Tackle Box", location("tackle_box").name);
		assertTrue(location("tackle_box").items.stream()
			.anyMatch(e -> "Angler boots".equals(e.name)));
		assertEquals(524, location("treasure_chest").items.size());
	}

	@Test
	public void derivationFollowsTheReferenceAlgorithm()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		BankSpaceModule module = module(state);

		BankStoragePack.Entry tackle = location("tackle_box").items.get(0);
		BankStoragePack.Entry seed = location("seed_vault").items.get(0);
		BankStoragePack.Entry bis = location("armour_case").items.stream()
			.filter(e -> e.bis).findFirst().orElseThrow();
		Map<Integer, Integer> bank = new HashMap<>();
		bank.put(tackle.id, 1);
		bank.put(seed.id, 40);
		bank.put(bis.id, 1);
		bank.put(4151, 1); // a whip — storable nowhere
		StateFixture.bank(state, bank);

		// bis hidden by default (the reference's default too)
		Set<Integer> flagged = module.flaggedItems();
		assertEquals(Set.of(tackle.id, seed.id), flagged);

		// flag bis too
		state.setBankStorageFlagBis(true);
		assertTrue(module.flaggedItems().contains(bis.id));
		state.setBankStorageFlagBis(false);

		// switching a location off keeps its report (faint) but unflags
		state.toggleBankStorageLocation("tackle_box");
		assertFalse(module.flaggedItems().contains(tackle.id));
		List<BankSpaceModule.LocationReport> reports = module.reports();
		BankSpaceModule.LocationReport tackleReport = reports.stream()
			.filter(r -> r.location.id.equals("tackle_box")).findFirst().orElseThrow();
		assertFalse(tackleReport.enabled);
		assertEquals(1, tackleReport.storable.size());
		state.toggleBankStorageLocation("tackle_box"); // back on

		// ignoring an item removes it everywhere and lists it for restore
		state.toggleBankStorageIgnored(seed.id);
		assertFalse(module.flaggedItems().contains(seed.id));
		List<BankStoragePack.Entry> ignored = module.ignoredInBank();
		assertEquals(1, ignored.size());
		assertEquals(seed.id, ignored.get(0).id);
		state.toggleBankStorageIgnored(seed.id); // restore
		assertTrue(module.flaggedItems().contains(seed.id));
		assertTrue(module.ignoredInBank().isEmpty());

		// the whip never flags
		assertFalse(module.isStorable(4151));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 42L);
		Map<Integer, Integer> bank = new HashMap<>();
		// a spread over four storages + noise, one ignored item
		for (int i = 0; i < 6; i++)
		{
			bank.put(location("tackle_box").items.get(i).id, 1);
		}
		for (int i = 0; i < 4; i++)
		{
			bank.put(location("seed_vault").items.get(i).id, 25);
		}
		bank.put(location("master_scroll_book").items.get(0).id, 12);
		bank.put(location("treasure_chest").items.get(3).id, 1);
		bank.put(4151, 1);
		bank.put(995, 1_000_000);
		StateFixture.bank(state, bank);

		BankSpaceModule module = module(state);
		module.startUp();
		BankSpaceTab tab = (BankSpaceTab) module.buildTab();
		assertNotNull(tab);
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			state.toggleBankStorageIgnored(location("treasure_chest").items.get(3).id);
			tab.expand("tackle_box");
		});
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain queued rebuilds
		BufferedImage image = SwingRender.render(tab);
		assertTrue("height " + image.getHeight(), image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/bank-space-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
