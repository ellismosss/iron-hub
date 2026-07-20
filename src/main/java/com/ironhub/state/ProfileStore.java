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
 * (DESIGN.md §2.3). Callers hand over a deep-copied snapshot, so both
 * serialization and the file write run on the executor — never on the
 * game thread (the 2026-07-17 freeze audit found toJson of a mature
 * account measurable per call, and persist fires per kill).
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
		File file = stateFile(profile);
		executor.execute(() ->
		{
			// serialize off the caller's thread too — the caller hands over a
			// deep-copied PersistedState, so toJson of the whole account (bank,
			// item names, loot logs) never runs on the game thread
			String json = gson.toJson(state);
			try
			{
				// write-then-rename so a crash mid-write can never tear state.json:
				// the file carries goal seeds, records and saved loadouts, none of
				// which are re-derivable from the client (2026-07-20 audit)
				Files.createDirectories(file.getParentFile().toPath());
				java.nio.file.Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
				Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
				try
				{
					Files.move(tmp, file.toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				}
				catch (java.nio.file.AtomicMoveNotSupportedException e)
				{
					Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
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
			// keep the damaged file — the next save would otherwise overwrite
			// it and make the loss permanent
			try
			{
				Files.move(file.toPath(), file.toPath().resolveSibling(file.getName() + ".bak"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				log.warn("corrupt iron-hub state at {} — kept as state.json.bak, starting fresh", file, e);
			}
			catch (IOException moveError)
			{
				log.warn("corrupt iron-hub state at {} — starting fresh", file, e);
			}
			return new PersistedState();
		}
	}

	File stateFile(long profile)
	{
		return new File(new File(baseDir, Long.toString(profile)), "state.json");
	}
}
