package com.ironhub.modules.loot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * Picked-up vs left-behind classification (L3, 2026-08-03; reworked after
 * the first live test). Pure and tick-fed so it unit-tests without a
 * client.
 *
 * <p>A kill's drops register as PENDING ground items. The client no longer
 * says which exact tile each stack landed on ({@code ItemStack.getLocation()}
 * is a null stub in current RuneLite), so pending drops carry the NPC's
 * death tile as an APPROXIMATE location and a despawn matches by item id
 * within {@link #MATCH_RADIUS} tiles — wide enough for loot under any tile
 * a large NPC covered, tight enough not to steal another room's drops.
 *
 * <p>When a ground item despawns, it is classified:
 *
 * <ul>
 * <li><b>PICKED</b> — the local player stands on the item's tile (a manual
 * pickup requires that), or the inventory gained that item id within the
 * last two ticks (covers telegrab and area-loot, which take from range).
 * <li><b>UNKNOWN</b> — the despawn arrived during a scene reload (teleport,
 * hop, logout): the client unloads ground items that still exist
 * server-side, so nothing about the drop's fate is knowable. Unknown is
 * never guessed into either bucket.
 * <li><b>LEFT</b> — anything else: the item timed out on the ground.
 * </ul>
 *
 * <p>Items picked up much later still classify (they stay pending until
 * their despawn event, whenever it comes). A pending item whose despawn
 * never arrives (scene unloaded and never revisited) stays unclassified —
 * honest silence, the module reports only confirmed pickups.
 */
final class LootPickupTracker
{
	/** How far a despawn tile may sit from the registered death tile and
	 *  still be the same drop (large NPCs spread loot across their area). */
	static final int MATCH_RADIUS = 6;

	enum Fate
	{
		PICKED, LEFT, UNKNOWN
	}

	static final class Classified
	{
		final String source;
		final int itemId;
		final int quantity;
		final Fate fate;

		Classified(String source, int itemId, int quantity, Fate fate)
		{
			this.source = source;
			this.itemId = itemId;
			this.quantity = quantity;
			this.fate = fate;
		}
	}

	private static final class Pending
	{
		final String source;
		final WorldPoint near;
		final int quantity;

		Pending(String source, WorldPoint near, int quantity)
		{
			this.source = source;
			this.near = near;
			this.quantity = quantity;
		}
	}

	/** item id -> pending drops of that id, oldest first. */
	private final Map<Integer, Deque<Pending>> pending = new HashMap<>();

	/** A kill's drop landed near the source NPC's death tile. */
	void onLoot(String source, int itemId, int quantity, WorldPoint near)
	{
		pending.computeIfAbsent(itemId, id -> new ArrayDeque<>())
			.addLast(new Pending(source, near, quantity));
	}

	/**
	 * A ground item despawned. Returns the classification for a tracked
	 * drop, or null for an item this tracker never registered (someone
	 * else's drop, world spawns, or too far from any registered kill).
	 */
	Classified onDespawn(int itemId, WorldPoint where, WorldPoint player,
		boolean sceneReloading, boolean inventoryGainedRecently)
	{
		Deque<Pending> drops = pending.get(itemId);
		if (drops == null)
		{
			return null;
		}
		Pending match = null;
		for (Pending drop : drops)
		{
			if (drop.near.distanceTo(where) <= MATCH_RADIUS)
			{
				match = drop; // oldest close-enough drop of this id
				break;
			}
		}
		if (match == null)
		{
			return null;
		}
		drops.remove(match);
		if (drops.isEmpty())
		{
			pending.remove(itemId);
		}
		Fate fate;
		if (sceneReloading)
		{
			fate = Fate.UNKNOWN;
		}
		else if (inventoryGainedRecently
			|| (player != null && player.distanceTo(where) == 0))
		{
			fate = Fate.PICKED;
		}
		else
		{
			fate = Fate.LEFT;
		}
		return new Classified(match.source, itemId, match.quantity, fate);
	}

	/** Scene gone (logout/hop): every pending drop's fate is unknowable. */
	void clear()
	{
		pending.clear();
	}

	int pendingCount()
	{
		return pending.values().stream().mapToInt(Deque::size).sum();
	}
}
