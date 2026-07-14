package com.ironhub.modules.supplies;

import com.ironhub.modules.supplies.SuppliesRunwayModule.Runway;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;

/**
 * Runway tab content: consumables sorted shortest-runway-first; rows
 * inside the warning threshold render red with the hours left.
 */
class RunwayTab extends JPanel
{
	private final AccountState state;
	private final ItemManager itemManager; // unused until restock links; kept for symmetry
	private final SuppliesRunwayModule module;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final JPanel list = new JPanel();

	RunwayTab(AccountState state, ItemManager itemManager, SuppliesRunwayModule module)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.module = module;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(new SectionLabel("Supplies runway"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(UiTokens.PANEL_BG);
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
		if (runways.isEmpty())
		{
			JLabel none = new JLabel("Consumption rates build up as you play.");
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			none.setAlignmentX(LEFT_ALIGNMENT);
			list.add(none);
		}
		for (Runway runway : runways.values())
		{
			String name = state.itemName(runway.itemId);
			String hours = SuppliesRunwayModule.formatHours(runway.hoursLeft());
			ListRow row;
			if (runway.hoursLeft() < module.warningHours())
			{
				row = ListRow.warning(name, hours + " left");
			}
			else
			{
				row = ListRow.owned(name);
				row.setToolTipText(name + " — " + hours + " of stock at your usage rate");
			}
			list.add(row);
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
