package com.ironhub.ui;

import java.awt.BorderLayout;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.PluginPanel;

/**
 * Root side panel: dashboard home plus a searchable navigation list of
 * module tabs. Each module contributes a tab component.
 */
@Singleton
public class IronHubPanel extends PluginPanel
{
	@Inject
	public IronHubPanel()
	{
		setLayout(new BorderLayout());
		// TODO: dashboard (account score, next best upgrades, dailies outstanding,
		// active goal strip) + module navigation
		add(new JLabel("Iron Hub — coming soon", SwingConstants.CENTER), BorderLayout.CENTER);
	}
}
