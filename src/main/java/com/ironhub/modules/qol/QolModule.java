package com.ironhub.modules.qol;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.QolPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.Status;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * Account QoL unlock checklist (DESIGN.md §3.5): owned detection via item
 * ownership, availability via the shared requirement graph. First UI
 * consumer of Requirement.missing().
 */
@Slf4j
@Singleton
public class QolModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final DataPack dataPack;
	private final javax.inject.Provider<com.ironhub.modules.goals.GoalPlannerModule> planner; // null in tests
	private QolTab tab;

	@Inject
	public QolModule(AccountState state, IronHubConfig config, DataPack dataPack,
		javax.inject.Provider<com.ironhub.modules.goals.GoalPlannerModule> planner)
	{
		this.state = state;
		this.config = config;
		this.dataPack = dataPack;
		this.planner = planner;
	}

	/** The current plan already obtains this item under some OTHER goal
	 *  (gear chart, supplies …) — the affordance says so instead of
	 *  offering a duplicate (Luke: Ava's assembler was offered twice). */
	boolean planWantsItem(int itemId)
	{
		if (planner == null)
		{
			return false;
		}
		try
		{
			return planner.get().planWantsItem(itemId);
		}
		catch (RuntimeException e)
		{
			return false; // provider may fail in odd lifecycles — never block the tab
		}
	}

	@Override
	public String name()
	{
		return "QoL checklist";
	}

	@Override
	public boolean enabled()
	{
		return config.qolChecklist();
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
			tab = new QolTab(state, dataPack.load("qol", QolPack.class),
				config.osrsTheme(), this::planWantsItem);
		}
		return tab;
	}

	/** A theme flip drops the tab; the host rebuilds it in the new clothes. */
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

	/** Owned > available (reqs met) > locked. Static for unit testing. */
	public static Status status(AccountState state, QolPack.Unlock unlock)
	{
		if (unlock.getItemIds().stream().anyMatch(id -> state.ownedCount(id) > 0))
		{
			return Status.OWNED;
		}
		return requirement(unlock).isMet(state) ? Status.AVAILABLE : Status.LOCKED;
	}

	/** First blocking requirement line for locked rows, or null. */
	static String blockingLine(AccountState state, QolPack.Unlock unlock)
	{
		List<Requirement> missing = requirement(unlock).missing(state);
		return missing.isEmpty() ? null : missing.get(0).describe();
	}

	private static Requirement requirement(QolPack.Unlock unlock)
	{
		return Requirements.allOf(unlock.getRequirements().stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}
}
