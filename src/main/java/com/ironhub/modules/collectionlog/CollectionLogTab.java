package com.ironhub.modules.collectionlog;

import com.ironhub.data.ClogPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
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
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Collection log tab in the OSRS stonework skin (frame 2c grown into a Log
 * Adviser-style ranking): overall progress + sync status card, then every
 * remaining activity ranked by Time-To-Next-Slot. Rows expand into the
 * activity's slots (owned ticks, drop rates) and every slot's +/× assigns it
 * to the Goal planner as a {@code clog:<itemId>} goal — same grammar as the
 * CA and diaries tabs. Skipped activities sink to a recoverable section.
 * Same brain as before the skin port; only the clothing changed.
 */
class CollectionLogTab extends JPanel
{
	private static final int TOP_N = 30;
	/** Hard row ceiling (the Bank tab's grammar, Luke 2026-07-17) — "Show
	 *  all" and searches render at most this many; hundreds of sprite rows
	 *  per rebuild was a measured freeze contributor. */
	private static final int MAX_ROWS = 50;
	private static final String[] SHOW_OPTIONS = {"All", "Combat", "Minigame", "Miscellaneous", "Slayer"};
	/** Marks a child that keeps its own click (the +/× glyphs) so the row's
	 *  clickAnywhere listener never doubles it. */
	private static final String OWN_ACTION = "clog.ownAction";
	/** Wrap width for in-card copy (see FarmingTab.HINT_WIDTH, minus card edges). */
	private static final int CARD_WRAP = 180;

	private final CollectionLogModule module;
	private final AccountState state;
	private final ItemManager itemManager; // null in headless tests
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::onStateChanged);

	// stats card — OsrsLabel text is immutable, so refreshStats refills it
	private final StonePanel card;
	private final StoneProgressBar bar;

	// controls
	private final StoneTextField search;
	private final JComboBox<String> showFilter;

	// content
	private final JPanel content = new JPanel();
	private final Set<Integer> expanded = new HashSet<>();
	private final Set<Integer> slayerActivities;
	private boolean showAll;
	private boolean skippedExpanded;
	private List<Object> lastFingerprint = List.of();

	CollectionLogTab(CollectionLogModule module, AccountState state, ItemManager itemManager,
		OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.itemManager = itemManager;
		this.theme = theme;

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
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		bar = new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, 0);
		add(pad(card));
		add(Box.createVerticalStrut(6));

		search = new StoneTextField(theme, "Search activities or items…");
		add(pad(search));
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildContent();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildContent();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuildContent();
			}
		});
		add(Box.createVerticalStrut(4));

		showFilter = StoneComboBoxUI.skin(new JComboBox<>(SHOW_OPTIONS), theme);
		showFilter.addActionListener(e -> rebuildContent());
		JPanel showRow = new JPanel(new BorderLayout(UiTokens.ROW_GAP, 0));
		showRow.setOpaque(false);
		showRow.setAlignmentX(LEFT_ALIGNMENT);
		JPanel showHolder = new JPanel(new BorderLayout());
		showHolder.setOpaque(false);
		showHolder.setPreferredSize(new Dimension(40, 22));
		showHolder.add(new OsrsLabel("Show", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned(),
			BorderLayout.CENTER);
		showRow.add(showHolder, BorderLayout.WEST);
		showRow.add(showFilter, BorderLayout.CENTER);
		add(pad(showRow));

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

	private void refreshStats()
	{
		// The COLLECTION_COUNT varp is the game's own live count; the log
		// window title (recorded on open) supplies the game-truth total,
		// with the data pack's slot count as the pre-sync fallback.
		int varpCount = state.getVarp(VarPlayerID.COLLECTION_COUNT);
		int slots = varpCount > 0 ? varpCount : state.getCollectionLogSlots();
		int total = state.getCollectionLogTotal() > 0
			? state.getCollectionLogTotal() : module.pack().slots.size();

		card.removeAll();
		card.add(new OsrsLabel(String.format(Locale.ROOT, "%,d/%,d slots · %d%%",
			slots, total, Math.round(100.0 * slots / Math.max(1, total))),
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		card.add(Box.createVerticalStrut(3));
		bar.setFraction((double) slots / Math.max(1, total));
		bar.setAlignmentX(LEFT_ALIGNMENT);
		card.add(bar);
		card.add(Box.createVerticalStrut(3));

		String syncText;
		Color syncColor;
		String syncTip;
		if (state.getClogBaseline() < 0)
		{
			syncColor = OsrsSkin.TITLE;
			syncText = "Open the log and press Log Sync to import";
			syncTip = "Open your collection log in-game and press the Log Sync "
				+ "button in its header — every obtained slot imports in one click.";
		}
		else if (!module.inSync())
		{
			syncColor = OsrsSkin.TITLE;
			syncText = "New slots since last sync · press Log Sync";
			syncTip = "Your in-game slot count moved past the last full sync — "
				+ "open the collection log and press Log Sync to catch up.";
		}
		else
		{
			syncColor = OsrsSkin.VALUE;
			long syncedMs = state.getClogSyncedMs();
			syncText = "Synced" + (syncedMs > 0
				? " · " + Format.relativeTime(System.currentTimeMillis() - syncedMs) : "");
			syncTip = "Live drops keep the data current between full syncs.";
		}
		OsrsLabel syncLine = OsrsLabel.wrapped(syncText, CARD_WRAP, syncColor, OsrsSkin.font());
		syncLine.leftAligned();
		syncLine.setToolTipText(syncTip);
		card.add(syncLine);
		cap(card);
		card.revalidate();
		card.repaint();
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

		content.add(section("Next slots"));
		if (visible.isEmpty())
		{
			content.add(emptyLabel(searching
				? "Nothing matches. Skipped activities are listed below."
				: "Every rankable slot is obtained."));
		}

		int limit = Math.min(searching || showAll ? MAX_ROWS : TOP_N, visible.size());
		for (int i = 0; i < limit; i++)
		{
			content.add(activityRow(visible.get(i), false));
			content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		if (limit < visible.size() && !searching && !showAll)
		{
			OsrsLabel more = new OsrsLabel("Show all (" + (visible.size() - limit) + " more)",
				OsrsSkin.LABEL, OsrsSkin.font()).leftAligned();
			more.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			more.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					showAll = true;
					rebuildContent();
				}
			});
			JPanel moreRow = row();
			moreRow.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
			moreRow.add(more);
			moreRow.add(Box.createHorizontalGlue());
			cap(moreRow);
			content.add(moreRow);
		}
		else if (limit < visible.size())
		{
			// past the ceiling even expanded — the Bank tab's honest hint
			content.add(emptyLabel("+ " + (visible.size() - limit)
				+ " more — refine your search"));
		}

		List<ClogRanker.Ranked> skipped = ClogRanker.rankSkipped(module.pack(),
			state.getClogObtained(), state.getClogSkipped(), state);
		if (!skipped.isEmpty())
		{
			content.add(Box.createVerticalStrut(UiTokens.PAD));
			content.add(skippedHeader(skipped.size()));
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

	/** The SKIPPED sink header — the PlannerTab snoozedSection idiom. */
	private JComponent skippedHeader(int count)
	{
		JPanel header = row();
		header.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP));
		JLabel triangle = new JLabel(new PaintedIcon(skippedExpanded
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.FAINT);
		header.add(triangle);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(new OsrsLabel("SKIPPED (" + count + ")",
			OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		header.add(Box.createHorizontalGlue());
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setToolTipText("Activities you told the ranking to ignore — click to "
			+ (skippedExpanded ? "collapse" : "review and unskip"));
		cap(header);
		clickAnywhere(header, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				skippedExpanded = !skippedExpanded;
				rebuildContent();
			}
		});
		return header;
	}

	/** Test seam (the FarmingTab.expandOverview precedent): open the
	 *  top-ranked activity's slot card and the skipped sink for renders. */
	void expandTopForRender()
	{
		List<ClogRanker.Ranked> visible = filtered(ranking());
		if (!visible.isEmpty())
		{
			expanded.add(visible.get(0).activity.index);
		}
		skippedExpanded = true;
		rebuildContent();
	}

	// ── activity rows ─────────────────────────────────────────────────

	/** An activity: a flat hoverable row collapsed, a stone card expanded —
	 *  the PlannerTab denseRow → explainCard pattern. */
	private JPanel activityRow(ClogRanker.Ranked ranked, boolean skippedRow)
	{
		ClogPack.Activity activity = ranked.activity;
		boolean open = expanded.contains(activity.index);

		JPanel row;
		if (open)
		{
			row = new StonePanel(theme);
		}
		else
		{
			row = new JPanel();
			row.setOpaque(true);
			row.setBackground(theme.background);
			row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		}
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel titleLine = row();
		if (ranked.display != null)
		{
			JLabel icon = new JLabel();
			icon.setToolTipText(ranked.display.name);
			itemIcon(ranked.display.itemId, icon::setIcon);
			titleLine.add(icon);
			titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		OsrsLabel name = new OsrsLabel(activity.name,
			ranked.locked ? OsrsSkin.FAINT : open ? OsrsSkin.TITLE : OsrsSkin.MUTED,
			OsrsSkin.boldFont()).leftAligned().squeezable();
		name.setToolTipText(activity.name);
		titleLine.add(name);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		OsrsLabel time = new OsrsLabel("~" + Format.hours(ranked.hours),
			OsrsSkin.MUTED, OsrsSkin.boldFont());
		time.setToolTipText("Expected time to this activity's next slot at ironman rates");
		titleLine.add(time);
		if (ranked.display != null)
		{
			titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			titleLine.add(goalGlyph(ranked.display.itemId, ranked.display.name, activity));
		}
		cap(titleLine);
		row.add(titleLine);

		if (ranked.locked)
		{
			OsrsLabel needs = OsrsLabel.wrapped("Locked · needs " + ranked.missing,
				CARD_WRAP, OsrsSkin.FAINT, OsrsSkin.font());
			needs.leftAligned();
			needs.setToolTipText("Unmet requirements: " + ranked.missing);
			row.add(needs);
		}
		if (ranked.display != null)
		{
			OsrsLabel next = new OsrsLabel("Next: " + ranked.display.name + " · "
				+ ranked.slotsLeft + "/" + ranked.slotsTotal + " left",
				OsrsSkin.FAINT, OsrsSkin.font()).leftAligned().squeezable();
			JPanel nextLine = row();
			nextLine.add(next);
			nextLine.add(Box.createHorizontalGlue());
			cap(nextLine);
			row.add(nextLine);
		}

		if (open)
		{
			row.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			for (ClogPack.Item item : uniqueItems(activity))
			{
				row.add(slotLine(item, activity));
			}
		}

		JPanel flat = open ? null : row;
		MouseAdapter interaction = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (flat != null)
				{
					flat.setBackground(theme.hoverFill);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (flat != null)
				{
					flat.setBackground(theme.background);
				}
			}

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
		clickAnywhere(row, interaction);
		clickAnywhere(titleLine, interaction);

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

	/** One slot inside an expanded activity: green = owned, sprite, name,
	 *  drop rate, and the +/× goal-planner toggle. */
	private JPanel slotLine(ClogPack.Item item, ClogPack.Activity activity)
	{
		boolean owned = state.getClogObtained().contains(item.itemId);
		JPanel line = row();
		JLabel icon = new JLabel();
		itemIcon(item.itemId, icon::setIcon);
		line.add(icon);
		line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		OsrsLabel name = new OsrsLabel(item.name,
			owned ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable();
		name.setToolTipText(item.name + (owned ? " — obtained" : " — not obtained yet"));
		line.add(name);
		line.add(Box.createHorizontalGlue());
		if (item.attempts > 0)
		{
			OsrsLabel rate = new OsrsLabel("1/" + Math.round(item.attempts),
				OsrsSkin.FAINT, OsrsSkin.font());
			rate.setToolTipText("Expected attempts per drop");
			line.add(rate);
			line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		line.add(goalGlyph(item.itemId, item.name, activity));
		cap(line);
		return line;
	}

	/** A compact +/× goal affordance in skin colours — faint until hovered
	 *  (the GoalsTab removeGlyph idiom). Same action as the old IconButton. */
	private JLabel goalGlyph(int itemId, String slotName, ClogPack.Activity activity)
	{
		boolean goal = isGoal(itemId);
		JLabel glyph = new JLabel(goal ? "×" : "+");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(goal ? "Remove " + slotName + " from the Goal planner"
			: "Add " + slotName + " as a goal in the Goal planner");
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.putClientProperty(OWN_ACTION, Boolean.TRUE);
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.TITLE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleGoal(itemId, slotName, activity);
			}
		});
		return glyph;
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

	private JComponent emptyLabel(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.ROW_GAP, UiTokens.PAD, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, UiTokens.PANEL_WIDTH - 30,
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	/** Attach a click to a container AND its passive children — labels with
	 *  tooltips register their own listeners and would swallow clicks. Skips
	 *  anything with its own action. */
	private static void clickAnywhere(JComponent container, MouseAdapter click)
	{
		container.addMouseListener(click);
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof StoneButton
				|| (child instanceof JComponent
					&& Boolean.TRUE.equals(((JComponent) child).getClientProperty(OWN_ACTION))))
			{
				continue; // keeps its own action
			}
			child.addMouseListener(click);
		}
	}

	// ── layout helpers (the DailiesNewTab/FarmingTab grammar) ─────────

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent section(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new BorderLayout());
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
