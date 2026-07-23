package com.ironhub.modules.moneymaking;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.MoneyMakingPack;
import com.ironhub.data.MoneyMakingPack.Method;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Money making (Bank section): the OSRS wiki Money making guide filtered to
 * what the account can actually do. The pack is the wiki's methods; this class
 * is the availability brain (category- and account-type-aware, per Luke) the
 * tab renders.
 *
 * <p>Availability rules:
 * <ul>
 *   <li>hard {@code reqs} (skill/quest/qp gates) must be met — always;</li>
 *   <li>Combat: also within a ballpark of the recommended combat levels;</li>
 *   <li>Processing on an IRONMAN: also owns the input items (a normal account
 *       just buys them, so hard reqs suffice);</li>
 *   <li>Skilling / Collecting / Recurring: hard reqs suffice.</li>
 * </ul>
 * Unavailable methods carry a {@link #distance} so the tab can rank the ones
 * closest to becoming available.
 */
@Slf4j
@Singleton
public class MoneyMakingModule implements IronHubModule
{
	/** How far below a recommended COMBAT level still counts as "ballpark". */
	static final int COMBAT_TOLERANCE = 8;

	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final net.runelite.client.game.ItemManager itemManager; // null headless
	private final net.runelite.client.game.SkillIconManager skillIcons; // null headless

	private MoneyMakingPack pack;
	private MoneyMakingTab tab;

	@Inject
	public MoneyMakingModule(AccountState state, IronHubConfig config, DataPack dataPack,
		net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.game.SkillIconManager skillIcons)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
		this.itemManager = itemManager;
		this.skillIcons = skillIcons;
	}

	@Override
	public String name()
	{
		return "Money making";
	}

	@Override
	public boolean enabled()
	{
		return config.moneyMaking();
	}

	@Override
	public void startUp()
	{
	}

	@Override
	public void shutDown()
	{
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new MoneyMakingTab(this, state, pack(), itemManager, skillIcons, config.osrsTheme());
		}
		return tab;
	}

	@Override
	public void onThemeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}

	/** Where-from lines for input rows (design/KB-RUNTIME.md); null headless
	 *  when no DataPack was given. */
	com.ironhub.data.ItemSourcesPack itemSources()
	{
		return dataPack == null ? null
			: dataPack.load("item-sources", com.ironhub.data.ItemSourcesPack.class);
	}

	MoneyMakingPack pack()
	{
		if (pack == null)
		{
			pack = dataPack.load("money-making", MoneyMakingPack.class);
		}
		return pack;
	}

	// ── availability brain (static, testable) ─────────────────────────────

	/** Whether the account can do this method now. */
	public static boolean available(AccountState state, Method m)
	{
		if (!hardReqsMet(state, m))
		{
			return false;
		}
		if ("Combat".equals(m.category) && !combatBallpark(state, m))
		{
			return false;
		}
		// an ironman must OWN the inputs of a processing method (can't buy them)
		return !("Processing".equals(m.category) && state.isIronman()) || ownsInputs(state, m);
	}

	private static boolean hardReqsMet(AccountState state, Method m)
	{
		for (String req : m.reqs)
		{
			if (!Requirements.parse(req).isMet(state))
			{
				return false;
			}
		}
		return true;
	}

	/** Combat readiness: every recommended combat level within a tolerance. */
	private static boolean combatBallpark(AccountState state, Method m)
	{
		for (String rec : m.recommends)
		{
			int[] sl = skillLevel(rec);
			if (sl != null && isCombatSkill(sl[0]) && state.getRealLevel(Skill.values()[sl[0]]) < sl[1] - COMBAT_TOLERANCE)
			{
				return false;
			}
			if (rec.startsWith("combat:"))
			{
				int want = Integer.parseInt(rec.substring("combat:".length()));
				if (net.runelite.api.Experience.getCombatLevel(
					state.getRealLevel(Skill.ATTACK), state.getRealLevel(Skill.STRENGTH),
					state.getRealLevel(Skill.DEFENCE), state.getRealLevel(Skill.HITPOINTS),
					state.getRealLevel(Skill.MAGIC), state.getRealLevel(Skill.RANGED),
					state.getRealLevel(Skill.PRAYER)) < want - COMBAT_TOLERANCE)
				{
					return false;
				}
			}
		}
		return true;
	}

	/** An ironman owns (bank + carried) every resolvable input item. */
	private static boolean ownsInputs(AccountState state, Method m)
	{
		if (m.inputs == null)
		{
			return true;
		}
		for (MoneyMakingPack.Input in : m.inputs)
		{
			if (in.itemId > 0 && state.canonicalStock(in.itemId) <= 0)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * How far the account is from being able to do this method (0 = available):
	 * missing skill levels count their level gap, missing quests/qp a flat cost.
	 * Lower = closer. Used to rank "closest to becoming available".
	 */
	public static double distance(AccountState state, Method m)
	{
		if (available(state, m))
		{
			return 0;
		}
		double d = 0;
		for (String req : m.reqs)
		{
			if (!Requirements.parse(req).isMet(state))
			{
				d += reqDistance(state, req);
			}
		}
		// combat category: unmet ballpark adds the biggest combat-level gap
		if ("Combat".equals(m.category))
		{
			for (String rec : m.recommends)
			{
				int[] sl = skillLevel(rec);
				if (sl != null && isCombatSkill(sl[0]))
				{
					d += Math.max(0, sl[1] - COMBAT_TOLERANCE - state.getRealLevel(Skill.values()[sl[0]])) * 0.5;
				}
			}
		}
		// an ironman short on a processing input is one gather away
		if ("Processing".equals(m.category) && state.isIronman() && !ownsInputs(state, m))
		{
			d += 5;
		}
		return d;
	}

	private static double reqDistance(AccountState state, String req)
	{
		// the graph's own distance-to-met (2026-07-20 intelligence arc) —
		// this used to be one of three private reimplementations of
		// "how close am I"; same level/qp semantics, quests now grade
		// in-progress closer than unstarted
		return Requirements.parse(req).gap(state);
	}

	/** The blocking hard requirements, as short display labels. */
	public static List<String> missing(AccountState state, Method m)
	{
		List<String> out = new ArrayList<>();
		for (String req : m.reqs)
		{
			com.ironhub.requirements.Requirement r = Requirements.parse(req);
			if (!r.isMet(state))
			{
				out.add(r.describe());
			}
		}
		if ("Processing".equals(m.category) && state.isIronman() && !ownsInputs(state, m))
		{
			out.add("the input items");
		}
		return out;
	}

	/** {skillOrdinal, level} from a "skill:Name:Lvl" leaf, or null. */
	private static int[] skillLevel(String leaf)
	{
		if (!leaf.startsWith("skill:"))
		{
			return null;
		}
		String[] p = leaf.split(":");
		if (p.length < 3)
		{
			return null;
		}
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(p[1]))
			{
				try
				{
					return new int[]{skill.ordinal(), Integer.parseInt(p[2])};
				}
				catch (NumberFormatException e)
				{
					return null;
				}
			}
		}
		return null;
	}

	private static boolean isCombatSkill(int ordinal)
	{
		Skill s = Skill.values()[ordinal];
		return s == Skill.ATTACK || s == Skill.STRENGTH || s == Skill.DEFENCE || s == Skill.RANGED
			|| s == Skill.MAGIC || s == Skill.HITPOINTS || s == Skill.PRAYER || s == Skill.SLAYER;
	}
}
