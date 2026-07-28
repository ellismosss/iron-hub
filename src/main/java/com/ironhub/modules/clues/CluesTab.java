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
	/** Each tier's fill sprite for the hero bar's six sections (Luke's
	 *  2026-07-28 drop; master arrived as progress_bar_green_master). */
	private static final String[] TIER_BARS = {
		"ui/progress_bar/progress_bar_clues_beginner",
		"ui/progress_bar/progress_bar_clues_easy",
		"ui/progress_bar/progress_bar_clues_medium",
		"ui/progress_bar/progress_bar_clues_hard",
		"ui/progress_bar/progress_bar_clues_elite",
		"ui/progress_bar/progress_bar_green_master"};
	/** The clog page grid's geometry: two perfect squares across 217px. */
	private static final int TIER_COLS = 2;
	private static final int TIER_TILE = 106;
	private static final int TIER_EMBLEM = 44;
	/** Outfit icons: BIG by default, stepping down only when a 5-6 icon
	 *  step needs the room (Luke, 2026-07-28 — "all too small"). */
	private static final int ICON_SLOT_BIG = 30;
	private static final int ICON_SLOT_SMALL = 24;
	private static final int STASH_ICON = 22;
	private static final int BADGE = 10;
	/**
	 * Emote phrases -> icons/emote sprite keys, matched word-bounded and
	 * longest-first against the clue text ("Bow" never fires on
	 * "crossbow"). Aliases cover wordings that differ from file stems.
	 */
	private static final String[][] EMOTES = {
		{"jump for joy", "jump_for_joy"}, {"blow a raspberry", "raspberry"},
		{"blow a kiss", "blow_kiss"}, {"slap your head", "slap_head"},
		{"goblin salute", "goblin_salute"}, {"goblin bow", "goblin_bow"},
		{"zombie walk", "zombie_walk"}, {"zombie dance", "zombie_dance"},
		{"crab dance", "crab_dance"}, {"star jump", "star_jump"},
		{"push up", "push_up"}, {"sit up", "sit_up"}, {"sit down", "sit_down"},
		{"fortis salute", "fortis_salute"}, {"air guitar", "air_guitar"},
		{"headbang", "headbang"}, {"beckon", "beckon"}, {"cheer", "cheer"},
		{"clap", "clap"}, {"dance", "dance"}, {"jig", "jig"}, {"panic", "panic"},
		{"salute", "salute"}, {"shrug", "shrug"}, {"spin", "spin"},
		{"stamp", "stamp"}, {"stomp", "stamp"}, {"think", "think"},
		{"wave", "wave"}, {"yawn", "yawn"}, {"bow", "bow"}, {"cry", "cry"},
		{"laugh", "laugh"}, {"flap", "flap"}, {"jog", "jog"}, {"lean", "lean"},
		{"angry", "angry"}, {"scared", "scared"}};
	/** Row ceiling (the Bank tab's grammar). */
	private static final int PAGE_ROWS = 10;
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
	/** The expanded tier's page of 10 steps (Luke, 2026-07-28). */
	private int page;
	/** Router stops skipped this session (by unit key) — cleared once
	 *  every ready stop is skipped. */
	private final Set<String> routeSkips = new HashSet<>();
	/** The router's "Items for this tier" fold. */
	private boolean routeMissingOpen;
	/** The unit key the router last auto-pathed to — the route re-posts
	 *  only when the NEXT stop actually changes, never per rebuild. */
	private String lastAutoRouted;
	/** The FROZEN route (Luke, 2026-07-28: "follow a set route unless
	 *  skipped"): the NN order locked in when the tier starts — moving
	 *  never reshuffles it; filled stops drop out, newly-ready units
	 *  append, and only fill/Skip advance the pointer. */
	private String routeTier;
	private final List<String> routeOrder = new ArrayList<>();
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

	void openRouteMissingForTest()
	{
		routeMissingOpen = true;
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
		// the bar's six sections: each tier's doable-steps progress
		int[] tierSteps = new int[TIERS.length];
		int[] tierDoable = new int[TIERS.length];
		if (pack != null)
		{
			for (ClueStepsPack.Clue clue : pack.clues)
			{
				int idx = tierIndex(clue.tier);
				tierSteps[idx]++;
				if (ClueStashModule.doable(clue, module.owningView()))
				{
					tierDoable[idx]++;
				}
			}
		}
		double[] sections = new double[TIERS.length];
		for (int i = 0; i < TIERS.length; i++)
		{
			sections[i] = tierSteps[i] == 0 ? Double.NaN
				: (double) tierDoable[i] / tierSteps[i];
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
		// ONE bar split six ways (Luke, 2026-07-28): each section is a
		// tier's doable-steps progress in that tier's own colour — no
		// riding label, the sections are the reading
		bar.sections(sections, TIER_BARS);
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
		JComponent router = routerCard(pack);
		if (router != null)
		{
			content.add(router);
			content.add(Box.createVerticalStrut(4));
		}

		JPanel filterRow = row();
		filterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT));
		filterRow.add(new V2Checkbox(theme, "Show doable", showDoable, () ->
		{
			showDoable = !showDoable;
			page = 0;
			rebuildContent();
		}));
		filterRow.add(Box.createHorizontalGlue());
		boolean anyMarks = pack.stash.stream().anyMatch(u ->
			state.isStashBuilt(u.objectId) || state.isStashFilled(u.objectId));
		if (anyMarks)
		{
			OsrsLabel reset = actionLabel("Reset STASH detection", () ->
			{
				int answer = javax.swing.JOptionPane.showConfirmDialog(this,
					"Forget every STASH built/filled mark for this account?",
					"Reset STASH detection", javax.swing.JOptionPane.YES_NO_OPTION);
				if (answer == javax.swing.JOptionPane.YES_OPTION)
				{
					module.resetDetection(); // listener rebuilds
				}
			});
			reset.setToolTipText("Forget every built/filled mark so detection "
				+ "can restart clean — manual marks go too");
			filterRow.add(reset);
		}
		content.add(filterRow);
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

	// ── the STASH route card ──────────────────────────────────────────

	/**
	 * The stocking router (Luke's 2026-07-28 goal): the first tier with
	 * unfilled units is the ACTIVE one — the card names it, shows the
	 * NEXT ready-to-fill unit (nearest first, by way of the player's
	 * position) with the outfit to carry, hands the stop to Shortest
	 * Path on "Route", and folds everything the tier still needs —
	 * missing outfit items and build materials — into a Well. Detection
	 * marking a unit filled advances the route by itself.
	 */
	private JComponent routerCard(ClueStepsPack pack)
	{
		StashRouter.Plan plan = module.routePlan();
		if (plan.tier == null)
		{
			// every unit filled — nothing to route; drop a lingering path
			if (lastAutoRouted != null)
			{
				lastAutoRouted = null;
				module.clearRoute();
			}
			return null;
		}
		V2Surface card = V2Surface.card(theme);
		JPanel title = row();
		title.add(new OsrsLabel("STASH route: ", OsrsSkin.LABEL, OsrsSkin.font()).leftAligned());
		title.add(new OsrsLabel(plan.tier, OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		title.add(Box.createHorizontalGlue());
		Color countColour = plan.filled == 0 ? V2Tokens.BLOCKED : V2Tokens.ACTION;
		title.add(new OsrsLabel(plan.filled + "/" + plan.units + " filled",
			countColour, OsrsSkin.smallFont()));
		cap(title);
		card.add(title);

		// reconcile the frozen route: a new tier re-freezes from scratch,
		// stops no longer ready leave, newcomers append — existing stops
		// KEEP their position, so moving never re-points the router
		Map<String, StashRouter.Stop> readyByKey = new LinkedHashMap<>();
		for (StashRouter.Stop stop : plan.route)
		{
			readyByKey.put(stop.unit.key, stop);
		}
		if (!plan.tier.equals(routeTier))
		{
			routeTier = plan.tier;
			routeOrder.clear();
			routeSkips.clear();
		}
		routeOrder.removeIf(key -> !readyByKey.containsKey(key));
		for (StashRouter.Stop stop : plan.route)
		{
			if (!routeOrder.contains(stop.unit.key))
			{
				routeOrder.add(stop.unit.key);
			}
		}

		// the next stop: first on the frozen route the player hasn't
		// skipped (all skipped = the skips have served their purpose)
		StashRouter.Stop next = null;
		if (!routeOrder.isEmpty() && routeSkips.containsAll(routeOrder))
		{
			routeSkips.clear();
		}
		for (String key : routeOrder)
		{
			if (!routeSkips.contains(key))
			{
				next = readyByKey.get(key);
				break;
			}
		}
		// auto-route (Luke, 2026-07-28): the path follows the NEXT stop by
		// itself — on the first plan, after a fill advances the route, and
		// after a Skip — re-posting only when the stop changes
		if (next != null && !next.unit.key.equals(lastAutoRouted))
		{
			lastAutoRouted = next.unit.key;
			module.routeTo(next.unit);
		}
		else if (next == null && lastAutoRouted != null)
		{
			lastAutoRouted = null;
			module.clearRoute();
		}
		if (next != null)
		{
			card.add(Box.createVerticalStrut(2));
			card.add(OsrsLabel.wrapped("Next: " + next.unit.name, ROW_WRAP + 30,
				V2Tokens.STRONG, OsrsSkin.smallFont()).leftAligned());
			String standing = (next.distance >= 0 ? next.distance + " tiles away · " : "")
				+ (next.built ? "built, take the outfit" : "not built — take materials too");
			card.add(new OsrsLabel(standing, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
			if (next.clue != null && !next.clue.reqs.isEmpty())
			{
				card.add(Box.createVerticalStrut(2));
				JPanel outfit = row();
				for (String raw : next.clue.reqs)
				{
					outfit.add(badgedItem(raw, true, ICON_SLOT_SMALL));
					outfit.add(Box.createHorizontalStrut(2));
				}
				outfit.add(Box.createHorizontalGlue());
				cap(outfit);
				card.add(outfit);
			}
			card.add(Box.createVerticalStrut(2));
			JPanel actions = row();
			StashRouter.Stop stop = next;
			OsrsLabel route = actionLabel("Route there", () -> module.routeTo(stop.unit));
			route.setToolTipText("Send this stop to the Shortest Path plugin");
			actions.add(route);
			actions.add(Box.createHorizontalStrut(V2Tokens.ROW));
			if (plan.route.size() > 1)
			{
				actions.add(actionLabel("Skip", () ->
				{
					routeSkips.add(stop.unit.key);
					javax.swing.SwingUtilities.invokeLater(this::rebuildContent);
				}));
			}
			actions.add(Box.createHorizontalGlue());
			cap(actions);
			card.add(actions);
		}
		else
		{
			card.add(new OsrsLabel("Nothing ready to fill on this tier.",
				OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		}

		card.add(Box.createVerticalStrut(2));
		JPanel counters = row();
		counters.add(new OsrsLabel(plan.route.size() + " ready · "
				+ plan.waiting.size() + " waiting on items · " + plan.unbuilt + " unbuilt",
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		counters.add(Box.createHorizontalGlue());
		cap(counters);
		card.add(counters);
		boolean anyNeeds = !plan.loadout.isEmpty() || !plan.missing.isEmpty();
		if (anyNeeds)
		{
			JPanel foldRow = row();
			foldRow.add(actionLabel(routeMissingOpen ? "Hide tier items"
				: "Items for this tier (" + plan.loadout.size() + ")", () ->
			{
				routeMissingOpen = !routeMissingOpen;
				javax.swing.SwingUtilities.invokeLater(this::rebuildContent);
			}));
			foldRow.add(Box.createHorizontalGlue());
			cap(foldRow);
			card.add(foldRow);
		}
		if (routeMissingOpen && anyNeeds)
		{
			V2Surface well = V2Surface.well(theme);
			int inset = com.ironhub.ui.v2.V2Well.CAP + V2Tokens.TIGHT;
			well.setBorder(new EmptyBorder(inset, inset, inset, inset));
			// every item the tier's unfilled units want — each STASH keeps
			// its copy, so counts are per step — met in green
			for (StashRouter.Loadout item : plan.loadout)
			{
				String line = "· " + item.label;
				if (item.needed > 1 || !item.met())
				{
					line += " — " + Math.min(item.have, item.needed) + "/" + item.needed;
				}
				well.add(OsrsLabel.wrapped(line, WELL_WRAP,
					item.met() ? OsrsSkin.VALUE : OsrsSkin.MUTED,
					OsrsSkin.smallFont()).leftAligned());
			}
			for (String line : plan.missing)
			{
				well.add(OsrsLabel.wrapped("· " + line, WELL_WRAP,
					OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
			}
			cap(well);
			card.add(Box.createVerticalStrut(2));
			card.add(well);
		}
		cap(card);
		return card;
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
				page = 0;
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

		// ten steps to a page, arrows below (Luke, 2026-07-28)
		int pages = Math.max(1, (shown.size() + PAGE_ROWS - 1) / PAGE_ROWS);
		page = Math.max(0, Math.min(page, pages - 1));
		int from = page * PAGE_ROWS;
		List<ClueStepsPack.Clue> pageShown = shown.subList(from,
			Math.min(from + PAGE_ROWS, shown.size()));
		V2Surface tile = V2Surface.tile(theme);
		tile.setAlignmentX(LEFT_ALIGNMENT);
		for (int i = 0; i < pageShown.size(); i++)
		{
			ClueStepsPack.Clue clue = pageShown.get(i);
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
		if (pages > 1)
		{
			content.add(Box.createVerticalStrut(V2Tokens.TIGHT));
			JPanel pager = row();
			pager.add(Box.createHorizontalGlue());
			pager.add(new com.ironhub.ui.v2.V2SpriteButton(theme,
				com.ironhub.ui.v2.V2SpriteButton.ARROW_LEFT, () ->
				{
					if (page > 0)
					{
						page--;
						rebuildContent();
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
						rebuildContent();
					}
				}));
			pager.add(Box.createHorizontalGlue());
			cap(pager);
			content.add(pager);
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
	 * One step's ROW: its emote icon(s) then the outfit as ITEM ICONS, all
	 * LEFT-aligned (Luke, 2026-07-28), each item badged with a checkmark or
	 * red cross and dark-ghosted when unobtained; the STASH unit's icon
	 * alone at the RIGHT with its own badge, then the +/x goal glyph while
	 * blocked. A click opens the step's Well.
	 */
	private JComponent stepHead(ClueStepsPack.Clue clue, boolean doable)
	{
		JPanel head = row();
		List<String> emotes = emotesFor(clue);
		// big icons by default; step down only when the row is crowded
		int slot = emotes.size() + clue.reqs.size() >= 5 ? ICON_SLOT_SMALL : ICON_SLOT_BIG;
		boolean any = false;
		for (String emote : emotes)
		{
			if (any)
			{
				head.add(Box.createHorizontalStrut(2));
			}
			any = true;
			head.add(emoteIcon(emote, slot));
		}
		for (String raw : clue.reqs)
		{
			Requirement req = com.ironhub.requirements.Requirements.parse(raw);
			boolean met = req.isMet(module.owningView());
			if (altItemId((raw.startsWith("any:") ? raw.substring(4) : raw).split("\\|")[0]) <= 0)
			{
				continue;
			}
			if (any)
			{
				head.add(Box.createHorizontalStrut(2));
			}
			any = true;
			head.add(badgedItem(raw, met, slot));
		}
		if (!any)
		{
			// a step with no readable outfit falls back to its text
			head.add(OsrsLabel.wrapped(clue.text, ROW_WRAP,
				doable ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		head.add(Box.createHorizontalGlue());
		head.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		ClueStepsPack.Stash unit = unitFor(clue);
		if (unit != null)
		{
			head.add(stashIcon(unit));
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
			// pin the anchor — a JPanel's default max out-competes glue for
			// the row's spare width and swallows the row
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

	/** The emote sprite keys a step's text names, in text order — at most
	 *  two, and one when the outfit is already five wide. */
	private List<String> emotesFor(ClueStepsPack.Clue clue)
	{
		String lower = clue.text.toLowerCase(java.util.Locale.ROOT);
		java.util.TreeMap<Integer, String> byPosition = new java.util.TreeMap<>();
		Set<String> seen = new HashSet<>();
		for (String[] emote : EMOTES)
		{
			java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("\\b" + java.util.regex.Pattern.quote(emote[0]) + "\\b")
				.matcher(lower);
			if (m.find() && seen.add(emote[1]))
			{
				byPosition.put(m.start(), emote[1]);
			}
		}
		int cap = clue.reqs.size() >= 5 ? 1 : 2;
		List<String> out = new ArrayList<>();
		for (String key : byPosition.values())
		{
			if (out.size() >= cap)
			{
				break;
			}
			out.add(key);
		}
		return out;
	}

	/** An emote's icon (the 48px source scales down), named on hover. */
	private JComponent emoteIcon(String key, int slotSize)
	{
		JLabel slot = new JLabel();
		Dimension d = new Dimension(slotSize, slotSize);
		slot.setPreferredSize(d);
		slot.setMinimumSize(d);
		slot.setMaximumSize(d);
		slot.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		String path = "icons/emote/" + key;
		if (com.ironhub.ui.v2.V2Sprites.has(path))
		{
			slot.setIcon(new javax.swing.ImageIcon(com.ironhub.ui.v2.V2Sprites.get(theme, path)
				.getScaledInstance(-1, slotSize - 2, Image.SCALE_SMOOTH)));
		}
		String name = key.replace('_', ' ');
		slot.setToolTipText(Character.toUpperCase(name.charAt(0)) + name.substring(1));
		return slot;
	}

	/** An outfit item with its verdict badged on: checkmark when the
	 *  requirement is met, red cross when not — the unmet sprite darkened,
	 *  and the item NAMED on hover; an owned item that is nowhere in
	 *  bank/carried says which storage holds it (Luke, 2026-07-28). */
	private JComponent badgedItem(String raw, boolean met, int slotSize)
	{
		String body = raw.startsWith("any:") ? raw.substring(4) : raw;
		String[] alts = body.split("\\|");
		String chosen = alts[0];
		if (met)
		{
			for (String alt : alts)
			{
				int id = altItemId(alt);
				if (id > 0 && module.owningView().canonicalStock(id) > 0)
				{
					chosen = alt;
					break;
				}
			}
		}
		int itemId = altItemId(chosen);
		String[] parts = chosen.split(":", 4);
		String name = parts.length >= 4 ? parts[3] : "item " + itemId;
		Image sprite = sprites.getBox(itemId, slotSize - 2);
		JComponent slot = badgedSlot(sprite == null ? null : met ? sprite : darkened(sprite),
			met, slotSize);
		String tip = name;
		if (state.ownedCount(itemId) == 0)
		{
			// owned, but nowhere on the account's person or bank — name the
			// storage that holds it (ONLY then; Luke, 2026-07-28)
			String label = state.storedLabel(itemId);
			if (label != null)
			{
				tip += " — stored in " + label;
			}
		}
		slot.setToolTipText(tip);
		return slot;
	}

	/** The step's STASH unit at the row's right, badged filled or not,
	 *  darkened until BUILT — and saying so on hover (Luke, 2026-07-28). */
	private JComponent stashIcon(ClueStepsPack.Stash unit)
	{
		boolean filled = state.isStashFilled(unit.objectId);
		boolean built = state.isStashBuilt(unit.objectId);
		Image art = null;
		if (com.ironhub.ui.v2.V2Sprites.has("icons/stash_unit"))
		{
			art = com.ironhub.ui.v2.V2Sprites.get(theme, "icons/stash_unit")
				.getScaledInstance(-1, STASH_ICON, Image.SCALE_SMOOTH);
			if (!built && !filled)
			{
				art = darkened(art);
			}
		}
		JComponent slot = badgedSlot(art, filled, STASH_ICON + 4);
		slot.setToolTipText("STASH unit — "
			+ (built || filled ? "built" : "not built") + " · "
			+ (filled ? "filled" : "not filled"));
		return slot;
	}

	/** A fixed slot painting its art centred with a checkmark / red cross
	 *  at the bottom-right corner; a recessed box headless. */
	private JComponent badgedSlot(Image art, boolean ok, int size)
	{
		String badgeKey = ok ? "ui/ticks/checkmark_small" : "ui/ticks/red_cross_small";
		Image badge = com.ironhub.ui.v2.V2Sprites.has(badgeKey)
			? com.ironhub.ui.v2.V2Sprites.get(theme, badgeKey)
				.getScaledInstance(-1, BADGE, Image.SCALE_SMOOTH)
			: null;
		JComponent slot = new JComponent()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				if (art != null)
				{
					g.drawImage(art, (getWidth() - art.getWidth(null)) / 2,
						(getHeight() - art.getHeight(null)) / 2, null);
				}
				else
				{
					g.setColor(theme.recess);
					g.fillRect(1, 1, getWidth() - 2, getHeight() - 2);
				}
				if (badge != null)
				{
					g.drawImage(badge, getWidth() - badge.getWidth(null),
						getHeight() - badge.getHeight(null), null);
				}
			}
		};
		Dimension d = new Dimension(size, size);
		slot.setPreferredSize(d);
		slot.setMinimumSize(d);
		slot.setMaximumSize(d);
		return slot;
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

	/** A DARK ghost of a sprite: the sprite's own pixels washed toward
	 *  black, never the washed-out GrayFilter look. */
	private static Image darkened(Image sprite)
	{
		// a fresh getScaledInstance reports -1 until rendered once — force it
		javax.swing.ImageIcon load = new javax.swing.ImageIcon(sprite);
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
			Math.max(1, load.getIconWidth()), Math.max(1, load.getIconHeight()),
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.drawImage(sprite, 0, 0, null);
		// paint black over the sprite's own pixels only
		g.setComposite(java.awt.AlphaComposite.SrcAtop.derive(0.45f));
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
