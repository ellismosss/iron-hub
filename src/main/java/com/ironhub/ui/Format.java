package com.ironhub.ui;

/**
 * Shared little formatters for panel copy.
 */
public final class Format
{
	private Format()
	{
	}

	/** "45s", "12m", "3h 20m", "1,200h" — expected-duration copy (Log
	 *  Adviser's time-to-slot style). Non-finite renders honestly as "?". */
	public static String hours(double hours)
	{
		if (!Double.isFinite(hours) || hours < 0)
		{
			return "?";
		}
		double seconds = hours * 3600.0;
		if (seconds < 90)
		{
			return Math.round(seconds) + "s";
		}
		if (seconds < 3600)
		{
			return Math.round(seconds / 60.0) + "m";
		}
		long wholeHours = (long) hours;
		long minutes = Math.round((hours - wholeHours) * 60.0);
		if (minutes == 60)
		{
			wholeHours++;
			minutes = 0;
		}
		if (wholeHours > 10)
		{
			return String.format("%,dh", minutes >= 30 ? wholeHours + 1 : wholeHours);
		}
		return minutes == 0 ? wholeHours + "h" : wholeHours + "h " + minutes + "m";
	}

	/** "1.2M", "250K", "812" — compact coin amounts (the game's own scale). */
	public static String gp(long coins)
	{
		long abs = Math.abs(coins);
		if (abs >= 10_000_000)
		{
			return coins / 1_000_000 + "M";
		}
		if (abs >= 1_000_000)
		{
			return Math.round(coins / 100_000.0) / 10.0 + "M";
		}
		if (abs >= 100_000)
		{
			return coins / 1_000 + "K";
		}
		if (abs >= 1_000)
		{
			return Math.round(coins / 100.0) / 10.0 + "K";
		}
		return String.valueOf(coins);
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
