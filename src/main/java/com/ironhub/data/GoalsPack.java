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
	}

	@Data
	public static class Step
	{
		private String label;
		private String requirement; // Requirements.parse() form
	}
}
