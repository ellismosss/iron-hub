package com.ironhub.modules.qol;

import com.google.gson.Gson;
import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.QolPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.StateFixture;
import com.ironhub.ui.SwingRender;
import com.ironhub.ui.components.Status;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class QolModuleTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private final QolPack pack = new DataPack(new Gson()).load("qol", QolPack.class);

	private QolPack.Unlock byId(String id)
	{
		return pack.getUnlocks().stream().filter(u -> u.getId().equals(id)).findFirst().orElseThrow();
	}

	@Test
	public void ownedAvailableLocked()
	{
		AccountState state = StateFixture.state(temp.getRoot());

		// locked: 60/60 attack/defence not met — blocking line names the leaf
		assertEquals(Status.LOCKED, QolModule.status(state, byId("dragon_defender")));
		assertEquals("60 Attack", QolModule.blockingLine(state, byId("dragon_defender")));

		// available: requirements met, item not owned
		StateFixture.stat(state, Skill.ATTACK, 60, 0);
		StateFixture.stat(state, Skill.DEFENCE, 60, 0);
		assertEquals(Status.AVAILABLE, QolModule.status(state, byId("dragon_defender")));

		// owned: item in bank wins regardless of requirements
		StateFixture.bank(state, Map.of(12954, 1));
		assertEquals(Status.OWNED, QolModule.status(state, byId("dragon_defender")));

		// quest requirement drives availability
		assertEquals(Status.LOCKED, QolModule.status(state, byId("ava_assembler")));
		StateFixture.quest(state, Quest.DRAGON_SLAYER_II, QuestState.FINISHED);
		assertEquals(Status.AVAILABLE, QolModule.status(state, byId("ava_assembler")));

		// manual text requirements never auto-complete
		assertEquals(Status.LOCKED, QolModule.status(state, byId("herb_sack")));
		assertEquals("250 Tithe Farm points", QolModule.blockingLine(state, byId("herb_sack")));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.bank(state, Map.of(12791, 1)); // rune pouch owned
		StateFixture.stat(state, Skill.ATTACK, 70, 0);
		StateFixture.stat(state, Skill.DEFENCE, 70, 0);

		QolModule module = new QolModule(state, new IronHubConfig()
		{
		}, new DataPack(new Gson()));
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 200);
		java.io.File out = new java.io.File("build/reports/qol-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
