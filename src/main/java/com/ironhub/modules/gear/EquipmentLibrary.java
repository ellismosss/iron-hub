package com.ironhub.modules.gear;

import com.ironhub.data.EquipmentPack;
import com.ironhub.state.StateView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Gear library's search / filter / sort, pure so it can be tested
 * without a client. The tab owns the widgets; this owns the logic over the
 * {@link EquipmentPack}.
 */
final class EquipmentLibrary
{
	/** The metrics a player can sort by — a name, the value, or any bonus. */
	enum Sort
	{
		VALUE("Value", true),
		NAME("Name", false),
		STAB("Stab attack", true),
		SLASH("Slash attack", true),
		CRUSH("Crush attack", true),
		RANGED_ATT("Ranged attack", true),
		MAGIC_ATT("Magic attack", true),
		DEF_STAB("Stab defence", true),
		DEF_SLASH("Slash defence", true),
		DEF_CRUSH("Crush defence", true),
		DEF_RANGE("Ranged defence", true),
		DEF_MAGIC("Magic defence", true),
		STRENGTH("Strength", true),
		RANGED_STR("Ranged strength", true),
		MAGIC_DMG("Magic damage", true),
		PRAYER("Prayer", true),
		SPEED("Attack speed", true);

		final String label;
		/** Whether high values sort first by default (name is A→Z). */
		final boolean descendingByDefault;

		Sort(String label, boolean descendingByDefault)
		{
			this.label = label;
			this.descendingByDefault = descendingByDefault;
		}

		/** The stat index this sort reads, or -1 for name/value/speed. */
		int statIndex()
		{
			switch (this)
			{
				case STAB: return EquipmentPack.A_STAB;
				case SLASH: return EquipmentPack.A_SLASH;
				case CRUSH: return EquipmentPack.A_CRUSH;
				case RANGED_ATT: return EquipmentPack.A_RANGE;
				case MAGIC_ATT: return EquipmentPack.A_MAGIC;
				case DEF_STAB: return EquipmentPack.D_STAB;
				case DEF_SLASH: return EquipmentPack.D_SLASH;
				case DEF_CRUSH: return EquipmentPack.D_CRUSH;
				case DEF_RANGE: return EquipmentPack.D_RANGE;
				case DEF_MAGIC: return EquipmentPack.D_MAGIC;
				case STRENGTH: return EquipmentPack.STR;
				case RANGED_STR: return EquipmentPack.RSTR;
				case MAGIC_DMG: return EquipmentPack.MDMG;
				case PRAYER: return EquipmentPack.PRAYER;
				default: return -1;
			}
		}
	}

	/** The obtained/unobtained cut. */
	enum Owned
	{
		ALL, OWNED, MISSING
	}

	/** The members/free cut. */
	enum Access
	{
		ALL, MEMBERS, FREE
	}

	private final EquipmentPack pack;
	private final Ownership ownership;
	private final java.util.function.ToLongFunction<EquipmentPack.Item> valueOf;

	/** Owning-any-id test, injected so the pure logic never touches a client. */
	interface Ownership
	{
		boolean owns(EquipmentPack.Item item);
	}

	/**
	 * @param valueOf the item's sortable/displayable market value — the live
	 *                GE price in-client, the high-alch value offline. The
	 *                exchange module's store value is deliberately not used
	 *                (it read Tumeken's shadow at 7M vs the real ~750M).
	 */
	EquipmentLibrary(EquipmentPack pack, Ownership ownership,
		java.util.function.ToLongFunction<EquipmentPack.Item> valueOf)
	{
		this.pack = pack;
		this.ownership = ownership;
		this.valueOf = valueOf;
	}

	long value(EquipmentPack.Item item)
	{
		return valueOf.applyAsLong(item);
	}

	/** The metric a row shows on its right, for the active sort. */
	long metric(EquipmentPack.Item item, Sort sort)
	{
		if (sort == Sort.SPEED)
		{
			return item.speed;
		}
		if (sort == Sort.VALUE || sort == Sort.NAME)
		{
			return valueOf.applyAsLong(item);
		}
		return item.stats[sort.statIndex()];
	}

	List<EquipmentPack.Item> query(String search, String slot, Owned owned, Access access,
		Sort sort, boolean ascending)
	{
		String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
		List<EquipmentPack.Item> out = new ArrayList<>();
		for (EquipmentPack.Item item : pack.items)
		{
			if (slot != null && !slot.equals(item.slot))
			{
				continue;
			}
			if (access == Access.MEMBERS && !item.members
				|| access == Access.FREE && item.members)
			{
				continue;
			}
			if (owned != Owned.ALL)
			{
				boolean owns = ownership.owns(item);
				if (owned == Owned.OWNED && !owns || owned == Owned.MISSING && owns)
				{
					continue;
				}
			}
			if (!term.isEmpty() && !item.name.toLowerCase(Locale.ROOT).contains(term))
			{
				continue;
			}
			out.add(item);
		}
		out.sort(comparator(sort, ascending));
		return out;
	}

	private Comparator<EquipmentPack.Item> comparator(Sort sort, boolean ascending)
	{
		Comparator<EquipmentPack.Item> order;
		if (sort == Sort.NAME)
		{
			order = Comparator.comparing(i -> i.name.toLowerCase(Locale.ROOT));
		}
		else
		{
			// ties break by name, so a metric-sorted list is still stable and
			// readable (dozens of items share a bonus of 0)
			Comparator<EquipmentPack.Item> byMetric =
				Comparator.comparingLong(i -> metric(i, sort));
			order = (ascending ? byMetric : byMetric.reversed())
				.thenComparing(i -> i.name.toLowerCase(Locale.ROOT));
		}
		return sort == Sort.NAME && !ascending ? order.reversed() : order;
	}
}
