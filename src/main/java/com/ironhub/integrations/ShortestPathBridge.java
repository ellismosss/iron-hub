package com.ironhub.integrations;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Soft integration with the Shortest Path hub plugin via RuneLite's
 * PluginMessage event bus (no hard dependency — Hub rules). If Shortest Path
 * isn't installed the message is simply unheard; callers should offer a
 * wiki-map fallback.
 */
@Slf4j
@Singleton
public class ShortestPathBridge
{
	private static final String NAMESPACE = "shortestpath";

	private final EventBus eventBus;

	@Inject
	public ShortestPathBridge(EventBus eventBus)
	{
		this.eventBus = eventBus;
	}

	public void pathTo(WorldPoint target)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("target", target);
		eventBus.post(new PluginMessage(NAMESPACE, "path", data));
	}

	public void clearPath()
	{
		eventBus.post(new PluginMessage(NAMESPACE, "clear"));
	}
}
