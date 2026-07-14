package com.ironhub.modules.farming;

import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the generated HerbPatchDecoder to RuneLite core's
 * PatchImplementation.HERB by reflectively sweeping every varbit value.
 * If core's mapping changes in a client update, this fails and the
 * decoder must be regenerated from source.
 */
public class HerbDecoderParityTest
{
	@Test
	public void decoderMatchesCoreForAllValues() throws Exception
	{
		Class<?> impl = Class.forName("net.runelite.client.plugins.timetracking.farming.PatchImplementation");
		Object herb = impl.getField("HERB").get(null);
		Method forVarbitValue = impl.getDeclaredMethod("forVarbitValue", int.class);
		forVarbitValue.setAccessible(true);

		for (int value = 0; value < 256; value++)
		{
			Object core = forVarbitValue.invoke(herb, value);
			HerbPatchDecoder.Seen ours = HerbPatchDecoder.decode(value);

			if (core == null)
			{
				assertEquals("value " + value, HerbPatchDecoder.State.UNKNOWN, ours.state);
				continue;
			}
			Method getProduce = core.getClass().getMethod("getProduce");
			getProduce.setAccessible(true);
			Method getCropState = core.getClass().getMethod("getCropState");
			getCropState.setAccessible(true);
			Method getStage = core.getClass().getMethod("getStage");
			getStage.setAccessible(true);

			String produce = getProduce.invoke(core).toString();
			String crop = getCropState.invoke(core).toString();
			int stage = (int) getStage.invoke(core);

			String expectedState = produce.equals("WEEDS") ? "EMPTY" : crop;
			assertEquals("state for value " + value, expectedState, ours.state.name());
			assertEquals("stage for value " + value, stage, ours.stage);
			if (!produce.equals("WEEDS"))
			{
				assertEquals("herb for value " + value,
					produce.replace('_', ' ').toLowerCase(Locale.ROOT),
					ours.herb.toLowerCase(Locale.ROOT));
			}
		}
	}

	@Test
	public void allHerbsGrowAtTwentyMinutesPerStage() throws Exception
	{
		// prediction assumes a uniform herb tick rate; verify against core
		Class<?> produceClass = Class.forName("net.runelite.client.plugins.timetracking.farming.Produce");
		Method getTickrate = produceClass.getMethod("getTickrate");
		for (int value = 4; value < 256; value++)
		{
			HerbPatchDecoder.Seen seen = HerbPatchDecoder.decode(value);
			if (seen.state == HerbPatchDecoder.State.GROWING)
			{
				Object produce = Enum.valueOf((Class<Enum>) produceClass.asSubclass(Enum.class),
					seen.herb.toUpperCase(Locale.ROOT).replace(' ', '_'));
				assertEquals("tickrate for " + seen.herb,
					HerbPatchDecoder.STAGE_MINUTES, getTickrate.invoke(produce));
			}
		}
	}
}
