package com.ironhub.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Typed model of {@code data/item-sources.json} — the universal per-item
 * obtainment + equip-requirement projection from the knowledge base
 * (design/KB-RUNTIME.md). One entry per obtainable item: the best source
 * per how (drop/shop/make/reward), rates and prices as the wiki's own
 * strings, and equip reqs in requirement-graph form with their origin.
 *
 * <p>All "where does this come from" copy across the plugin goes through
 * {@link #sourceLine(int)} — no surface hand-rolls obtainment text. An
 * unknown item returns null, never an invented source.
 */
@Data
public class ItemSourcesPack
{
	private int version;
	private String provenance;
	private List<Entry> items;

	@Data
	public static class Entry
	{
		private String name;
		private List<Integer> ids;
		private List<Source> sources;
		private List<String> reqs;
		/** "audited" (hand-audited gear chains) or "extracted" (validated
		 *  prose extraction) — extracted reqs are phrased "the wiki states". */
		private String reqsOrigin;
	}

	@Data
	public static class Source
	{
		private String how;
		private String from;
		private String rate;
		private String price;
		private String skill;
		/** The recipe's actual materials ("Salve amulet + 800,000 x
		 *  Nightmare Zone points") — pack v2, never "(see recipe)". */
		private String detail;
		/** What a shop price is PAID IN, machine-readable (v2) — so "buy it
		 *  for 500 points" becomes a tracked requirement, not a dead end. */
		private Currency currency;
		/** The recipe's materials as DATA (v2) — the UI renders one sprite
		 *  row each instead of one clumped sentence, and the planner turns
		 *  them into gather steps. */
		private List<Material> materials;

		/** "Drop: Abyssal demon 1/512" / "Buy: Slayer Rewards (750 points)" /
		 *  "Make: Amethyst (Crafting 85)" / "Open: Dragon impling jar 1/19". */
		public String label()
		{
			switch (how == null ? "" : how)
			{
				case "drop":
					return "Drop: " + from + (rate != null ? " " + rate : "");
				case "open":
					return "Open: " + from + (rate != null ? " " + rate : "");
				case "shop":
					return "Buy: " + from + (price != null ? " (" + price + ")" : "");
				case "make":
					return detail != null
						? "Make: " + detail + (skill != null ? " (" + skill + ")" : "")
						: skill != null ? "Make: " + skill : "Make (see recipe)";
				case "reward":
					return "Reward: " + from + (rate != null ? " " + rate : "");
				case "spell":
					return "Spell: " + from;
				default:
					return "From: " + (from != null ? from : "")
						+ (rate != null ? " " + rate : "");
			}
		}

		/** The requirement-graph leaf for this source's price, or null when
		 *  the currency has no readable balance (honest manual step). */
		public String currencyReq()
		{
			if (currency == null)
			{
				return null;
			}
			if (currency.itemId > 0)
			{
				return "item:" + currency.itemId + ":" + currency.qty + ":" + currency.name;
			}
			return currency.varbit > 0
				? "varbit:" + currency.varbit + ":" + currency.qty + ":" + currency.name : null;
		}
	}

	@Data
	public static class Currency
	{
		private String name;
		private int qty;
		private int itemId;  // 0 = not an item
		private int varbit;  // 0 = no readable balance
	}

	@Data
	public static class Material
	{
		private String name;
		private int qty;
		private int itemId;  // 0 = unresolved (renders without a sprite)

		/** The requirement-graph leaf for gathering this material. */
		public String req()
		{
			return itemId > 0 ? "item:" + itemId + ":" + qty + ":" + name : null;
		}
	}

	/** Lazy id index; volatile because pack instances are shared across
	 *  threads (2026-07-20 audit rule). */
	private transient volatile Map<Integer, Entry> byId;

	public Entry entry(int itemId)
	{
		Map<Integer, Entry> index = byId;
		if (index == null)
		{
			index = new HashMap<>();
			if (items != null)
			{
				for (Entry e : items)
				{
					if (e.ids == null)
					{
						continue;
					}
					for (Integer id : e.ids)
					{
						index.putIfAbsent(id, e);
					}
				}
			}
			byId = index;
		}
		return index.get(itemId);
	}

	/**
	 * The compact where-from one-liner ("Drop: Abyssal demon 1/512 ·
	 * Reward: Unsired 12/128"), max 3 sources, or null when the knowledge
	 * base has nothing — callers show nothing rather than a guess.
	 */
	public String sourceLine(int itemId)
	{
		return sourceLine(itemId, null, null);
	}

	/**
	 * State- and preference-aware where-from. A chosen method (prefKey from
	 * {@link #key}) becomes THE line — the player picked how they'll get it.
	 * With state, a make row whose skill gate is already met drops the gate
	 * text (telling a level-68 mage "needs Magic 68" is noise — Luke).
	 */
	public String sourceLine(int itemId, com.ironhub.state.StateView state, String prefKey)
	{
		Entry e = entry(itemId);
		if (e == null || e.sources == null || e.sources.isEmpty())
		{
			return null;
		}
		if (prefKey != null)
		{
			for (Source s : e.sources)
			{
				if (prefKey.equals(key(s)))
				{
					String label = label(s, state);
					return label.isEmpty() ? null : label;
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (Source s : e.sources)
		{
			String label = label(s, state);
			if (label.isEmpty())
			{
				continue;
			}
			if (shown > 0)
			{
				sb.append(" · ");
			}
			sb.append(label);
			if (++shown >= 3)
			{
				break;
			}
		}
		return shown == 0 ? null : sb.toString();
	}

	/** Stable identity for a source — the persisted method-choice key. */
	public static String key(Source s)
	{
		return s.how + "|" + (s.from != null ? s.from : s.detail != null ? s.detail : "");
	}

	/**
	 * The row HEADER when the materials are rendered separately underneath:
	 * "Make (Magic 68)" rather than the whole recipe as one sentence, which
	 * was unreadable at 225px (Luke, 2026-07-23).
	 */
	public static String shortLabel(Source s, com.ironhub.state.StateView state)
	{
		if (!"make".equals(s.how) || s.materials == null || s.materials.isEmpty())
		{
			return label(s, state);
		}
		if (s.skill == null)
		{
			return "Make it from:";
		}
		int cut = s.skill.lastIndexOf(' ');
		boolean met = cut > 0 && com.ironhub.requirements.Requirements.parse(
			"skillb:" + s.skill.substring(0, cut) + ":" + s.skill.substring(cut + 1)).isMet(state);
		return met ? "Make it from:" : "Make it (" + s.skill + ") from:";
	}

	/** label(), minus a make row's skill gate once the account meets it. */
	public static String label(Source s, com.ironhub.state.StateView state)
	{
		String label = s.label();
		if (state == null || s.skill == null || !"make".equals(s.how) || s.detail == null)
		{
			return label;
		}
		int cut = s.skill.lastIndexOf(' ');
		if (cut < 0)
		{
			return label;
		}
		// production gates are boostable-action gates (skillb semantics)
		com.ironhub.requirements.Requirement gate = com.ironhub.requirements.Requirements.parse(
			"skillb:" + s.skill.substring(0, cut) + ":" + s.skill.substring(cut + 1));
		return gate.isMet(state) ? label.replace(" (" + s.skill + ")", "") : label;
	}

	/** Equip reqs in requirement-graph form, or null when none are known. */
	public List<String> reqs(int itemId)
	{
		Entry e = entry(itemId);
		return e == null ? null : e.reqs;
	}

	/** True when this item's reqs came from prose extraction rather than
	 *  the hand-audited chains — surfaces say "the wiki states". */
	public boolean reqsExtracted(int itemId)
	{
		Entry e = entry(itemId);
		return e != null && "extracted".equals(e.reqsOrigin);
	}
}
