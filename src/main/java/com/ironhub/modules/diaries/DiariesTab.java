package com.ironhub.modules.diaries;

import com.ironhub.data.DiariesPack;
import com.ironhub.modules.diaries.DiariesModule.DiaryRegion;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Achievement diaries in the clog/CA reference shape (Luke, 2026-07-27,
 * "apply the same design points"):
 *
 * <ul>
 * <li>a hero Card counting tasks over the whole journal on the sprite bar
 *     — diaries hand a reward per tier, not a rank ladder, so nothing
 *     flanks the count and the tier tally rides a counter line instead;
 * <li>a Completed checkbox under the hero (unchecked = the to-do list, the
 *     CA filter grammar) that the region cards and task lists obey;
 * <li>the twelve regions as 2-wide square CARD tiles — each region's own
 *     tier-1 reward as the emblem, bold inside captions on the
 *     orange/green scale, corner counts, meter strips — expanding a
 *     region's journal IN-LINE below its row, one at a time;
 * <li>the expanded journal in the Goals grammar: a header card carrying
 *     the counts and the four-segment tier bar, then every task on ONE
 *     Tile — tier headers with their whole-tier goal glyphs between the
 *     rows — the open task's requirements and notes in a Well beneath it,
 *     and the collapsible Rewards fold at the foot.
 * </ul>
 */
class DiariesTab extends JPanel
{
	/** The clog page grid's geometry: two perfect squares across 217px. */
	private static final int REGION_COLS = 2;
	private static final int REGION_TILE = 106;
	private static final int REGION_EMBLEM = 44;
	/** Wrap widths: task rows beside the + column, lines inside a Well. */
	private static final int TASK_WRAP = 170;
	private static final int WELL_WRAP = 165;
	private static final int REWARD_WRAP = 185;
	/** The thin-meter height — the small bar variant (Luke, 2026-07-17). */
	private static final int TIER_BAR_HEIGHT = 5;
	/** Marks a child that keeps its own click (the +/x goal glyphs). */
	private static final String OWN_ACTION = "ironhub.diaries.ownAction";
	/**
	 * Each region's card emblem: its own tier-1 reward item, drawn from the
	 * item cache like the CA boss cards. Ids verified against
	 * data/item-sources.json (wiki-audited) — never guessed.
	 */
	private static final Map<String, Integer> REWARD_EMBLEMS = Map.ofEntries(
		Map.entry("Ardougne", 13121),            // Ardougne cloak 1
		Map.entry("Desert", 13133),              // Desert amulet 1
		Map.entry("Falador", 13117),             // Falador shield 1
		Map.entry("Fremennik", 13129),           // Fremennik sea boots 1
		Map.entry("Kandarin", 13137),            // Kandarin headgear 1
		Map.entry("Karamja", 11136),             // Karamja gloves 1
		Map.entry("Kourend & Kebos", 22941),     // Rada's blessing 1
		Map.entry("Lumbridge & Draynor", 13125), // Explorer's ring 1
		Map.entry("Morytania", 13112),           // Morytania legs 1
		Map.entry("Varrock", 13104),             // Varrock armour 1
		Map.entry("Western Provinces", 13141),   // Western banner 1
		Map.entry("Wilderness", 13108));         // Wilderness sword 1

	private final DiariesModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final SpriteCache sprites;

	private final V2Surface hero;
	private final V2ProgressBar bar;
	/** Checked = the finished tasks; unchecked = the to-do list (the CA
	 *  filter grammar). */
	private final V2Checkbox completedFilter;
	private final JPanel content = new JPanel();

	/** Region whose journal is open (one at a time, the clog grammar). */
	private String expandedRegion;
	/** Task rows open NON-exclusively into Wells, keyed by task slug. */
	private final Set<String> expandedTasks = new HashSet<>();
	/** Regions whose Rewards section is open. */
	private final Set<String> rewardsOpen = new HashSet<>();
	/** Usable temporary-boost headroom per skill, refreshed each rebuild. */
	private java.util.Map<net.runelite.api.Skill, Integer> boosts = java.util.Map.of();

	DiariesTab(DiariesModule module, AccountState state, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;
		this.sprites = new SpriteCache(module.itemManager(), listener);

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		// the journal standing is the one live readout on the page — the
		// Card, with the SPRITE bar (the clog reference shape)
		hero = V2Surface.card(theme);
		bar = new V2ProgressBar(theme);
		add(hero);
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

	/** Test seam: open a region's journal and its rewards section. */
	void expandForTest(String regionName)
	{
		expandedRegion = regionName;
		rewardsOpen.add(regionName);
		rebuild();
	}

	/** Test seam: open one task's Well in the expanded journal. */
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

	/** The Completed checkbox's verdict on one task. */
	private boolean filterPasses(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		boolean completed = completedFilter.state() == V2Checkbox.State.ON;
		return module.taskComplete(region, tierIndex, task) == completed;
	}

	private void rebuild()
	{
		boosts = module.availableBoosts();
		rebuildHero();
		rebuildContent();
	}

	// ── the hero card ─────────────────────────────────────────────────

	/**
	 * "Diary Tasks: 207 / 492" on the sprite bar. Diaries have no rank
	 * ladder — every tier hands its own reward — so nothing flanks the
	 * count; the tier tally rides a counter line in the shared colours.
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
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Diary Tasks", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(String.format(Locale.ROOT, "%,d / %,d", done, total),
			OsrsSkin.TITLE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		cap(top);
		hero.add(top);

		hero.add(Box.createVerticalStrut(3));
		// the fill answers the SAME numbers as the label riding it
		bar.fraction(total == 0 ? 0 : (double) done / total);
		bar.labels("", String.format(Locale.ROOT, "%,d / %,d", done, total), "");
		hero.add(bar);

		hero.add(Box.createVerticalStrut(2));
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

	// ── the region grid ───────────────────────────────────────────────

	private void rebuildContent()
	{
		content.removeAll();
		DiariesPack pack = module.pack();
		// a card with nothing behind the filter does not show (the CA rule)
		java.util.List<DiariesPack.Region> regions = new java.util.ArrayList<>();
		for (DiariesPack.Region region : pack.regions)
		{
			if (anyTaskPasses(region))
			{
				regions.add(region);
			}
		}
		if (regions.isEmpty())
		{
			content.add(note(completedFilter.state() == V2Checkbox.State.ON
				? "No completed diary tasks yet."
				: "Every diary task is complete."));
		}
		for (int start = 0; start < regions.size(); start += REGION_COLS)
		{
			JPanel line = row();
			// glue BOTH sides — rows centre in the column (the clog grammar)
			line.add(Box.createHorizontalGlue());
			for (int col = 0; col < REGION_COLS && start + col < regions.size(); col++)
			{
				if (col > 0)
				{
					line.add(Box.createHorizontalStrut(V2Tokens.ROW));
				}
				line.add(regionTile(regions.get(start + col)));
			}
			line.add(Box.createHorizontalGlue());
			cap(line);
			content.add(line);
			content.add(Box.createVerticalStrut(V2Tokens.ROW));
			// the ONE expanded region's journal lands under its own row
			for (int col = 0; col < REGION_COLS && start + col < regions.size(); col++)
			{
				DiariesPack.Region region = regions.get(start + col);
				if (region.name.equals(expandedRegion))
				{
					regionDetail(region);
					content.add(Box.createVerticalStrut(V2Tokens.ROW));
				}
			}
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

	/** One region as a square Card in the clog page-grid grammar: reward
	 *  emblem, bold inside caption on the orange/green scale, corner count
	 *  in the shared colours, a plain meter strip, no tooltip. */
	private V2Tile regionTile(DiariesPack.Region region)
	{
		int done = module.regionDone(region);
		int total = DiariesModule.regionTotal(region);
		boolean complete = total > 0 && done >= total;
		boolean open = region.name.equals(expandedRegion);
		Integer emblemId = REWARD_EMBLEMS.get(region.name);
		Image emblem = emblemId == null ? null : sprites.getBox(emblemId, REGION_EMBLEM);
		Color cornerDone = complete ? V2Tokens.DONE
			: done == 0 ? V2Tokens.BLOCKED : V2Tokens.ACTION;
		Color cornerRest = complete ? V2Tokens.DONE : V2Tokens.ACTION;
		return new V2Tile(theme, emblem, region.name, REGION_TILE, () ->
			{
				// single expansion: a second click on the open tile closes it
				expandedRegion = open ? null : region.name;
				rebuildContent();
			})
			.card().captionLines(2).captionInside()
			.captionStatus(complete ? V2Tokens.DONE : V2Tokens.ACTION)
			.corner(String.valueOf(done), cornerDone, "/" + total, cornerRest)
			.selected(open)
			.meter(total == 0 ? Double.NaN : (double) done / total);
	}

	// ── the expanded journal ──────────────────────────────────────────

	/** Header card, the task Tile, and the Rewards fold — the results a
	 *  region tile expands in-line (the clog grammar). */
	private void regionDetail(DiariesPack.Region region)
	{
		content.add(regionHeader(region));
		content.add(Box.createVerticalStrut(V2Tokens.TIGHT));

		// the Goals grammar: every task on ONE Tile, tier headers between
		// the rows, the open task's details in a Well beneath its row
		V2Surface tile = V2Surface.tile(theme);
		tile.setAlignmentX(LEFT_ALIGNMENT);
		boolean first = true;
		for (int i = 0; i < region.tiers.size(); i++)
		{
			DiariesPack.Tier tier = region.tiers.get(i);
			if (!first)
			{
				tile.add(Box.createVerticalStrut(V2Tokens.TIGHT));
			}
			first = false;
			tile.add(tierHeader(region, i));
			for (DiariesPack.Task task : tier.tasks)
			{
				if (!filterPasses(region, i, task))
				{
					continue;
				}
				tile.add(taskHead(region, i, task));
				if (expandedTasks.contains(DiariesModule.slug(task)))
				{
					tile.add(Box.createVerticalStrut(2));
					tile.add(taskWell(region, i, task));
				}
				tile.add(Box.createVerticalStrut(3));
			}
		}
		cap(tile);
		content.add(tile);
		content.add(Box.createVerticalStrut(V2Tokens.TIGHT));
		content.add(rewardsSection(region));
	}

	/** Name, "Tasks Completed: n/N" in the game's colour scale, the tier
	 *  tally, and the four-segment tier bar (the approved four METER
	 *  atoms; Luke's Progression pass, 2026-07-26). */
	private JComponent regionHeader(DiariesPack.Region region)
	{
		int done = module.regionDone(region);
		int total = DiariesModule.regionTotal(region);
		V2Surface card = V2Surface.card(theme);
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
		DiaryRegion meta = DiariesModule.regionMeta(region.name);
		if (meta != null)
		{
			counts.add(new OsrsLabel(DiariesModule.tiersComplete(state, meta) + "/4 tiers",
				OsrsSkin.MUTED, OsrsSkin.smallFont()));
		}
		cap(counts);
		card.add(counts);
		card.add(Box.createVerticalStrut(3));
		card.add(tierBar(region));
		cap(card);
		return card;
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

	/** A tier's header row on the shared Tile: name, count, and the
	 *  whole-tier goal glyph while the tier is incomplete. */
	private JComponent tierHeader(DiariesPack.Region region, int tierIndex)
	{
		DiariesPack.Tier tier = region.tiers.get(tierIndex);
		int done = module.tierDone(region, tierIndex);
		int total = tier.tasks.size();
		Color color = done >= total ? OsrsSkin.VALUE : OsrsSkin.MUTED;
		JPanel row = row();
		row.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, UiTokens.PAD_TIGHT, 0));
		row.add(new OsrsLabel(tier.tier.toUpperCase(Locale.ROOT), color, OsrsSkin.font())
			.leftAligned());
		row.add(Box.createHorizontalGlue());
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
		cap(row);
		return row;
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
	 * One task's ROW on the shared Tile (the Goals grammar): the task text
	 * on the classic scale — green done, orange doable now, muted otherwise
	 * — and the +/x goal glyph. A click expands the task NON-exclusively
	 * into a Well below with its requirements and notes.
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
	private static JLabel goalGlyph(boolean isGoal, String tooltip, Runnable onClick)
	{
		JLabel glyph = new JLabel(isGoal ? "×" : "+");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tooltip);
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
				onClick.run();
			}
		});
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

	// ── rewards ───────────────────────────────────────────────────────

	private JComponent rewardsSection(DiariesPack.Region region)
	{
		boolean open = rewardsOpen.contains(region.name);
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		JPanel header = row();
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel triangle = new JLabel(new PaintedIcon(open
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		header.add(triangle);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(new OsrsLabel("Rewards", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		header.add(Box.createHorizontalGlue());
		cap(header);
		clickAnywhere(header, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!rewardsOpen.remove(region.name))
				{
					rewardsOpen.add(region.name);
				}
				rebuildContent();
			}
		});
		section.add(header);

		if (open)
		{
			section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			for (DiariesPack.Tier tier : region.tiers)
			{
				section.add(new OsrsLabel(tier.tier, OsrsSkin.TITLE, OsrsSkin.boldFont())
					.leftAligned());
				for (String reward : tier.rewards)
				{
					section.add(OsrsLabel.wrapped("· " + reward, REWARD_WRAP,
						OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
					section.add(Box.createVerticalStrut(2));
				}
				section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}
		cap(section);
		return section;
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
