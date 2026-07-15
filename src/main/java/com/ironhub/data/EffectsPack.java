package com.ironhub.data;

import java.util.List;

/**
 * Force-multiplier effects (data/effects.json): unlocks that discount
 * later work (ENGINE-DESIGN §5's discount table). v1 models the travel
 * factor only — each active effect steps the global travel multiplier
 * down; per-method rate multipliers arrive with later pack versions.
 * Curated from the July-2026 progression research (ENGINE-DESIGN
 * Appendix B §2), one source per entry.
 */
public class EffectsPack
{
	public String source;
	public String generated;
	/** Travel multiplier on quest-shaped actions before any effects. */
	public double baseTravelFactor;
	/** The floor the factor cannot drop below. */
	public double minTravelFactor;
	public List<Effect> effects;

	public static class Effect
	{
		public String id;
		public String name;
		/** Requirement string; the effect is active once this is met. */
		public String active;
		/** Subtracted from the travel factor while active. */
		public double travelDelta;
		public String note;
		public String provenance;
	}
}
