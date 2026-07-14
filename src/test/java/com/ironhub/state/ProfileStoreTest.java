package com.ironhub.state;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
}
