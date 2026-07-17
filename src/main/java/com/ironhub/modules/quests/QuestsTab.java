package com.ironhub.modules.quests;

import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.util.LinkBrowser;

/**
 * Quests tab content in the OSRS stonework skin: summary card (QP +
 * completion bar), search, state filter chips, and one flat quest-list row
 * per quest — the game's own journal idiom (green done, orange in progress).
 */
class QuestsTab extends JPanel
{
	static final String[] FILTERS = {"All", "Active", "Todo", "Done"};

	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StonePanel card;
	private final StoneProgressBar bar;
	private final StoneTextField search;
	private final StoneChipRow filters;
	private final JPanel list = new JPanel();

	QuestsTab(AccountState state, OsrsTheme theme)
	{
		this.state = state;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		add(section("Quest points"));
		card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		bar = new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, 0);
		add(pad(card));
		add(Box.createVerticalStrut(6));

		search = new StoneTextField(theme, "Search quests…");
		add(pad(search));
		add(Box.createVerticalStrut(4));
		filters = new StoneChipRow(theme, true, FILTERS);
		add(pad(filters));
		add(Box.createVerticalStrut(4));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		filters.onChange(i -> rebuild());
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
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
		card.removeAll();
		// OsrsLabel text is immutable — the card refills each rebuild
		card.add(new OsrsLabel(state.getQuestPoints() + " QP · " + done + "/"
			+ Quest.values().length + " complete", OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		card.add(Box.createVerticalStrut(3));
		bar.setFraction((double) done / Quest.values().length);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		card.add(bar);
		cap(card);

		list.removeAll();
		for (Quest quest : quests)
		{
			list.add(row(quest));
		}
		revalidate();
		repaint();
	}

	/** A quest as a flat colour-coded row. The wiki stays behind its own
	 *  compact W affordance — a whole-row click launching a browser is an
	 *  interaction change, not a clothing swap. */
	private JComponent row(Quest quest)
	{
		Color color;
		switch (state.getQuestState(quest))
		{
			case FINISHED:
				color = OsrsSkin.VALUE;
				break;
			case IN_PROGRESS:
				color = OsrsSkin.TITLE;
				break;
			default:
				color = OsrsSkin.MUTED;
				break;
		}

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(true);
		row.setBackground(theme.background);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));

		OsrsLabel name = new OsrsLabel(quest.getName(), color, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(quest.getName());
		row.setToolTipText(quest.getName());
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(wikiGlyph(quest.getName()));
		cap(row);
		return row;
	}

	/** A small W affordance in skin colours — faint until hovered. */
	private static OsrsLabel wikiGlyph(String questName)
	{
		OsrsLabel glyph = new OsrsLabel("W", OsrsSkin.FAINT, OsrsSkin.font());
		glyph.setToolTipText("Open the wiki page");
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setColor(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				glyph.setColor(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
					+ questName.replace(' ', '_'));
			}
		});
		return glyph;
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

	// ── layout helpers (the DailiesNewTab/FarmingTab grammar) ─────────

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

	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new java.awt.BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, 4, 0, 4));
		holder.add(inner);
		cap(holder);
		return holder;
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
