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

		/** "Drop: Abyssal demon 1/512" / "Shop: Durrik's Goods (2 gp)" /
		 *  "Make: Herblore 25". */
		public String label()
		{
			switch (how == null ? "" : how)
			{
				case "drop":
					return "Drop: " + from + (rate != null ? " " + rate : "");
				case "shop":
					return "Shop: " + from + (price != null ? " (" + price + ")" : "");
				case "make":
					return skill != null ? "Make: " + skill : "Make (see recipe)";
				case "reward":
					return "Reward: " + from + (rate != null ? " " + rate : "");
				case "spell":
					return "Spell: " + from;
				default:
					return from != null ? from : "";
			}
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
		Entry e = entry(itemId);
		if (e == null || e.sources == null || e.sources.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (Source s : e.sources)
		{
			String label = s.label();
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
