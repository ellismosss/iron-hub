package com.ironhub.modules.quests;

import com.ironhub.data.QuestsPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2TextField;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.util.LinkBrowser;

/**
 * Quests in the clog/CA/diaries reference grammar (Luke, 2026-07-28):
 *
 * <ul>
 * <li>a hero Card — "Quests completed" between two quest emblems over the
 *     large sprite bar (the Log/Combat framing), quest points and the
 *     in-progress count on a counter line;
 * <li>search, the Quests/Miniquests and sort chips, and a "Completed"
 *     checkbox that defaults ON with include semantics (unchecked = the
 *     to-do list only — the diaries filter grammar);
 * <li>every quest on ONE Tile in the Goals grammar — the game's own
 *     red/orange/green quest icon by state, the name in the journal's
 *     colours, difficulty at the right, a +/x goal glyph — a row click
 *     opening the quest's Well non-exclusively: difficulty and standing,
 *     what finishing it unlocks (the reverse unlock index, formerly a
 *     hover tooltip), and Quest Helper / wiki actions (also behind
 *     right-click).
 * </ul>
 */
class QuestsTab extends JPanel
{
	static final String[] TYPES = {"Quests", "Miniquests"};
	static final String[] SORTS = {"Difficulty", "A-Z", "Started"};
	private static final List<String> DIFFICULTY_ORDER = List.of(
		"Novice", "Intermediate", "Experienced", "Master", "Grandmaster", "Special");
	/** Page size — arrows below past this (Luke, 2026-07-28). */
	private static final int PAGE_ROWS = 20;
	private static final int WELL_WRAP = 165;
	/** Marks a child that keeps its own click (glyphs, well actions). */
	private static final String OWN_ACTION = "ironhub.quests.ownAction";

	private final AccountState state;
	private final QuestsModule module;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final V2Surface hero;
	private final V2ProgressBar bar;
	private final V2TextField search;
	private final V2ChipRow types;
	private final V2ChipRow sorts;
	/** Checked = completed quests shown too; unchecked (default) = the
	 *  to-do list only (include semantics; Luke, 2026-07-28). */
	private final V2Checkbox completedFilter;
	private final JPanel list = new JPanel();
	/** Rows open NON-exclusively into Wells, keyed by quest name. */
	private final java.util.Set<String> expandedQuests = new java.util.HashSet<>();
	/** The list's current page of 20 (reset when the filters move). */
	private int page;

	QuestsTab(AccountState state, QuestsModule module, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		// the quest-cape standing is the one live readout on the page — the
		// Card, with the SPRITE bar (the reference hero shape)
		hero = V2Surface.card(theme);
		bar = new V2ProgressBar(theme);
		add(hero);
		add(Box.createVerticalStrut(4));

		search = new V2TextField(theme, "Search quests…", null);
		add(search);
		add(Box.createVerticalStrut(4));
		types = new V2ChipRow(theme, true, TYPES);
		types.onChange(i -> filtersChanged());
		add(types);
		add(Box.createVerticalStrut(4));
		sorts = new V2ChipRow(theme, true, SORTS);
		sorts.onChange(i -> filtersChanged());
		add(sorts);
		add(Box.createVerticalStrut(4));

		completedFilter = new V2Checkbox(theme, "Completed", false, this::toggleCompleted);
		JPanel filterRow = new JPanel();
		filterRow.setLayout(new BoxLayout(filterRow, BoxLayout.X_AXIS));
		filterRow.setOpaque(false);
		filterRow.setAlignmentX(LEFT_ALIGNMENT);
		filterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT));
		filterRow.add(completedFilter);
		filterRow.add(Box.createHorizontalGlue());
		add(filterRow);
		add(Box.createVerticalStrut(4));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		search.editor().getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				filtersChanged();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				filtersChanged();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				filtersChanged();
			}
		});

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seams. */
	void selectType(int index)
	{
		types.setSelected(index);
		rebuild();
	}

	void selectSort(int index)
	{
		sorts.setSelected(index);
		rebuild();
	}

	/** Test seam: open one quest's Well. */
	void expandForTest(String questName)
	{
		expandedQuests.add(questName);
		rebuild();
	}

	/** The atom fires the toggle and the CALLER flips the state (the
	 *  V2Checkbox contract; Luke, 2026-07-27). */
	private void toggleCompleted()
	{
		completedFilter.state(completedFilter.state() == V2Checkbox.State.ON
			? V2Checkbox.State.OFF : V2Checkbox.State.ON);
		filtersChanged();
	}

	/** A filter moved — back to the first page. */
	private void filtersChanged()
	{
		page = 0;
		rebuild();
	}

	private void rebuild()
	{
		boolean miniquests = types.selected() == 1;

		// the hero: the quest cape is every quest complete
		long questsDone = 0;
		long questsTotal = 0;
		long inProgress = 0;
		for (Quest quest : Quest.values())
		{
			if (isMiniquest(quest))
			{
				continue;
			}
			questsTotal++;
			QuestState questState = state.getQuestState(quest);
			if (questState == QuestState.FINISHED)
			{
				questsDone++;
			}
			else if (questState == QuestState.IN_PROGRESS)
			{
				inProgress++;
			}
		}
		hero.removeAll();
		JPanel top = row();
		// two quest emblems flank the count, the Log/Combat framing; glue
		// BOTH sides keeps the block centred between them
		top.add(questEmblem());
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Quests completed", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(questsDone + " / " + questsTotal,
			OsrsSkin.TITLE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(questEmblem());
		cap(top);
		hero.add(top);
		hero.add(Box.createVerticalStrut(3));
		// the fill answers the SAME numbers as the label riding it
		bar.fraction(questsTotal == 0 ? 0 : (double) questsDone / questsTotal);
		bar.labels("", questsDone + " / " + questsTotal, "");
		hero.add(bar);
		hero.add(Box.createVerticalStrut(3));
		JPanel meta = row();
		meta.add(new OsrsLabel("Quest points: ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		meta.add(new OsrsLabel(String.valueOf(state.getQuestPoints()),
			V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
		meta.add(Box.createHorizontalGlue());
		meta.add(new OsrsLabel(inProgress + " in progress",
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		cap(meta);
		hero.add(meta);
		cap(hero);

		// the list
		list.removeAll();
		List<Quest> quests = new ArrayList<>();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		boolean includeCompleted = completedFilter.state() == V2Checkbox.State.ON;
		for (Quest quest : Quest.values())
		{
			if (isMiniquest(quest) != miniquests)
			{
				continue;
			}
			if (!query.isEmpty()
				&& !quest.getName().toLowerCase(Locale.ROOT).contains(query))
			{
				continue;
			}
			if (!includeCompleted && state.getQuestState(quest) == QuestState.FINISHED)
			{
				continue;
			}
			quests.add(quest);
		}
		quests.sort(comparator(SORTS[sorts.selected()]));
		if (quests.isEmpty())
		{
			list.add(faintLine(query.isEmpty()
				? "All " + (miniquests ? "miniquests" : "quests") + " complete."
				: "No matches."));
		}
		else
		{
			// the Goals grammar: every quest on ONE Tile, the open row's
			// details in a Well beneath it — 20 to a page, arrows below
			// (Luke, 2026-07-28)
			int pages = (quests.size() + PAGE_ROWS - 1) / PAGE_ROWS;
			page = Math.max(0, Math.min(page, pages - 1));
			int from = page * PAGE_ROWS;
			List<Quest> shown = quests.subList(from,
				Math.min(from + PAGE_ROWS, quests.size()));
			V2Surface tile = V2Surface.tile(theme);
			tile.setAlignmentX(LEFT_ALIGNMENT);
			for (int i = 0; i < shown.size(); i++)
			{
				Quest quest = shown.get(i);
				if (i > 0)
				{
					tile.add(Box.createVerticalStrut(3));
				}
				tile.add(questHead(quest));
				if (expandedQuests.contains(quest.getName()))
				{
					tile.add(Box.createVerticalStrut(2));
					tile.add(questWell(quest));
				}
			}
			cap(tile);
			list.add(tile);
			if (pages > 1)
			{
				list.add(Box.createVerticalStrut(V2Tokens.TIGHT));
				JPanel pager = row();
				pager.add(Box.createHorizontalGlue());
				pager.add(new com.ironhub.ui.v2.V2SpriteButton(theme,
					com.ironhub.ui.v2.V2SpriteButton.ARROW_LEFT, () ->
					{
						if (page > 0)
						{
							page--;
							rebuild();
						}
					}));
				pager.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
				pager.add(new OsrsLabel("Page " + (page + 1) + "/" + pages,
					OsrsSkin.MUTED, OsrsSkin.smallFont()));
				pager.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
				pager.add(new com.ironhub.ui.v2.V2SpriteButton(theme,
					com.ironhub.ui.v2.V2SpriteButton.ARROW_RIGHT, () ->
					{
						if (page < pages - 1)
						{
							page++;
							rebuild();
						}
					}));
				pager.add(Box.createHorizontalGlue());
				cap(pager);
				list.add(pager);
			}
		}
		revalidate();
		repaint();
	}

	/** Scaled once per tab lifetime — the emblem is static per theme, and
	 *  questEmblem runs twice per rebuild: each call re-area-averaged the
	 *  full-wiki-resolution art on the EDT. */
	private javax.swing.ImageIcon emblemIconCache;

	private javax.swing.ImageIcon emblemIcon()
	{
		if (emblemIconCache == null)
		{
			java.awt.image.BufferedImage art =
				com.ironhub.ui.v2.V2Sprites.get(theme, "icons/quests_large");
			if (art != null)
			{
				emblemIconCache = new javax.swing.ImageIcon(
					art.getScaledInstance(-1, 30, java.awt.Image.SCALE_SMOOTH));
			}
		}
		return emblemIconCache;
	}

	/** The quest-journal emblem (Luke's curated sprite), scaled to the
	 *  hero's flank height — the source art is full wiki resolution. */
	private JComponent questEmblem()
	{
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		javax.swing.ImageIcon art = emblemIcon();
		if (art != null)
		{
			icon.setIcon(art);
		}
		else
		{
			icon.setPreferredSize(new Dimension(30, 30));
		}
		return icon;
	}

	/** Whether the pack knows this enum entry as a miniquest; enum entries
	 *  the pack lacks entirely sit in the miniquest bucket (honest default
	 *  for content newer than the route) — except the Recipe for Disaster
	 *  parent, whose pack identity is its ten Special-rated subquests. */
	boolean isMiniquest(Quest quest)
	{
		if (quest.getName().equals("Recipe for Disaster"))
		{
			return false;
		}
		QuestsPack.QuestEntry entry = packEntry(quest);
		return entry == null || Boolean.TRUE.equals(entry.miniquest);
	}

	private QuestsPack.QuestEntry packEntry(Quest quest)
	{
		return module.pack() == null ? null : module.pack().byName(quest.getName());
	}

	String difficulty(Quest quest)
	{
		QuestsPack.QuestEntry entry = packEntry(quest);
		return entry == null ? null : entry.difficulty;
	}

	Comparator<Quest> comparator(String sort)
	{
		Comparator<Quest> alpha = Comparator.comparing(Quest::getName);
		switch (sort)
		{
			case "Difficulty":
				return Comparator.<Quest>comparingInt(q ->
				{
					String difficulty = difficulty(q);
					int i = difficulty == null ? -1 : DIFFICULTY_ORDER.indexOf(difficulty);
					return i < 0 ? DIFFICULTY_ORDER.size() : i;
				}).thenComparing(alpha);
			case "Started":
				return Comparator.<Quest>comparingInt(q ->
				{
					switch (state.getQuestState(q))
					{
						case IN_PROGRESS:
							return 0;
						case NOT_STARTED:
							return 1;
						default:
							return 2;
					}
				}).thenComparing(alpha);
			default:
				return alpha;
		}
	}

	/**
	 * One quest's ROW on the shared Tile: the game's own red/orange/green
	 * quest icon by state, the name in the journal's colours, difficulty
	 * at the right, and the +/x goal glyph. A click expands the quest
	 * NON-exclusively into a Well below; right-click keeps the Quest
	 * Helper / wiki menu.
	 */
	private JComponent questHead(Quest quest)
	{
		QuestState questState = state.getQuestState(quest);
		Color color;
		switch (questState)
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

		JPanel head = row();
		// one blue journal icon for every row (Luke, 2026-07-28) — the
		// name's colour carries the state
		java.awt.Image icon = com.ironhub.ui.v2.V2Sprites.get(theme, "icons/quest/quest_blue_small");
		if (icon != null)
		{
			head.add(new JLabel(new javax.swing.ImageIcon(icon)));
			head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		OsrsLabel name = new OsrsLabel(quest.getName(), color, OsrsSkin.font())
			.leftAligned().squeezable();
		head.add(name);
		head.add(Box.createHorizontalGlue());
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		String difficulty = difficulty(quest);
		if (difficulty != null)
		{
			head.add(new OsrsLabel(difficulty, OsrsSkin.FAINT, OsrsSkin.smallFont()));
			head.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		if (questState != QuestState.FINISHED)
		{
			head.add(goalGlyph(quest));
		}
		head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		clickAnywhere(head, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					questMenu(quest, e);
					return;
				}
				if (!expandedQuests.remove(quest.getName()))
				{
					expandedQuests.add(quest.getName());
				}
				rebuild();
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					questMenu(quest, e);
				}
			}
		});
		cap(head);
		return head;
	}

	/**
	 * The open quest's Well: difficulty and standing, what finishing it
	 * unlocks (the reverse unlock index — deduped, first few named, the
	 * rest counted), and the Quest Helper / wiki actions.
	 */
	private JComponent questWell(Quest quest)
	{
		QuestState questState = state.getQuestState(quest);
		String standing = questState == QuestState.FINISHED ? "Complete"
			: questState == QuestState.IN_PROGRESS ? "In progress" : "Not started";

		V2Surface well = V2Surface.well(theme);
		int inset = com.ironhub.ui.v2.V2Well.CAP + V2Tokens.TIGHT;
		well.setBorder(new EmptyBorder(inset, inset, inset, inset));
		JPanel meta = row();
		String difficulty = difficulty(quest);
		if (difficulty != null)
		{
			meta.add(new OsrsLabel(difficulty, V2Tokens.STRONG, OsrsSkin.smallFont())
				.leftAligned());
			meta.add(new OsrsLabel(" · " + standing, OsrsSkin.FAINT, OsrsSkin.smallFont())
				.leftAligned());
		}
		else
		{
			meta.add(new OsrsLabel(standing, V2Tokens.STRONG, OsrsSkin.smallFont())
				.leftAligned());
		}
		meta.add(Box.createHorizontalGlue());
		cap(meta);
		well.add(meta);

		// what finishing this opens — the reverse unlock index's whole
		// point (2026-07-20 intelligence arc), now IN the Well rather than
		// behind a hover tooltip
		List<com.ironhub.data.UnlockIndex.Ref> refs = module.questUnlocks(quest.getName());
		if (!refs.isEmpty())
		{
			java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
			java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
			for (com.ironhub.data.UnlockIndex.Ref ref : refs)
			{
				if (names.add(ref.source + "|" + ref.name))
				{
					counts.merge(ref.source, 1, Integer::sum);
				}
			}
			StringBuilder summary = new StringBuilder();
			for (java.util.Map.Entry<String, Integer> entry : counts.entrySet())
			{
				if (summary.length() > 0)
				{
					summary.append(" · ");
				}
				summary.append(entry.getValue()).append(" ").append(entry.getKey())
					.append(entry.getValue() == 1 ? "" : "s");
			}
			JPanel gates = row();
			gates.add(new OsrsLabel("Unlocks: ",
				OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
			gates.add(new OsrsLabel(summary.toString(),
				V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned().squeezable());
			gates.add(Box.createHorizontalGlue());
			cap(gates);
			well.add(gates);
			int shown = 0;
			for (String key : names)
			{
				if (shown++ >= 5)
				{
					well.add(new OsrsLabel("… + " + (names.size() - 5) + " more",
						OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
					break;
				}
				well.add(OsrsLabel.wrapped("· " + key.substring(key.indexOf('|') + 1),
					WELL_WRAP, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
			}
		}

		well.add(Box.createVerticalStrut(2));
		JPanel actions = row();
		actions.add(actionLabel("Open in Quest Helper", () ->
		{
			if (!module.openInQuestHelper(quest.getName()))
			{
				// Quest Helper absent — the wiki quest guide is the fallback
				LinkBrowser.browse(wikiUrl(quest));
			}
		}));
		actions.add(new OsrsLabel("  ·  ", OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		actions.add(actionLabel("Wiki", () -> LinkBrowser.browse(wikiUrl(quest))));
		actions.add(Box.createHorizontalGlue());
		cap(actions);
		well.add(actions);
		cap(well);
		return well;
	}

	private static String wikiUrl(Quest quest)
	{
		return "https://oldschool.runescape.wiki/w/" + quest.getName().replace(' ', '_');
	}

	private void questMenu(Quest quest, MouseEvent e)
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem helper = new javax.swing.JMenuItem("Open in Quest Helper");
		helper.addActionListener(a ->
		{
			if (!module.openInQuestHelper(quest.getName()))
			{
				LinkBrowser.browse(wikiUrl(quest));
			}
		});
		menu.add(helper);
		javax.swing.JMenuItem wiki = new javax.swing.JMenuItem("Open wiki page");
		wiki.addActionListener(a -> LinkBrowser.browse(wikiUrl(quest)));
		menu.add(wiki);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	/** A Well action in skin colours — faint until hovered. */
	private static OsrsLabel actionLabel(String text, Runnable onClick)
	{
		OsrsLabel label = new OsrsLabel(text, OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned();
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.putClientProperty(OWN_ACTION, Boolean.TRUE);
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setColor(OsrsSkin.TITLE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setColor(OsrsSkin.LABEL);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}
		});
		return label;
	}

	/** The row's +/x: track completing this quest in the Goal planner —
	 *  the engine expands the quest: requirement into its full chain. */
	private JComponent goalGlyph(Quest quest)
	{
		boolean tracked = module.isGoal(quest.getName());
		JLabel glyph = new JLabel(tracked ? "×" : "+");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tracked ? "Remove from Goals"
			: "Track completing this quest in Goals");
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
				if (tracked)
				{
					module.removeGoal(quest.getName());
				}
				else
				{
					module.addGoal(quest.getName());
				}
				javax.swing.SwingUtilities.invokeLater(QuestsTab.this::rebuild);
			}
		});
		return glyph;
	}

	// ── layout helpers ────────────────────────────────────────────────

	private JComponent faintLine(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, 195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	/** Attach a click to a container AND its passive children — AWT delivers
	 *  a press to the DEEPEST component only (the MouseRelay lesson). */
	private static void clickAnywhere(JComponent container, MouseAdapter click)
	{
		container.addMouseListener(click);
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof JComponent
				&& Boolean.TRUE.equals(((JComponent) child).getClientProperty(OWN_ACTION)))
			{
				continue;
			}
			child.addMouseListener(click);
		}
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
