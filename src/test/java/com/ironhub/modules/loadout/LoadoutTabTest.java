package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import javax.swing.JPanel;
import net.runelite.api.EquipmentInventorySlot;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

public class LoadoutTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void currentLoadoutAndActivityRenderHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		state.setSlayerTask("Dust devils");
		state.setCombatTarget("Zulrah", 2042);
		int[] worn = new int[14];
		java.util.Arrays.fill(worn, -1);
		worn[EquipmentInventorySlot.WEAPON.getSlotIdx()] = 4151;
		worn[EquipmentInventorySlot.HEAD.getSlotIdx()] = 11865;
		StateFixture.equipmentSlots(state, worn);
		int[] inv = new int[28];
		java.util.Arrays.fill(inv, -1);
		inv[0] = 385; // shark
		StateFixture.inventorySlots(state, inv);

		LoadoutModule module = new LoadoutModule(state, null, null, new IronHubConfig()
		{
		}, new DataPack(new Gson()), new Gson(), null);
		module.startUp();
		JPanel tab = (JPanel) module.buildTab();
		java.awt.image.BufferedImage image = SwingRender.render(tab);
		assertTrue(image.getHeight() > 300); // equipped + inventory grids present
		java.io.File out = new java.io.File("build/reports/loadout-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
