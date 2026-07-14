package com.ironhub.modules.bank;

import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.SearchField;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Bank tab content (frame 2f, search section): search the last bank
 * snapshot from anywhere — icon · name · ×count rows, with a faint
 * "snapshot from last bank visit" timestamp line.
 */
class BankTab extends JPanel
{
	private static final int MAX_RESULTS = 50;

	private final AccountState state;
	private final ItemManager itemManager; // null in unit tests — icons skipped
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final SearchField search = new SearchField("Search bank…");
	private final JLabel snapshotLine = new JLabel();
	private final JPanel list = new JPanel();

	BankTab(AccountState state, ItemManager itemManager)
	{
		this.state = state;
		this.itemManager = itemManager;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(search);
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		snapshotLine.setForeground(UiTokens.TEXT_FAINT);
		snapshotLine.setFont(snapshotLine.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		snapshotLine.setAlignmentX(LEFT_ALIGNMENT);
		add(snapshotLine);
		add(Box.createVerticalStrut(UiTokens.PAD));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(UiTokens.PANEL_BG);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

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
		list.removeAll();
		Map<Integer, Integer> bank = state.getBankSnapshot();

		if (bank.isEmpty())
		{
			snapshotLine.setText("no snapshot yet");
			list.add(faintLine("Open your bank once to take a snapshot."));
		}
		else
		{
			snapshotLine.setText("snapshot from last bank visit · "
				+ relativeTime(System.currentTimeMillis() - state.getBankTimestamp()));

			List<Integer> matches = bank.keySet().stream()
				.filter(id -> matches(state.itemName(id), search.getText()))
				.sorted(Comparator.comparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)))
				.collect(Collectors.toList());

			for (Integer id : matches.subList(0, Math.min(matches.size(), MAX_RESULTS)))
			{
				list.add(itemRow(id, bank.get(id)));
				list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			if (matches.size() > MAX_RESULTS)
			{
				list.add(faintLine("+ " + (matches.size() - MAX_RESULTS) + " more — refine your search"));
			}
			else if (matches.isEmpty())
			{
				list.add(faintLine("No banked items match."));
			}
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel itemRow(int itemId, int quantity)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));

		JLabel icon = new JLabel();
		Dimension iconSize = new Dimension(UiTokens.TILE_ICON_SIZE, UiTokens.TILE_ICON_SIZE);
		icon.setPreferredSize(iconSize);
		icon.setMinimumSize(iconSize);
		icon.setMaximumSize(iconSize);
		if (itemManager != null)
		{
			AsyncBufferedImage sprite = itemManager.getImage(itemId, quantity, quantity > 1);
			icon.setIcon(new ImageIcon(sprite));
			sprite.onLoaded(icon::repaint);
		}
		row.add(icon);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		String name = state.itemName(itemId);
		JLabel nameLabel = new JLabel(name);
		nameLabel.setForeground(UiTokens.TEXT_BODY);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		nameLabel.setToolTipText(name);
		nameLabel.setMinimumSize(new Dimension(0, 0));
		row.add(nameLabel);
		row.add(Box.createHorizontalGlue());

		JLabel count = new JLabel("×" + QuantityFormatter.quantityToStackSize(quantity));
		count.setForeground(UiTokens.TEXT_PRIMARY);
		count.setFont(count.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		row.add(count);
		return row;
	}

	private JLabel faintLine(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(UiTokens.TEXT_FAINT);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	/** Filter predicate — static for direct unit testing. */
	static boolean matches(String itemName, String query)
	{
		return query.trim().isEmpty()
			|| itemName.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
	}

	/** "just now", "5 min ago", "2 h ago", "3 d ago" — static for testing. */
	static String relativeTime(long millisAgo)
	{
		long minutes = millisAgo / 60_000;
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + " h ago";
		}
		return (hours / 24) + " d ago";
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
