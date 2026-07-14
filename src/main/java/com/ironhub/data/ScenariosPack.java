package com.ironhub.data;

import java.util.List;
import lombok.Data;

/**
 * Typed model of {@code data/scenarios.json}: the boss/context targets
 * the best-in-bank loadout solver optimizes against (DESIGN.md §3.6).
 */
@Data
public class ScenariosPack
{
	private int version;
	private List<Scenario> scenarios;

	@Data
	public static class Scenario
	{
		private String id;
		private String name;
		private String style; // Melee | Range | Mage
		private String notes;
	}
}
