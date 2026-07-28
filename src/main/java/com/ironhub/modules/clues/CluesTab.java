package com.ironhub.modules.clues;

import com.ironhub.data.ClueStepsPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2ChipRow;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Clues &amp; STASH, rebuilt from the ground up in the reference grammar
 * (Luke, 2026-07-28 — "build them from the ground up"):
 *
 * <ul>
 * <li>a hero Card — "STASH units filled" between the beginner and master
 *     scrolls over the large sprite bar, with the steps-doable tally and
 *     the ready-to-fill count on a counter line;
 * <li>Steps · STASH chips, then an include-semantics checkbox per view
 *     ("Doable" / "Filled", both defaulting OFF so each view opens on its
 *     to-do list);
 * <li>the six tiers as 2-wide square CARD tiles — the tier's own clue
 *     scroll as the emblem, corner counts (doable or filled), meter
 *     strips — expanding IN-LINE below their row, one at a time;
 * <li>the expanded tier in the Goals grammar: a header card with the
 *     counts, then every step / unit on ONE Tile, rows opening Wells
 *     non-exclusively — a step's Well carries its standing and every
 *     outfit item in met colours with where-from lines (formerly hover
 *     tooltips); a unit's Well carries its status, outfit, and the
 *     manual Mark-filled action (moved off the row click).
 * </ul>
 */
class CluesTab extends JPanel
{
	private static final String[] TIERS =
		{"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};
	/** Each tier's clue scroll — ids verified against item-sources.json. */
	private static final int[] SCROLLS = {23182, 2677, 2801, 2722, 12073, 19835};
	/** The clog page grid's geometry: two perfect squares across 217px. */
	private static final int TIER_COLS = 2;
	private static final int TIER_TILE = 106;
	private static final int TIER_EMBLEM = 44;
	/** Row ceiling (the Bank tab's grammar). */
	private static final int MAX_ROWS = 50;
	private static final int ROW_WRAP = 160;
	private static final int WELL_WRAP = 150;
	/** Marks a child that keeps its own click (glyphs, well actions). */
	private static final String OWN_ACTION = "ironhub.clues.ownAction";

	private final AccountState state;
	private final ClueStashModule module;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final SpriteCache sprites;

	private final V2Surface hero;
	private final V2ProgressBar bar;
	private final V2ChipRow views;
	private final JPanel content = new JPanel();

	/** The ONE expanded tier (the clog grammar), or null. */
	private String expandedTier;
	/** Step rows open NON-exclusively into Wells, keyed by clue id. */
	private final Set<String> expandedSteps = new HashSet<>();
	/** Unit rows open NON-exclusively into Wells, keyed by object id. */
	private final Set<Integer> expandedUnits = new HashSet<>();
	/** Include filters (the diaries grammar): checked = the done things
	 *  show too; both default OFF so each view opens on its to-do list. */
	private boolean showDoable;
	private boolean showFilled;

	CluesTab(AccountState state, ClueStashModule module, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.sprites = new SpriteCache(module.itemManager(), listener);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		hero = V2Surface.card(theme);
		bar = new V2ProgressBar(theme);
		add(hero);
		add(Box.createVerticalStrut(4));

		views = new V2ChipRow(theme, true, "Steps", "STASH");
		views.onChange(i ->
		{
			expandedTier = null;
			rebuildContent();
		});
		add(views);
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

	// ── test seams ────────────────────────────────────────────────────

	/** Test seam: 0 = Steps, 1 = STASH. */
	void selectView(int index)
	{
		views.setSelected(index);
		expandedTier = null;
		rebuild();
	}

	void expandTierForTest(String tier)
	{
		expandedTier = tier;
		rebuild();
	}

	void expandStepForTest(String clueId)
	{
		expandedSteps.add(clueId);
		rebuild();
	}

	void expandUnitForTest(int objectId)
	{
		expandedUnits.add(objectId);
		rebuild();
	}

	private void rebuild()
	{
		rebuildHero();
		rebuildContent();
	}

	// ── the hero card ─────────────────────────────────────────────────

	/**
	 * "STASH units filled: f / F" between the beginner and master scrolls
	 * — the tier span the page climbs — with the steps-doable tally and
	 * the ready-to-fill count on a counter line.
	 */
	private void rebuildHero()
	{
		ClueStepsPack pack = module.pack();
		int filled = 0;
		int units = 0;
		int doable = 0;
		int steps = 0;
		int ready = 0;
		if (pack != null)
		{
			for (ClueStepsPack.Stash unit : pack.stash)
			{
				units++;
				if (state.isStashFilled(unit.objectId))
				{
					filled++;
				}
				if (module.readyToFill(unit))
				{
					ready++;
				}
			}
			for (ClueStepsPack.Clue clue : pack.clues)
			{
				steps++;
				if (ClueStashModule.doable(clue, module.owningView()))
				{
					doable++;
				}
			}
		}
		hero.removeAll();
		JPanel top = row();
		top.add(scrollEmblem(SCROLLS[0]));
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("STASH units filled", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(filled + " / " + units, OsrsSkin.TITLE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(scrollEmblem(SCROLLS[SCROLLS.length - 1]));
		cap(top);
		hero.add(top);
		hero.add(Box.createVerticalStrut(3));
		// the fill answers the SAME numbers as the label riding it
		bar.fraction(units == 0 ? 0 : (double) filled / units);
		bar.labels("", filled + " / " + units, "");
		hero.add(bar);
		hero.add(Box.createVerticalStrut(3));
		Color doableColour = doable == 0 ? V2Tokens.BLOCKED
			: doable >= steps ? OsrsSkin.VALUE : OsrsSkin.COUNT_YELLOW;
		JPanel counters = row();
		counters.add(new OsrsLabel("Steps doable: ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		counters.add(new OsrsLabel(doable + "/" + steps,
			doableColour, OsrsSkin.smallFont()).leftAligned());
		counters.add(Box.createHorizontalGlue());
		counters.add(new OsrsLabel(ready + " ready to fill",
			ready > 0 ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.smallFont()));
		cap(counters);
		hero.add(counters);
		cap(hero);
		hero.revalidate();
		hero.repaint();
	}

	/** A tier's clue scroll, flanking the hero. */
	private JComponent scrollEmblem(int itemId)
	{
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		Image sprite = sprites.get(itemId, -1, 30);
		if (sprite != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(sprite));
		}
		else
		{
			icon.setPreferredSize(new Dimension(26, 30));
		}
		return icon;
	}

	// ── the tier grid ─────────────────────────────────────────────────

	private void rebuildContent()
	{
		content.removeAll();
		ClueStepsPack pack = module.pack();
		if (pack == null)
		{
			content.add(note("Clue pack unavailable."));
		}
		else if (views.selected() == 1)
		{
			stashGrid(pack);
		}
		else
		{
			stepsGrid(pack);
		}
		content.revalidate();
		content.repaint();
	}

	/** The per-view include checkbox on its own row (the diaries filter
	 *  grammar) — rebuilt with the content, so it reads its boolean. */
	private JComponent filterRow(String label, boolean value, Runnable flip)
	{
		JPanel holder = row();
		holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT));
		holder.add(new V2Checkbox(theme, label, value, flip));
		holder.add(Box.createHorizontalGlue());
		return holder;
	}

	private void stepsGrid(ClueStepsPack pack)
	{
		content.add(filterRow("Doable", showDoable, () ->
		{
			showDoable = !showDoable;
			rebuildContent();
		}));
		content.add(Box.createVerticalStrut(4));

		Map<String, List<ClueStepsPack.Clue>> byTier = new LinkedHashMap<>();
		for (ClueStepsPack.Clue clue : pack.clues)
		{
			byTier.computeIfAbsent(clue.tier, t -> new ArrayList<>()).add(clue);
		}
		// a card with nothing behind the filter does not show (the CA rule)
		List<String> tiers = new ArrayList<>();
		for (String tier : TIERS)
		{
			List<ClueStepsPack.Clue> clues = byTier.get(tier);
			if (clues != null && clues.stream()
				.anyMatch(c -> showDoable || !ClueStashModule.doable(c, module.owningView())))
			{
				tiers.add(tier);
			}
		}
		if (tiers.isEmpty())
		{
			content.add(note("Every emote step is doable."));
			return;
		}
		for (int start = 0; start < tiers.size(); start += TIER_COLS)
		{
			JPanel line = row();
			line.add(Box.createHorizontalGlue());
			for (int col = 0; col < TIER_COLS && start + col < tiers.size(); col++)
			{
				if (col > 0)
				{
					line.add(Box.createHorizontalStrut(V2Tokens.ROW));
				}
				String tier = tiers.get(start + col);
				List<ClueStepsPack.Clue> clues = byTier.get(tier);
				int doable = (int) clues.stream()
					.filter(c -> ClueStashModule.doable(c, module.owningView())).count();
				line.add(tierTile(tier, doable, clues.size()));
			}
			line.add(Box.createHorizontalGlue());
			cap(line);
			content.add(line);
			content.add(Box.createVerticalStrut(V2Tokens.ROW));
			for (int col = 0; col < TIER_COLS && start + col < tiers.size(); col++)
			{
				if (tiers.get(start + col).equals(expandedTier))
				{
					stepsDetail(expandedTier, byTier.get(expandedTier));
					content.add(Box.createVerticalStrut(V2Tokens.ROW));
				}
			}
		}
	}

	private void stashGrid(ClueStepsPack pack)
	{
		content.add(filterRow("Filled", showFilled, () ->
		{
			showFilled = !showFilled;
			rebuildContent();
		}));
		content.add(note("Ownership counts your bank, carried items, and every storage "
			+ "Where's my stuff has seen (as of its last visit)."));
		content.add(Box.createVerticalStrut(4));

		Map<String, List<ClueStepsPack.Stash>> byTier = new LinkedHashMap<>();
		for (ClueStepsPack.Stash unit : pack.stash)
		{
			byTier.computeIfAbsent(unit.tier, t -> new ArrayList<>()).add(unit);
		}
		List<String> tiers = new ArrayList<>();
		for (String tier : TIERS)
		{
			List<ClueStepsPack.Stash> units = byTier.get(tier);
			if (units != null && units.stream()
				.anyMatch(u -> showFilled || !state.isStashFilled(u.objectId)))
			{
				tiers.add(tier);
			}
		}
		if (tiers.isEmpty())
		{
			content.add(note("Every STASH unit is filled."));
			return;
		}
		for (int start = 0; start < tiers.size(); start += TIER_COLS)
		{
			JPanel line = row();
			line.add(Box.createHorizontalGlue());
			for (int col = 0; col < TIER_COLS && start + col < tiers.size(); col++)
			{
				if (col > 0)
				{
					line.add(Box.createHorizontalStrut(V2Tokens.ROW));
				}
				String tier = tiers.get(start + col);
				List<ClueStepsPack.Stash> units = byTier.get(tier);
				int filled = (int) units.stream()
					.filter(u -> state.isStashFilled(u.objectId)).count();
				line.add(tierTile(tier, filled, units.size()));
			}
			line.add(Box.createHorizontalGlue());
			cap(line);
			content.add(line);
			content.add(Box.createVerticalStrut(V2Tokens.ROW));
			for (int col = 0; col < TIER_COLS && start + col < tiers.size(); col++)
			{
				if (tiers.get(start + col).equals(expandedTier))
				{
					stashDetail(expandedTier, byTier.get(expandedTier));
					content.add(Box.createVerticalStrut(V2Tokens.ROW));
				}
			}
		}
	}

	/** One tier as a square Card in the clog page-grid grammar: the tier's
	 *  clue scroll as the emblem, corner count, meter strip, no tooltip. */
	private V2Tile tierTile(String tier, int done, int total)
	{
		boolean complete = total > 0 && done >= total;
		boolean open = tier.equals(expandedTier);
		int scroll = SCROLLS[tierIndex(tier)];
		Image emblem = sprites.getBox(scroll, TIER_EMBLEM);
		Color cornerDone = complete ? V2Tokens.DONE
			: done == 0 ? V2Tokens.BLOCKED : V2Tokens.ACTION;
		Color cornerRest = complete ? V2Tokens.DONE : V2Tokens.ACTION;
		return new V2Tile(theme, emblem, tier, TIER_TILE, () ->
			{
				expandedTier = open ? null : tier;
				rebuildContent();
			})
			.card().captionLines(2).captionInside()
			.captionStatus(complete ? V2Tokens.DONE : V2Tokens.ACTION)
			.corner(String.valueOf(done), cornerDone, "/" + total, cornerRest)
			.selected(open)
			.meter(total == 0 ? Double.NaN : (double) done / total);
	}

	private static int tierIndex(String tier)
	{
		for (int i = 0; i < TIERS.length; i++)
		{
			if (TIERS[i].equals(tier))
			{
				return i;
			}
		}
		return 0;
	}

	// ── the expanded tier: steps ──────────────────────────────────────

	private void stepsDetail(String tier, List<ClueStepsPack.Clue> clues)
	{
		int doable = (int) clues.stream()
			.filter(c -> ClueStashModule.doable(c, module.owningView())).count();
		content.add(tierHeader(tier, "Steps doable: ", doable, clues.size(), null));
		content.add(Box.createVerticalStrut(V2Tokens.TIGHT));

		// evaluate each step ONCE per rebuild (2026-07-20 audit); blocked
		// steps first, closest-to-doable leading — the graph's own distance
		Map<ClueStepsPack.Clue, Boolean> doableBy = new java.util.IdentityHashMap<>();
		Map<ClueStepsPack.Clue, Double> gapBy = new java.util.IdentityHashMap<>();
		for (ClueStepsPack.Clue clue : clues)
		{
			boolean can = ClueStashModule.doable(clue, module.owningView());
			doableBy.put(clue, can);
			double gap = 0;
			if (!can && clue.reqs != null)
			{
				for (String req : clue.reqs)
				{
					gap += com.ironhub.requirements.Requirements.parse(req).gap(module.owningView());
				}
			}
			gapBy.put(clue, gap);
		}
		List<ClueStepsPack.Clue> shown = new ArrayList<>();
		for (ClueStepsPack.Clue clue : clues)
		{
			if (showDoable || !doableBy.get(clue))
			{
				shown.add(clue);
			}
		}
		shown.sort(java.util.Comparator.comparing(doableBy::get).thenComparing(gapBy::get));

		V2Surface tile = V2Surface.tile(theme);
		tile.setAlignmentX(LEFT_ALIGNMENT);
		int limit = Math.min(MAX_ROWS, shown.size());
		for (int i = 0; i < limit; i++)
		{
			ClueStepsPack.Clue clue = shown.get(i);
			if (i > 0)
			{
				tile.add(Box.createVerticalStrut(3));
			}
			tile.add(stepHead(clue, doableBy.get(clue)));
			if (expandedSteps.contains(clue.id))
			{
				tile.add(Box.createVerticalStrut(2));
				tile.add(stepWell(clue, doableBy.get(clue)));
			}
		}
		cap(tile);
		content.add(tile);
		if (shown.size() > limit)
		{
			content.add(note("+ " + (shown.size() - limit) + " more steps"));
		}
	}

	/**
	 * One step's ROW: the outfit as ITEM ICONS — solid when the requirement
	 * is met, greyed when not (Luke, 2026-07-28; the clog grid's ghosting)
	 * — and the +/x goal glyph while blocked. A click opens the step's
	 * Well with the clue text and requirements.
	 */
	private JComponent stepHead(ClueStepsPack.Clue clue, boolean doable)
	{
		JPanel head = row();
		boolean anyIcon = false;
		for (String raw : clue.reqs)
		{
			Requirement req = com.ironhub.requirements.Requirements.parse(raw);
			boolean met = req.isMet(module.owningView());
			int itemId = reqIcon(raw, met);
			if (itemId <= 0)
			{
				continue;
			}
			if (anyIcon)
			{
				head.add(Box.createHorizontalStrut(2));
			}
			anyIcon = true;
			head.add(reqIconLabel(itemId, met));
		}
		if (!anyIcon)
		{
			// a step with no readable outfit falls back to its text
			head.add(OsrsLabel.wrapped(clue.text, ROW_WRAP,
				doable ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		head.add(Box.createHorizontalGlue());
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		if (!doable && !clue.reqs.isEmpty())
		{
			boolean tracked = module.isGoal(clue);
			JPanel anchor = new JPanel(new java.awt.BorderLayout());
			anchor.setOpaque(false);
			anchor.putClientProperty(OWN_ACTION, Boolean.TRUE);
			anchor.add(goalGlyph(tracked,
				tracked ? "Remove from Goals" : "Track unlocking this step in Goals",
				() ->
				{
					if (tracked)
					{
						module.removeGoal(clue);
					}
					else
					{
						module.addGoal(clue);
					}
					javax.swing.SwingUtilities.invokeLater(this::rebuildContent);
				}), java.awt.BorderLayout.NORTH);
			head.add(anchor);
		}
		head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		clickAnywhere(head, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!expandedSteps.remove(clue.id))
				{
					expandedSteps.add(clue.id);
				}
				rebuildContent();
			}
		});
		cap(head);
		return head;
	}

	/**
	 * The icon for one requirement: the first OWNED alternative when met,
	 * else the first alternative. The pack's reqs are all
	 * {@code item:<id>:<qty>:<name>} (optionally {@code any:}-grouped), so
	 * the raw string parses directly.
	 */
	private int reqIcon(String raw, boolean met)
	{
		String body = raw.startsWith("any:") ? raw.substring(4) : raw;
		String[] alts = body.split("\\|");
		int first = altItemId(alts[0]);
		if (met)
		{
			for (String alt : alts)
			{
				int id = altItemId(alt);
				if (id > 0 && module.owningView().canonicalStock(id) > 0)
				{
					return id;
				}
			}
		}
		return first;
	}

	private static int altItemId(String alt)
	{
		String[] parts = alt.split(":");
		if (parts.length < 2 || !"item".equals(parts[0]))
		{
			return -1;
		}
		try
		{
			return Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** One outfit icon: solid when its requirement is met, GREYED when not
	 *  (GrayFilter, the Swing disabled treatment); a recessed slot headless. */
	private JComponent reqIconLabel(int itemId, boolean met)
	{
		JLabel slot = new JLabel();
		slot.setPreferredSize(new Dimension(22, 22));
		slot.setMinimumSize(new Dimension(22, 22));
		slot.setMaximumSize(new Dimension(22, 22));
		slot.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		Image sprite = sprites.getBox(itemId, 20);
		if (sprite != null)
		{
			slot.setIcon(new javax.swing.ImageIcon(met ? sprite
				: javax.swing.GrayFilter.createDisabledImage(sprite)));
		}
		else
		{
			slot.setOpaque(true);
			slot.setBackground(theme.recess);
		}
		return slot;
	}

	/** The open step's Well: the clue text, its standing, then every outfit
	 *  item in met colours — a missing item carries its where-from line
	 *  (formerly a hover tooltip). */
	private JComponent stepWell(ClueStepsPack.Clue clue, boolean doable)
	{
		V2Surface well = V2Surface.well(theme);
		int inset = com.ironhub.ui.v2.V2Well.CAP + V2Tokens.TIGHT;
		well.setBorder(new EmptyBorder(inset, inset, inset, inset));
		// the step's text leads (Luke, 2026-07-28 — the row is icons now)
		well.add(OsrsLabel.wrapped(clue.text, WELL_WRAP,
			V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
		JPanel meta = row();
		meta.add(new OsrsLabel(doable ? "Doable now" : "Missing items",
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		meta.add(Box.createHorizontalGlue());
		cap(meta);
		well.add(meta);
		for (String raw : clue.reqs)
		{
			Requirement req = com.ironhub.requirements.Requirements.parse(raw);
			boolean met = req.isMet(module.owningView());
			String line = "· " + req.describe();
			if (!met)
			{
				Integer itemId = firstMissingItem(req);
				String source = itemId == null || module.itemSources() == null ? null
					: module.itemSources().sourceLine(itemId, state,
						state.getItemSourcePref(itemId));
				if (source != null)
				{
					line += " — " + source;
				}
			}
			well.add(OsrsLabel.wrapped(line, WELL_WRAP,
				met ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		cap(well);
		return well;
	}

	private Integer firstMissingItem(Requirement req)
	{
		for (Requirement leaf : req.missing(state))
		{
			Integer itemId = leaf.itemId();
			if (itemId != null)
			{
				return itemId;
			}
		}
		return null;
	}

	// ── the expanded tier: STASH ──────────────────────────────────────

	private void stashDetail(String tier, List<ClueStepsPack.Stash> units)
	{
		int filled = (int) units.stream()
			.filter(u -> state.isStashFilled(u.objectId)).count();
		int built = (int) units.stream()
			.filter(u -> state.isStashBuilt(u.objectId)).count();
		content.add(tierHeader(tier, "Units filled: ", filled, units.size(),
			built + "/" + units.size() + " built"));
		content.add(Box.createVerticalStrut(V2Tokens.TIGHT));

		List<ClueStepsPack.Stash> shown = new ArrayList<>();
		for (ClueStepsPack.Stash unit : units)
		{
			if (showFilled || !state.isStashFilled(unit.objectId))
			{
				shown.add(unit);
			}
		}
		V2Surface tile = V2Surface.tile(theme);
		tile.setAlignmentX(LEFT_ALIGNMENT);
		int limit = Math.min(MAX_ROWS, shown.size());
		for (int i = 0; i < limit; i++)
		{
			ClueStepsPack.Stash unit = shown.get(i);
			if (i > 0)
			{
				tile.add(Box.createVerticalStrut(3));
			}
			tile.add(unitHead(unit));
			if (expandedUnits.contains(unit.objectId))
			{
				tile.add(Box.createVerticalStrut(2));
				tile.add(unitWell(unit));
			}
		}
		cap(tile);
		content.add(tile);
		if (shown.size() > limit)
		{
			content.add(note("+ " + (shown.size() - limit) + " more units"));
		}
	}

	/** One unit's ROW: the location on the filled scale, a green "ready"
	 *  tag when its outfit is owned. A click opens the unit's Well. */
	private JComponent unitHead(ClueStepsPack.Stash unit)
	{
		boolean filled = state.isStashFilled(unit.objectId);
		boolean built = state.isStashBuilt(unit.objectId);
		Color colour = filled ? OsrsSkin.VALUE : built ? OsrsSkin.TITLE : OsrsSkin.MUTED;
		JPanel head = row();
		OsrsLabel name = OsrsLabel.wrapped(unit.name, ROW_WRAP, colour, OsrsSkin.smallFont())
			.leftAligned();
		head.add(name);
		head.add(Box.createHorizontalGlue());
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		if (module.readyToFill(unit))
		{
			head.add(new OsrsLabel("ready", OsrsSkin.VALUE, OsrsSkin.smallFont()));
		}
		head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		clickAnywhere(head, new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!expandedUnits.remove(unit.objectId))
				{
					expandedUnits.add(unit.objectId);
				}
				rebuildContent();
			}
		});
		cap(head);
		return head;
	}

	/** The open unit's Well: its status, the outfit in met colours, and the
	 *  manual Mark-filled action — the escape hatch for STASHes filled
	 *  before Iron Hub, moved off the row click. */
	private JComponent unitWell(ClueStepsPack.Stash unit)
	{
		boolean filled = state.isStashFilled(unit.objectId);
		boolean built = state.isStashBuilt(unit.objectId);
		String standing = filled ? "Filled" : built ? "Built, empty" : "Not built";
		V2Surface well = V2Surface.well(theme);
		int inset = com.ironhub.ui.v2.V2Well.CAP + V2Tokens.TIGHT;
		well.setBorder(new EmptyBorder(inset, inset, inset, inset));
		JPanel meta = row();
		meta.add(new OsrsLabel(standing, V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
		if (module.readyToFill(unit))
		{
			meta.add(new OsrsLabel(" · ready to fill",
				OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		}
		meta.add(Box.createHorizontalGlue());
		cap(meta);
		well.add(meta);
		ClueStepsPack.Clue clue = unit.clueId == null || module.pack() == null
			? null : module.pack().clue(unit.clueId);
		if (clue != null)
		{
			for (String raw : clue.reqs)
			{
				Requirement req = com.ironhub.requirements.Requirements.parse(raw);
				well.add(OsrsLabel.wrapped("· " + req.describe(), WELL_WRAP,
					req.isMet(module.owningView()) ? OsrsSkin.VALUE : OsrsSkin.MUTED,
					OsrsSkin.smallFont()).leftAligned());
			}
		}
		well.add(Box.createVerticalStrut(2));
		JPanel actions = row();
		OsrsLabel mark = actionLabel(filled ? "Unmark filled" : "Mark filled", () ->
		{
			module.toggleFilled(unit);
			javax.swing.SwingUtilities.invokeLater(this::rebuild);
		});
		mark.setToolTipText("For STASHes filled before Iron Hub existed");
		actions.add(mark);
		actions.add(Box.createHorizontalGlue());
		cap(actions);
		well.add(actions);
		cap(well);
		return well;
	}

	// ── shared pieces ─────────────────────────────────────────────────

	/** Tier name, a counter in the game's colour scale, and optional sub
	 *  text — the header card an expanded tier opens with (the CA shape). */
	private JComponent tierHeader(String tier, String label, int done, int total, String sub)
	{
		V2Surface card = V2Surface.card(theme);
		JPanel titleRow = row();
		titleRow.add(new OsrsLabel(tier, OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		titleRow.add(Box.createHorizontalGlue());
		cap(titleRow);
		card.add(titleRow);
		Color colour = done == 0 ? V2Tokens.BLOCKED
			: done >= total ? OsrsSkin.VALUE : OsrsSkin.COUNT_YELLOW;
		JPanel counts = row();
		counts.add(new OsrsLabel(label, OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		counts.add(new OsrsLabel(done + "/" + total, colour, OsrsSkin.smallFont()).leftAligned());
		counts.add(Box.createHorizontalGlue());
		if (sub != null)
		{
			counts.add(new OsrsLabel(sub, OsrsSkin.MUTED, OsrsSkin.smallFont()));
		}
		cap(counts);
		card.add(counts);
		cap(card);
		return card;
	}

	/** A Well action in skin colours — faint until hovered (the quests
	 *  grammar). */
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

	/** The +/× goal affordance in skin colours — faint until hovered. */
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

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent note(String text)
	{
		JPanel holder = row();
		holder.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, 185, OsrsSkin.FAINT, OsrsSkin.smallFont())
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
