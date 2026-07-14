package com.ironhub.data;

import java.util.List;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;

/**
 * Typed model of {@code data/dailies.json} (schema:
 * {@code data/schemas/dailies.schema.json}).
 */
@Data
public class DailiesPack
{
	private int version;
	private List<Daily> dailies;

	@Data
	public static class Daily
	{
		private String id;
		private String name;
		private String reset;      // daily | weekly | growth
		private WorldPoint location;
		private List<String> requirements; // Requirements.parse() string form
		private String scaling;
		private String detection;  // manual | varbit | chat
	}
}
