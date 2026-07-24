package com.ironhub.modules.wheresmystuff;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.StorageLocationsPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.awt.image.BufferedImage;
import java.util.Map;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WheresMyStuffModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final IronHubConfig config = new IronHubConfig()
	{
	};

	private final StorageLocationsPack pack =
		new DataPack(new Gson()).load("storage-locations", StorageLocationsPack.class);

	private StorageLocationsPack.Storage byKey(String key)
	{
		return pack.storages.stream().filter(s -> s.key.equals(key)).findFirst().orElseThrow();
	}

	/** The heart of POH detection: a POH_COSTUMES read is split across the
	 *  costume storages by allow-list, and Uncategorised catches the rest. */
	@Test
	public void attributesCostumeContainerByAllowList()
	{
		int fancyId = byKey("fancyDressBox").items.get(0);
		int armourId = byKey("armourCase").items.get(0);
		int stray = 995; // coins — in no costume allow-list

		Map<Integer, Integer> container = Map.of(fancyId, 1, armourId, 1, stray, 1);
		Map<String, Map<Integer, Integer>> byStorage =
			WheresMyStuffModule.attributePoh(pack, container);

		String fancy = "playerownedhouse:fancyDressBox";
		String armour = "playerownedhouse:armourCase";
		String uncat = "playerownedhouse:uncategorised";
		assertEquals(Integer.valueOf(1), byStorage.get(fancy).get(fancyId));
		assertFalse(byStorage.get(fancy).containsKey(armourId));
		assertFalse(byStorage.get(fancy).containsKey(stray));

		assertEquals(Integer.valueOf(1), byStorage.get(armour).get(armourId));

		// the null-list catch-all keeps what nothing else claimed
		assertEquals(Integer.valueOf(1), byStorage.get(uncat).get(stray));
		assertFalse(byStorage.get(uncat).containsKey(fancyId));
	}

	/** Cape hanger: a mounted-cape object spawn means that cape [+ hood] is
	 *  stored; the empty-hanger object clears it; anything else is ignored. */
	@Test
	public void capeHangerResolvesMountedCape()
	{
		StorageLocationsPack.Storage cape = byKey("capeHanger");
		assertEquals("objectmount", cape.mode);
		assertFalse(cape.mounts.isEmpty());

		StorageLocationsPack.Mount infernal = cape.mounts.get(0);
		assertEquals(infernal.items, WheresMyStuffModule.mountItems(cape, infernal.object));
		assertTrue(WheresMyStuffModule.mountItems(cape, cape.clearObjects.get(0)).isEmpty());
		org.junit.Assert.assertNull(WheresMyStuffModule.mountItems(cape, 1));
	}

	@Test
	public void moduleLabelUsesFamilySuffix()
	{
		WheresMyStuffModule module = new WheresMyStuffModule(
			null, config, new DataPack(new Gson()), new EventBus(), null, null);
		assertEquals("Fancy dress box (PoH)", module.label(byKey("fancyDressBox")));
	}

	@Test
	public void rendersTrackedStorages() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 5L);
		long twoHoursAgo = System.currentTimeMillis() - 2 * 3_600_000L;
		state.putStorageContents("fancyDressBox", "Fancy dress box", "playerownedhouse",
			"Fancy dress box (PoH)",
			Map.of(19_991, 1, 19_992, 1, 19_993, 1),
			Map.of(19_991, "Frog mask", 19_992, "Camo top", 19_993, "Camo bottoms"),
			twoHoursAgo);
		state.putStorageContents("armourCase", "Armour case", "playerownedhouse",
			"Armour case (PoH)",
			Map.of(20_001, 1, 20_002, 1),
			Map.of(20_001, "Rune platebody", 20_002, "Rune platelegs"),
			twoHoursAgo);

		WheresMyStuffModule module = new WheresMyStuffModule(
			state, config, new DataPack(new Gson()), new EventBus(), null, null);
		module.startUp();
		WheresMyStuffTab tab = (WheresMyStuffTab) module.buildTab();
		assertNotNull(tab);
		javax.swing.SwingUtilities.invokeAndWait(() -> tab.expand("playerownedhouse:fancyDressBox"));
		javax.swing.SwingUtilities.invokeAndWait(() -> { }); // drain queued rebuilds
		BufferedImage image = SwingRender.render(tab);
		assertTrue("height " + image.getHeight(), image.getHeight() > 120);
		java.io.File out = new java.io.File("build/reports/wheres-my-stuff-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);

		// the whole-account search — "where is this item?"
		javax.swing.SwingUtilities.invokeAndWait(() -> tab.searchFor("plate"));
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		BufferedImage search = SwingRender.render(tab);
		java.io.File out2 = new java.io.File("build/reports/wheres-my-stuff-search.png");
		javax.imageio.ImageIO.write(search, "png", out2);
		module.shutDown();
	}
}
