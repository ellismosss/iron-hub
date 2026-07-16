package com.ironhub.modules.designlab;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import java.awt.BorderLayout;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Development-stage test surface for the OSRS "stonework" design system
 * (design/OSRS-SKIN.md): renders the atom gallery in the real sidebar so the
 * skin can be judged in-client before any module adopts it. Follows the
 * "OSRS skin theme" setting, so flipping it compares the two clothings.
 */
@Singleton
public class DesignLabModule implements IronHubModule
{
	private final IronHubConfig config;
	private final EventBus eventBus;
	private JPanel holder;

	@Inject
	public DesignLabModule(IronHubConfig config, EventBus eventBus)
	{
		this.config = config;
		this.eventBus = eventBus;
	}

	@Override
	public String name()
	{
		return "Design lab";
	}

	@Override
	public boolean enabled()
	{
		return config.designLab();
	}

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
		}
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		holder = null;
	}

	@Override
	public JComponent buildTab()
	{
		if (holder == null)
		{
			holder = new JPanel(new BorderLayout());
			holder.setOpaque(false);
			rebuild();
		}
		return holder;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("ironhub".equals(event.getGroup()) && "osrsTheme".equals(event.getKey()) && holder != null)
		{
			SwingUtilities.invokeLater(this::rebuild);
		}
	}

	/** EDT only — a tab rebuild off the EDT races the listener and doubles rows. */
	private void rebuild()
	{
		holder.removeAll();
		holder.add(new DesignLabTab(config.osrsTheme()), BorderLayout.NORTH);
		holder.revalidate();
		holder.repaint();
	}
}
