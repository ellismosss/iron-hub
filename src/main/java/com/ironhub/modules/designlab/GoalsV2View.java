package com.ironhub.modules.designlab;

import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Glyph;
import com.ironhub.ui.v2.V2Label;
import com.ironhub.ui.v2.V2Layout;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Sprites;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2TextField;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
import com.ironhub.ui.v2.V2Tooltip;
import java.awt.image.BufferedImage;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * The Goals hub, rebuilt from V2 atoms — the system's first real workload
 * (Luke, 2026-07-25). Sample data throughout; nothing is wired to the engine,
 * because the question is whether the ATOMS can carry a real screen.
 *
 * <p><b>Round 2</b>, against Luke's review of round 1:
 *
 * <ul>
 * <li>The hero is TWO TILES — active goals and completed this month — not a
 *     big progress bar. The bar was mine, not V1's: V1 shows two StatBoxes and
 *     has no hero bar at all, and inventing one put a number on a scale that
 *     does not exist.
 * <li>Goals sit ON Tiles, and open into a Well of Tasks beneath.
 * <li>The green double chevron marks the current task, as in V1.
 * <li>Expand/collapse uses the DROPDOWN's arrows, not single grey chevrons.
 * <li>Route and task bars are V1's blue, not the possession green.
 * <li>No square buttons on rows. The pin was a blank {@code SQUARE_SMALL} with
 *     no emblem in it — the curated set has no pin sprite, so V1's pin and
 *     remove affordances have nothing to draw with yet. Recorded as a gap
 *     rather than faked.
 * <li>Category headers are BODY, matching V1's {@code OsrsSkin.font()}.
 * <li>Suggestions use the goal format.
 * </ul>
 *
 * <p><b>Gaps this screen still exposes</b>, unchanged from round 1: there is no
 * Row atom (every line here is glyph + label + glue + trailing, eleven times),
 * no section-header atom, no disclosure atom, and no colour for priority.
 */
public class GoalsV2View extends JPanel
{
	/** name, emblem, tasks, progress, weight. */
	private static final String[][] ROUTES = {
		{"Bow of Faerdhinen", "icons/skills/ranged", "6", "0.5", "pinned"},
		{"Barrows gloves", "icons/skills/defence", "4", "0.75", ""},
		{"99 Farming", "icons/skills/farming", "3", "0.34", ""},
		{"Graceful outfit", "icons/skills/agility", "5", "0.2", "low"},
	};

	/** The tasks under the one open route. */
	private static final String[][] TASKS = {
		{"Song of the Elves", "done", ""},
		{"70 Agility", "now", "~2h"},
		{"Crystal armour seed x3", "", "~4h"},
	};

	private static final String[][] SUGGESTIONS = {
		{"Fairy rings", "icons/skills/magic", "Unlocks 40+ teleports"},
		{"Rune pouch", "icons/skills/runecraft", "Frees 3 inventory slots"},
	};

	private final OsrsTheme theme;

	public GoalsV2View(OsrsTheme theme)
	{
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(V2Tokens.PAD, 0, V2Tokens.PAD, 0));
		setAlignmentX(LEFT_ALIGNMENT);

		stats();
		currentTask();
		addGoal();
		goals();
		suggestions();

		add(V2Layout.gap(V2Tokens.SECTION));
		add(V2Label.faint("Goals · rebuilt from V2 atoms · sample data"));

		V2Surface frame = V2Surface.frame(theme);
		while (getComponentCount() > 0)
		{
			frame.add(getComponent(0));
		}
		add(frame);
	}

	// ── 1 · the two stat tiles ────────────────────────────────────────

	private void stats()
	{
		JPanel row = V2Layout.row();
		row.add(statTile("Active goals", "4"));
		row.add(V2Layout.hgap(V2Tokens.ROW));
		row.add(statTile("Done this month", "3"));
		add(row);
		gap(V2Tokens.SECTION);
	}

	/** V1's StatBox: an orange label over its number, on a tile. */
	private JComponent statTile(String label, String value)
	{
		V2Surface tile = V2Surface.tile(theme);
		tile.stack(V2Label.heading(label), V2Tokens.TIGHT);
		tile.add(V2Label.value(value));
		return tile;
	}

	// ── 2 · current task ──────────────────────────────────────────────

	private void currentTask()
	{
		JPanel header = V2Layout.row();
		header.add(new V2Glyph(theme, V2Glyph.ACTIVE));
		header.add(V2Layout.hgap(V2Tokens.TIGHT));
		header.add(V2Label.heading("Current task"));
		header.add(V2Layout.glue());
		add(header);
		gap(V2Tokens.ROW);

		V2Surface card = V2Surface.card(theme);
		JPanel title = V2Layout.row();
		title.add(new V2Tile(theme, sprite("icons/skills/agility"), null, 24, null));
		title.add(V2Layout.hgap(V2Tokens.PAD));
		title.add(V2Label.heading("70 Agility"));
		title.add(V2Layout.glue());
		card.stack(title, V2Tokens.ROW);

		card.add(new V2ProgressBar(theme, V2ProgressBar.Size.ROW)
			.fill(V2Tokens.BAR_BLUE).fraction(0.62).labels("Lvl 62", "62%", "Lvl 70"));
		card.add(V2Layout.gap(V2Tokens.ROW));

		JPanel stats = V2Layout.row();
		stats.add(V2Label.detail("412k xp left"));
		stats.add(V2Layout.glue());
		com.ironhub.ui.osrs.OsrsLabel pace = V2Label.faint("~6h");
		V2Tooltip.install(pace, "At your measured pace (68k/hr observed)");
		stats.add(pace);
		card.add(stats);
		add(card);
		gap(V2Tokens.SECTION);
	}

	// ── 3 · add a goal ────────────────────────────────────────────────

	private void addGoal()
	{
		add(new V2TextField(theme, "Add a goal...", null));
		gap(V2Tokens.SECTION);
	}

	// ── 4 · goals, by category ────────────────────────────────────────

	private void goals()
	{
		add(V2Label.heading("Goals"));
		gap(V2Tokens.ROW);
		category("Gear", 0, 2);
		gap(V2Tokens.ROW);
		category("Skills", 2, 4);
		gap(V2Tokens.SECTION);
	}

	/** A category header — BODY font, as V1's is — then its routes. */
	private void category(String name, int from, int to)
	{
		JPanel header = V2Layout.row();
		header.add(new V2Glyph(theme, V2Glyph.OPEN));
		header.add(V2Layout.hgap(V2Tokens.TIGHT));
		header.add(V2Label.body(name));
		header.add(V2Layout.glue());
		header.add(V2Label.faint(String.valueOf(to - from)));
		add(header);
		gap(V2Tokens.TIGHT);

		for (int i = from; i < to; i++)
		{
			if (i > from)
			{
				gap(V2Tokens.ROW);
			}
			route(ROUTES[i], i == 0);
		}
	}

	/**
	 * A route ON a tile: arrow, emblem, name, task count, then its meter. When
	 * open, its Tasks follow in a Well underneath — the tile is the goal, the
	 * well is the plan.
	 */
	private void route(String[] route, boolean open)
	{
		boolean pinned = "pinned".equals(route[4]);
		boolean low = "low".equals(route[4]);

		V2Surface tile = V2Surface.tile(theme);
		JPanel top = V2Layout.row();
		top.add(new V2Glyph(theme, open ? V2Glyph.OPEN : V2Glyph.CLOSED));
		top.add(V2Layout.hgap(V2Tokens.TIGHT));
		top.add(new V2Tile(theme, sprite(route[1]), null, 20, null));
		top.add(V2Layout.hgap(V2Tokens.TIGHT));
		// priority has no colour of its own (§6), so it reads as weight:
		// pinned takes HEADING, someday takes FAINT, the rest are BODY
		top.add(pinned ? V2Label.heading(route[0])
			: low ? V2Label.faint(route[0]) : V2Label.body(route[0]));
		top.add(V2Layout.glue());
		if (!low)
		{
			top.add(V2Label.faint(route[2]));
		}
		tile.add(top);
		if (!low)
		{
			tile.add(V2Layout.gap(V2Tokens.TIGHT));
			tile.add(new V2ProgressBar(theme, V2ProgressBar.Size.METER)
				.fill(V2Tokens.BAR_BLUE)
				.fraction(Double.parseDouble(route[3]))
				.segments(Integer.parseInt(route[2])));
		}
		add(tile);

		if (open)
		{
			gap(V2Tokens.TIGHT);
			V2Surface well = V2Surface.well(theme);
			for (String[] task : TASKS)
			{
				well.add(taskRow(task));
			}
			well.add(V2Label.faint("+ 3 more"));
			add(well);
		}
	}

	/** A task inside the open route's Well. */
	private JComponent taskRow(String[] task)
	{
		boolean done = "done".equals(task[1]);
		boolean now = "now".equals(task[1]);
		JPanel row = V2Layout.row();
		row.add(new V2Glyph(theme, done ? V2Glyph.TICK : now ? V2Glyph.ACTIVE : V2Glyph.CLOSED));
		row.add(V2Layout.hgap(V2Tokens.TIGHT));
		row.add(now ? V2Label.status(task[0], V2Tokens.ACTION)
			: done ? V2Label.faint(task[0]) : V2Label.detail(task[0]));
		row.add(V2Layout.glue());
		row.add(V2Label.faint(task[2]));
		return row;
	}

	// ── 5 · suggestions ───────────────────────────────────────────────

	/** The goal format exactly: a tile per suggestion, arrow and all. */
	private void suggestions()
	{
		add(V2Label.heading("Suggestions"));
		gap(V2Tokens.ROW);
		for (int i = 0; i < SUGGESTIONS.length; i++)
		{
			if (i > 0)
			{
				gap(V2Tokens.ROW);
			}
			String[] suggestion = SUGGESTIONS[i];
			V2Surface tile = V2Surface.tile(theme);
			JPanel top = V2Layout.row();
			top.add(new V2Glyph(theme, V2Glyph.CLOSED));
			top.add(V2Layout.hgap(V2Tokens.TIGHT));
			top.add(new V2Tile(theme, sprite(suggestion[1]), null, 20, null));
			top.add(V2Layout.hgap(V2Tokens.TIGHT));
			top.add(V2Label.body(suggestion[0]));
			top.add(V2Layout.glue());
			tile.add(top);
			tile.add(V2Layout.gap(V2Tokens.TIGHT));
			tile.add(V2Label.detail(suggestion[2]));
			add(tile);
		}
	}

	// ── helpers ───────────────────────────────────────────────────────

	private void gap(int px)
	{
		add(V2Layout.gap(px));
	}

	private BufferedImage sprite(String key)
	{
		return V2Sprites.get(theme, key);
	}
}
