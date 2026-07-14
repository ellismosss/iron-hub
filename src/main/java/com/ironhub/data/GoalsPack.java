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
	}

	@Data
	public static class Step
	{
		private String label;
		private String requirement; // Requirements.parse() form
	}
}
