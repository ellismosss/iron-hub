package com.ironhub.state;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Profile-scoped JSON persistence under {@code RUNELITE_DIR/iron-hub/<profile>/}
 * (DESIGN.md §2.3). Serialization happens on the caller's thread so callers
 * can hand over mutable state safely; the file write runs on the executor.
 */
@Slf4j
@Singleton
public class ProfileStore
{
	private final Gson gson;
	private final Executor executor;
	private final File baseDir;

	@Inject
	public ProfileStore(Gson gson, ScheduledExecutorService executor)
	{
		this(gson, executor, new File(RuneLite.RUNELITE_DIR, "iron-hub"));
	}

	ProfileStore(Gson gson, Executor executor, File baseDir)
	{
		this.gson = gson;
		this.executor = executor;
		this.baseDir = baseDir;
	}

	void save(long profile, PersistedState state)
	{
		String json = gson.toJson(state);
		File file = stateFile(profile);
		executor.execute(() ->
		{
			try
			{
				// ponytail: direct write; load tolerates a corrupt file, and a
				// lost bank snapshot is recovered by opening the bank
				Files.createDirectories(file.getParentFile().toPath());
				Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				log.warn("failed to persist iron-hub state to {}", file, e);
			}
		});
	}

	PersistedState load(long profile)
	{
		File file = stateFile(profile);
		if (!file.exists())
		{
			return new PersistedState();
		}
		try
		{
			String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			PersistedState state = gson.fromJson(json, PersistedState.class);
			return state != null ? state : new PersistedState();
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("corrupt iron-hub state at {} — starting fresh", file, e);
			return new PersistedState();
		}
	}

	File stateFile(long profile)
	{
		return new File(new File(baseDir, Long.toString(profile)), "state.json");
	}
}
