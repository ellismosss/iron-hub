package com.ironhub.modules;

/**
 * A self-contained Iron Hub feature module. Each module owns its panel tab,
 * overlays and persistence, and reads shared account data from AccountState.
 */
public interface IronHubModule
{
	String name();

	void startUp();

	void shutDown();
}
