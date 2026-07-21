package com.ironhub.data;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The combat-style names pack (cs2-dump provenance): anchor rows straight
 * from the game's own script text, and the live-cache compatibility gate
 * that keeps a stale weapon-type id from ever naming styles wrongly.
 */
public class WeaponStylesPackTest
{
	private final WeaponStylesPack pack =
		new DataPack(new Gson()).load("weapon-styles", WeaponStylesPack.class);

	@Test
	public void anchorsMatchTheGamesOwnScriptText()
	{
		WeaponStylesPack.Option spearLunge = pack.option(15, 0);
		assertEquals("Lunge", spearLunge.button);
		assertEquals("Stab", spearLunge.type);
		assertEquals("Controlled", spearLunge.style);

		WeaponStylesPack.Option axeHack = pack.option(1, 1);
		assertEquals("Hack", axeHack.button);
		assertEquals("Slash", axeHack.type);
		assertEquals("Aggressive", axeHack.style);

		WeaponStylesPack.Option bowRapid = pack.option(3, 1);
		assertEquals("Rapid", bowRapid.button);
		assertEquals("Ranged", bowRapid.type);

		WeaponStylesPack.Option punch = pack.option(0, 0);
		assertEquals("Punch", punch.button);
		assertEquals("Crush", punch.type);

		// undefined slots (staff has no index 2) and unknown types stay
		// null — the display's honest "Attack style N" fallback
		assertNull(pack.option(18, 2));
		assertNull(pack.option(99, 0));
	}

	/** Melee kinds match 1:1; ranged vocab differs (Rapid/Accurate read
	 *  "Ranging" in the cache); any mismatch fails the whole type. */
	@Test
	public void cacheSignatureGate()
	{
		// spear: Controlled ×3 + Defensive — the real cache signature
		assertTrue(pack.matchesKinds(15,
			new String[]{"Controlled", "Controlled", "Controlled", "Defensive"}));
		// bow: Accurate + Rapid both read "Ranging", Longrange is itself
		assertTrue(pack.matchesKinds(3,
			new String[]{"Ranging", "Ranging", "Other", "Longrange"}));
		// a reused id whose kinds moved on: refused
		assertFalse(pack.matchesKinds(15,
			new String[]{"Accurate", "Aggressive", "Aggressive", "Defensive"}));
		// no cache signature at all: refused (never trust blind)
		assertFalse(pack.matchesKinds(15, null));
		assertFalse(pack.matchesKinds(99, new String[]{"Accurate"}));
	}

	@Test
	public void everyOptionCarriesAButtonAndKnownVocabulary()
	{
		java.util.Set<String> styles = new java.util.HashSet<>(java.util.Arrays.asList(
			"Accurate", "Aggressive", "Controlled", "Defensive", "Rapid", "Longrange"));
		java.util.Set<String> types = new java.util.HashSet<>(java.util.Arrays.asList(
			"Stab", "Slash", "Crush", "Ranged", "Magic"));
		int options = 0;
		for (WeaponStylesPack.TypeEntry entry : pack.types.values())
		{
			for (WeaponStylesPack.Option option : entry.options)
			{
				options++;
				assertFalse(option.button.isEmpty());
				assertTrue(String.valueOf(option.style), option.style == null || styles.contains(option.style));
				assertTrue(String.valueOf(option.type), option.type == null || types.contains(option.type));
			}
		}
		assertTrue("suspiciously few options: " + options, options >= 90);
	}
}
