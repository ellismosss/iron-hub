package com.ironhub.modules.moneymaking;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.MoneyMakingPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import java.io.File;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MoneyMakingTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private AccountState seeded()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 1L);
		StateFixture.stat(state, Skill.RUNECRAFT, 55, net.runelite.api.Experience.getXpForLevel(55));
		StateFixture.stat(state, Skill.CRAFTING, 60, net.runelite.api.Experience.getXpForLevel(60));
		StateFixture.stat(state, Skill.RANGED, 80, net.runelite.api.Experience.getXpForLevel(80));
		return state;
	}

	private MoneyMakingModule module(AccountState state)
	{
		return new MoneyMakingModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null, new SkillIconManager());
	}

	@Test
	public void rendersEveryThemeWithFilters() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			AccountState state = seeded();
			MoneyMakingModule module = module(state);
			MoneyMakingTab[] holder = new MoneyMakingTab[1];
			javax.swing.SwingUtilities.invokeAndWait(() ->
				holder[0] = new MoneyMakingTab(module, state, module.pack(),
					null, new SkillIconManager(), theme));
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			BufferedImage all = SwingRender.render(holder[0]);
			assertEquals(UiTokens.PANEL_WIDTH, all.getWidth());
			assertTrue("tall", all.getHeight() > 300);
			write(all, "money-making-all-" + theme.name().toLowerCase());

			// the "Can do" filter + the "Can't" (closest-first) view
			javax.swing.SwingUtilities.invokeAndWait(() -> holder[0].filter(2, 1)); // Combat · Can do
			javax.swing.SwingUtilities.invokeAndWait(() -> { });
			write(SwingRender.render(holder[0]), "money-making-available-" + theme.name().toLowerCase());
			holder[0].dispose();
		}
	}

	@Test
	public void availabilityGatesOnHardReqs()
	{
		AccountState state = seeded();
		MoneyMakingModule module = module(state);
		MoneyMakingPack.Method nats = byId(module, "crafting-nature-runes-through-the-abyss");
		// 55 Runecraft ≥ 44, but the access quest isn't done → unavailable, and
		// its distance is a finite, positive gap (a quest away)
		assertTrue("blocked by the quest", !MoneyMakingModule.available(state, nats));
		assertTrue("has a finite distance", MoneyMakingModule.distance(state, nats) > 0);
		assertTrue("the quest is the missing req",
			MoneyMakingModule.missing(state, nats).stream().anyMatch(s -> s.contains("Enter the Abyss")));
	}

	private MoneyMakingPack.Method byId(MoneyMakingModule module, String id)
	{
		return module.pack().methods.stream().filter(m -> id.equals(m.id)).findFirst().orElseThrow();
	}

	private static void write(BufferedImage img, String name) throws Exception
	{
		File out = new File("build/reports/" + name + ".png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(img, "png", out);
	}
}
