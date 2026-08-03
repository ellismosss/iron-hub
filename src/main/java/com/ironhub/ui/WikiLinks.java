package com.ironhub.ui;

import net.runelite.client.util.LinkBrowser;

/**
 * THE wiki-URL builder. Ten modules each hand-rolled this prefix with
 * drifting escaping (some encoded apostrophes, some didn't) — unified on
 * Luke's word, 2026-08-03.
 *
 * <p>{@link #url} takes a PAGE NAME ("Ghommal's hilt 3") and escapes it the
 * union of every previous builder's way: spaces to underscores (the wiki's
 * canonical form) and apostrophes percent-encoded. {@link #ofSlug} takes a
 * pack-provided PATH FRAGMENT already in wiki form
 * ("Money_making_guide/Killing_Yama_...") and appends it verbatim — never
 * re-escape a slug, or its own %27s double-encode.
 */
public final class WikiLinks
{
	private static final String BASE = "https://oldschool.runescape.wiki/w/";

	private WikiLinks()
	{
	}

	/** The page URL for a plain display name. */
	public static String url(String pageName)
	{
		return BASE + pageName.trim().replace(' ', '_').replace("'", "%27");
	}

	/** The page URL for a pack-provided, already-wiki-form fragment. */
	public static String ofSlug(String slug)
	{
		return BASE + slug;
	}

	/** Open the page for a plain display name in the browser. */
	public static void open(String pageName)
	{
		LinkBrowser.browse(url(pageName));
	}
}
