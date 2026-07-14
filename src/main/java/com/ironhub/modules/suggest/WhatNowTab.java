package com.ironhub.modules.suggest;

import com.ironhub.modules.suggest.WhatNowModule.Suggestion;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.ChipRow;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.SuggestionCard;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * What Now tab: time budget chips + ranked, explainable suggestions.
 */
class WhatNowTab extends JPanel
{
	private static final int[] BUDGETS = {5, 30, 60, 180};

	private final AccountState state;
	private final WhatNowModule.Packs packs;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final ChipRow time = new ChipRow("5m", "30m", "1h", "2h+");
	private final JPanel list = new JPanel();

	WhatNowTab(AccountState state, WhatNowModule.Packs packs)
	{
		this.state = state;
		this.packs = packs;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(new SectionLabel("What now?"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		time.setSelected(2);
		time.onChange(i -> rebuild());
		add(time);
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
		list.removeAll();
		List<Suggestion> suggestions =
			WhatNowModule.suggest(state, packs, BUDGETS[time.getSelected()]);
		if (suggestions.isEmpty())
		{
			JLabel none = new JLabel("Nothing urgent — pick a goal or go touch grass.");
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			none.setAlignmentX(LEFT_ALIGNMENT);
			list.add(none);
		}
		int rank = 1;
		for (Suggestion suggestion : suggestions)
		{
			list.add(new SuggestionCard(rank++, suggestion.title,
				"~" + suggestion.minutes + " min", suggestion.why));
			list.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
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
