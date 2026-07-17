package com.ironhub.modules.quests;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only quest tracker: states from the client quest API via
 * AccountState, quest points, search + state filter. Optimal ordering and
 * per-quest requirement chains arrive with the quest-order data pack.
 * See DESIGN.md §3.2.
 */
@Slf4j
@Singleton
public class QuestsModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private QuestsTab tab;

	@Inject
	public QuestsModule(AccountState state, IronHubConfig config)
	{
		this.state = state;
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Quests";
	}

	@Override
	public boolean enabled()
	{
		return config.questProgression();
	}

	@Override
	public void startUp()
	{
	}

	@Override
	public void shutDown()
	{
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new QuestsTab(state, config.osrsTheme());
		}
		return tab;
	}

	/** A theme flip re-clothes the tab: the next buildTab dresses it fresh. */
	@Override
	public void onThemeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}
}
