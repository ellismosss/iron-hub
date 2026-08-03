package com.ironhub.modules.diaries;

import com.ironhub.data.DiariesPack;
import com.ironhub.modules.diaries.DiariesModule.DiaryRegion;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Achievement diaries in the clog/CA reference grammar (Luke, 2026-07-27/28):
 *
 * <ul>
 * <li>a hero Card with ONE medium (ROW) progress bar over the whole
 *     journal — no icons (Luke, 2026-07-28) — and the tier tally on a
 *     counter line beneath;
 * <li>a Completed checkbox under the hero (unchecked = the to-do list, the
 *     CA filter grammar) that the region cards and task lists obey;
 * <li>the twelve regions as full-width Cards carrying name, counts and the
 *     four-segment tier bar; clicking one expands the card (one at a time)
 *     into its four tier rows — each with its own meter, count and
 *     whole-tier goal glyph, collapsed until clicked;
 * <li>clicking a tier row opens that tier's tasks ON THE SAME CARD, a
 *     subtle divider between each, its rewards following the tasks — each
 *     task row opening its requirements Well non-exclusively.
 * </ul>
 */
class DiariesTab extends JPanel
{
	/** Wrap widths: task rows beside the + column, lines inside a Well. */
	private static final int TASK_WRAP = 160;
	private static final int WELL_WRAP = 150;
	private static final int REWARD_WRAP = 175;
	/** The thin-meter height — the small bar variant (Luke, 2026-07-17). */
	private static final int TIER_BAR_HEIGHT = 5;
	/** Marks a child that keeps its own click (the +/x goal glyphs). */
	private static final String OWN_ACTION = "ironhub.diaries.ownAction";

	private final DiariesModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final V2Surface hero;
	/** The large sprite bar, back after the medium round (Luke, 2026-07-28). */
	private final V2ProgressBar bar;
	/** Checked = the finished tasks; unchecked = the to-do list (the CA
	 *  filter grammar). */
	private final V2Checkbox completedFilter;
	private final JPanel content = new JPanel();

	/** Region whose card is open (one at a time, the clog grammar). */
	private String expandedRegion;
	/** The open region's ONE open tier, -1 for none — always collapsed
	 *  until clicked (Luke, 2026-07-28). */
	private int expandedTier = -1;
	/** Task rows open NON-exclusively into Wells, keyed by task slug. */
	private final Set<String> expandedTasks = new HashSet<>();
	/** Tier reward folds open into Wells, keyed "region#tierIndex". */
	private final Set<String> rewardsOpen = new HashSet<>();
	/** Usable temporary-boost headroom per skill, refreshed each rebuild. */
	private java.util.Map<net.runelite.api.Skill, Integer> boosts = java.util.Map.of();

	DiariesTab(DiariesModule module, AccountState state, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		hero = V2Surface.card(theme);
		bar = new V2ProgressBar(theme);
		add(hero);
		add(Box.createVerticalStrut(4));

		// checked by DEFAULT (Luke, 2026-07-28): completed tasks and tiers
		// show; unchecking strips them down to the to-do list
		completedFilter = new V2Checkbox(theme, "Completed", true, this::toggleCompleted);
		JPanel filterRow = new JPanel();
		filterRow.setLayout(new BoxLayout(filterRow, BoxLayout.X_AXIS));
		filterRow.setOpaque(false);
		filterRow.setAlignmentX(LEFT_ALIGNMENT);
		filterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT));
		filterRow.add(completedFilter);
		filterRow.add(Box.createHorizontalGlue());
		add(filterRow);
		add(Box.createVerticalStrut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seam: open a region's card, its first tier, and its rewards. */
	void expandForTest(String regionName)
	{
		expandedRegion = regionName;
		expandedTier = 0;
		rewardsOpen.add(regionName + "#0");
		rebuild();
	}

	/** Test seam: open one task's Well in the expanded tier. */
	void expandTaskForTest(String slug)
	{
		expandedTasks.add(slug);
		rebuild();
	}

	/** The atom fires the toggle and the CALLER flips the state (the
	 *  V2Checkbox contract; Luke, 2026-07-27). */
	private void toggleCompleted()
	{
		completedFilter.state(completedFilter.state() == V2Checkbox.State.ON
			? V2Checkbox.State.OFF : V2Checkbox.State.ON);
		rebuildContent();
	}

	/** The Completed checkbox's verdict on one task: checked = completed
	 *  tasks INCLUDED, unchecked = the to-do list only (Luke, 2026-07-28). */
	private boolean filterPasses(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		return completedFilter.state() == V2Checkbox.State.ON
			|| !module.taskComplete(region, tierIndex, task);
	}

	private void rebuild()
	{
		boosts = module.availableBoosts();
		rebuildHero();
		rebuildContent();
	}

	// ── the hero card ─────────────────────────────────────────────────

	/**
	 * "Diaries completed: 207 / 492" between two diary emblems, over the
	 * large sprite bar (the Log/Combat framing; Luke, 2026-07-28), the tier
	 * tally on a counter line.
	 */
	private void rebuildHero()
	{
		DiariesPack pack = module.pack();
		int total = 0;
		int done = 0;
		for (DiariesPack.Region region : pack.regions)
		{
			total += DiariesModule.regionTotal(region);
			done += module.regionDone(region);
		}
		int tiersDone = DiariesModule.totalTiersComplete(state);
		int tiersTotal = DiariesModule.REGIONS.length * 4;

		hero.removeAll();
		JPanel top = row();
		// two diary emblems flank the count, the Log/Combat framing (Luke,
		// 2026-07-28); glue BOTH sides keeps the block centred between them
		top.add(diaryIcon());
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Diaries completed", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(String.format(Locale.ROOT, "%,d / %,d", done, total),
			OsrsSkin.TITLE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(diaryIcon());
		cap(top);
		hero.add(top);

		hero.add(Box.createVerticalStrut(3));
		// the fill answers the SAME numbers as the label riding it
		bar.fraction(total == 0 ? 0 : (double) done / total);
		bar.labels("", String.format(Locale.ROOT, "%,d / %,d", done, total), "");
		hero.add(bar);

		hero.add(Box.createVerticalStrut(3));
		Color tierColour = tiersDone == 0 ? V2Tokens.BLOCKED
			: tiersDone >= tiersTotal ? OsrsSkin.VALUE : OsrsSkin.COUNT_YELLOW;
		JPanel tiers = row();
		tiers.add(new OsrsLabel("Tiers complete: ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		tiers.add(new OsrsLabel(tiersDone + "/" + tiersTotal,
			tierColour, OsrsSkin.smallFont()).leftAligned());
		tiers.add(Box.createHorizontalGlue());
		cap(tiers);
		hero.add(tiers);
		cap(hero);
		hero.revalidate();
		hero.repaint();
	}

	/** The achievement-diary emblem (Luke's curated sprite), scaled to the
	 *  hero's flank height — the source art is full wiki resolution. */
	/** Scaled once per tab lifetime — static art, re-area-averaged on the
	 *  EDT twice per rebuild before (QuestsTab's twin). */
	private javax.swing.ImageIcon diaryIconCache;

	private JComponent diaryIcon()
	{
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		if (diaryIconCache == null)
		{
			java.awt.image.BufferedImage art =
				com.ironhub.ui.v2.V2Sprites.get(theme, "icons/achievement_dairy_large");
			if (art != null)
			{
				diaryIconCache = new javax.swing.ImageIcon(
					art.getScaledInstance(-1, 30, java.awt.Image.SCALE_SMOOTH));
			}
		}
		if (diaryIconCache != null)
		{
			icon.setIcon(diaryIconCache);
		}
		else
		{
			icon.setPreferredSize(new Dimension(30, 30));
		}
		return icon;
	}

	// ── the region cards ──────────────────────────────────────────────

	private void rebuildContent()
	{
		content.removeAll();
		DiariesPack pack = module.pack();
		// a card with nothing behind the filter does not show (the CA rule)
		boolean any = false;
		for (DiariesPack.Region region : pack.regions)
		{
			if (!anyTaskPasses(region))
			{
				continue;
			}
			any = true;
			content.add(regionCard(region));
			content.add(Box.createVerticalStrut(V2Tokens.ROW));
		}
		if (!any)
		{
			content.add(note(completedFilter.state() == V2Checkbox.State.ON
				? "No completed diary tasks yet."
				: "Every diary task is complete."));
		}
		content.revalidate();
		content.repaint();
	}

	private boolean anyTaskPasses(DiariesPack.Region region)
	{
		for (int i = 0; i < region.tiers.size(); i++)
		{
			for (DiariesPack.Task task : region.tiers.get(i).tasks)
			{
				if (filterPasses(region, i, task))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * One region's Card: name, "Tasks Completed: n/N", the tier tally and
	 * the four-segment tier bar. Clicking the face expands the card (one
	 * region at a time) into its four tier rows; the Rewards fold closes
	 * the open card.
	 */
	private JComponent regionCard(DiariesPack.Region region)
	{
		boolean open = region.name.equals(expandedRegion);
		int done = module.regionDone(region);
		int total = DiariesModule.regionTotal(region);
		V2Surface card = V2Surface.card(theme);
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel titleRow = row();
		OsrsLabel title = new OsrsLabel(region.name, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		titleRow.add(title);
		titleRow.add(Box.createHorizontalGlue());
		cap(titleRow);
		card.add(titleRow);

		Color colour = done == 0 ? V2Tokens.BLOCKED
			: done >= total ? OsrsSkin.VALUE : OsrsSkin.COUNT_YELLOW;
		JPanel counts = row();
		counts.add(new OsrsLabel("Tasks Completed: ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		counts.add(new OsrsLabel(done + "/" + total, colour, OsrsSkin.smallFont()).leftAligned());
		counts.add(Box.createHorizontalGlue());
		counts.add(new OsrsLabel((total - done) + " tasks left",
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		cap(counts);
		card.add(counts);
		card.add(Box.createVerticalStrut(3));
		JComponent strip = tierBar(region);
		card.add(strip);

		// the FACE toggles the card; the tier rows below keep their own
		// clicks, so the listener rides the head rows, never the card
		MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedRegion = open ? null : region.name;
				expandedTier = -1;
				rebuildContent();
			}
		};
		for (JComponent face : new JComponent[]{titleRow, counts})
		{
			face.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			clickAnywhere(face, toggle);
		}
		strip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		strip.addMouseListener(toggle);

		if (open)
		{
			card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			for (int i = 0; i < region.tiers.size(); i++)
			{
				// a tier with nothing behind the filter hides completely
				// (Luke, 2026-07-28: no completed tiers on the to-do list)
				if (!anyTierTaskPasses(region, i))
				{
					continue;
				}
				card.add(tierRow(region, i));
				if (expandedTier == i)
				{
					card.add(Box.createVerticalStrut(2));
					tierTasks(card, region, i);
					card.add(Box.createVerticalStrut(2));
				}
			}
		}
		cap(card);
		return card;
	}

	private boolean anyTierTaskPasses(DiariesPack.Region region, int tierIndex)
	{
		for (DiariesPack.Task task : region.tiers.get(tierIndex).tasks)
		{
			if (filterPasses(region, tierIndex, task))
			{
				return true;
			}
		}
		return false;
	}

	/** Four tier segments, in-game-journal style: green complete, orange partial. */
	private JComponent tierBar(DiariesPack.Region region)
	{
		DiaryRegion meta = DiariesModule.regionMeta(region.name);
		double[] fractions = new double[4];
		boolean[] complete = new boolean[4];
		StringBuilder tip = new StringBuilder("<html>");
		for (int i = 0; i < region.tiers.size() && i < 4; i++)
		{
			DiariesPack.Tier tier = region.tiers.get(i);
			int done = module.tierDone(region, i);
			fractions[i] = tier.tasks.isEmpty() ? 0 : (double) done / tier.tasks.size();
			complete[i] = meta != null && state.getVarbit(meta.tierVarbits[i]) >= 1;
			tip.append(i > 0 ? "<br>" : "").append(tier.tier).append(" — ")
				.append(done).append("/").append(tier.tasks.size())
				.append(complete[i] ? " (complete)" : "");
		}
		// four METER atoms, not a hand-painted strip (Luke's Progression pass,
		// 2026-07-26): green when the tier is signed off, orange while partial
		JPanel segments = new JPanel(new java.awt.GridLayout(1, 4, V2Tokens.TIGHT, 0));
		segments.setOpaque(false);
		for (int i = 0; i < 4; i++)
		{
			segments.add(new V2ProgressBar(theme, V2ProgressBar.Size.METER)
				.fill(complete[i] ? V2Tokens.BAR_FILL : OsrsSkin.TITLE.darker())
				.fraction(fractions[i]));
		}
		segments.setPreferredSize(new Dimension(0, TIER_BAR_HEIGHT));
		segments.setMinimumSize(new Dimension(0, TIER_BAR_HEIGHT));
		segments.setMaximumSize(new Dimension(Integer.MAX_VALUE, TIER_BAR_HEIGHT));
		segments.setAlignmentX(LEFT_ALIGNMENT);
		segments.setToolTipText(tip.append("</html>").toString());
		return segments;
	}

	// ── the expanded card's tier rows ─────────────────────────────────

	/**
	 * One tier's ROW on the open card: fold triangle, name, its own meter,
	 * count, and the whole-tier goal glyph while incomplete. A click opens
	 * the tier's tasks on a card beneath — one tier at a time, collapsed
	 * until clicked (Luke, 2026-07-28).
	 */
	private JComponent tierRow(DiariesPack.Region region, int tierIndex)
	{
		DiariesPack.Tier tier = region.tiers.get(tierIndex);
		int done = module.tierDone(region, tierIndex);
		int total = tier.tasks.size();
		boolean open = expandedTier == tierIndex;
		boolean claimed = module.tierAllDone(region, tierIndex);
		Color color = done >= total ? OsrsSkin.VALUE : OsrsSkin.MUTED;

		JPanel row = row();
		row.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, UiTokens.PAD_TIGHT, 0));
		JLabel triangle = new JLabel(new PaintedIcon(open
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		row.add(triangle);
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(tier.tier.toUpperCase(Locale.ROOT), color, OsrsSkin.font())
			.leftAligned());
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		// the tier's own meter rides the row's middle (Luke, 2026-07-28)
		row.add(new V2ProgressBar(theme, V2ProgressBar.Size.METER)
			.fill(claimed ? V2Tokens.BAR_FILL : OsrsSkin.TITLE.darker())
			.fraction(total == 0 ? 0 : (double) done / total));
		row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		row.add(new OsrsLabel(done + "/" + total, color, OsrsSkin.font()));
		// track the WHOLE tier as one goal (Luke, 2026-07-23) — one step per
		// task, sharing the per-task proof keys
		if (done < total)
		{
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			String goalId = tierGoalId(region, tierIndex);
			boolean isGoal = state.getGoalSeeds().containsKey(goalId);
			row.add(goalGlyph(isGoal,
				isGoal ? region.name + " " + tier.tier + " — tracked; click to untrack"
					: "Track the whole " + region.name + " " + tier.tier
						+ " tier in Goals",
				() -> toggleTierGoal(region, tierIndex)));
		}
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		clickAnywhere(row, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedTier = open ? -1 : tierIndex;
				rebuildContent();
			}
		});
		cap(row);
		return row;
	}

	/** The open tier's tasks, ON the region card itself with a subtle
	 *  divider between each (Luke, 2026-07-28) — each row opening its
	 *  requirements Well non-exclusively — then the tier's Rewards fold,
	 *  whose click opens the reward lines in a Well. */
	private void tierTasks(V2Surface card, DiariesPack.Region region, int tierIndex)
	{
		DiariesPack.Tier tier = region.tiers.get(tierIndex);
		boolean any = false;
		for (DiariesPack.Task task : tier.tasks)
		{
			if (!filterPasses(region, tierIndex, task))
			{
				continue;
			}
			if (any)
			{
				card.add(Box.createVerticalStrut(3));
				card.add(divider());
			}
			card.add(Box.createVerticalStrut(3));
			any = true;
			card.add(taskHead(region, tierIndex, task));
			if (expandedTasks.contains(DiariesModule.slug(task)))
			{
				card.add(Box.createVerticalStrut(2));
				card.add(taskWell(region, tierIndex, task));
			}
		}
		// the tier's rewards ride with its tasks (Luke, 2026-07-28): a fold
		// row whose click opens them in a Well
		String rewardsKey = region.name + "#" + tierIndex;
		boolean open = rewardsOpen.contains(rewardsKey);
		card.add(Box.createVerticalStrut(3));
		card.add(divider());
		card.add(Box.createVerticalStrut(3));
		JPanel fold = row();
		fold.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel triangle = new JLabel(new PaintedIcon(open
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		fold.add(triangle);
		fold.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		fold.add(new OsrsLabel("Rewards", OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		fold.add(Box.createHorizontalGlue());
		cap(fold);
		clickAnywhere(fold, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!rewardsOpen.remove(rewardsKey))
				{
					rewardsOpen.add(rewardsKey);
				}
				rebuildContent();
			}
		});
		card.add(fold);
		if (open)
		{
			card.add(Box.createVerticalStrut(2));
			V2Surface well = V2Surface.well(theme);
			int inset = com.ironhub.ui.v2.V2Well.CAP + V2Tokens.TIGHT;
			well.setBorder(new EmptyBorder(inset, inset, inset, inset));
			for (String reward : tier.rewards)
			{
				well.add(OsrsLabel.wrapped("· " + reward, WELL_WRAP,
					OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
				well.add(Box.createVerticalStrut(2));
			}
			cap(well);
			card.add(well);
		}
	}

	/** A subtle 1px divider between task rows (Luke, 2026-07-28). */
	private JComponent divider()
	{
		JPanel line = new JPanel();
		line.setBackground(theme.recess);
		line.setOpaque(true);
		line.setAlignmentX(LEFT_ALIGNMENT);
		line.setPreferredSize(new Dimension(0, 1));
		line.setMinimumSize(new Dimension(0, 1));
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		return line;
	}

	private static String tierGoalId(DiariesPack.Region region, int tierIndex)
	{
		return "diarytier:" + region.name.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "_") + "_"
			+ region.tiers.get(tierIndex).tier.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "_");
	}

	/** The tier-level +/×: every task becomes a step; already-complete tasks
	 *  prove on the next state pass (the module marks the shared
	 *  {@code diarytask_} unlocks for tier goals too). */
	private void toggleTierGoal(DiariesPack.Region region, int tierIndex)
	{
		DiariesPack.Tier tier = region.tiers.get(tierIndex);
		String goalId = tierGoalId(region, tierIndex);
		if (state.getGoalSeeds().containsKey(goalId))
		{
			state.removeGoalSeed(goalId);
			return;
		}
		java.util.List<String> slugs = new java.util.ArrayList<>();
		java.util.List<String> texts = new java.util.ArrayList<>();
		for (DiariesPack.Task task : tier.tasks)
		{
			slugs.add(DiariesModule.slug(task));
			texts.add(task.task);
		}
		state.addGoalSeed(com.ironhub.state.GoalSeeds.diaryTier(
			region.name, tier.tier, slugs, texts));
		module.markDiaryGoalProofs(); // already-done tasks prove immediately
	}

	/**
	 * One task's ROW on the tier card: the task text on the classic scale —
	 * green done, orange doable now, muted otherwise — and the +/x goal
	 * glyph. A click expands the task NON-exclusively into a Well below.
	 */
	private JComponent taskHead(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		boolean complete = module.taskComplete(region, tierIndex, task);
		boolean doable = !complete && module.taskDoable(region, tierIndex, task, boosts);
		Color textColor = complete ? OsrsSkin.VALUE
			: doable ? OsrsSkin.TITLE : OsrsSkin.MUTED;
		String slug = DiariesModule.slug(task);

		JPanel head = row();
		// small font (Luke, 2026-07-17) — the progress-bar label size
		OsrsLabel text = OsrsLabel.wrapped(task.task, TASK_WRAP, textColor, OsrsSkin.smallFont())
			.leftAligned();
		head.add(text);
		head.add(Box.createHorizontalGlue());
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		// live count where the game exposes one (Karamja's counting varbits,
		// DI1 2026-08-03) — everywhere else, honest silence
		String count = complete ? null : module.taskCount(task);
		if (count != null)
		{
			head.add(new OsrsLabel(count, OsrsSkin.MUTED, OsrsSkin.smallFont()));
			head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		}
		boolean isGoal = state.getGoalSeeds().containsKey("diary:" + slug);
		JPanel anchor = new JPanel(new java.awt.BorderLayout());
		anchor.setOpaque(false);
		anchor.putClientProperty(OWN_ACTION, Boolean.TRUE);
		anchor.add(goalGlyph(isGoal,
			isGoal ? "Remove this task from the goal planner" : "Add this task to the goal planner",
			() -> toggleGoal(region, tierIndex, task)), java.awt.BorderLayout.NORTH);
		head.add(anchor);
		head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		clickAnywhere(head, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!expandedTasks.remove(slug))
				{
					expandedTasks.add(slug);
				}
				rebuildContent();
			}
		});
		cap(head);
		return head;
	}

	/** The open task's Well: its tier and standing, each requirement line
	 *  in its met colour (with the boost route when one closes the gap),
	 *  and the wiki note beneath. */
	private JComponent taskWell(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		boolean complete = module.taskComplete(region, tierIndex, task);
		boolean doable = !complete && module.taskDoable(region, tierIndex, task, boosts);
		boolean boostOnly = doable && !module.taskDoable(region, tierIndex, task);
		String standing = complete ? "Complete"
			: boostOnly ? "Doable with a temporary boost"
			: doable ? "Doable now" : "Requirements not met";

		V2Surface well = V2Surface.well(theme);
		// the Checklist's inset: CAP clears the end caps, TIGHT is the only
		// air on top (the Goals tasks' own numbers)
		int inset = com.ironhub.ui.v2.V2Well.CAP + V2Tokens.TIGHT;
		well.setBorder(new EmptyBorder(inset, inset, inset, inset));
		JPanel meta = row();
		meta.add(new OsrsLabel(region.tiers.get(tierIndex).tier,
			V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
		meta.add(new OsrsLabel(" · " + standing,
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		meta.add(Box.createHorizontalGlue());
		cap(meta);
		well.add(meta);
		for (DiariesPack.Req req : task.reqs)
		{
			Boolean met = module.reqMet(req);
			String boostNote = met != null && !met ? module.reqBoostNote(req, boosts) : null;
			String line = "· " + req.text + (boostNote != null ? " — " + boostNote : "");
			well.add(OsrsLabel.wrapped(line, WELL_WRAP,
				met == null ? OsrsSkin.FAINT : met ? OsrsSkin.VALUE : OsrsSkin.MUTED,
				OsrsSkin.smallFont()).leftAligned());
		}
		if (task.note != null && !task.note.isEmpty())
		{
			well.add(Box.createVerticalStrut(2));
			well.add(OsrsLabel.wrapped(task.note, WELL_WRAP,
				OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		cap(well);
		return well;
	}

	/** The per-task +/× affordance in skin colours — faint until hovered
	 *  (the GoalsTab glyph grammar; a dedicated control, never a row click). */
	private static JComponent goalGlyph(boolean isGoal, String tooltip, Runnable onClick)
	{
		// the shared letter-glyph atom (unified 2026-08-03)
		JComponent glyph = new com.ironhub.ui.v2.V2GlyphButton(
			isGoal ? "×" : "+", tooltip, onClick);
		glyph.putClientProperty(OWN_ACTION, Boolean.TRUE);
		return glyph;
	}

	private void toggleGoal(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		String slug = DiariesModule.slug(task);
		if (state.getGoalSeeds().containsKey("diary:" + slug))
		{
			state.removeGoalSeed("diary:" + slug);
			return;
		}
		state.addGoalSeed(com.ironhub.state.GoalSeeds.diary(slug, task.task, region.name,
			region.tiers.get(tierIndex).tier));
		if (module.taskComplete(region, tierIndex, task))
		{
			// already done in-game: prove the goal immediately
			state.setUnlocked("diarytask_" + slug, true);
		}
	}

	// ── layout helpers ────────────────────────────────────────────────

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private static JComponent note(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, REWARD_WRAP, OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
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
