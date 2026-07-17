package com.ironhub.modules.supplies;

import com.ironhub.modules.supplies.SuppliesRunwayModule.Runway;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;

/**
 * Runway tab content in the OSRS stonework skin: consumables sorted
 * shortest-runway-first; rows inside the warning threshold render red
 * with the hours left. Frameless — the host's header plate names it.
 */
class RunwayTab extends JPanel
{
	private final AccountState state;
	private final ItemManager itemManager; // unused until restock links; kept for symmetry
	private final SuppliesRunwayModule module;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final JPanel list = new JPanel();

	RunwayTab(AccountState state, ItemManager itemManager, SuppliesRunwayModule module, OsrsTheme theme)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.module = module;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		list.removeAll();
		Map<Integer, Runway> runways = SuppliesRunwayModule.compute(state);
		// no title of its own: the host's stone header plate names the module
		list.add(Box.createVerticalStrut(4));
		if (runways.isEmpty())
		{
			JPanel holder = new JPanel();
			holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
			holder.setOpaque(false);
			holder.setAlignmentX(LEFT_ALIGNMENT);
			holder.add(OsrsLabel.wrapped("Consumption rates build up as you play.",
				195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
			holder.add(Box.createHorizontalGlue());
			cap(holder);
			list.add(holder);
		}
		else
		{
			// the rows sit inside one notched frame, checklist-style
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			for (Runway runway : runways.values())
			{
				group.add(row(runway));
			}
			cap(group);
			list.add(group);
		}
		list.revalidate();
		list.repaint();
	}

	/** One consumable: name (+ hours left when inside the warning window). */
	private JComponent row(Runway runway)
	{
		String name = state.itemName(runway.itemId);
		String hours = SuppliesRunwayModule.formatHours(runway.hoursLeft());
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		OsrsLabel label;
		if (runway.hoursLeft() < module.warningHours())
		{
			label = new OsrsLabel(name, UiTokens.STATUS_WARNING, OsrsSkin.font());
			row.add(label.leftAligned().squeezable());
			row.add(Box.createHorizontalGlue());
			row.add(new OsrsLabel(hours + " left", UiTokens.STATUS_WARNING, OsrsSkin.font()));
		}
		else
		{
			label = new OsrsLabel(name, OsrsSkin.VALUE, OsrsSkin.font());
			String tip = name + " — " + hours + " of stock at your usage rate";
			label.setToolTipText(tip);
			row.setToolTipText(tip);
			row.add(label.leftAligned().squeezable());
			row.add(Box.createHorizontalGlue());
		}
		cap(row);
		return row;
	}

	private void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
