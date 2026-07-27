package com.ironhub.modules.ca;

import com.ironhub.IronHubConfig;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2Dropdown;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2TextField;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.util.LinkBrowser;

/**
 * The searchable task list behind the Combat Achievements surface: All and
 * Goals views, search, sorting, and flat hoverable rows that expand into
 * stone cards carrying the description, community completion rate, wiki
 * link and per-task tracking. The status/type/tier FILTERS moved up to the
 * tab, under the Difficulty/Bosses chips, and apply here too (Luke,
 * 2026-07-27). Feature parity with the Combat Achievements Tracker hub
 * plugin.
 *
 * <p>Since 2026-07-24 this is a SECTION of {@link CombatAchievementsTab}
 * (Luke: "move the existing module into a collapsible section below"), so
 * it carries no progress card and no boss grid — the surface above owns
 * both, in the game's own shape.
 */
class CaTaskBrowser extends JPanel
{
	private static final String[] SORT_OPTIONS = {"Tier", "Name", "Completion", "Community %"};
	/** Wrap widths: free-standing notes vs text inside a stone card. */
	private static final int NOTE_WIDTH = 195;
	private static final int CARD_TEXT_WIDTH = 180;
	/** Marks a child with its own action so a row click never steals it. */
	private static final String OWN_ACTION = "ironhub.ca.ownAction";

	private final CombatAchievementsModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::onStateChanged);
	/** Set by the surface above: open a boss's page (a row's right-click
	 *  offers it, but the page itself is not this component's). */
	private java.util.function.Consumer<String> onShowBoss;

	// controls
	private final V2ChipRow views;
	private final V2TextField search;
	private final V2Dropdown sortFilter;
	private final JLabel sortDirection;
	private boolean sortAscending = true;
	/** The tab owns the status/type/tier filters since 2026-07-27 (Luke:
	 *  they sit under the Difficulty/Bosses chips and filter everything). */
	private CombatAchievementsTab owner;

	// content
	private final JPanel content = new JPanel();
	private final Set<Integer> expanded = new HashSet<>();
	private Set<String> lastCaGoals;
	private int lastPoints = -1;

	private static final Map<CaTier, ImageIcon> TIER_ICONS = loadTierIcons();

	/** The bundled wiki tier icon, shared with the surface above. */
	static ImageIcon tierIcon(CaTier tier)
	{
		return TIER_ICONS.get(tier);
	}

	CaTaskBrowser(CombatAchievementsModule module, AccountState state,
		IronHubConfig config, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		views = new V2ChipRow(theme, true, "All", "Goals");
		views.onChange(i -> rebuildContent());
		add(pad(views));
		add(strut(4));

		sortFilter = combo(new V2Dropdown(theme, SORT_OPTIONS));
		search = new V2TextField(theme, "Search tasks…", null);
		add(pad(search));
		search.editor().getDocument().addDocumentListener(new DocumentListener()
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
		add(strut(4));

		sortDirection = glyph(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_UP, 10),
			"Flip sort direction", this::flipSortDirection);
		// sorting stays here; the filters live under the tab's view chips
		JPanel sortControls = new JPanel(new BorderLayout(UiTokens.PAD_TIGHT, 0));
		sortControls.setOpaque(false);
		sortControls.add(sortFilter, BorderLayout.CENTER);
		sortControls.add(sortDirection, BorderLayout.EAST);
		add(pad(filterRow("Sort", sortControls)));
		add(strut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		content.setBorder(new EmptyBorder(0, 4, 0, 4));
		add(content);
		add(Box.createVerticalGlue());

		lastCaGoals = selectedCaGoals();
		state.addListener(listener);
		rebuildContent();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	void onShowBoss(java.util.function.Consumer<String> onShowBoss)
	{
		this.onShowBoss = onShowBoss;
	}

	/** Module callback after the catalog (re)loads on the client thread. */
	void onTasksUpdated()
	{
		rebuildContent();
	}

	/** Test seam: expand a task's card. */
	void expandForTest(int taskId)
	{
		expanded.add(taskId);
		rebuildContent();
	}

	/** The tab hands itself over for the shared filters. */
	void filtersFrom(CombatAchievementsTab owner)
	{
		this.owner = owner;
	}

	/** The tab's filters changed — re-run the list. */
	void refilter()
	{
		rebuildContent();
	}

	/** Cheap path for AccountState notifications: stats always, rows only
	 * when points or the set of CA goals actually changed. */
	private void onStateChanged()
	{
		// only rebuild when something CA-relevant moved — refreshStats alone
		// is three full ~637-task passes, and it ran on EVERY coalesced
		// state change (2026-07-20 audit)
		Set<String> caGoals = selectedCaGoals();
		int points = module.points();
		if (!caGoals.equals(lastCaGoals) || points != lastPoints)
		{
			lastCaGoals = caGoals;
			lastPoints = points;
			rebuildContent();
		}
	}

	/** The "ca:" goal ids currently selected in the goal planner. */
	private Set<String> selectedCaGoals()
	{
		Set<String> ids = new HashSet<>();
		for (String goalId : state.getSelectedGoals())
		{
			if (goalId.startsWith("ca:"))
			{
				ids.add(goalId);
			}
		}
		return ids;
	}

	private boolean isGoal(CaTask task)
	{
		return state.getSelectedGoals().contains("ca:" + task.id);
	}

	private V2Dropdown combo(V2Dropdown box)
	{
		box.onChange(i -> rebuildContent());
		return box;
	}

	private JPanel filterRow(String label, Component control)
	{
		// the row FOLLOWS its dropdown: the V2 dropdown grows in place when
		// opened, and a row pinned to one control height would clip the list
		JPanel row = new JPanel(new BorderLayout(UiTokens.ROW_GAP, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		JPanel nameHolder = new JPanel(new BorderLayout());
		nameHolder.setOpaque(false);
		nameHolder.setPreferredSize(new Dimension(48, V2Tokens.CONTROL_HEIGHT));
		nameHolder.add(new OsrsLabel(label, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned(),
			BorderLayout.CENTER);
		row.add(nameHolder, BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	private void flipSortDirection()
	{
		sortAscending = !sortAscending;
		sortDirection.setIcon(new PaintedIcon(sortAscending
			? PaintedIcon.Shape.TRIANGLE_UP : PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		rebuildContent();
	}

	// ── content ───────────────────────────────────────────────────────

	private void rebuildContent()
	{
		content.removeAll();
		List<CaTask> tasks = module.tasks();
		if (tasks.isEmpty())
		{
			content.add(note("Log in to load your combat tasks."));
		}
		else
		{
			List<CaTask> visible = filteredSorted(tasks, views.selected() == 1, null);
			if (visible.isEmpty())
			{
				content.add(note(views.selected() == 1
					? "No CA goals match the filters. The + on any row adds that task to Goals."
					: "No tasks match the filters."));
			}
			addTaskRows(visible);
		}
		content.revalidate();
		content.repaint();
	}

	List<CaTask> filteredSorted(List<CaTask> tasks, boolean goalsOnly, String boss)
	{
		String term = search.getText().trim();
		List<CaTask> visible = new ArrayList<>();
		for (CaTask task : tasks)
		{
			if (goalsOnly && !isGoal(task))
			{
				continue;
			}
			if (boss != null && !boss.equals(task.boss))
			{
				continue;
			}
			if (!task.matches(term))
			{
				continue;
			}
			// the tab's shared status/type/tier filters (Luke, 2026-07-27)
			if (owner != null && !owner.filterPasses(task, true))
			{
				continue;
			}
			visible.add(task);
		}
		visible.sort(comparator());
		return visible;
	}

	private Comparator<CaTask> comparator()
	{
		String sort = SORT_OPTIONS[sortFilter.selected()];
		Comparator<CaTask> byTierThenName = Comparator
			.<CaTask>comparingInt(t -> t.tier.ordinal())
			.thenComparing(t -> t.name);
		Comparator<CaTask> order;
		switch (sort == null ? "Tier" : sort)
		{
			case "Name":
				order = Comparator.comparing(t -> t.name);
				break;
			case "Completion":
				order = Comparator.<CaTask, Boolean>comparing(t -> t.completed)
					.thenComparing(byTierThenName);
				break;
			case "Community %":
				// most-completed-by-the-community first when "ascending":
				// that ordering is the useful "easiest next" reading
				order = Comparator.comparingDouble(
					(CaTask t) -> t.communityPct == null ? -1 : t.communityPct).reversed();
				break;
			default:
				order = byTierThenName;
		}
		return sortAscending ? order : order.reversed();
	}

	// ── task rows ─────────────────────────────────────────────────────

	/** Collapsed = flat hoverable row; expanded = stone card. Row click
	 *  toggles; the +/× tracking affordance keeps its own action. */
	private JPanel taskRow(CaTask task)
	{
		boolean goal = isGoal(task);
		boolean open = expanded.contains(task.id);

		JPanel row;
		if (open)
		{
			// the opened task is the one live readout in the list — the Card
			row = V2Surface.card(theme);
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

		row.add(titleLine(task, goal));

		String context = task.boss.isEmpty() ? task.type
			: task.type.isEmpty() ? task.boss : task.boss + " · " + task.type;
		if (!context.isEmpty())
		{
			row.add(new OsrsLabel(context, OsrsSkin.FAINT, OsrsSkin.font())
				.leftAligned().squeezable());
		}

		if (open)
		{
			OsrsLabel description = OsrsLabel.wrapped(task.description, CARD_TEXT_WIDTH,
				OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
			row.add(description);

			String pct = task.communityPct == null ? "unknown"
				: String.format("%.1f%% of players", task.communityPct);
			OsrsLabel details = new OsrsLabel(task.tier.display + " · "
				+ task.tier.points + (task.tier.points == 1 ? " pt" : " pts") + " · " + pct,
				OsrsSkin.FAINT, OsrsSkin.font()).leftAligned().squeezable();
			details.setToolTipText("Community completion rate from the wiki's task table (bundled snapshot)");
			row.add(details);
		}
		else
		{
			OsrsLabel description = new OsrsLabel(task.description, OsrsSkin.MUTED, OsrsSkin.font())
				.leftAligned().squeezable();
			description.setToolTipText(task.description);
			row.add(description);
		}

		MouseAdapter interaction = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!open)
				{
					row.setBackground(theme.hoverFill);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!open)
				{
					row.setBackground(theme.background);
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					taskMenu(task, e);
				}
				else if (SwingUtilities.isLeftMouseButton(e))
				{
					if (!expanded.remove(task.id))
					{
						expanded.add(task.id);
					}
					rebuildContent();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					taskMenu(task, e);
				}
			}
		};
		clickAnywhere(row, interaction);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** Tier icon + name + the dedicated +/× planner affordance. */
	private JPanel titleLine(CaTask task, boolean goal)
	{
		JPanel titleLine = new JPanel();
		titleLine.setLayout(new BoxLayout(titleLine, BoxLayout.X_AXIS));
		titleLine.setOpaque(false);
		titleLine.setAlignmentX(LEFT_ALIGNMENT);
		JLabel icon = new JLabel(TIER_ICONS.get(task.tier));
		icon.setToolTipText(task.tier.display + " · " + task.tier.points
			+ (task.tier.points == 1 ? " pt" : " pts"));
		titleLine.add(icon);
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		OsrsLabel name = new OsrsLabel(task.name,
			task.completed ? OsrsSkin.VALUE : goal ? OsrsSkin.TITLE : OsrsSkin.MUTED,
			OsrsSkin.boldFont()).leftAligned().squeezable();
		name.setToolTipText(task.name + (goal ? " - in your Goals" : ""));
		titleLine.add(name);
		titleLine.add(Box.createHorizontalGlue());
		titleLine.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		OsrsLabel track = new OsrsLabel(goal ? "×" : "+", OsrsSkin.FAINT, OsrsSkin.boldFont());
		track.setToolTipText(goal ? "Remove this task from Goals"
			: "Add this task as a goal in Goals");
		track.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		track.putClientProperty(OWN_ACTION, Boolean.TRUE);
		track.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				track.setColor(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				track.setColor(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				toggleGoal(task);
			}
		});
		titleLine.add(track);
		return titleLine;
	}

	/** The '+' action: the task joins the Goal planner as a "ca:" goal. */
	private void toggleGoal(CaTask task)
	{
		if (isGoal(task))
		{
			state.removeGoalSeed("ca:" + task.id);
		}
		else
		{
			state.addGoalSeed(com.ironhub.state.GoalSeeds.ca(task.id, task.name, task.description, task.tier.display));
			if (task.completed)
			{
				// already done in-game: prove the goal immediately
				state.setUnlocked("catask_" + task.id, true);
			}
		}
	}

	private void taskMenu(CaTask task, MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem wiki = new JMenuItem("Open wiki page");
		wiki.addActionListener(a -> LinkBrowser.browse(task.wikiUrl()));
		menu.add(wiki);
		JMenuItem trackItem = new JMenuItem(isGoal(task)
			? "Remove from Goals" : "Add as goal in Goals");
		trackItem.addActionListener(a -> toggleGoal(task));
		menu.add(trackItem);
		if (!task.boss.isEmpty() && onShowBoss != null)
		{
			// the boss page lives on the surface above, not in here
			JMenuItem bossItem = new JMenuItem("Show all " + task.boss + " tasks");
			bossItem.addActionListener(a -> onShowBoss.accept(task.boss));
			menu.add(bossItem);
		}
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	/** Hard row ceiling (the Bank tab's grammar, Luke 2026-07-17): the full
	 *  catalog is ~500+ tasks and rendering it per rebuild was a measured
	 *  freeze contributor — cap and say so, never render hundreds of rows. */
	private static final int MAX_ROWS = 50;

	private void addTaskRows(List<CaTask> visible)
	{
		int limit = Math.min(MAX_ROWS, visible.size());
		for (int i = 0; i < limit; i++)
		{
			content.add(taskRow(visible.get(i)));
			content.add(strut(UiTokens.PAD_TIGHT));
		}
		if (limit < visible.size())
		{
			content.add(note("+ " + (visible.size() - limit)
				+ " more — refine your search or filters"));
		}
	}

	private static Map<CaTier, ImageIcon> loadTierIcons()
	{
		Map<CaTier, ImageIcon> icons = new EnumMap<>(CaTier.class);
		for (CaTier tier : CaTier.values())
		{
			try (java.io.InputStream in =
				CaTaskBrowser.class.getResourceAsStream(tier.iconResource()))
			{
				if (in != null)
				{
					icons.put(tier, new ImageIcon(ImageIO.read(in)
						.getScaledInstance(-1, 16, Image.SCALE_SMOOTH)));
				}
			}
			catch (java.io.IOException ignored)
			{
			}
		}
		return icons;
	}

	// ── layout helpers (the DailiesNewTab/FarmingTab grammar) ─────────

	/** Attach a click to a container AND its passive children — labels with
	 * tooltips register their own listeners and would swallow clicks. Skips
	 * anything marked as carrying its own action. */
	private static void clickAnywhere(JComponent container, MouseAdapter click)
	{
		container.addMouseListener(click);
		for (Component child : container.getComponents())
		{
			if (child instanceof JComponent
				&& Boolean.TRUE.equals(((JComponent) child).getClientProperty(OWN_ACTION)))
			{
				continue;
			}
			if (child instanceof JPanel)
			{
				clickAnywhere((JComponent) child, click);
			}
			else
			{
				child.addMouseListener(click);
			}
		}
	}

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

	private static JLabel triangle()
	{
		JLabel label = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		label.setForeground(OsrsSkin.MUTED);
		return label;
	}

	/** Collapsible section header: triangle + skin label, whole row toggles. */
	private JComponent collapsibleHeader(JLabel triangleLabel, String title, String tooltip,
		Runnable onToggle)
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setBorder(new EmptyBorder(8, 4, 3, 4));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		OsrsLabel label = new OsrsLabel(title, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
		header.add(triangleLabel);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(label);
		header.add(Box.createHorizontalGlue());
		cap(header);
		MouseAdapter press = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onToggle.run();
			}
		};
		header.addMouseListener(press);
		// a tooltip registers the label's own mouse listeners, which would
		// swallow the row's — so the children carry the press listener too
		header.setToolTipText(tooltip);
		label.setToolTipText(tooltip);
		label.addMouseListener(press);
		triangleLabel.addMouseListener(press);
		return header;
	}

	/** A small hoverable glyph control — faint until hovered. */
	private static JLabel glyph(javax.swing.Icon icon, String tooltip, Runnable onPress)
	{
		JLabel label = new JLabel(icon);
		label.setForeground(OsrsSkin.FAINT);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				onPress.run();
			}
		});
		return label;
	}

	/** Wrapped muted note — never html (the pixel font disagrees with it). */
	private JComponent note(String text)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(6, 0, 6, 0));
		holder.add(OsrsLabel.wrapped(text, NOTE_WIDTH, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
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

	private JComponent strut(int height)
	{
		return (JComponent) Box.createVerticalStrut(height);
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
