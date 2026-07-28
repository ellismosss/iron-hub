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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * (Luke, 2026-07-28) — and since the same day ONE combined view: each
 * clue step row carries its STASH unit's state, so the old Steps · STASH
 * chip split is gone.
 *
 * <ul>
 * <li>a hero Card — "STASH units filled" between the beginner and master
 *     scrolls over the large sprite bar, with the steps-doable tally and
 *     the ready-to-fill count on a counter line;
 * <li>a "Doable" include checkbox (default OFF — the tab opens on the
 *     to-do list);
 * <li>the six tiers as 2-wide square CARD tiles — the tier's own clue
 *     scroll as the emblem, corner counts, meter strips — expanding
 *     IN-LINE below their row, one at a time;
 * <li>the expanded tier: a header card (steps doable · STASH filled),
 *     then every step on ONE Tile with subtle dividers between rows —
 *     each row the step's OUTFIT as item icons filling the row's width,
 *     dark-ghosted when unobtained, with a STASH state dot (green
 *     filled · orange built-empty · faint unbuilt) — opening a Well
 *     with the clue text, standing, STASH state, the outfit in met
 *     colours with where-from lines, and the manual Mark-filled action.
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
	/** Outfit icons big enough to FILL the row (Luke, 2026-07-28). */
	private static final int ICON_SLOT = 36;
	private static final int ICON_ART = 32;
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
	private final JPanel content = new JPanel();

	/** The ONE expanded tier (the clog grammar), or null. */
	private String expandedTier;
	/** Step rows open NON-exclusively into Wells, keyed by clue id. */
	private final Set<String> expandedSteps = new HashSet<>();
	/** Include filter (the diaries grammar): checked = doable steps show
	 *  too; OFF by default so the tab opens on the to-do list. */
	private boolean showDoable;
	/** clue id -> its STASH unit, built once per pack. */
	private Map<String, ClueStepsPack.Stash> unitByClue;

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

	private void rebuild()
	{
		rebuildHero();
		rebuildContent();
	}

	private ClueStepsPack.Stash unitFor(ClueStepsPack.Clue clue)
	{
		if (unitByClue == null)
		{
			unitByClue = new HashMap<>();
			if (module.pack() != null)
			{
				for (ClueStepsPack.Stash unit : module.pack().stash)
				{
					if (unit.clueId != null)
					{
						unitByClue.putIfAbsent(unit.clueId, unit);
					}
				}
			}
		}
		return unitByClue.get(clue.id);
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
			content.revalidate();
			content.repaint();
			return;
		}
		JPanel filterRow = row();
		filterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT));
		filterRow.add(new V2Checkbox(theme, "Doable", showDoable, () ->
		{
			showDoable = !showDoable;
			rebuildContent();
		}));
		filterRow.add(Box.createHorizontalGlue());
		content.add(filterRow);
		content.add(note("Ownership counts your bank, carried items, and every storage "
			+ "Where's my stuff has seen (as of its last visit)."));
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
					tierDetail(expandedTier, byTier.get(expandedTier));
					content.add(Box.createVerticalStrut(V2Tokens.ROW));
				}
			}
		}
		content.revalidate();
		content.repaint();
	}

	/** One tier as a square Card in the clog page-grid grammar: the tier's
	 *  clue scroll as the emblem, corner count, meter strip, no tooltip. */
	private V2Tile tierTile(String tier, int done, int total)
	{
		boolean complete = total > 0 && done >= total;
		boolean open = tier.equals(expandedTier);
		Image emblem = sprites.getBox(SCROLLS[tierIndex(tier)], TIER_EMBLEM);
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

	// ── the expanded tier ─────────────────────────────────────────────

	private void tierDetail(String tier, List<ClueStepsPack.Clue> clues)
	{
		int doable = (int) clues.stream()
			.filter(c -> ClueStashModule.doable(c, module.owningView())).count();
		int units = 0;
		int filled = 0;
		for (ClueStepsPack.Clue clue : clues)
		{
			ClueStepsPack.Stash unit = unitFor(clue);
			if (unit != null)
			{
				units++;
				if (state.isStashFilled(unit.objectId))
				{
					filled++;
				}
			}
		}
		content.add(tierHeader(tier, doable, clues.size(),
			units > 0 ? filled + "/" + units + " STASH filled" : null));
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
					gap += com.ironhub.requirements.Requirements.parse(req)
						.gap(module.owningView());
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
				// a subtle divider between steps (the diaries grammar)
				tile.add(Box.createVerticalStrut(3));
				tile.add(divider());
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

	/** Tier name, "Steps doable: d/D" in the game's colour scale, and the
	 *  tier's STASH tally at the right. */
	private JComponent tierHeader(String tier, int done, int total, String sub)
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
		counts.add(new OsrsLabel("Steps doable: ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
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

	/**
	 * One step's ROW: the outfit as ITEM ICONS spread across the row's full
	 * width — dark-ghosted when the requirement is unmet (Luke, 2026-07-28)
	 * — its STASH unit's state as a coloured dot, and the +/x goal glyph
	 * while blocked. A click opens the step's Well.
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
				// glue BETWEEN the icons spreads them across the row
				head.add(Box.createHorizontalGlue());
			}
			anyIcon = true;
			head.add(reqIconLabel(itemId, met));
		}
		if (!anyIcon)
		{
			// a step with no readable outfit falls back to its text
			head.add(OsrsLabel.wrapped(clue.text, ROW_WRAP,
				doable ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
			head.add(Box.createHorizontalGlue());
		}
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		ClueStepsPack.Stash unit = unitFor(clue);
		if (unit != null)
		{
			head.add(stashDot(unit));
			head.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
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
			// pin the anchor to its preferred size — a JPanel's default max
			// (Integer.MAX_VALUE) out-competes glue (Short.MAX_VALUE) for
			// the row's spare width and swallowed the whole row
			anchor.setMaximumSize(anchor.getPreferredSize());
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

	/** The step's STASH state at a glance: green filled, orange built and
	 *  empty, faint unbuilt — the Well names it in words. PAINTED, not a
	 *  glyph (the OSRS font carries no bullet — GlyphSafetyTest). */
	private JComponent stashDot(ClueStepsPack.Stash unit)
	{
		boolean filled = state.isStashFilled(unit.objectId);
		boolean built = state.isStashBuilt(unit.objectId);
		Color colour = filled ? OsrsSkin.VALUE : built ? OsrsSkin.TITLE : OsrsSkin.FAINT;
		JComponent dot = new JComponent()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(colour);
				g2.fillOval(1, (getHeight() - 6) / 2, 6, 6);
				g2.dispose();
			}
		};
		Dimension size = new Dimension(8, 10);
		dot.setPreferredSize(size);
		dot.setMinimumSize(size);
		dot.setMaximumSize(size);
		return dot;
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

	/** One outfit icon: solid when its requirement is met, DARKENED when
	 *  not (Luke, 2026-07-28 — grey read lighter); a recessed slot headless. */
	private JComponent reqIconLabel(int itemId, boolean met)
	{
		JLabel slot = new JLabel();
		slot.setPreferredSize(new Dimension(ICON_SLOT, ICON_SLOT));
		slot.setMinimumSize(new Dimension(ICON_SLOT, ICON_SLOT));
		slot.setMaximumSize(new Dimension(ICON_SLOT, ICON_SLOT));
		slot.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		Image sprite = sprites.getBox(itemId, ICON_ART);
		if (sprite != null)
		{
			slot.setIcon(new javax.swing.ImageIcon(met ? sprite : darkened(sprite)));
		}
		else
		{
			slot.setOpaque(true);
			slot.setBackground(theme.recess);
		}
		return slot;
	}

	/** A DARK ghost of a sprite: the sprite's own pixels washed toward
	 *  black, never the washed-out GrayFilter look. */
	private static Image darkened(Image sprite)
	{
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
			sprite.getWidth(null), sprite.getHeight(null),
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.drawImage(sprite, 0, 0, null);
		// paint black over the sprite's own pixels only
		g.setComposite(java.awt.AlphaComposite.SrcAtop.derive(0.65f));
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		g.dispose();
		return out;
	}

	/** A subtle 1px divider between step rows (the diaries grammar). */
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

	/**
	 * The open step's Well: the clue text, its standing and STASH state,
	 * every outfit item in met colours — a missing item carries its
	 * where-from line — and the manual Mark-filled action (the escape
	 * hatch for STASHes filled before Iron Hub).
	 */
	private JComponent stepWell(ClueStepsPack.Clue clue, boolean doable)
	{
		V2Surface well = V2Surface.well(theme);
		int inset = com.ironhub.ui.v2.V2Well.CAP + V2Tokens.TIGHT;
		well.setBorder(new EmptyBorder(inset, inset, inset, inset));
		well.add(OsrsLabel.wrapped(clue.text, WELL_WRAP,
			V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
		ClueStepsPack.Stash unit = unitFor(clue);
		JPanel meta = row();
		meta.add(new OsrsLabel(doable ? "Doable now" : "Missing items",
			doable ? OsrsSkin.VALUE : OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		if (unit != null)
		{
			boolean filled = state.isStashFilled(unit.objectId);
			boolean built = state.isStashBuilt(unit.objectId);
			meta.add(new OsrsLabel(" · STASH "
					+ (filled ? "filled" : built ? "built, empty" : "not built"),
				OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		}
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
		if (unit != null)
		{
			boolean filled = state.isStashFilled(unit.objectId);
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
		}
		cap(well);
		return well;
	}

	private Integer firstMissingItem(Requirement req)
	{
		for (Requirement leaf : req.missing(module.owningView()))
		{
			Integer itemId = leaf.itemId();
			if (itemId != null)
			{
				return itemId;
			}
		}
		return null;
	}

	// ── shared pieces ─────────────────────────────────────────────────

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
