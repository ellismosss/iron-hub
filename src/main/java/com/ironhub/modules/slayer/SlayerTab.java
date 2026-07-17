package com.ironhub.modules.slayer;

import com.ironhub.state.AccountState;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Slayer tab content in the OSRS stonework skin: current task (name +
 * remaining) and streak / points stat rows. Frameless — the host's header
 * plate names the module. Advisors arrive with the task-rates pack.
 */
class SlayerTab extends JPanel
{
	private final AccountState state;
	private final SlayerOptimizerModule module;
	private final OsrsTheme theme;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final JPanel content = new JPanel();

	SlayerTab(AccountState state, SlayerOptimizerModule module, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	void rebuild()
	{
		content.removeAll();
		content.add(section("Current task"));

		int left = module.remaining();
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.X_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		if (left > 0)
		{
			String name = module.taskName();
			card.add(new OsrsLabel(name.isEmpty() ? "Task in progress" : name,
				OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned().squeezable());
			card.add(Box.createHorizontalGlue());
			card.add(new OsrsLabel(left + " left", OsrsSkin.VALUE, OsrsSkin.boldFont()));
		}
		else
		{
			card.add(new OsrsLabel("No task", OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned());
			card.add(Box.createHorizontalGlue());
		}
		cap(card);
		content.add(card);

		content.add(Box.createVerticalStrut(4));
		content.add(statRow("Task streak", String.valueOf(module.streak())));
		content.add(Box.createVerticalStrut(4));
		content.add(statRow("Slayer points", String.valueOf(module.points())));
		content.revalidate();
		content.repaint();
	}

	/** Section header in the skin grammar (the DailiesNewTab pattern). */
	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** A label · value line in a stone box (the stat-row grammar). */
	private JComponent statRow(String label, String value)
	{
		StonePanel row = new StonePanel(theme);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new OsrsLabel(label, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		row.add(OsrsLabel.value(value));
		cap(row);
		return row;
	}

	private static void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
