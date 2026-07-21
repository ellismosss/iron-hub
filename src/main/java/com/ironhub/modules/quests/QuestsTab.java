package com.ironhub.modules.quests;

import com.ironhub.data.QuestsPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTextField;
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
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.util.LinkBrowser;

/**
 * Quests tab in the OSRS stonework skin: quest-cape hero chart (every
 * quest complete is the cape), Quests/Miniquests chip split, difficulty /
 * A-Z / started sorts, completed hidden by default, per-quest goal-planner
 * tracking, and click-to-open in Quest Helper (wiki behind the W glyph).
 */
class QuestsTab extends JPanel
{
	static final String[] TYPES = {"Quests", "Miniquests"};
	static final String[] SORTS = {"Difficulty", "A-Z", "Started"};
	private static final List<String> DIFFICULTY_ORDER = List.of(
		"Novice", "Intermediate", "Experienced", "Master", "Grandmaster", "Special");

	private final AccountState state;
	private final QuestsModule module;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StonePanel hero;
	private final StoneProgressBar capeBar;
	private final StoneTextField search;
	private final StoneChipRow types;
	private final StoneChipRow sorts;
	private final JPanel list = new JPanel();
	private boolean showCompleted;

	QuestsTab(AccountState state, QuestsModule module, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		hero = new StonePanel(theme);
		hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
		hero.setAlignmentX(LEFT_ALIGNMENT);
		capeBar = new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, 0);
		add(pad(hero));
		add(Box.createVerticalStrut(6));

		search = new StoneTextField(theme, "Search quests…");
		add(pad(search));
		add(Box.createVerticalStrut(4));
		types = new StoneChipRow(theme, true, TYPES);
		types.onChange(i -> rebuild());
		add(pad(types));
		add(Box.createVerticalStrut(4));
		sorts = new StoneChipRow(theme, true, SORTS);
		sorts.onChange(i -> rebuild());
		add(pad(sorts));
		add(Box.createVerticalStrut(4));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

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

	private void rebuild()
	{
		boolean miniquests = types.getSelected() == 1;

		// the hero chart: the quest cape is every quest complete
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
		JPanel title = bareRow();
		title.add(new OsrsLabel("Quest cape", OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		title.add(Box.createHorizontalGlue());
		title.add(OsrsLabel.value(questsDone + "/" + questsTotal));
		cap(title);
		hero.add(title);
		hero.add(Box.createVerticalStrut(3));
		capeBar.setFraction(questsTotal == 0 ? 0 : (double) questsDone / questsTotal);
		capeBar.setAlignmentX(LEFT_ALIGNMENT);
		hero.add(capeBar);
		hero.add(Box.createVerticalStrut(3));
		JPanel meta = bareRow();
		meta.add(new OsrsLabel(state.getQuestPoints() + " Quest points · "
				+ inProgress + " in progress",
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		meta.add(Box.createHorizontalGlue());
		cap(meta);
		hero.add(meta);
		cap(hero);

		// the list
		list.removeAll();
		list.add(toggleRow(showCompleted ? "Hide completed" : "Show completed"));
		List<Quest> quests = new ArrayList<>();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
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
			if (!showCompleted && state.getQuestState(quest) == QuestState.FINISHED)
			{
				continue;
			}
			quests.add(quest);
		}
		quests.sort(comparator(SORTS[sorts.getSelected()]));
		if (quests.isEmpty())
		{
			list.add(faintLine(query.isEmpty()
				? "All " + (miniquests ? "miniquests" : "quests") + " complete."
				: "No matches."));
		}
		// the Bank grammar row cap (2026-07-20 audit — a fresh account
		// rendered ~200 rows per rebuild, per keystroke)
		int limit = Math.min(quests.size(), 50);
		for (Quest quest : quests.subList(0, limit))
		{
			list.add(row(quest));
		}
		if (quests.size() > limit)
		{
			list.add(faintLine("+ " + (quests.size() - limit)
				+ " more — refine your search"));
		}
		revalidate();
		repaint();
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

	/** Hover answer: what finishing this quest actually opens, from the
	 *  reverse unlock index (deduped; first few named, the rest counted). */
	String questTooltip(Quest quest)
	{
		StringBuilder tip = new StringBuilder("<html><b>").append(quest.getName())
			.append("</b><br>Click to open in Quest Helper");
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
			tip.append("<br>Gates: ").append(summary);
			int shown = 0;
			for (String key : names)
			{
				if (shown++ >= 5)
				{
					tip.append("<br>… + ").append(names.size() - 5).append(" more");
					break;
				}
				String display = key.substring(key.indexOf('|') + 1);
				tip.append("<br>· ").append(display.length() > 60
					? display.substring(0, 57) + "…" : display);
			}
		}
		return tip.toString();
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

	/** A quest row: click opens it in Quest Helper (wiki fallback when
	 *  Quest Helper is absent), W = wiki, +/× = Goal planner tracking. */
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

		// tooltip computed on hover: the unlock join is lazy-built off-thread
		// and the answer ("what does finishing this open?") is the reverse
		// unlock index's whole point (2026-07-20 intelligence arc)
		OsrsLabel name = new OsrsLabel(quest.getName(), color, OsrsSkin.font())
		{
			@Override
			public String getToolTipText()
			{
				return questTooltip(quest);
			}
		}.leftAligned().squeezable();
		name.setToolTipText("");
		row.add(name);
		row.add(Box.createHorizontalGlue());

		String difficulty = difficulty(quest);
		if (difficulty != null)
		{
			row.add(new OsrsLabel(difficulty, OsrsSkin.FAINT, OsrsSkin.smallFont()));
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		if (state.getQuestState(quest) != QuestState.FINISHED)
		{
			boolean tracked = module.isGoal(quest.getName());
			StoneButton goal = new StoneButton(theme, tracked ? "×" : "+", () ->
			{
				if (tracked)
				{
					module.removeGoal(quest.getName());
				}
				else
				{
					module.addGoal(quest.getName());
				}
				SwingUtilities.invokeLater(this::rebuild);
			});
			goal.setToolTipText(tracked ? "Remove from Goals"
				: "Track completing this quest in Goals");
			goal.setMaximumSize(goal.getPreferredSize());
			row.add(goal);
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		row.add(wikiGlyph(quest.getName()));

		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!module.openInQuestHelper(quest.getName()))
				{
					// Quest Helper absent — the wiki quest guide is the fallback
					LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
						+ quest.getName().replace(' ', '_'));
				}
			}
		});
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
				e.consume();
			}
		});
		return glyph;
	}

	// ── layout helpers (the DailiesNewTab/FarmingTab grammar) ─────────

	private JComponent toggleRow(String label)
	{
		JPanel row = bareRow();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		OsrsLabel toggle = new OsrsLabel(label, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned();
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showCompleted = !showCompleted;
				SwingUtilities.invokeLater(QuestsTab.this::rebuild);
			}
		});
		row.add(toggle);
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JComponent faintLine(String text)
	{
		JPanel holder = bareRow();
		holder.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, 195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private JPanel bareRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
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
