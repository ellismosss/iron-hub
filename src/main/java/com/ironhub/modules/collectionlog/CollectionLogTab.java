package com.ironhub.modules.collectionlog;

import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Comparator;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * Collection log tab (frame 2c, overall card): slots progress from the
 * last log open, and per-source kill counts from loot tracking.
 */
class CollectionLogTab extends JPanel
{
	private static final int MAX_SOURCES = 15;

	private final AccountState state;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final JLabel summary = new JLabel();
	private final JLabel seenLine = new JLabel();
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final JPanel list = new JPanel();

	CollectionLogTab(AccountState state)
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
		card.add(new SectionLabel("Collection log"));
		summary.setForeground(UiTokens.TEXT_PRIMARY);
		summary.setFont(summary.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		card.add(summary);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(bar);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		seenLine.setForeground(UiTokens.TEXT_FAINT);
		seenLine.setFont(seenLine.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		seenLine.setAlignmentX(LEFT_ALIGNMENT);
		card.add(seenLine);
		add(card);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		add(new SectionLabel("Kill counts"));
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
		if (state.getCollectionLogTotal() > 0)
		{
			int slots = state.getCollectionLogSlots();
			int total = state.getCollectionLogTotal();
			summary.setText(slots + "/" + total + " slots · " + Math.round(100.0 * slots / total) + "%");
			bar.setFraction((double) slots / total);
			seenLine.setText("as of last log open · "
				+ Format.relativeTime(System.currentTimeMillis() - state.getCollectionLogSeenMs()));
		}
		else
		{
			summary.setText("not synced yet");
			bar.setFraction(0);
			seenLine.setText("Open your collection log once to sync.");
		}

		list.removeAll();
		List<java.util.Map.Entry<String, Integer>> sources = state.getKillCounts().entrySet().stream()
			.sorted(Comparator.comparingInt(e -> -e.getValue()))
			.limit(MAX_SOURCES)
			.collect(java.util.stream.Collectors.toList());
		if (sources.isEmpty())
		{
			JLabel none = new JLabel("Kill counts appear as you get drops.");
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			none.setAlignmentX(LEFT_ALIGNMENT);
			list.add(none);
		}
		for (java.util.Map.Entry<String, Integer> source : sources)
		{
			JPanel row = new JPanel();
			row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
			row.setBackground(UiTokens.CARD_BG);
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
				new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
			row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
			JLabel name = new JLabel(source.getKey());
			name.setForeground(UiTokens.TEXT_BODY);
			name.setFont(name.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
			name.setMinimumSize(new Dimension(0, 0));
			row.add(name);
			row.add(Box.createHorizontalGlue());
			JLabel kc = new JLabel(source.getValue() + " kc");
			kc.setForeground(UiTokens.TEXT_PRIMARY);
			kc.setFont(kc.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
			row.add(kc);
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
