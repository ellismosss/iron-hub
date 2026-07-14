package com.ironhub.modules.slayer;

import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * Slayer tab content: current task card (name + remaining) and
 * streak / points rows. Advisors arrive with the task-rates pack.
 */
class SlayerTab extends JPanel
{
	private final AccountState state;
	private final SlayerOptimizerModule module;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel task = new JLabel();
	private final JLabel remaining = new JLabel();
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final JPanel stats = new JPanel();

	SlayerTab(AccountState state, SlayerOptimizerModule module)
	{
		this.state = state;
		this.module = module;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.add(new SectionLabel("Current task"));

		JPanel line = new JPanel();
		line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);
		task.setForeground(UiTokens.TEXT_PRIMARY);
		task.setFont(task.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		task.setMinimumSize(new Dimension(0, 0));
		line.add(task);
		line.add(Box.createHorizontalGlue());
		remaining.setForeground(UiTokens.STATUS_AVAILABLE);
		remaining.setFont(remaining.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		line.add(remaining);
		card.add(line);
		add(card);
		add(Box.createVerticalStrut(UiTokens.PAD));

		stats.setLayout(new BoxLayout(stats, BoxLayout.Y_AXIS));
		stats.setBackground(UiTokens.PANEL_BG);
		stats.setAlignmentX(LEFT_ALIGNMENT);
		add(stats);
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
		int left = module.remaining();
		if (left > 0)
		{
			String name = module.taskName();
			task.setText(name.isEmpty() ? "Task in progress" : name);
			remaining.setText(left + " left");
		}
		else
		{
			task.setText("No task");
			remaining.setText("");
		}

		stats.removeAll();
		stats.add(statRow("Task streak", String.valueOf(module.streak())));
		stats.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		stats.add(statRow("Slayer points", String.valueOf(module.points())));
		stats.revalidate();
		stats.repaint();
	}

	private JPanel statRow(String label, String value)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));

		JLabel name = new JLabel(label);
		name.setForeground(UiTokens.TEXT_BODY);
		name.setFont(name.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		row.add(name);
		row.add(Box.createHorizontalGlue());
		JLabel val = new JLabel(value);
		val.setForeground(UiTokens.TEXT_PRIMARY);
		val.setFont(val.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		row.add(val);
		return row;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
