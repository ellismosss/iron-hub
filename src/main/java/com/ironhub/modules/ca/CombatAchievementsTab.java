package com.ironhub.modules.ca;

import com.ironhub.modules.ca.CombatAchievementsModule.Tier;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.StatusGlyph;
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
import net.runelite.api.gameval.VarbitID;

/**
 * Combat achievements tab content: tier progress card (points toward the
 * next tier) + one row per tier with completed-task counts.
 */
class CombatAchievementsTab extends JPanel
{
	private final AccountState state;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel summary = new JLabel();
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final JPanel list = new JPanel();

	CombatAchievementsTab(AccountState state)
	{
		this.state = state;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.add(new SectionLabel("Combat achievements"));
		summary.setForeground(UiTokens.TEXT_PRIMARY);
		summary.setFont(summary.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		card.add(summary);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(bar);
		add(card);
		add(Box.createVerticalStrut(UiTokens.PAD));

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
		int points = state.getVarbit(VarbitID.CA_POINTS);
		Tier next = CombatAchievementsModule.nextTier(state);
		if (next != null)
		{
			int threshold = state.getVarbit(next.thresholdVarbit);
			summary.setText(points + "/" + threshold + " pts · next: " + next.name);
			bar.setFraction(threshold == 0 ? 0 : (double) points / threshold);
		}
		else
		{
			summary.setText(points + " pts · all tiers complete");
			bar.setFraction(points > 0 ? 1 : 0);
		}

		list.removeAll();
		for (Tier tier : CombatAchievementsModule.TIERS)
		{
			list.add(tierRow(tier));
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel tierRow(Tier tier)
	{
		boolean complete = state.getVarbit(tier.statusVarbit) >= 1;
		int completedTasks = state.getVarbit(tier.completedCountVarbit);

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));

		JLabel glyph = new JLabel(new StatusGlyph(complete ? Status.OWNED
			: completedTasks > 0 ? Status.AVAILABLE : Status.LOCKED));
		row.add(glyph);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		JLabel name = new JLabel(tier.name);
		name.setForeground(complete ? UiTokens.TEXT_BODY : completedTasks > 0
			? UiTokens.TEXT_PRIMARY : UiTokens.TEXT_MUTED);
		name.setFont(name.getFont().deriveFont(
			completedTasks > 0 && !complete ? Font.BOLD : Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		row.add(name);
		row.add(Box.createHorizontalGlue());

		JLabel count = new JLabel(completedTasks + " tasks done");
		count.setForeground(complete ? UiTokens.STATUS_OWNED : UiTokens.TEXT_MUTED);
		count.setFont(count.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		row.add(count);
		return row;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
