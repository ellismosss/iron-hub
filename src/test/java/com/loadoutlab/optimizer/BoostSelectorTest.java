package com.loadoutlab.optimizer;

import com.loadoutlab.engine.BoostProfile;
import com.loadoutlab.engine.CombatStyle;
import com.loadoutlab.engine.OwnedItems;
import com.loadoutlab.engine.PlayerLevels;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class BoostSelectorTest
{

	@org.junit.BeforeClass
	public static void ironHubAssumeBoosts()
	{
		// Iron Hub: production defaults the boost toggles OFF; these
		// vendored suites were written against upstream's always-on model.
		com.loadoutlab.engine.PrayerBonuses.MELEE_PRAYER = true;
		com.loadoutlab.engine.PrayerBonuses.RANGED_PRAYER = true;
		com.loadoutlab.engine.PrayerBonuses.MAGIC_PRAYER = true;
		com.loadoutlab.optimizer.BoostSelector.POTIONS_ASSUMED = true;
		com.loadoutlab.optimizer.BoostSelector.HEART_ASSUMED = true;
	}
	@Test
	public void picksTheBestOwnedBoostPerStyle()
	{
		// Tradeable potions are always assumed, ownership or not.
		OwnedItems none = OwnedItems.EMPTY;
		Assert.assertEquals(BoostProfile.SUPER_COMBAT, BoostSelector.bestFor(CombatStyle.MELEE, none));
		Assert.assertEquals(BoostProfile.RANGING, BoostSelector.bestFor(CombatStyle.RANGED, none));
		Assert.assertEquals(BoostProfile.MAGIC, BoostSelector.bestFor(CombatStyle.MAGIC, none));

		// The hearts (untradeable) gate on ownership; saturated outranks imbued.
		OwnedItems hearts = new OwnedItems(Map.of(20724, 1, 27641, 1), true);
		Assert.assertEquals(BoostProfile.SATURATED_HEART, BoostSelector.bestFor(CombatStyle.MAGIC, hearts));
		Assert.assertEquals(BoostProfile.IMBUED_HEART,
			BoostSelector.bestFor(CombatStyle.MAGIC, new OwnedItems(Map.of(20724, 1), true)));

		// Boost application: 99 melee stats -> 118 with super combat.
		PlayerLevels boosted = PlayerLevels.MAXED.boosted(BoostProfile.SUPER_COMBAT, PlayerLevels.MAXED);
		Assert.assertEquals(118, boosted.getStrength());
		// Never below live: element-wise max keeps a higher live boost.
		PlayerLevels live = new PlayerLevels(120, 120, 99, 99, 99, 99, 99);
		Assert.assertEquals(120, boosted.max(live).getStrength());
	}
}
