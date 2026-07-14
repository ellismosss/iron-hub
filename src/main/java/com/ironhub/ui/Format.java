package com.ironhub.ui;

/**
 * Shared little formatters for panel copy.
 */
public final class Format
{
	private Format()
	{
	}

	/** "just now", "5 min ago", "2 h ago", "3 d ago". */
	public static String relativeTime(long millisAgo)
	{
		long minutes = millisAgo / 60_000;
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + " h ago";
		}
		return (hours / 24) + " d ago";
	}
}
