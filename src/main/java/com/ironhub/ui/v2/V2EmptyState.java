package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;

/**
 * The two things a surface says when it has no rows — and they are not the
 * same thing.
 *
 * <p><b>Empty</b> means we looked and there is nothing: no runs configured,
 * no results for that search. <b>Unknown</b> means we cannot see: the log
 * hasn't been opened, the house hasn't been visited, the varbit only syncs
 * in-region. Honesty is a feature here (an unexplained empty grid has read
 * as "broken" more than once), so unknown always says what would make it
 * knowable.
 */
public final class V2EmptyState
{
	private V2EmptyState()
	{
	}

	/** We looked; there is nothing. */
	public static V2Surface empty(OsrsTheme theme, String message)
	{
		V2Surface well = V2Surface.well(theme);
		well.add(V2Label.wrappedDetail(message, V2Tokens.CONTENT_WIDTH
			- 2 * (V2Tokens.SLICE_INSET + V2Tokens.PAD)));
		return well;
	}

	/**
	 * We cannot see this yet.
	 *
	 * @param how what the player would do to make it knowable — required,
	 *            because "?" on its own is what reads as a bug
	 */
	public static V2Surface unknown(OsrsTheme theme, String what, String how)
	{
		V2Surface well = V2Surface.well(theme);
		int width = V2Tokens.CONTENT_WIDTH - 2 * (V2Tokens.SLICE_INSET + V2Tokens.PAD);
		well.stack(V2Label.wrapped(what, width), V2Tokens.ROW);
		well.add(V2Label.wrappedDetail(how, width));
		return well;
	}
}
