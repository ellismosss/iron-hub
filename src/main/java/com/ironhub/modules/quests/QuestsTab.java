package com.ironhub.modules.quests;

import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.ChipRow;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.util.LinkBrowser;

/**
 * Quests tab content: summary card (QP + completion bar), search, state
 * filter chips, and one shared list row per quest.
 */
class QuestsTab extends JPanel
{
	static final String[] FILTERS = {"All", "Active", "Todo", "Done"};

	private final AccountState state;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel summary = new JLabel();
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final SearchField search = new SearchField("Search quests…");
	private final ChipRow filters = new ChipRow(FILTERS);
	private final JPanel list = new JPanel();

	QuestsTab(AccountState state)
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
		SectionLabel label = new SectionLabel("Quest points");
		card.add(label);
		summary.setForeground(UiTokens.TEXT_PRIMARY);
		summary.setFont(summary.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		card.add(summary);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(bar);
		add(card);
		add(Box.createVerticalStrut(UiTokens.PAD));

		add(search);
		add(Box.createVerticalStrut(UiTokens.PAD));
		add(filters);
		add(Box.createVerticalStrut(UiTokens.PAD));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(UiTokens.PANEL_BG);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		filters.onChange(i -> rebuild());
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		List<Quest> quests = Arrays.stream(Quest.values())
			.filter(q -> matches(q, state.getQuestState(q), FILTERS[filters.getSelected()], search.getText()))
			.sorted(Comparator.comparing(Quest::getName))
			.collect(Collectors.toList());

		long done = Arrays.stream(Quest.values())
			.filter(q -> state.getQuestState(q) == QuestState.FINISHED)
			.count();
		summary.setText(state.getQuestPoints() + " QP · " + done + "/" + Quest.values().length + " complete");
		bar.setFraction((double) done / Quest.values().length);

		list.removeAll();
		for (Quest quest : quests)
		{
			list.add(row(quest));
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	private ListRow row(Quest quest)
	{
		IconButton wiki = IconButton.wiki(() ->
			LinkBrowser.browse("https://oldschool.runescape.wiki/w/" + quest.getName().replace(' ', '_')));
		switch (state.getQuestState(quest))
		{
			case FINISHED:
				return ListRow.owned(quest.getName(), wiki);
			case IN_PROGRESS:
				return ListRow.available(quest.getName(), wiki);
			default:
				return ListRow.locked(quest.getName(), wiki);
		}
	}

	/** Filter predicate — static for direct unit testing. */
	static boolean matches(Quest quest, QuestState questState, String filter, String query)
	{
		if (!query.trim().isEmpty()
			&& !quest.getName().toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT)))
		{
			return false;
		}
		switch (filter)
		{
			case "Active":
				return questState == QuestState.IN_PROGRESS;
			case "Todo":
				return questState == QuestState.NOT_STARTED;
			case "Done":
				return questState == QuestState.FINISHED;
			default:
				return true;
		}
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
