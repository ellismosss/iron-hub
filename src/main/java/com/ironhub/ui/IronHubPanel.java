package com.ironhub.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/**
 * Root side panel: dashboard home (frame 1b) plus a searchable navigation
 * list of module tabs (frame 1c). Navigation depth ≤ 2 — module detail
 * screens are added as their modules are implemented.
 */
@Singleton
public class IronHubPanel extends PluginPanel
{
	private static final String CARD_DASHBOARD = "dashboard";
	private static final String CARD_MODULES = "modules";

	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);

	@Inject
	public IronHubPanel()
	{
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(0, 0, 0, 0)); // design owns all padding
		setBackground(UiTokens.PANEL_BG);

		cardPanel.setBackground(UiTokens.PANEL_BG);
		cardPanel.add(new DashboardPanel(this::showModules), CARD_DASHBOARD);
		cardPanel.add(new ModuleNavPanel(this::showDashboard), CARD_MODULES);
		add(cardPanel, BorderLayout.NORTH);
	}

	public void showDashboard()
	{
		cards.show(cardPanel, CARD_DASHBOARD);
	}

	public void showModules()
	{
		cards.show(cardPanel, CARD_MODULES);
	}
}
