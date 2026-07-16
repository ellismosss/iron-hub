package com.ironhub.modules.dailies;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Varbits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * The OSRS-skin dailies tab (the phase-4 migration pilot) over the same brain
 * and the same seeded account the classic tab's render uses — the two PNGs
 * are the comparison Luke judges the migration by.
 */
public class DailiesNewTabTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private DailiesModule brain(AccountState state)
	{
		DailiesModule module = new DailiesModule(state, null, new IronHubConfig()
		{
		}, new DataPack(new Gson()), null, null, null, null, null, null, null, null, null, null, null);
		module.startUp();
		return module;
	}

	private AccountState midGameIron()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.profile(state, 9L);
		StateFixture.varbit(state, Varbits.DIARY_VARROCK_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_KANDARIN_EASY, 1);
		StateFixture.varbit(state, Varbits.DIARY_MORYTANIA_MEDIUM, 1);
		StateFixture.varbit(state, Varbits.DIARY_KOUREND_MEDIUM, 1);
		StateFixture.quest(state, Quest.TEARS_OF_GUTHIX, QuestState.FINISHED);
		StateFixture.quest(state, Quest.THRONE_OF_MISCELLANIA, QuestState.FINISHED);
		return state;
	}

	@Test
	public void checklistRendersInBothThemes() throws Exception
	{
		AccountState state = midGameIron();
		DailiesModule brain = brain(state);
		// one daily already claimed, like the classic render
		StateFixture.varbit(state, brain.pack().daily("flax_bowstring").detection.varbit, 1);

		for (OsrsTheme theme : OsrsTheme.values())
		{
			DailiesNewTab tab = new DailiesNewTab(brain, theme);
			BufferedImage image = SwingRender.render(tab);
			assertTrue(image.getHeight() > 200);
			write(image, "dailies-new-" + theme.name().toLowerCase() + ".png");
			tab.dispose();
		}
		brain.shutDown();
	}

	@Test
	public void activeRunRendersAndFollowsTheBrain() throws Exception
	{
		AccountState state = midGameIron();
		DailiesModule brain = brain(state);

		DailiesNewTab tab = new DailiesNewTab(brain, OsrsTheme.MYSTIC);
		brain.startRun();
		// run rebuilds land on the EDT — let them drain before rendering
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
		assertTrue("a run should have stops", !brain.stops().isEmpty());

		BufferedImage image = SwingRender.render(tab);
		assertTrue(image.getHeight() > 100);
		write(image, "dailies-new-run.png");

		// the new tab hears the brain's run events through the listener seam
		brain.endRun(false);
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
		tab.dispose();
		brain.shutDown();
	}

	private static void write(BufferedImage image, String name) throws Exception
	{
		File out = new File("build/reports/" + name);
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
	}
}
