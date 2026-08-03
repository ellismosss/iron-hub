package com.ironhub.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the unified wiki-URL builder (2026-08-03, Luke's word): names get
 * the union of every previous hand-rolled builder's escaping; pack slugs
 * pass through verbatim so their own %27s never double-encode.
 */
public class WikiLinksTest
{
	@Test
	public void namesEscapeSpacesAndApostrophes()
	{
		assertEquals("https://oldschool.runescape.wiki/w/Ghommal%27s_hilt_3",
			WikiLinks.url("Ghommal's hilt 3"));
		assertEquals("https://oldschool.runescape.wiki/w/Dragon_defender",
			WikiLinks.url(" Dragon defender "));
	}

	@Test
	public void slugsPassThroughVerbatim()
	{
		String slug = "Money_making_guide/Killing_Yama_(Contract,_duo)";
		assertEquals("https://oldschool.runescape.wiki/w/" + slug,
			WikiLinks.ofSlug(slug));
	}
}
