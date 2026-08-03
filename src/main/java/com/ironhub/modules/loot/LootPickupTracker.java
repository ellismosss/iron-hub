package com.ironhub.modules.loot;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * Picked-up vs left-behind classification (L3, 2026-08-03). Pure and
 * tick-fed so it unit-tests without a client.
 *
 * <p>A kill's drops register as PENDING ground items by tile. When a
 * ground item despawns, it is classified:
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
		int quantity;

		Pending(String source, int quantity)
		{
			this.source = source;
			this.quantity = quantity;
		}
	}

	/** packed tile -> item id -> pending drop. */
	private final Map<Long, Map<Integer, Pending>> pending = new HashMap<>();

	private static long pack(WorldPoint point)
	{
		return ((long) point.getPlane() << 32)
			| ((long) point.getX() << 16) | point.getY();
	}

	/** A kill's drop landed on a tile. */
	void onLoot(String source, int itemId, int quantity, WorldPoint where)
	{
		pending.computeIfAbsent(pack(where), t -> new HashMap<>())
			.merge(itemId, new Pending(source, quantity), (a, b) ->
			{
				a.quantity += b.quantity;
				return a;
			});
	}

	/**
	 * A ground item despawned. Returns the classification for a tracked
	 * drop, or null for an item this tracker never registered (someone
	 * else's drop, world spawns).
	 */
	Classified onDespawn(int itemId, WorldPoint where, WorldPoint player,
		boolean sceneReloading, boolean inventoryGainedRecently)
	{
		Map<Integer, Pending> tile = pending.get(pack(where));
		if (tile == null)
		{
			return null;
		}
		Pending drop = tile.remove(itemId);
		if (drop == null)
		{
			return null;
		}
		if (tile.isEmpty())
		{
			pending.remove(pack(where));
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
		return new Classified(drop.source, itemId, drop.quantity, fate);
	}

	/** Scene gone (logout/hop): every pending drop's fate is unknowable. */
	void clear()
	{
		pending.clear();
	}

	int pendingCount()
	{
		return pending.values().stream().mapToInt(Map::size).sum();
	}
}
