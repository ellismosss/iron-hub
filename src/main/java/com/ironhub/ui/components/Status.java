package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Color;

/**
 * The four semantic statuses of the design system (DESIGN-PACKAGE.md
 * "Status colors"). Identical meaning everywhere, never repurposed.
 */
public enum Status
{
	OWNED(UiTokens.STATUS_OWNED, "owned"),
	AVAILABLE(UiTokens.STATUS_AVAILABLE, "available"),
	LOCKED(UiTokens.STATUS_LOCKED, "locked"),
	WARNING(UiTokens.STATUS_WARNING, "warning");

	private final Color color;
	private final String label;

	Status(Color color, String label)
	{
		this.color = color;
		this.label = label;
	}

	public Color color()
	{
		return color;
	}

	public String label()
	{
		return label;
	}

	public StatusGlyph glyph()
	{
		return new StatusGlyph(this);
	}
}
