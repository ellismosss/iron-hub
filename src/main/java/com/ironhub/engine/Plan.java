package com.ironhub.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * The router's output (ENGINE-DESIGN §3.7): an ordered, costed, explained
 * step list. Immutable once built; the fingerprint keys plan diffing so
 * the UI never reshuffles silently.
 */
public class Plan
{
	public static class Alternative
	{
		public final String methodId;
		public final String name;
		public final double deltaHours; // vs the chosen method (+slower)
		public final String style;

		public Alternative(String methodId, String name, double deltaHours, String style)
		{
			this.methodId = methodId;
			this.name = name;
			this.deltaHours = deltaHours;
			this.style = style;
		}
	}

	public static class Step
	{
		public final Action action;
		/** Expected hours; NaN = honestly unknown. */
		public final double hours;
		public final String why;
		public final String chapter;
		/** Method used for TRAIN steps (null otherwise). */
		public final String methodName;
		public final List<Alternative> alternatives;
		public final boolean pinned;
		public final boolean snoozed;

		public Step(Action action, double hours, String why, String chapter,
			String methodName, List<Alternative> alternatives, boolean pinned, boolean snoozed)
		{
			this.action = action;
			this.hours = hours;
			this.why = why;
			this.chapter = chapter;
			this.methodName = methodName;
			this.alternatives = alternatives;
			this.pinned = pinned;
			this.snoozed = snoozed;
		}
	}

	public final List<Step> steps = new ArrayList<>();
	public double knownHours;
	public int unknownCount;
	public final List<String> degraded = new ArrayList<>();
	public String fingerprint = "";

	/** First actionable step (skips snoozed), or null when empty/done. */
	public Step head()
	{
		return steps.stream().filter(s -> !s.snoozed).findFirst().orElse(null);
	}
}
