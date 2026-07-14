package com.ironhub.modules.diaries;

import com.ironhub.modules.diaries.DiariesModule.DiaryRegion;
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
 * Diaries tab content: overall tier completion card + one row per region
 * with its four tier markers (E M H E) colored by completion.
 */
class DiariesTab extends JPanel
{
	private static final String[] TIER_LETTERS = {"E", "M", "H", "E"};
	private static final String[] TIER_NAMES = {"Easy", "Medium", "Hard", "Elite"};

	private final AccountState state;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel summary = new JLabel();
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final JPanel list = new JPanel();

	DiariesTab(AccountState state)
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
		card.add(new SectionLabel("Diary tiers"));
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
		int total = DiariesModule.REGIONS.length * 4;
		int done = DiariesModule.totalTiersComplete(state);
		summary.setText(done + "/" + total + " tiers complete");
		bar.setFraction((double) done / total);

		list.removeAll();
		for (DiaryRegion region : DiariesModule.REGIONS)
		{
			list.add(regionRow(region));
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel regionRow(DiaryRegion region)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));

		JLabel name = new JLabel(region.name);
		name.setForeground(UiTokens.TEXT_BODY);
		name.setFont(name.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		name.setToolTipText(tierTooltip(region));
		name.setMinimumSize(new Dimension(0, 0));
		row.add(name);
		row.add(Box.createHorizontalGlue());

		for (int tier = 0; tier < 4; tier++)
		{
			boolean complete = state.getVarbit(region.tierVarbits[tier]) >= 1;
			JLabel letter = new JLabel(TIER_LETTERS[tier]);
			letter.setForeground(complete ? UiTokens.STATUS_OWNED : UiTokens.TEXT_FAINT);
			letter.setFont(letter.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL));
			letter.setToolTipText(TIER_NAMES[tier] + (complete ? " — complete" : " — incomplete"));
			row.add(letter);
			if (tier < 3)
			{
				row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
		}
		return row;
	}

	private String tierTooltip(DiaryRegion region)
	{
		StringBuilder tip = new StringBuilder("<html>").append(region.name);
		for (int tier = 0; tier < 4; tier++)
		{
			boolean complete = state.getVarbit(region.tierVarbits[tier]) >= 1;
			tip.append("<br>").append(TIER_NAMES[tier]).append(complete ? " — complete" : " — incomplete");
		}
		return tip.append("</html>").toString();
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
