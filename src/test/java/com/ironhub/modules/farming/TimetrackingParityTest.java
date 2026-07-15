package com.ironhub.modules.farming;

import com.ironhub.modules.farming.rl.FarmingPatch;
import com.ironhub.modules.farming.rl.FarmingRegion;
import com.ironhub.modules.farming.rl.FarmingWorld;
import com.ironhub.modules.farming.rl.PatchImplementation;
import com.ironhub.modules.farming.rl.PatchState;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Pins the generated com.ironhub.modules.farming.rl package to RuneLite
 * core's timetracking plugin by reflectively sweeping the classpath
 * client. If a client update changes patch decoding, the farming world
 * layout, or the bird house varps, this fails and the package must be
 * regenerated: {@code python3 tools/gen_timetracking.py <new tag>}.
 */
public class TimetrackingParityTest
{
	private static final String CORE = "net.runelite.client.plugins.timetracking.";

	@Test
	public void everyPatchImplementationDecodesLikeCore() throws Exception
	{
		Class<?> coreImpl = Class.forName(CORE + "farming.PatchImplementation");
		Method forVarbitValue = coreImpl.getDeclaredMethod("forVarbitValue", int.class);
		forVarbitValue.setAccessible(true);

		for (PatchImplementation ours : PatchImplementation.values())
		{
			Object core = coreImpl.getField(ours.name()).get(null);

			for (int value = 0; value < 2048; value++)
			{
				Object coreState = forVarbitValue.invoke(core, value);
				PatchState ourState = ours.forVarbitValue(value);

				if (coreState == null)
				{
					assertNull(ours.name() + " value " + value, ourState);
					continue;
				}
				assertNotNull(ours.name() + " value " + value, ourState);
				assertEquals(ours.name() + " produce for " + value,
					call(coreState, "getProduce").toString(), ourState.getProduce().name());
				assertEquals(ours.name() + " cropState for " + value,
					call(coreState, "getCropState").toString(), ourState.getCropState().name());
				assertEquals(ours.name() + " stage for " + value,
					call(coreState, "getStage"), ourState.getStage());
				assertEquals(ours.name() + " stages for " + value,
					call(coreState, "getStages"), ourState.getStages());
				assertEquals(ours.name() + " tickRate for " + value,
					call(coreState, "getTickRate"), ourState.getTickRate());
			}
		}
	}

	@Test
	public void farmingWorldLayoutMatchesCore() throws Exception
	{
		// every patch as "regionId/regionName:varbit:implementation" —
		// grouping tabs differ by design (ours are finer), the patch set must not
		Set<String> ourLayout = new HashSet<>();
		for (Set<FarmingPatch> patches : new FarmingWorld().getTabs().values())
		{
			for (FarmingPatch patch : patches)
			{
				ourLayout.add(patchKey(patch.getRegion().getRegionID(),
					patch.getRegion().getName(), patch.getVarbit(),
					patch.getImplementation().name()));
			}
		}

		Class<?> coreWorldClass = Class.forName(CORE + "farming.FarmingWorld");
		java.lang.reflect.Constructor<?> ctor = coreWorldClass.getDeclaredConstructor();
		ctor.setAccessible(true);
		Object coreWorld = ctor.newInstance();
		Method getTabs = coreWorldClass.getDeclaredMethod("getTabs");
		getTabs.setAccessible(true);

		Set<String> coreLayout = new HashSet<>();
		for (Object patches : ((Map<?, ?>) getTabs.invoke(coreWorld)).values())
		{
			for (Object patch : (Set<?>) patches)
			{
				Object region = call(patch, "getRegion");
				coreLayout.add(patchKey((int) call(region, "getRegionID"),
					(String) call(region, "getName"), (int) call(patch, "getVarbit"),
					call(patch, "getImplementation").toString()));
			}
		}

		assertEquals(coreLayout, ourLayout);
	}

	@Test
	public void birdHouseSpacesMatchCore() throws Exception
	{
		Class<?> coreSpace = Class.forName(CORE + "hunter.BirdHouseSpace");
		Method values = coreSpace.getMethod("values");
		values.setAccessible(true);
		Set<String> core = new HashSet<>();
		for (Object space : (Object[]) values.invoke(null))
		{
			core.add(space.toString() + ":" + call(space, "getVarp"));
		}
		Set<String> ours = new HashSet<>();
		for (com.ironhub.modules.farming.rl.hunter.BirdHouseSpace space
			: com.ironhub.modules.farming.rl.hunter.BirdHouseSpace.values())
		{
			ours.add(space.name() + ":" + space.getVarp());
		}
		assertEquals(core, ours);
	}

	private static String patchKey(int regionId, String regionName, int varbit, String impl)
	{
		return regionId + "/" + regionName + ":" + varbit + ":" + impl;
	}

	/** Invoke a no-arg method wherever it's declared — core regions are
	 *  anonymous FarmingRegion subclasses, so walk up the hierarchy. */
	private static Object call(Object target, String method) throws Exception
	{
		for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
		{
			try
			{
				Method m = c.getDeclaredMethod(method);
				m.setAccessible(true);
				return m.invoke(target);
			}
			catch (NoSuchMethodException e)
			{
				// keep walking
			}
		}
		throw new NoSuchMethodException(target.getClass() + "#" + method);
	}
}
