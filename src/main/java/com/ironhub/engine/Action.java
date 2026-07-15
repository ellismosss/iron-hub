package com.ironhub.engine;

import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * One node of the merged plan DAG (ENGINE-DESIGN §3.2): the atomic unit
 * the router orders. Kind-specific payload fields are nullable; the id is
 * the dedupe key ("train:Agility:70" merges across every goal that wants
 * it).
 */
public class Action
{
	public enum Kind
	{
		TRAIN, QUEST, OBTAIN, KILL, MANUAL
	}

	public final String id;
	public final Kind kind;
	public final String name;
	/** Goal ids this action serves (dedupe makes this a set). */
	public final Set<String> neededBy = new LinkedHashSet<>();
	/** Ids of actions that must complete before this one. */
	public final Set<String> dependsOn = new LinkedHashSet<>();

	// TRAIN
	public Skill trainSkill;
	public int trainToLevel;

	// QUEST
	public String questName;
	public boolean startOnly; // queststarted: gates need only the start

	// KILL
	public String kcSource;
	public int kcTarget;

	// OBTAIN / MANUAL
	public int itemId;
	/** Unlock flag that proves a manual step done (goalstep/diary/gear key). */
	public String unlockKey;
	/** Free-text for manual steps (the plan shows a tick-box). */
	public String manualText;
	/** Build/craft materials this action consumes (POH furniture etc.). */
	public java.util.List<com.ironhub.data.GearProgressionPack.Material> materials;

	public Action(String id, Kind kind, String name)
	{
		this.id = id;
		this.kind = kind;
		this.name = name;
	}
}
