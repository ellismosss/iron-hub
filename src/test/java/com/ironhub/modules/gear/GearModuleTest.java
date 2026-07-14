package com.ironhub.modules.gear;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.GearLaddersPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import com.ironhub.ui.components.GridTile;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GearModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final GearLaddersPack pack =
		new DataPack(new Gson()).load("gear-ladders", GearLaddersPack.class);

	@Test
	public void ownedThenFirstMetIsNextRestLocked()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(1323, 1)); // iron scimitar owned
		StateFixture.stat(state, Skill.ATTACK, 60, 0);

		List<GearLaddersPack.Rung> weapon = pack.getStyles().get(0).getSlots().get(0).getLadder();
		List<GridTile.State> states = GearProgressionModule.ladderStates(state, weapon);
		assertEquals(GridTile.State.OWNED, states.get(0));  // iron scim
		assertEquals(GridTile.State.NEXT, states.get(1));   // rune scim (40 atk met)
		assertEquals(GridTile.State.LOCKED, states.get(2)); // d scim (quest unmet)
		assertEquals(GridTile.State.LOCKED, states.get(3)); // whip (70 + kc unmet)
	}

	@Test
	public void obtainedSuccessorsImplyTheirPredecessors()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		com.ironhub.data.GearProgressionPack progression =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);

		// owning only an Ava's assembler (22109) proves the whole chain
		StateFixture.equipment(state, Map.of(22109, 1));
		java.util.Set<String> obtained = GearProgressionModule.obtainedNames(progression, state);
		assertTrue(obtained.contains("Ava's assembler"));
		assertTrue(obtained.contains("Ava's accumulator")); // consumed making it
		assertTrue(obtained.contains("Ava's attractor"));   // transitively
		assertTrue(!obtained.contains("Book of the dead"));

		// Book of the dead (25818) upgraded from Kharedst's memoirs
		StateFixture.bank(state, Map.of(25818, 1));
		obtained = GearProgressionModule.obtainedNames(progression, state);
		assertTrue(obtained.contains("Kharedst's memoirs"));
	}

	@Test
	public void everyImpliedNameResolvesToAnEntry()
	{
		com.ironhub.data.GearProgressionPack progression =
			new DataPack(new Gson()).load("gear-progression", com.ironhub.data.GearProgressionPack.class);
		java.util.Set<String> names = new java.util.HashSet<>();
		progression.getPhases().forEach(p -> p.getGroups().forEach(g ->
			g.getItems().forEach(i -> names.add(i.getName()))));
		progression.getPhases().forEach(p -> p.getGroups().forEach(g ->
			g.getItems().forEach(i ->
			{
				if (i.getImplies() != null)
				{
					// a typo'd implies target would silently never fire
					assertTrue("unknown implies target on " + i.getName() + ": " + i.getImplies(),
						names.containsAll(i.getImplies()));
				}
			})));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.stat(state, Skill.ATTACK, 40, 0);
		GearProgressionModule module = new GearProgressionModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null, null);
		module.startUp();
		JPanel tab = (JPanel) module.buildTab();
		java.awt.image.BufferedImage image = SwingRender.render(tab);
		assertTrue(image.getHeight() > 100);
		java.io.File out = new java.io.File("build/reports/gear-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
