package com.ironhub.modules;

import javax.swing.JComponent;

/**
 * A self-contained Iron Hub feature module. Each module owns its panel tab,
 * overlays and persistence, and reads shared account data from AccountState.
 */
public interface IronHubModule
{
	String name();

	void startUp();

	void shutDown();

	/**
	 * Whether the module's config toggle is on. Disabled modules are not
	 * started and their navigation rows are inert.
	 */
	default boolean enabled()
	{
		return true;
	}

	/**
	 * The module's panel tab content (shown under a nav header), or null
	 * while the module has no tab yet. Called lazily on the EDT when the
	 * module is first opened; implementations should cache.
	 */
	default JComponent buildTab()
	{
		return null;
	}
}
