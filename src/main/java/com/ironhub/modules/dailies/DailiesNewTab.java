package com.ironhub.modules.dailies;

import com.ironhub.data.DailiesPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Button;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2Checklist;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2EmptyState;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
import com.ironhub.ui.v2.V2Well;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * The Dailies tab in the OSRS stonework skin (design/OSRS-SKIN.md) — the
 * phase-4 migration pilot. Same brain, same behaviour as {@link DailiesTab}:
 * tick what a run should include, row colour = where each event stands, End
 * run pinned to the top while one is active. Only the clothing changed.
 */
class DailiesNewTab extends JPanel
{
	/** The dailies status scale in skin colours: green = go (farm parity),
	 *  orange = short, warm-faint = spent, neutral grey = locked. */
	private static final Color LOCKED = new Color(0x8C8C8C);

	/** The wiki's own Wilderness skull, bundled (never a painted stand-in) —
	 *  moved here when the classic DailiesTab was deleted (2026-07-20). */
	static final javax.swing.Icon SKULL = bundledIcon("/data/icons/wilderness_skull.png");

	private static javax.swing.Icon bundledIcon(String resource)
	{
		java.net.URL url = DailiesNewTab.class.getResource(resource);
		if (url == null)
		{
			return null;
		}
		javax.swing.ImageIcon icon = new javax.swing.ImageIcon(url);
		return new javax.swing.ImageIcon(
			icon.getImage().getScaledInstance(-1, 14, java.awt.Image.SCALE_SMOOTH));
	}

	private final DailiesModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final SpriteCache sprites;
	private final Runnable stateListener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	// the run flow's ticks ride the same gate — a bare this::rebuild
	// rebuilt a hidden tab on every run event
	private final Runnable runListener = stateListener;

	private final JPanel frame = new JPanel();

	DailiesNewTab(DailiesModule module, OsrsTheme theme)
	{
		this.module = module;
		this.state = module.state();
		this.theme = theme;
		this.sprites = new SpriteCache(module.itemManager(), stateListener);
		// frameless: the tab renders its content directly on the theme's
		// backing, so a host (the Dailies hub inside the home's stone frame)
		// connects with it as ONE block — no frame-in-frame seam (Luke)
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);

		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(false);
		frame.setAlignmentX(LEFT_ALIGNMENT);
		add(frame);
		add(Box.createVerticalGlue());

		// scoped: the tab renders daily states (varbits + bank/carried
		// stock + req gates) and manual ticks — the tagged LOOT/RECORDS/
		// GOALS/STORAGE churn it used to rebuild on touches none of that
		// (untagged broadcasts always deliver; 2026-08-03 audit ruling 9)
		state.addListener(stateListener,
			com.ironhub.state.AccountState.Topic.SKILLS,
			com.ironhub.state.AccountState.Topic.QUESTS,
			com.ironhub.state.AccountState.Topic.BANK,
			com.ironhub.state.AccountState.Topic.INVENTORY,
			com.ironhub.state.AccountState.Topic.EQUIPMENT,
			com.ironhub.state.AccountState.Topic.VARBITS,
			com.ironhub.state.AccountState.Topic.UNLOCKS);
		module.addTabListener(runListener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(stateListener);
		module.removeTabListener(runListener);
	}

	void rebuild()
	{
		frame.removeAll();
		if (module.pack() == null)
		{
			// the brain lives in the Dailies module; without it there is
			// nothing honest to show
			frame.add(pad(new OsrsLabel("Enable the Dailies module", OsrsSkin.MUTED, OsrsSkin.font())));
		}
		else if (module.running())
		{
			buildActiveRun();
		}
		else
		{
			buildChecklist();
		}
		frame.add(strut(4));
		revalidate();
		repaint();
	}

	// ── active run ───────────────────────────────────────────────────

	private void buildActiveRun()
	{
		frame.add(strut(4));
		V2Button end = new V2Button(theme, "End run", () ->
		{
			module.endRun(false);
			rebuild();
		});
		end.setToolTipText("Stop the daily run now");
		frame.add(pad(end));
		frame.add(strut(3));
		int done = module.visitedCount();
		int total = module.stops().size();
		frame.add(pad(new V2ProgressBar(theme, V2ProgressBar.Size.ROW)
			.fraction(total == 0 ? 0 : (double) done / total)
			.labels("Daily run", null, done + "/" + total)));

		frame.add(section("Stops"));
		// the stops are a LIST, so they sit in a Well at the list inset (§4)
		V2Surface list = V2Surface.well(theme);
		int inset = V2Well.CAP + V2Tokens.TIGHT;
		list.setBorder(new EmptyBorder(inset, inset, inset, inset));
		DailiesPack.Daily next = module.nextStop();
		for (DailiesPack.Daily daily : module.stops())
		{
			list.add(stopRow(daily, daily == next));
		}
		cap(list);
		frame.add(pad(list));
	}

	/** One run stop: name coloured by progress, Skip on what is still to do. */
	private JComponent stopRow(DailiesPack.Daily daily, boolean isNext)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		// the next stop is lit by the Well's own wash, not a fill of its own —
		// a solid selectFill inside a Well painted over the field texture
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		boolean visited = module.isVisited(daily.id);
		Color color = visited ? OsrsSkin.VALUE : isNext ? OsrsSkin.TITLE : OsrsSkin.MUTED;
		OsrsLabel name = new OsrsLabel(daily.name, color, OsrsSkin.font()).leftAligned();
		name.setToolTipText(daily.name);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		if (!visited)
		{
			JComponent skip = V2ChipRow.action(theme, "Skip", null, null,
				OsrsSkin.smallFont(), () -> module.markThrough(daily.id));
			skip.setToolTipText("Skip this stop (and any before it)");
			row.add(skip);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// ── checklist ────────────────────────────────────────────────────

	private void buildChecklist()
	{
		// no title of its own: the host's stone header plate names the module
		frame.add(strut(4));
		frame.add(pad(tileStrip()));
		frame.add(strut(3));
		frame.add(pad(new OsrsLabel("Resets daily at 00:00 UTC", OsrsSkin.FAINT, OsrsSkin.font())));
		frame.add(strut(4));

		int outstanding = module.outstanding();
		if (outstanding > 0)
		{
			V2Button start = new V2Button(theme,
				"Start daily run · " + outstanding + " stops", module::startRun);
			start.setToolTipText("Guide me through the " + outstanding
				+ " ticked dailies I can do right now");
			frame.add(pad(start));
		}
		else
		{
			// nothing to do is a real state — a dead button would lie
			V2Surface none = V2EmptyState.empty(theme, "Nothing to run");
			none.setToolTipText("Every ticked daily is done, locked, or unavailable");
			cap(none);
			frame.add(pad(none));
		}

		frame.add(section("Include in a run"));
		V2Checklist list = new V2Checklist(theme);
		for (DailiesPack.Daily daily : module.pack().dailies)
		{
			checklistRow(list, daily);
		}
		cap(list);
		frame.add(pad(list));

		frame.add(strut(8));
		boolean hasSetup = module.hasSetup();
		V2Button setup = new V2Button(theme,
			hasSetup ? "Update gear & inventory" : "Configure gear & inventory", () ->
		{
			module.saveSetup();
			rebuild();
		});
		setup.setToolTipText("Snapshot your worn gear and inventory now; while a daily "
			+ "run is active, opening the bank lays it out for you to re-stock");
		frame.add(pad(setup));
		if (hasSetup)
		{
			OsrsLabel clear = new OsrsLabel("Clear setup", OsrsSkin.FAINT, OsrsSkin.font());
			clear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			clear.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					module.clearSetup();
					rebuild();
				}
			});
			frame.add(strut(3));
			frame.add(pad(clear));
		}
	}

	private void checklistRow(V2Checklist list, DailiesPack.Daily daily)
	{
		DailyTracker.State current = module.stateOf(daily);
		boolean selected = module.selected(daily);
		String label = daily.name + (current == DailyTracker.State.UNKNOWN ? " ?" : "");
		V2Checkbox row = new V2Checkbox(theme, label, selected,
			() -> onTicked(daily, !module.selected(daily)));
		// the dailies scale is the CALLER's, so it is applied after state()
		row.labelColor(selected ? statusColor(current) : OsrsSkin.FAINT);
		if (daily.warning != null)
		{
			row.badge(SKULL);
		}
		row.setToolTipText(rowTooltip(daily, current));
		list.row(row);
	}

	private void onTicked(DailiesPack.Daily daily, boolean selected)
	{
		state.setDailySelected(daily.id, selected);
		if (selected && daily.warning != null)
		{
			JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
				daily.warning, daily.name, JOptionPane.WARNING_MESSAGE);
		}
	}

	private String rowTooltip(DailiesPack.Daily daily, DailyTracker.State current)
	{
		// the kingdom's live numbers (approval varbit + coffer varp, both
		// synced at login — the same read core's Kingdom plugin announces)
		if (daily.detection != null && "approval".equals(daily.detection.mode))
		{
			int approvalRaw = state.getVarbit(daily.detection.varbit);
			long coffer = state.getVarp(DailyTracker.KINGDOM_COFFER_VARP);
			if (approvalRaw > 0 || coffer > 0)
			{
				return "<html>" + daily.name + "<br>Approval "
					+ DailyTracker.approvalPercent(approvalRaw) + "% · Coffer "
					+ String.format("%,d", coffer) + " gp (as of login)</html>";
			}
			return daily.name;
		}
		if (current != DailyTracker.State.SHORT)
		{
			return daily.name;
		}
		String rich = module.missingTooltip(daily);
		return rich != null ? rich
			: daily.name + " — need " + String.join(", ", module.missing(daily));
	}

	/** The old tab's scale, in skin colours (see DailiesTab.statusColor). */
	private static Color statusColor(DailyTracker.State current)
	{
		switch (current)
		{
			case AVAILABLE:
				return OsrsSkin.VALUE;
			case SHORT:
				return OsrsSkin.TITLE;
			case DONE:
				return OsrsSkin.FAINT;
			case LOCKED:
				return LOCKED;
			default:
				return OsrsSkin.MUTED;
		}
	}

	// ── status tiles ─────────────────────────────────────────────────

	/** The DLV2 status tile (Luke, 2026-07-26), 5 to a row. */
	private JComponent tileStrip()
	{
		List<DailiesPack.Daily> dailies = module.pack().dailies;
		JPanel strip = new JPanel(new GridLayout(0, 5, V2Tokens.ROW, V2Tokens.ROW));
		strip.setOpaque(false);
		strip.setAlignmentX(LEFT_ALIGNMENT);
		int rows = (dailies.size() + 4) / 5;
		strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * (TILE + V2Tokens.ROW)));
		for (DailiesPack.Daily daily : dailies)
		{
			DailyTracker.State current = module.stateOf(daily);
			V2Tile tile = new V2Tile(theme, sprites.get(daily.icon, V2Tokens.TILE_ICON),
				null, TILE, null);
			tile.status(module.selected(daily) ? tileStatus(current)
				: V2Tile.Status.UNAVAILABLE);
			// claimed = the done tick, not a ring (see tileStatus)
			tile.owned(module.selected(daily) && current == DailyTracker.State.DONE);
			tile.setToolTipText(daily.name);
			strip.add(tile);
		}
		return strip;
	}

	/** The tile's own height; its width comes from the grid's column. */
	private static final int TILE = 30;

	/**
	 * Luke's D2 ruling (2026-08-03, reversing the 2026-07-26 port reading):
	 * an AVAILABLE daily wears the sanctioned green ring — green is the
	 * go-signal, "you can do this now". A CLAIMED daily carries the
	 * system's done-mark instead (the owned tick, set in tileStrip) with
	 * no ring, so the two greens can never be confused: ring = can do,
	 * tick = done today.
	 */
	private static V2Tile.Status tileStatus(DailyTracker.State current)
	{
		switch (current)
		{
			case AVAILABLE:
				return V2Tile.Status.DONE; // the sanctioned green ring
			case SHORT:
			case LOCKED:
				return V2Tile.Status.UNAVAILABLE;
			case DONE:
			default:
				return V2Tile.Status.PLAIN; // claimed: the tick says it
		}
	}

	// ── layout helpers (the DesignLabTab grammar) ─────────────────────

	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 8, 3, 8));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
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

	private JComponent strut(int height)
	{
		return (JComponent) Box.createVerticalStrut(height);
	}

	private void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
