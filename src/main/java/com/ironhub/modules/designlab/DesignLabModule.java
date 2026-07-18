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
	private int view; // 0 = atom gallery, 1 = Goals hub mockup (GOALS-V2 §6)

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
		com.ironhub.ui.osrs.OsrsTheme theme = config.osrsTheme();
		JPanel column = new JPanel();
		column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
		column.setOpaque(false);

		com.ironhub.ui.osrs.StoneChipRow views =
			new com.ironhub.ui.osrs.StoneChipRow(theme, true, "Atoms", "Goals hub");
		views.setSelected(view);
		views.onChange(index ->
		{
			view = index;
			SwingUtilities.invokeLater(this::rebuild);
		});
		JPanel chips = new JPanel(new BorderLayout());
		chips.setOpaque(false);
		chips.setBorder(new javax.swing.border.EmptyBorder(4, 4, 2, 4));
		chips.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		chips.add(views);
		column.add(chips);

		JComponent content = view == 0 ? new DesignLabTab(theme) : new GoalsHubMockup(theme);
		content.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		column.add(content);

		holder.removeAll();
		holder.add(column, BorderLayout.NORTH);
		holder.revalidate();
		holder.repaint();
	}
}
