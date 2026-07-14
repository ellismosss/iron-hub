package com.ironhub.modules.clues;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.ClueItemsPack;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CluesTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final ClueItemsPack pack = new DataPack(new Gson()).load("clue-items", ClueItemsPack.class);

	private ClueItemsPack.Clue draynorDance()
	{
		return pack.getClues().stream()
			.filter(c -> c.getClue().contains("Draynor")).findFirst().orElseThrow();
	}

	@Test
	public void readinessFollowsOwnedItems()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		ClueItemsPack.Clue clue = draynorDance();

		assertFalse(ClueStashModule.doable(clue, state));
		assertEquals("item 1101", ClueStashModule.blocking(clue, state)); // iron chainbody

		// owning all three flips it doable
		StateFixture.bank(state, Map.of(1101, 1, 1095, 1, 1169, 1));
		assertTrue(ClueStashModule.doable(clue, state));
		assertEquals(null, ClueStashModule.blocking(clue, state));
	}

	@Test
	public void itemFreeCluesAreAlwaysDoable()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		long doableEasy = ClueStashModule.doableCount(
			pack.getClues().stream().filter(c -> c.getTier().equals("Easy"))
				.collect(java.util.stream.Collectors.toList()), state);
		assertEquals(3, doableEasy); // three of four easy clues need nothing
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(1101, 1));

		ClueStashModule module = new ClueStashModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()));
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 150);
		java.io.File out = new java.io.File("build/reports/clues-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
