package com.ironhub.modules.ca;

/**
 * One tile of the Combat Achievements interface's boss grid, read from the
 * game cache: the name and combat level it shows, and the index its tasks
 * are tagged with (a task's boss param holds this).
 */
class CaBoss
{
	/** 1 boss, 2 skilling boss, 3 raid — the profile's "Top X" categories. */
	static final int CATEGORY_BOSS = 1;
	static final int CATEGORY_SKILLING = 2;
	static final int CATEGORY_RAID = 3;

	final int index;
	final String name;
	/** Combat level; 0 = the interface prints "N/A" (raids, skilling bosses). */
	final int level;
	final int category;

	CaBoss(int index, String name, int level, int category)
	{
		this.index = index;
		this.name = name;
		this.level = level;
		this.category = category;
	}
}
