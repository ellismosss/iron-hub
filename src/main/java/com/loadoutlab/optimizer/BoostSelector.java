package com.loadoutlab.optimizer;

import com.loadoutlab.engine.BoostProfile;
import com.loadoutlab.engine.CombatStyle;
import com.loadoutlab.engine.OwnedItems;

/**
 * The best stat boost assumed per style. Tradeable potions are ALWAYS
 * assumed (cheap consumables - like prayers, you bring them); only the
 * hearts (untradeable drops) gate on ownership. Raid-scoped boosts
 * (overloads, smelling salts) are deliberately not auto-assumed.
 */
public final class BoostSelector
{
	// Iron Hub: global toggle - assume stat potions/hearts
	public static volatile boolean POTIONS_ASSUMED = false;
	public static volatile boolean HEART_ASSUMED = false;

	private static final int SATURATED_HEART = 27641;
	private static final int IMBUED_HEART = 20724;

	private BoostSelector()
	{
	}

	/** The best boost in the GAME per style - the BiS ceiling assumption. */
	public static BoostProfile ceilingFor(CombatStyle style)
	{
		if (!POTIONS_ASSUMED)
		{
			return BoostProfile.NONE;
		}
		switch (style)
		{
			case MELEE: return BoostProfile.SUPER_COMBAT;
			case RANGED: return BoostProfile.RANGING;
			case MAGIC: return BoostProfile.SATURATED_HEART;
			default: return BoostProfile.NONE;
		}
	}

	public static BoostProfile bestFor(CombatStyle style, OwnedItems owned)
	{
		if (!POTIONS_ASSUMED)
		{
			return BoostProfile.NONE;
		}
		switch (style)
		{
			case MELEE:
				return BoostProfile.SUPER_COMBAT;
			case RANGED:
				return BoostProfile.RANGING;
			case MAGIC:
				if (HEART_ASSUMED && owned.owns(SATURATED_HEART))
				{
					return BoostProfile.SATURATED_HEART;
				}
				if (HEART_ASSUMED && owned.owns(IMBUED_HEART))
				{
					return BoostProfile.IMBUED_HEART;
				}
				return BoostProfile.MAGIC;
			default:
				return BoostProfile.NONE;
		}
	}
}
