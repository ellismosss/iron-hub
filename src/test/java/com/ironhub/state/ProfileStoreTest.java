package com.ironhub.state;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfileStoreTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void corruptStateFileStartsFresh() throws Exception
	{
		ProfileStore store = StateFixture.store(temp.getRoot());
		Files.createDirectories(store.stateFile(7L).getParentFile().toPath());
		Files.write(store.stateFile(7L).toPath(), "{not json!".getBytes(StandardCharsets.UTF_8));

		PersistedState state = store.load(7L);
		assertTrue(state.bank.isEmpty());
		assertTrue(state.unlocks.isEmpty());
	}

	@Test
	public void corruptStateFileIsKeptAsBackup() throws Exception
	{
		ProfileStore store = StateFixture.store(temp.getRoot());
		Files.createDirectories(store.stateFile(7L).getParentFile().toPath());
		Files.write(store.stateFile(7L).toPath(), "{not json!".getBytes(StandardCharsets.UTF_8));

		store.load(7L);

		// the damaged file must survive as .bak — the next save overwrites
		// state.json and would otherwise make the loss permanent
		java.io.File bak = new java.io.File(store.stateFile(7L).getParentFile(), "state.json.bak");
		assertTrue(bak.exists());
		assertEquals("{not json!",
			new String(Files.readAllBytes(bak.toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void saveLeavesNoTempFileBehind() throws Exception
	{
		ProfileStore store = StateFixture.store(temp.getRoot());
		PersistedState state = new PersistedState();
		state.unlocks.add("test_flag");

		store.save(7L, state); // fixture store runs the executor synchronously

		assertTrue(store.stateFile(7L).exists());
		assertFalse(new java.io.File(store.stateFile(7L).getParentFile(), "state.json.tmp").exists());
		PersistedState loaded = store.load(7L);
		assertTrue(loaded.unlocks.contains("test_flag"));
	}
}
