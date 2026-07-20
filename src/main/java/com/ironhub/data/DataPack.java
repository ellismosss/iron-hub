package com.ironhub.data;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Loads bundled data packs from {@code /data/<name>.json} into typed models.
 * Content is schema-validated in CI (DataPackTest); a missing or corrupt
 * bundled pack is a build defect, so this fails fast rather than degrading.
 */
@Singleton
public class DataPack
{
	private final Gson gson;

	/** Parsed packs are immutable and several modules load the same ones
	 *  (clog, quests, xp-actions...), so each was Gson-parsed and retained
	 *  two or three times — plus once more per theme-flip tab rebuild
	 *  (2026-07-20 audit). Keyed by name+type: two models over one file
	 *  stay distinct. */
	private final java.util.concurrent.ConcurrentHashMap<String, Object> cache =
		new java.util.concurrent.ConcurrentHashMap<>();

	@Inject
	public DataPack(Gson gson)
	{
		this.gson = gson;
	}

	public <T> T load(String name, Class<T> type)
	{
		return type.cast(cache.computeIfAbsent(name + "|" + type.getName(),
			key -> parse(name, type)));
	}

	private <T> T parse(String name, Class<T> type)
	{
		String path = "/data/" + name + ".json";
		InputStream in = DataPack.class.getResourceAsStream(path);
		if (in == null)
		{
			throw new IllegalStateException("missing bundled data pack: " + path);
		}
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			T pack = gson.fromJson(reader, type);
			if (pack == null)
			{
				throw new IllegalStateException("empty data pack: " + path);
			}
			return pack;
		}
		catch (IOException | JsonParseException e)
		{
			throw new IllegalStateException("corrupt data pack: " + path, e);
		}
	}
}
