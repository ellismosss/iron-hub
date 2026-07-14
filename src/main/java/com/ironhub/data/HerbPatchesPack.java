package com.ironhub.data;

import java.util.List;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;

/**
 * Typed model of {@code data/herb-patches.json}: herb patch locations for
 * run routing, proximity detection and Path buttons.
 */
@Data
public class HerbPatchesPack
{
	private int version;
	private List<Patch> patches;

	@Data
	public static class Patch
	{
		private String id;
		private String name;
		private WorldPoint location;
	}
}
