package com.ironhub.modules.loadout;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ironhub.data.ItemNameIndex;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches gear strategies from the OSRS wiki — strictly user-initiated
 * (the Fetch button), like the DPS-calc export. Tries the slayer-task
 * page for tasks and /Strategies then the base page for NPCs; results
 * cache per activity for the session.
 */
@Slf4j
class StrategyClient
{
	private static final String API = "https://oldschool.runescape.wiki/api.php";
	private static final String UA = "RuneLite Iron Hub plugin (strategy lookup; user-initiated)";

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ItemNameIndex names;
	private final Map<String, List<WikiStrategy>> cache = new ConcurrentHashMap<>();

	StrategyClient(OkHttpClient httpClient, Gson gson, ItemNameIndex names)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.names = names;
	}

	List<WikiStrategy> cached(String activity)
	{
		return cache.get(activity);
	}

	/** Page titles worth trying for an activity, best first. */
	static List<String> candidateTitles(String activity, boolean slayerTask)
	{
		return slayerTask
			? List.of("Slayer task/" + activity, activity + "/Strategies", activity)
			: List.of(activity + "/Strategies", "Slayer task/" + activity, activity);
	}

	/** Fetch (async) and cache; callback on an arbitrary thread. */
	void fetch(String activity, boolean slayerTask, Consumer<List<WikiStrategy>> onDone)
	{
		List<WikiStrategy> hit = cache.get(activity);
		if (hit != null)
		{
			onDone.accept(hit);
			return;
		}
		List<String> titles = candidateTitles(activity, slayerTask);
		HttpUrl url = HttpUrl.get(API).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("prop", "revisions")
			.addQueryParameter("rvprop", "content")
			.addQueryParameter("rvslots", "main")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("format", "json")
			.addQueryParameter("titles", String.join("|", titles))
			.build();
		httpClient.newCall(new Request.Builder().url(url)
				.header("User-Agent", UA).build())
			.enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					log.warn("wiki strategy fetch failed for {}", activity, e);
					onDone.accept(List.of());
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException
				{
					try (ResponseBody body = response.body())
					{
						List<WikiStrategy> strategies =
							parseResponse(body.string(), titles);
						cache.put(activity, strategies);
						onDone.accept(strategies);
					}
					catch (RuntimeException e)
					{
						log.warn("wiki strategy parse failed for {}", activity, e);
						onDone.accept(List.of());
					}
				}
			});
	}

	/** First candidate page (by preference) that yields gear templates. */
	List<WikiStrategy> parseResponse(String json, List<String> titlesByPreference)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		JsonObject query = root.getAsJsonObject("query");
		if (query == null)
		{
			return List.of();
		}
		Map<String, String> wikitextByTitle = new ConcurrentHashMap<>();
		Map<String, String> redirected = new ConcurrentHashMap<>();
		if (query.has("redirects"))
		{
			query.getAsJsonArray("redirects").forEach(r ->
				redirected.put(r.getAsJsonObject().get("to").getAsString(),
					r.getAsJsonObject().get("from").getAsString()));
		}
		if (query.has("normalized"))
		{
			query.getAsJsonArray("normalized").forEach(n ->
				redirected.merge(n.getAsJsonObject().get("to").getAsString(),
					n.getAsJsonObject().get("from").getAsString(), (a, b) -> b));
		}
		query.getAsJsonObject("pages").entrySet().forEach(entry ->
		{
			JsonObject page = entry.getValue().getAsJsonObject();
			if (!page.has("revisions"))
			{
				return;
			}
			String title = page.get("title").getAsString();
			String original = redirected.getOrDefault(title, title);
			String text = page.getAsJsonArray("revisions").get(0).getAsJsonObject()
				.getAsJsonObject("slots").getAsJsonObject("main").get("*").getAsString();
			wikitextByTitle.put(original, text);
			wikitextByTitle.putIfAbsent(title, text);
		});
		for (String title : titlesByPreference)
		{
			String text = wikitextByTitle.get(title);
			if (text != null)
			{
				List<WikiStrategy> strategies = WikiStrategy.parse(text, names);
				if (!strategies.isEmpty())
				{
					return strategies;
				}
			}
		}
		return List.of();
	}
}
