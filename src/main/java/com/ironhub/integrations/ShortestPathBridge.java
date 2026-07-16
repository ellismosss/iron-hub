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
	private final net.runelite.client.callback.ClientThread clientThread; // null in unit tests

	@Inject
	public ShortestPathBridge(EventBus eventBus, net.runelite.client.callback.ClientThread clientThread)
	{
		this.eventBus = eventBus;
		this.clientThread = clientThread;
	}

	public void pathTo(WorldPoint target)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("target", target);
		post(new PluginMessage(NAMESPACE, "path", data));
	}

	public void clearPath()
	{
		post(new PluginMessage(NAMESPACE, "clear"));
	}

	/**
	 * Always deliver on the client thread: EventBus.post runs subscribers on
	 * the CALLING thread, and Shortest Path's handler works against client
	 * state — a post from the EDT (a sidebar Skip button) left its path
	 * pointing at the previous stop until the next client-thread advance.
	 */
	private void post(PluginMessage message)
	{
		if (clientThread == null)
		{
			eventBus.post(message);
			return;
		}
		clientThread.invoke(() -> eventBus.post(message));
	}
}
