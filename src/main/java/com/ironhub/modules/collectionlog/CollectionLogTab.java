package com.ironhub.modules.collectionlog;

import com.ironhub.data.ClogPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.StatusGlyph;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Collection log tab (frame 2c grown into a Log Adviser-style ranking):
 * overall progress + sync status card, then every remaining activity
 * ranked by Time-To-Next-Slot. Rows expand into the activity's slots
 * (owned ticks, drop rates) and every slot's +/× assigns it to the Goal
 * planner as a {@code clog:<itemId>} goal — same grammar as the CA and
 * diaries tabs. Skipped activities sink to a recoverable section.
 */
class CollectionLogTab extends JPanel
{
	private static final int TOP_N = 30;
	private static final String[] SHOW_OPTIONS = {"All", "Combat", "Minigame", "Miscellaneous", "Slayer"};

	private final CollectionLogModule module;
	private final AccountState state;
	private final ItemManager itemManager; // null in headless tests
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::onStateChanged);

	// stats card
	private final JLabel slotsLine = new JLabel(" ");
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final JLabel syncLine = new JLabel(" ");

	// controls
	private final JTextField search = new SearchField("Search activities or items…");
	private final JComboBox<String> showFilter = new JComboBox<>(SHOW_OPTIONS);

	// content
	private final JPanel content = new JPanel();
	private final Set<Integer> expanded = new HashSet<>();
	private final Set<Integer> slayerActivities;
	private boolean showAll;
	private boolean skippedExpanded;
	private List<Object> lastFingerprint = List.of();

	CollectionLogTab(CollectionLogModule module, AccountState state, ItemManager itemManager)
	{
		this.module = module;
		this.state = state;
		this.itemManager = itemManager;

		// Activities that count as "Slayer" for the Show filter: anything
		// with a Slayer-level requirement, minus boat bounty-task entries
		// (sailing bounties, not slayer tasks) — Log Adviser's rule.
		Set<Integer> slayer = new HashSet<>();
		for (ClogPack.Activity a : module.pack().activities)
		{
			if (a.reqs.stream().anyMatch(r -> r.startsWith("skill:Slayer:"))
				&& !a.name.toLowerCase(Locale.ROOT).contains("bounty task"))
			{
				slayer.add(a.index);
			}
		}
		this.slayerActivities = slayer;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(buildStatsCard());
		add(Box.createVerticalStrut(UiTokens.PAD));

		add(search);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				rebuildContent();
			}

			public void removeUpdate(DocumentEvent e)
			{
				rebuildContent();
			}

			public void changedUpdate(DocumentEvent e)
			{
				rebuildContent();
			}
		});
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		showFilter.setFont(showFilter.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		showFilter.addActionListener(e -> rebuildContent());
		JPanel showRow = new JPanel(new BorderLayout(UiTokens.ROW_GAP, 0));
		showRow.setOpaque(false);
		showRow.setAlignmentX(LEFT_ALIGNMENT);
		showRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		JLabel showLabel = new JLabel("Show");
		showLabel.setForeground(UiTokens.TEXT_MUTED);
		showLabel.setFont(showLabel.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		showLabel.setPreferredSize(new Dimension(48, UiTokens.BUTTON_HEIGHT));
		showRow.add(showLabel, BorderLayout.WEST);
		showRow.add(showFilter, BorderLayout.CENTER);
		add(showRow);
		add(Box.createVerticalStrut(UiTokens.PAD));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		refreshStats();
		rebuildContent();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** AccountState notification: stats always; rows only when the row
	 *  model actually changed (xp drops fire this constantly). */
	private void onStateChanged()
	{
		refreshStats();
		List<Object> fingerprint = fingerprint();
		if (!fingerprint.equals(lastFingerprint))
		{
			rebuildContent();
		}
	}

	private List<Object> fingerprint()
	{
		List<Object> print = new ArrayList<>();
		print.add(state.getClogObtained().size());
		print.add(state.getClogSkipped());
		print.add(selectedClogGoals());
		print.add(state.getClogBaseline());
		for (ClogRanker.Ranked r : ranking())
		{
			print.add(r.activity.index);
			print.add(r.locked);
		}
		return print;
	}

	private Set<String> selectedClogGoals()
	{
		Set<String> ids = new HashSet<>();
		for (String goalId : state.getSelectedGoals())
		{
			if (goalId.startsWith("clog:"))
			{
				ids.add(goalId);
			}
		}
		return ids;
	}

	private boolean isGoal(int itemId)
	{
		return state.getSelectedGoals().contains("clog:" + itemId);
	}

	// ── stats card ────────────────────────────────────────────────────

	private JPanel buildStatsCard()
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER),
			new EmptyBorder(6, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD)));
		card.add(new SectionLabel("Collection log"));
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		slotsLine.setForeground(UiTokens.TEXT_PRIMARY);
		slotsLine.setFont(slotsLine.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		slotsLine.setAlignmentX(LEFT_ALIGNMENT);
		card.add(slotsLine);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(bar);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		syncLine.setFont(syncLine.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		syncLine.setAlignmentX(LEFT_ALIGNMENT);
		card.add(syncLine);
		return card;
	}

	private void refreshStats()
	{
		// The COLLECTION_COUNT varp is the game's own live count; the log
		// window title (recorded on open) supplies the game-truth total,
		// with the data pack's slot count as the pre-sync fallback.
		int varpCount = state.getVarp(VarPlayerID.COLLECTION_COUNT);
		int slots = varpCount > 0 ? varpCount : state.getCollectionLogSlots();
		int total = state.getCollectionLogTotal() > 0
			? state.getCollectionLogTotal() : module.pack().slots.size();
		slotsLine.setText(String.format(Locale.ROOT, "%,d/%,d slots · %d%%",
			slots, total, Math.round(100.0 * slots / Math.max(1, total))));
		bar.setFraction((double) slots / Math.max(1, total));

		if (state.getClogBaseline() < 0)
		{
			syncLine.setForeground(UiTokens.STATUS_AVAILABLE);
			syncLine.setText("Open the log and press Log Sync to import");
			syncLine.setToolTipText("Open your collection log in-game and press the Log Sync "
				+ "button in its header — every obtained slot imports in one click.");
		}
		else if (!module.inSync())
		{
			syncLine.setForeground(UiTokens.STATUS_AVAILABLE);
			syncLine.setText("New slots since last sync · press Log Sync");
			syncLine.setToolTipText("Your in-game slot count moved past the last full sync — "
				+ "open the collection log and press Log Sync to catch up.");
		}
		else
		{
			syncLine.setForeground(UiTokens.STATUS_OWNED);
			long syncedMs = state.getClogSyncedMs();
			syncLine.setText("Synced" + (syncedMs > 0
				? " · " + Format.relativeTime(System.currentTimeMillis() - syncedMs) : ""));
			syncLine.setToolTipText("Live drops keep the data current between full syncs.");
		}
	}

	// ── ranking ───────────────────────────────────────────────────────

	private List<ClogRanker.Ranked> ranking()
	{
		return ClogRanker.rank(module.pack(), state.getClogObtained(),
			state.getClogSkipped(), state);
	}

	private List<ClogRanker.Ranked> filtered(List<ClogRanker.Ranked> ranked)
	{
		String term = search.getText().trim().toLowerCase(Locale.ROOT);
		String show = (String) showFilter.getSelectedItem();
		List<ClogRanker.Ranked> out = new ArrayList<>();
		for (ClogRanker.Ranked r : ranked)
		{
			if (!matchesShow(r.activity, show))
			{
				continue;
			}
			if (!term.isEmpty() && !matchesTerm(r.activity, term))
			{
				continue;
			}
			out.add(r);
		}
		return out;
	}

	private boolean matchesShow(ClogPack.Activity activity, String show)
	{
		if (show == null || "All".equals(show))
		{
			return true;
		}
		if ("Slayer".equals(show))
		{
			return slayerActivities.contains(activity.index);
		}
		return activity.category.equalsIgnoreCase(show);
	}

	private static boolean matchesTerm(ClogPack.Activity activity, String term)
	{
		if (activity.name.toLowerCase(Locale.ROOT).contains(term))
		{
			return true;
		}
		for (ClogPack.Item item : activity.items)
		{
			if (item.name.toLowerCase(Locale.ROOT).contains(term))
			{
				return true;
			}
		}
		return false;
	}

	// ── content ───────────────────────────────────────────────────────

	private void rebuildContent()
	{
		lastFingerprint = fingerprint();
		content.removeAll();

		boolean searching = !search.getText().trim().isEmpty();
		List<ClogRanker.Ranked> visible = filtered(ranking());

		content.add(new SectionLabel("Next slots"));
		content.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		if (visible.isEmpty())
		{
			content.add(emptyLabel(searching
				? "Nothing matches. Skipped activities are listed below."
				: "Every rankable slot is obtained."));
		}

		int limit = searching || showAll ? visible.size() : Math.min(TOP_N, visible.size());
		for (int i = 0; i < limit; i++)
		{
			content.add(activityRow(visible.get(i), false));
			content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		if (limit < visible.size())
		{
			JLabel more = new JLabel("Show all (" + (visible.size() - limit) + " more)");
			more.setForeground(UiTokens.ACCENT);
			more.setFont(more.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			more.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			more.setAlignmentX(LEFT_ALIGNMENT);
			more.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					showAll = true;
					rebuildContent();
				}
			});
			content.add(more);
		}

		List<ClogRanker.Ranked> skipped = ClogRanker.rankSkipped(module.pack(),
			state.getClogObtained(), state.getClogSkipped(), state);
		if (!skipped.isEmpty())
		{
			content.add(Box.createVerticalStrut(UiTokens.PAD));
			JLabel header = new JLabel("SKIPPED (" + skipped.size() + ")"
				+ (skippedExpanded ? "" : " …"));
			header.setForeground(UiTokens.TEXT_MUTED);
			header.setFont(SectionLabel.letterSpaced(
				header.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
				UiTokens.LETTER_SPACING_LABEL));
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.setAlignmentX(LEFT_ALIGNMENT);
			header.setToolTipText("Activities you told the ranking to ignore — click to "
				+ (skippedExpanded ? "collapse" : "review and unskip"));
			header.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					skippedExpanded = !skippedExpanded;
					rebuildContent();
				}
			});
			content.add(header);
			if (skippedExpanded)
			{
				content.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
				for (ClogRanker.Ranked r : skipped)
				{
					content.add(activityRow(r, true));
					content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
				}
			}
		}

		content.revalidate();
		content.repaint();
	}

	// ── activity rows ─────────────────────────────────────────────────

	private JPanel activityRow(ClogRanker.Ranked ranked, boolean skippedRow)
	{
		ClogPack.Activity activity = ranked.activity;
		boolean open = expanded.contains(activity.index);

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.PAD_TIGHT, UiTokens.ROW_GAP, UiTokens.PAD_TIGHT, UiTokens.ROW_GAP)));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel titleLine = new JPanel();
		titleLine.setLayout(new BoxLayout(titleLine, BoxLayout.X_AXIS));
		titleLine.setOpaque(false);
		titleLine.setAlignmentX(LEFT_ALIGNMENT);
		if (ranked.display != null)
		{
			JLabel icon = new JLabel();
			icon.setToolTipText(ranked.display.name);
			itemIcon(ranked.display.itemId, icon::setIcon);
			titleLine.add(icon);
			titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		JLabel name = new JLabel(activity.name);
		name.setForeground(ranked.locked ? UiTokens.STATUS_LOCKED : UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setToolTipText(activity.name);
		name.setMinimumSize(new Dimension(0, 0)); // ellipsize before pushing the time out
		titleLine.add(name);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel time = new JLabel("~" + Format.hours(ranked.hours));
		time.setForeground(UiTokens.TEXT_PRIMARY);
		time.setFont(time.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		time.setToolTipText("Expected time to this activity's next slot at ironman rates");
		titleLine.add(time);
		if (ranked.display != null)
		{
			titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			titleLine.add(goalButton(ranked.display.itemId, ranked.display.name, activity));
		}
		row.add(titleLine);

		if (ranked.locked)
		{
			JLabel needs = new JLabel("Locked · needs " + ranked.missing);
			needs.setForeground(UiTokens.STATUS_LOCKED);
			needs.setFont(needs.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			needs.setToolTipText("Unmet requirements: " + ranked.missing);
			needs.setAlignmentX(LEFT_ALIGNMENT);
			row.add(needs);
		}
		if (ranked.display != null)
		{
			JLabel next = new JLabel("Next: " + ranked.display.name + " · "
				+ ranked.slotsLeft + "/" + ranked.slotsTotal + " left");
			next.setForeground(UiTokens.TEXT_MUTED);
			next.setFont(next.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			next.setAlignmentX(LEFT_ALIGNMENT);
			row.add(next);
		}

		if (open)
		{
			row.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			for (ClogPack.Item item : uniqueItems(activity))
			{
				row.add(slotLine(item, activity));
			}
		}

		MouseAdapter interaction = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					activityMenu(ranked, skippedRow, e);
				}
				else if (SwingUtilities.isLeftMouseButton(e))
				{
					if (!expanded.remove(activity.index))
					{
						expanded.add(activity.index);
					}
					rebuildContent();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					activityMenu(ranked, skippedRow, e);
				}
			}
		};
		row.addMouseListener(interaction);
		for (Component child : row.getComponents())
		{
			if (child != titleLine)
			{
				child.addMouseListener(interaction);
			}
		}

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** The activity's slots, deduped by item id (shared multi-source rows). */
	private static List<ClogPack.Item> uniqueItems(ClogPack.Activity activity)
	{
		Map<Integer, ClogPack.Item> unique = new LinkedHashMap<>();
		for (ClogPack.Item item : activity.items)
		{
			unique.putIfAbsent(item.itemId, item);
		}
		return new ArrayList<>(unique.values());
	}

	/** One slot inside an expanded activity: owned tick, sprite, name,
	 *  drop rate, and the +/× goal-planner toggle. */
	private JPanel slotLine(ClogPack.Item item, ClogPack.Activity activity)
	{
		boolean owned = state.getClogObtained().contains(item.itemId);
		JPanel line = new JPanel();
		line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);
		JLabel glyph = new JLabel(new StatusGlyph(owned ? Status.OWNED : Status.LOCKED));
		glyph.setToolTipText(owned ? "Obtained" : "Not obtained yet");
		line.add(glyph);
		line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel icon = new JLabel();
		itemIcon(item.itemId, icon::setIcon);
		line.add(icon);
		line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel name = new JLabel(item.name);
		name.setForeground(owned ? UiTokens.STATUS_OWNED : UiTokens.TEXT_BODY);
		name.setFont(name.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		name.setToolTipText(item.name);
		name.setMinimumSize(new Dimension(0, 0));
		line.add(name);
		line.add(Box.createHorizontalGlue());
		if (item.attempts > 0)
		{
			JLabel rate = new JLabel("1/" + Math.round(item.attempts));
			rate.setForeground(UiTokens.TEXT_FAINT);
			rate.setFont(rate.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			rate.setToolTipText("Expected attempts per drop");
			line.add(rate);
			line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		line.add(goalButton(item.itemId, item.name, activity));
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
		return line;
	}

	private IconButton goalButton(int itemId, String slotName, ClogPack.Activity activity)
	{
		boolean goal = isGoal(itemId);
		return new IconButton(goal ? "×" : "+",
			goal ? "Remove " + slotName + " from the Goal planner"
				: "Add " + slotName + " as a goal in the Goal planner",
			() -> toggleGoal(itemId, slotName, activity));
	}

	/** The '+' action: the slot joins the Goal planner as a "clog:" goal. */
	private void toggleGoal(int itemId, String slotName, ClogPack.Activity activity)
	{
		if (isGoal(itemId))
		{
			state.removeClogGoal(itemId);
		}
		else
		{
			state.addClogGoal(itemId, slotName, activity.name, activity.reqs);
			if (state.getClogObtained().contains(itemId))
			{
				// already in the log: prove the goal immediately
				state.setUnlocked("clogitem_" + itemId, true);
			}
		}
	}

	private void activityMenu(ClogRanker.Ranked ranked, boolean skippedRow, MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		if (ranked.display != null)
		{
			JMenuItem wiki = new JMenuItem("Open wiki page (" + ranked.display.name + ")");
			wiki.addActionListener(a -> LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
				+ ranked.display.name.replace(" ", "_").replace("'", "%27")));
			menu.add(wiki);
		}
		JMenuItem skip = new JMenuItem(skippedRow ? "Unskip activity" : "Skip activity");
		skip.addActionListener(a ->
			state.setClogSkipped(ranked.activity.index, !skippedRow));
		menu.add(skip);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private void itemIcon(int itemId, Consumer<javax.swing.Icon> setter)
	{
		if (itemManager == null)
		{
			return;
		}
		net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(itemId);
		Runnable apply = () -> setter.accept(new javax.swing.ImageIcon(
			image.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH)));
		apply.run();
		image.onLoaded(apply);
	}

	private JLabel emptyLabel(String text)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setForeground(UiTokens.TEXT_MUTED);
		label.setFont(label.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		label.setBorder(new EmptyBorder(UiTokens.PAD, 0, UiTokens.PAD, 0));
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
