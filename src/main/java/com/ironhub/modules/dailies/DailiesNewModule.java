package com.ironhub.modules.dailies;

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
 * The OSRS-skin migration pilot (design/OSRS-SKIN.md phase 4): the Dailies
 * tab re-clothed in the stonework system, kept BESIDE the classic one so the
 * two can be compared in-client. Same brain — this module owns no detection,
 * no run state, no overlays; it renders {@link DailiesNewTab} over the
 * injected {@link DailiesModule} and follows the osrsTheme setting. When
 * Luke signs off, the skinned tab replaces DailiesTab and this scaffold goes.
 */
@Singleton
public class DailiesNewModule implements IronHubModule
{
	private final DailiesModule brain;
	private final IronHubConfig config;
	private final EventBus eventBus; // null in unit tests

	private JPanel holder;
	private DailiesNewTab tab;

	@Inject
	public DailiesNewModule(DailiesModule brain, IronHubConfig config, EventBus eventBus)
	{
		this.brain = brain;
		this.config = config;
		this.eventBus = eventBus;
	}

	@Override
	public String name()
	{
		return "Dailies (New)";
	}

	@Override
	public boolean enabled()
	{
		return config.dailiesNew();
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
		if (tab != null)
		{
			tab.dispose();
			tab = null;
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

	/** A theme flip re-clothes the tab; everything else it hears itself. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (IronHubConfig.GROUP.equals(event.getGroup()) && "osrsTheme".equals(event.getKey())
			&& holder != null)
		{
			SwingUtilities.invokeLater(this::rebuild);
		}
	}

	/** EDT only — a tab rebuild off the EDT races the listener and doubles rows. */
	private void rebuild()
	{
		if (tab != null)
		{
			tab.dispose();
		}
		tab = new DailiesNewTab(brain, config.osrsTheme());
		holder.removeAll();
		holder.add(tab, BorderLayout.NORTH);
		holder.revalidate();
		holder.repaint();
	}
}
