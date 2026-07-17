package com.ironhub.modules.dailies;

import com.ironhub.data.DailiesPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChecklist;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTile;
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

	private final DailiesModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final SpriteCache sprites;
	private final Runnable stateListener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final Runnable runListener = this::rebuild; // already on the EDT

	private final JPanel frame = new JPanel();

	DailiesNewTab(DailiesModule module, OsrsTheme theme)
	{
		this.module = module;
		this.state = module.state();
		this.theme = theme;
		this.sprites = new SpriteCache(module.itemManager(), this::rebuild);
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

		state.addListener(stateListener);
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
		StoneButton end = new StoneButton(theme, "End run", () ->
		{
			module.endRun(false);
			rebuild();
		});
		end.setToolTipText("Stop the daily run now");
		frame.add(pad(end));
		frame.add(strut(3));
		int done = module.visitedCount();
		int total = module.stops().size();
		frame.add(pad(new StoneProgressBar(theme, OsrsSkin.VALUE.darker(), total == 0 ? 0 : (double) done / total)
			.labels("Daily run", null, done + "/" + total)));

		frame.add(section("Stops"));
		StonePanel list = new StonePanel(theme);
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setAlignmentX(LEFT_ALIGNMENT);
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
		row.setOpaque(isNext);
		if (isNext)
		{
			row.setBackground(theme.selectFill);
		}
		row.setAlignmentX(LEFT_ALIGNMENT);
		boolean visited = module.isVisited(daily.id);
		Color color = visited ? OsrsSkin.VALUE : isNext ? OsrsSkin.TITLE : OsrsSkin.MUTED;
		OsrsLabel name = new OsrsLabel(daily.name, color, OsrsSkin.font()).leftAligned();
		name.setToolTipText(daily.name);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		if (!visited)
		{
			StoneButton skip = new StoneButton(theme, isNext ? theme.selectFill : theme.boxFill,
				"Skip", () -> module.markThrough(daily.id));
			skip.setToolTipText("Skip this stop (and any before it)");
			skip.setMaximumSize(skip.getPreferredSize());
			row.add(skip);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// ── checklist ────────────────────────────────────────────────────

	private void buildChecklist()
	{
		frame.add(strut(4));
		frame.add(centered(OsrsLabel.title("Dailies")));
		frame.add(strut(4));
		frame.add(pad(tileStrip()));
		frame.add(strut(3));
		frame.add(pad(new OsrsLabel("Resets daily at 00:00 UTC", OsrsSkin.FAINT, OsrsSkin.font())));
		frame.add(strut(4));

		int outstanding = module.outstanding();
		if (outstanding > 0)
		{
			StoneButton start = new StoneButton(theme,
				"Start daily run · " + outstanding + " stops", module::startRun);
			start.setToolTipText("Guide me through the " + outstanding
				+ " ticked dailies I can do right now");
			frame.add(pad(start));
		}
		else
		{
			// nothing to do is a real state — a dead button would lie
			StonePanel none = new StonePanel(theme);
			none.setLayout(new BoxLayout(none, BoxLayout.X_AXIS));
			none.setAlignmentX(LEFT_ALIGNMENT);
			none.add(Box.createHorizontalGlue());
			none.add(new OsrsLabel("Nothing to run", OsrsSkin.FAINT, OsrsSkin.font()));
			none.add(Box.createHorizontalGlue());
			none.setToolTipText("Every ticked daily is done, locked, or unavailable");
			cap(none);
			frame.add(pad(none));
		}

		frame.add(section("Include in a run"));
		StoneChecklist list = new StoneChecklist(theme);
		for (DailiesPack.Daily daily : module.pack().dailies)
		{
			checklistRow(list, daily);
		}
		cap(list);
		frame.add(pad(list));

		frame.add(strut(8));
		boolean hasSetup = module.hasSetup();
		StoneButton setup = new StoneButton(theme,
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

	private void checklistRow(StoneChecklist list, DailiesPack.Daily daily)
	{
		DailyTracker.State current = module.stateOf(daily);
		boolean selected = module.selected(daily);
		String label = daily.name + (current == DailyTracker.State.UNKNOWN ? " ?" : "");
		Color color = selected ? statusColor(current) : OsrsSkin.FAINT;
		list.row(label, selected, color, rowTooltip(daily, current),
			daily.warning != null ? DailiesTab.SKULL : null,
			ticked -> onTicked(daily, ticked));
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
		if (current != DailyTracker.State.SHORT)
		{
			return daily.name;
		}
		return daily.name + " — need " + String.join(", ", module.missing(daily));
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

	private JComponent tileStrip()
	{
		List<DailiesPack.Daily> dailies = module.pack().dailies;
		JPanel strip = new JPanel(new GridLayout(0, 5, 4, 4));
		strip.setOpaque(false);
		strip.setAlignmentX(LEFT_ALIGNMENT);
		int rows = (dailies.size() + 4) / 5;
		strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * (StoneTile.HEIGHT + 4)));
		for (DailiesPack.Daily daily : dailies)
		{
			DailyTracker.State current = module.stateOf(daily);
			boolean mine = module.selected(daily) && current != DailyTracker.State.LOCKED;
			StoneTile tile = new StoneTile(theme, mine ? tileBevel(current) : null, !mine, daily.name);
			tile.setIconImage(sprites.get(daily.icon, StoneTile.ICON));
			strip.add(tile);
		}
		return strip;
	}

	/** Green ready · orange short · the plain engraved bevel otherwise. */
	private static Color tileBevel(DailyTracker.State current)
	{
		switch (current)
		{
			case AVAILABLE:
				return OsrsSkin.VALUE.darker();
			case SHORT:
				return OsrsSkin.TITLE.darker();
			default:
				return null;
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

	private JComponent centered(JComponent inner)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(Box.createHorizontalGlue());
		holder.add(inner);
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
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
