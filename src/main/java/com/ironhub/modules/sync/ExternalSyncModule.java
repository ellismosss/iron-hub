package com.ironhub.modules.sync;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * External sync (DESIGN.md §3.19) — ALL opt-in, defaults off, documented
 * in the README for Hub review:
 * - Wise Old Man + TempleOSRS: update ping on logout (rate-limited)
 * No data leaves the client unless the user enables a toggle.
 * (The Discord webhook was removed entirely — Luke, 2026-08-03.)
 */
@Slf4j
@Singleton
public class ExternalSyncModule implements IronHubModule
{
	static final String WOM_URL = "https://api.wiseoldman.net/v2/players/";
	static final String TEMPLE_URL = "https://templeosrs.com/php/add_datapoint.php?player=";
	private static final long PING_MIN_GAP_MS = 5 * 60_000;

	private final Client client;
	private final EventBus eventBus;
	private final IronHubConfig config;
	private final okhttp3.OkHttpClient httpClient; // null in unit tests

	private volatile String username;
	private volatile long lastPingMs;

	@Inject
	public ExternalSyncModule(Client client, EventBus eventBus,
		IronHubConfig config, okhttp3.OkHttpClient httpClient)
	{
		this.client = client;
		this.eventBus = eventBus;
		this.config = config;
		this.httpClient = httpClient;
	}

	@Override
	public String name()
	{
		return "External sync";
	}

	@Override
	public void startUp()
	{
		username = null; // may be a different account now; re-seeded next tick
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
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
			// the next session may be a different account: reseed the name,
			// or WOM pings target the old player (the LOGIN_SCREEN ping
			// above already ran for the outgoing account)
			username = null;
		}
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
		// URLEncoder is form encoding: '+' for a space decodes as a space
		// only in QUERY strings — the WOM url uses the name as a PATH
		// segment, where a literal '+' names a different player. %20 is
		// valid in both positions.
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
			.replace("+", "%20");
	}
}
