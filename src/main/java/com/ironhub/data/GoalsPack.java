package com.ironhub.data;

import java.util.List;
import lombok.Data;

/**
 * Typed model of {@code data/goals.json}: capstone targets whose steps
 * are requirement-graph strings; unparseable ones are manual ticks.
 */
@Data
public class GoalsPack
{
	private int version;
	private List<Goal> goals;

	@Data
	public static class Goal
	{
		private String id;
		private String name;
		private List<Step> steps;
		/**
		 * Optional ownership proof (all must hold): owning the end product
		 * completes the goal even when steps can't be detected retroactively
		 * (pre-plugin kill counts, spent marks of grace).
		 */
		private List<String> achieved;
		/** Item id for the goal's icon; set programmatically on synthetic gear goals. */
		private transient Integer iconItemId;

		/** Icon item id: explicit, or derived from an item ownership proof. */
		public Integer icon()
		{
			if (iconItemId != null)
			{
				return iconItemId;
			}
			if (achieved != null)
			{
				for (String proof : achieved)
				{
					if (proof.startsWith("item:") || proof.startsWith("itemx:"))
					{
						return Integer.parseInt(proof.split(":")[1]);
					}
				}
			}
			return null;
		}
	}

	@Data
	public static class Step
	{
		private String label;
		private String requirement; // Requirements.parse() form
	}
}
