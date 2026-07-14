package com.ironhub.modules.loadoutlab;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.ui.UiTokens;
import com.loadoutlab.LoadoutLabPlugin;
import java.awt.BorderLayout;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;

/**
 * Loadout Lab (github.com/ajkatz/runelite-loadout-lab, BSD-2-Clause,
 * imported whole per user direction) as an Iron Hub module: exact-DPS
 * best-in-slot sets from owned gear per enemy and combat style. The
 * upstream code lives untouched under {@code com.loadoutlab} — this
 * wrapper only drives its lifecycle, registers its event subscribers,
 * and mounts its panel as a module tab instead of a sidebar button.
 */
@Slf4j
@Singleton
public class LoadoutLabModule implements IronHubModule
{
	private final LoadoutLabPlugin lab;
	private final EventBus eventBus;
	private final IronHubConfig config;
	private JPanel holder;
	private boolean started;

	@Inject
	public LoadoutLabModule(LoadoutLabPlugin lab, EventBus eventBus, IronHubConfig config)
	{
		this.lab = lab;
		this.eventBus = eventBus;
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Loadout Lab";
	}

	@Override
	public boolean enabled()
	{
		return config.loadoutLab();
	}

	@Override
	public void startUp()
	{
		lab.setPanelReadyCallback(() -> SwingUtilities.invokeLater(this::mountPanel));
		eventBus.register(lab);
		lab.startUp();
		started = true;
	}

	@Override
	public void shutDown()
	{
		if (started)
		{
			eventBus.unregister(lab);
			lab.shutDown();
			started = false;
		}
		holder = null;
	}

	@Override
	public JComponent buildTab()
	{
		if (holder == null)
		{
			holder = new JPanel(new BorderLayout());
			holder.setBackground(UiTokens.PANEL_BG);
			mountPanel();
		}
		return holder;
	}

	/** The lab panel arrives async (its ~3MB dataset parses off-thread). */
	private void mountPanel()
	{
		if (holder == null)
		{
			return;
		}
		holder.removeAll();
		if (lab.getPanel() != null)
		{
			holder.add(lab.getPanel(), BorderLayout.CENTER);
		}
		else
		{
			JLabel loading = new JLabel("Loading gear dataset...", JLabel.CENTER);
			loading.setForeground(UiTokens.TEXT_MUTED);
			holder.add(loading, BorderLayout.CENTER);
		}
		holder.revalidate();
		holder.repaint();
	}
}
