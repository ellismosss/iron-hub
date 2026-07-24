package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;

/**
 * The block at the top of a tab that says where you are: a title, the number
 * that matters, a bar, and a line of provenance. Collections logged, points
 * to the next tier, quests done — every tab has one and, before this atom,
 * every tab drew its own.
 *
 * <p>Composition only: a Card holding V2Label roles and a V2ProgressBar in a
 * fixed order. The order IS the atom — a hero whose number sits under its
 * bar on one tab and over it on another is the drift this system exists to
 * stop.
 */
public final class V2Hero
{
	private V2Hero()
	{
	}

	/**
	 * @param title      what this tab counts ("Collections Logged")
	 * @param value      the number itself, already formatted ("1,482 / 1,706")
	 * @param fraction   0..1, or NaN for a hero with nothing measurable yet
	 * @param provenance the honest footnote, or null ("as of last log open")
	 */
	public static V2Surface build(OsrsTheme theme, String title, String value,
		double fraction, String provenance)
	{
		V2Surface card = V2Surface.card(theme);
		card.stack(V2Label.heading(title), V2Tokens.ROW);
		card.stack(V2Label.value(value), V2Tokens.ROW);
		card.add(new V2ProgressBar(theme).fraction(fraction));
		if (provenance != null)
		{
			card.add(V2Layout.gap(V2Tokens.ROW));
			card.add(V2Label.faint(provenance));
		}
		return card;
	}
}
