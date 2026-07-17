package com.ironhub.modules.supplies;

import com.ironhub.IronHubConfig;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RunwayTest
{
	private static final int SHARK = 385;
	private static final int LOBSTER = 379;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	/** Two consumption checkpoints an hour apart -> a usable rate. */
	private AccountState stateWithSharkRate() throws Exception
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.inventory(state, Map.of(SHARK, 28, LOBSTER, 26));
		StateFixture.checkpointSupplies(state);
		StateFixture.inventory(state, Map.of(SHARK, 18, LOBSTER, 25)); // ate 10 sharks, 1 lobster
		state.ingestLoot("Zulrah", Map.of(1, 1));
		// backdate the first events by an hour so the span is meaningful
		java.lang.reflect.Field f = AccountState.class.getDeclaredField("consumptionLog");
		f.setAccessible(true);
		java.util.List<?> log = (java.util.List<?>) f.get(state);
		for (Object event : log)
		{
			java.lang.reflect.Field t = event.getClass().getDeclaredField("timeMs");
			t.setAccessible(true);
			t.setLong(event, t.getLong(event) - 3_600_000);
		}

		StateFixture.inventory(state, Map.of(SHARK, 8, LOBSTER, 24)); // ate 10 more, 1 more
		state.ingestLoot("Zulrah", Map.of(1, 1));
		StateFixture.bank(state, Map.of(SHARK, 100, LOBSTER, 5000)); // stock; lobsters plentiful
		return state;
	}

	@Test
	public void ratesAndRunwayFromTheLog() throws Exception
	{
		AccountState state = stateWithSharkRate();
		Map<Integer, SuppliesRunwayModule.Runway> runways = SuppliesRunwayModule.compute(state);
		SuppliesRunwayModule.Runway shark = runways.get(SHARK);
		assertNotNull(shark);
		assertEquals(20.0, shark.perHour, 0.5);      // 20 sharks over ~1 h
		assertEquals(108, shark.stock);              // 100 banked + 8 carried
		assertEquals(5.4, shark.hoursLeft(), 0.2);   // inside the 6 h warning
	}

	@Test
	public void singleEventsAreNotRated()
	{
		AccountState state = StateFixture.state(temp.getRoot());
		StateFixture.inventory(state, Map.of(SHARK, 5));
		StateFixture.checkpointSupplies(state);
		StateFixture.inventory(state, Map.of(SHARK, 3));
		state.ingestLoot("Zulrah", Map.of(1, 1));
		assertTrue(SuppliesRunwayModule.compute(state).isEmpty());
	}

	@Test
	public void formatting()
	{
		assertEquals("14 h", SuppliesRunwayModule.formatHours(14.3));
		assertEquals("45 min", SuppliesRunwayModule.formatHours(0.75));
		assertEquals("-", SuppliesRunwayModule.formatHours(Double.POSITIVE_INFINITY));
	}

	@Test
	public void tabRendersHeadless() throws Exception
	{
		AccountState state = stateWithSharkRate();
		StateFixture.itemNames(state, Map.of(SHARK, "Shark", LOBSTER, "Lobster"));
		SuppliesRunwayModule module = new SuppliesRunwayModule(state, null, new IronHubConfig()
		{
		});
		module.startUp();
		JComponent tab = module.buildTab();
		assertNotNull(tab);
		java.awt.image.BufferedImage image = SwingRender.render((JPanel) tab);
		assertTrue(image.getHeight() > 50);
		java.io.File out = new java.io.File("build/reports/runway-tab.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(image, "png", out);
		module.shutDown();
	}
}
