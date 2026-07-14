package com.ironhub.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Community completion rates per Combat Achievement task, snapshotted from
 * the wiki's All-tasks table by tools/gen_ca_completion.py (no runtime
 * HTTP). Task ids are the in-game ids (struct param 1306), which the wiki
 * carries as data-ca-task-id; names are kept as a join fallback.
 */
public class CaCompletionPack
{
	public String source;
	public String generated;
	public List<Task> tasks;

	public static class Task
	{
		public int id;
		public String name;
		public double pct;
	}

	/** Task id → completion percentage. */
	public Map<Integer, Double> byId()
	{
		Map<Integer, Double> map = new HashMap<>();
		if (tasks != null)
		{
			for (Task task : tasks)
			{
				map.put(task.id, task.pct);
			}
		}
		return map;
	}

	/** Normalized task name → completion percentage (join fallback). */
	public Map<String, Double> byName()
	{
		Map<String, Double> map = new HashMap<>();
		if (tasks != null)
		{
			for (Task task : tasks)
			{
				map.put(normalize(task.name), task.pct);
			}
		}
		return map;
	}

	/** Case/punctuation-insensitive name key. */
	public static String normalize(String name)
	{
		return name.toLowerCase().replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
	}
}
