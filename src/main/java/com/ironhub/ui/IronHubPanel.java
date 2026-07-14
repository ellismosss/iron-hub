package com.ironhub.ui;

import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.HubScrollPane;
import com.ironhub.ui.components.NavHeader;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;

/**
 * Root side panel: dashboard home (frame 1b), searchable module navigation
 * (frame 1c), and one card per opened module tab. Navigation depth ≤ 2:
 * dashboard → module → detail.
 *
 * Unwrapped PluginPanel: each card keeps its nav header fixed and scrolls
 * only its content (the client's wrapped panel would scroll the back
 * button away and crawls at the default 1 px wheel increment).
 */
@Singleton
public class IronHubPanel extends PluginPanel
{
	private static final String CARD_DASHBOARD = "dashboard";
	private static final String CARD_MODULES = "modules";

	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final Map<String, IronHubModule> modulesByName;
	private final Map<String, Component> moduleWrappers = new HashMap<>();

	@Inject
	public IronHubPanel(Set<IronHubModule> modules, AccountState state, DataPack dataPack)
	{
		super(false);
		modulesByName = modules.stream()
			.collect(Collectors.toMap(IronHubModule::name, Function.identity()));

		setLayout(new BorderLayout());
		setBackground(UiTokens.PANEL_BG);

		cardPanel.setBackground(UiTokens.PANEL_BG);
		cardPanel.add(new HubScrollPane(new DashboardPanel(state, dataPack, this::openModule, this::showModules)), CARD_DASHBOARD);
		cardPanel.add(new ModuleNavPanel(this::showDashboard, this::openModule), CARD_MODULES);
		add(cardPanel, BorderLayout.CENTER);
	}

	public void showDashboard()
	{
		cards.show(cardPanel, CARD_DASHBOARD);
	}

	public void showModules()
	{
		cards.show(cardPanel, CARD_MODULES);
	}

	/** Open a module tab by nav name; rows without a live tab are inert. */
	public void openModule(String name)
	{
		IronHubModule module = modulesByName.get(name);
		if (module == null || !module.enabled())
		{
			return;
		}
		Component wrapper = moduleWrappers.get(name);
		if (wrapper == null)
		{
			JComponent tab = module.buildTab();
			if (tab == null)
			{
				return;
			}
			wrapper = wrap(name, tab);
			moduleWrappers.put(name, wrapper);
			cardPanel.add(wrapper, "module:" + name);
		}
		cards.show(cardPanel, "module:" + name);
	}

	/** Drop a module's cached card (module was shut down / re-enabled). */
	public void invalidateModule(String name)
	{
		Component wrapper = moduleWrappers.remove(name);
		if (wrapper != null)
		{
			boolean showing = wrapper.isVisible();
			cardPanel.remove(wrapper);
			if (showing)
			{
				showDashboard();
			}
		}
	}

	private JPanel wrap(String name, JComponent tab)
	{
		// back header fixed at the top; only the tab content scrolls
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(UiTokens.PANEL_BG);
		wrapper.add(new NavHeader(name, this::showModules), BorderLayout.NORTH);
		wrapper.add(new HubScrollPane(tab), BorderLayout.CENTER);
		return wrapper;
	}
}
