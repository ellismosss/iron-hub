package com.ironhub.modules.sync;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * External sync (DESIGN.md §3.19) — ALL opt-in, defaults off, documented
 * in the README for Hub review:
 * - Wise Old Man + TempleOSRS: update ping on logout (rate-limited)
 * - Discord webhook: level milestones (multiples of 10, and 99) and
 *   completed goals
 * No data leaves the client unless the user enables a toggle.
 */
@Slf4j
@Singleton
public class ExternalSyncModule implements IronHubModule
{
	static final String WOM_URL = "https://api.wiseoldman.net/v2/players/";
	static final String TEMPLE_URL = "https://templeosrs.com/php/add_datapoint.php?player=";
	private static final long PING_MIN_GAP_MS = 5 * 60_000;

	private final AccountState state;
	private final Client client;
	private final EventBus eventBus;
	private final IronHubConfig config;
	private final okhttp3.OkHttpClient httpClient; // null in unit tests
	private final com.google.gson.Gson gson;

	private final Map<Skill, Integer> lastLevels = new ConcurrentHashMap<>();
	private final Runnable listener = this::onStateChanged;
	private volatile String username;
	private volatile long lastPingMs;

	@Inject
	public ExternalSyncModule(AccountState state, Client client, EventBus eventBus,
		IronHubConfig config, okhttp3.OkHttpClient httpClient, com.google.gson.Gson gson)
	{
		this.state = state;
		this.client = client;
		this.eventBus = eventBus;
		this.config = config;
		this.httpClient = httpClient;
		this.gson = gson;
	}

	@Override
	public String name()
	{
		return "External sync";
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
		state.addListener(listener);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		state.removeListener(listener);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (username == null && client != null && client.getLocalPlayer() != null
			&& client.getLocalPlayer().getName() != null)
		{
			username = client.getLocalPlayer().getName();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN && username != null
			&& config.womSync() && httpClient != null
			&& System.currentTimeMillis() - lastPingMs > PING_MIN_GAP_MS)
		{
			lastPingMs = System.currentTimeMillis();
			post(WOM_URL + encode(username), "");
			get(TEMPLE_URL + encode(username));
			log.debug("sync ping sent for {}", username);
		}
		if (event.getGameState() == GameState.LOGGING_IN)
		{
			// the next session may be a different account: reseed the name and
			// the milestone baseline, or WOM pings target the old player and
			// the first stat ingest fires bogus level webhooks (2026-07-20 audit;
			// the LOGIN_SCREEN ping above already ran for the outgoing account)
			username = null;
			lastLevels.clear();
		}
	}

	/** Level-milestone detection off state notifications. */
	private void onStateChanged()
	{
		for (Skill skill : Skill.values())
		{
			int level = state.getRealLevel(skill);
			Integer previous = lastLevels.put(skill, level);
			if (previous != null && level > previous)
			{
				milestone(previous, level)
					.ifPresent(m -> webhook("Level milestone: " + m + " " + skill.getName()));
			}
		}
	}

	/** The milestone crossed between two levels, if any. Static for tests. */
	static Optional<Integer> milestone(int from, int to)
	{
		for (int level = to; level > from; level--)
		{
			if (level == 99 || level % 10 == 0)
			{
				return Optional.of(level);
			}
		}
		return Optional.empty();
	}

	private void webhook(String message)
	{
		String url = config.discordWebhookUrl();
		if (url == null || url.isBlank() || httpClient == null)
		{
			return;
		}
		com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
		payload.addProperty("content", message);
		post(url, gson.toJson(payload));
	}

	private void post(String url, String jsonBody)
	{
		fire(new okhttp3.Request.Builder().url(url)
			.post(okhttp3.RequestBody.create(
				okhttp3.MediaType.parse("application/json"), jsonBody))
			.build());
	}

	private void get(String url)
	{
		fire(new okhttp3.Request.Builder().url(url).get().build());
	}

	private void fire(okhttp3.Request request)
	{
		httpClient.newCall(request).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(okhttp3.Call call, java.io.IOException e)
			{
				log.debug("external sync call failed: {}", call.request().url(), e);
			}

			@Override
			public void onResponse(okhttp3.Call call, okhttp3.Response response)
			{
				response.close();
			}
		});
	}

	private static String encode(String value)
	{
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}
}
