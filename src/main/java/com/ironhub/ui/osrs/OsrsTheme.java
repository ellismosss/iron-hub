package com.ironhub.ui.osrs;

import java.awt.Color;

/**
 * One stone "clothing" for the skin: the four surface colors plus the corner
 * notch stamp (themes differ in geometry, not just palette — vanilla's notch
 * is 7x7, Mystic's 6x6). Text colors and fonts stay global on OsrsSkin: the
 * game's text rendering is identical under resource packs.
 */
public final class OsrsTheme
{
	/** Interface backing between boxes. */
	public final Color background;
	/** Stone box interior. */
	public final Color boxFill;
	/** Outer 1px line of the engraved box border. */
	public final Color edgeDark;
	/** Inner 1px line of the engraved box border. */
	public final Color edgeLight;
	/**
	 * Top-left corner pixel stamp, mirrored to the other three corners.
	 * B = outside, D = dark line, L = light line, F = box fill.
	 */
	public final String[] cornerStamp;

	public OsrsTheme(Color background, Color boxFill, Color edgeDark, Color edgeLight, String[] cornerStamp)
	{
		this.background = background;
		this.boxFill = boxFill;
		this.edgeDark = edgeDark;
		this.edgeLight = edgeLight;
		this.cornerStamp = cornerStamp;
	}
}
