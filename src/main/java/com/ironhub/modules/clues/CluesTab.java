package com.ironhub.modules.clues;

import com.ironhub.data.ClueStepsPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Clues & STASH tab: chip views Steps · STASH. Steps = emote clue-step
 * doability per tier against owned items, blocked steps first with their
 * first missing item and a "+ Goal" affordance into the Goal planner.
 * STASH = built/filled/ready-to-fill counts per tier with unit rows
 * (click toggles filled — the manual escape hatch for pre-plugin fills).
 * Clue collection-log slots deliberately live elsewhere. Frameless —
 * the host's header plate names the module.
 */
class CluesTab extends JPanel
{
	private static final int MAX_ROWS_PER_TIER = 15;
	private static final String[] TIERS =
		{"Beginner", "Easy", "Medium", "Hard", "Elite", "Master"};

	private final AccountState state;
	private final ClueStashModule module;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StoneChipRow views;
	private final JPanel content = new JPanel();
	private boolean showAll;

	CluesTab(AccountState state, ClueStashModule module, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		views = new StoneChipRow(theme, true, "Steps", "STASH");
		views.onChange(i -> rebuild());
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

	/** Test seam: 0 = Steps, 1 = STASH. */
	void selectView(int index)
	{
		views.setSelected(index);
		rebuild();
	}

	void rebuild()
	{
		content.removeAll();
		if (module.pack() == null)
		{
			content.add(faintLine("Clue pack unavailable."));
		}
		else if (views.getSelected() == 1)
		{
			rebuildStash();
		}
		else
		{
			rebuildSteps();
		}
		content.revalidate();
		content.repaint();
	}

	// ── Steps view ────────────────────────────────────────────────────

	private void rebuildSteps()
	{
		Map<String, List<ClueStepsPack.Clue>> byTier = groupClues();
		content.add(toggleRow(showAll ? "Hide doable steps" : "Show doable steps"));
		for (String tier : TIERS)
		{
			List<ClueStepsPack.Clue> clues = byTier.get(tier);
			if (clues == null)
			{
				continue;
			}
			// evaluate each clue's requirement graph ONCE per rebuild — the
			// count, filter and sort key each re-ran it, the sort per
			// comparison (2026-07-20 audit)
			Map<ClueStepsPack.Clue, Boolean> doableByClue = new java.util.IdentityHashMap<>();
			for (ClueStepsPack.Clue clue : clues)
			{
				doableByClue.put(clue, ClueStashModule.doable(clue, state));
			}
			long doable = doableByClue.values().stream().filter(Boolean::booleanValue).count();
			content.add(section(tier, doable + "/" + clues.size() + " doable"));

			List<ClueStepsPack.Clue> shown = new ArrayList<>();
			for (ClueStepsPack.Clue clue : clues)
			{
				if (showAll || !doableByClue.get(clue))
				{
					shown.add(clue);
				}
			}
			// blocked (actionable) rows first, then doable when shown
			shown.sort(java.util.Comparator.comparing(doableByClue::get));
			if (shown.isEmpty())
			{
				content.add(faintLine("All " + tier.toLowerCase() + " steps doable."));
				continue;
			}
			int rows = 0;
			for (ClueStepsPack.Clue clue : shown)
			{
				if (rows++ >= MAX_ROWS_PER_TIER)
				{
					content.add(faintLine("+ " + (shown.size() - MAX_ROWS_PER_TIER) + " more steps"));
					break;
				}
				content.add(clueRow(clue));
			}
		}
	}

	private Map<String, List<ClueStepsPack.Clue>> groupClues()
	{
		Map<String, List<ClueStepsPack.Clue>> byTier = new LinkedHashMap<>();
		for (ClueStepsPack.Clue clue : module.pack().clues)
		{
			byTier.computeIfAbsent(clue.tier, t -> new ArrayList<>()).add(clue);
		}
		return byTier;
	}

	/** One step: green doable / muted blocked with its first missing item
	 *  and a "+ Goal" button that tracks unlocking it in the Goal planner. */
	private JComponent clueRow(ClueStepsPack.Clue clue)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, 2, 1, 2));

		boolean doable = ClueStashModule.doable(clue, state);
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.setOpaque(false);
		top.setAlignmentX(LEFT_ALIGNMENT);
		OsrsLabel name = new OsrsLabel(clue.text, doable ? OsrsSkin.VALUE : OsrsSkin.MUTED,
			OsrsSkin.font()).leftAligned().squeezable();
		name.setToolTipText("<html><div style='width:200px'>" + clue.text + "</div></html>");
		top.add(name);
		top.add(Box.createHorizontalGlue());
		if (!doable && !clue.reqs.isEmpty())
		{
			boolean tracked = module.isGoal(clue);
			StoneButton goal = new StoneButton(theme, tracked ? "×" : "+ Goal", () ->
			{
				if (tracked)
				{
					module.removeGoal(clue);
				}
				else
				{
					module.addGoal(clue);
				}
				SwingUtilities.invokeLater(this::rebuild);
			});
			goal.setToolTipText(tracked ? "Remove from the Goal planner"
				: "Track unlocking this step in the Goal planner");
			goal.setMaximumSize(goal.getPreferredSize());
			top.add(goal);
		}
		cap(top);
		row.add(top);

		if (!doable)
		{
			String needs = "needs: " + ClueStashModule.blocking(clue, state);
			OsrsLabel blocker = new OsrsLabel(needs, OsrsSkin.FAINT, OsrsSkin.smallFont())
				.leftAligned().squeezable();
			blocker.setToolTipText(needs);
			blocker.setBorder(new EmptyBorder(0, 8, 0, 0));
			row.add(blocker);
		}
		cap(row);
		return row;
	}

	// ── STASH view ────────────────────────────────────────────────────

	private void rebuildStash()
	{
		Map<String, List<ClueStepsPack.Stash>> byTier = new LinkedHashMap<>();
		for (ClueStepsPack.Stash unit : module.pack().stash)
		{
			byTier.computeIfAbsent(unit.tier, t -> new ArrayList<>()).add(unit);
		}

		int built = 0;
		int filled = 0;
		int ready = 0;
		for (ClueStepsPack.Stash unit : module.pack().stash)
		{
			if (state.isStashBuilt(unit.objectId))
			{
				built++;
			}
			if (state.isStashFilled(unit.objectId))
			{
				filled++;
			}
			if (module.readyToFill(unit))
			{
				ready++;
			}
		}
		StonePanel summary = new StonePanel(theme);
		summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		JPanel line1 = bareRow();
		line1.add(new OsrsLabel("STASH units", OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned());
		line1.add(Box.createHorizontalGlue());
		line1.add(OsrsLabel.value(built + " built · " + filled + " filled"));
		cap(line1);
		summary.add(line1);
		JPanel line2 = bareRow();
		line2.add(new OsrsLabel("Sets owned, not stored", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		line2.add(Box.createHorizontalGlue());
		line2.add(new OsrsLabel(String.valueOf(ready),
			ready > 0 ? OsrsSkin.VALUE : OsrsSkin.FAINT, OsrsSkin.boldFont()));
		cap(line2);
		summary.add(line2);
		cap(summary);
		content.add(summary);
		content.add(textLine("Ownership counts your bank and carried items — POH costume storage is not readable.",
			OsrsSkin.FAINT, OsrsSkin.smallFont()));

		content.add(toggleRow(showAll ? "Hide filled units" : "Show filled units"));
		for (String tier : TIERS)
		{
			List<ClueStepsPack.Stash> units = byTier.get(tier);
			if (units == null)
			{
				continue;
			}
			int tierBuilt = 0;
			int tierFilled = 0;
			for (ClueStepsPack.Stash unit : units)
			{
				if (state.isStashBuilt(unit.objectId))
				{
					tierBuilt++;
				}
				if (state.isStashFilled(unit.objectId))
				{
					tierFilled++;
				}
			}
			content.add(section(tier,
				tierBuilt + "/" + units.size() + " built · " + tierFilled + " filled"));
			List<ClueStepsPack.Stash> shown = new ArrayList<>();
			for (ClueStepsPack.Stash unit : units)
			{
				if (showAll || !state.isStashFilled(unit.objectId))
				{
					shown.add(unit);
				}
			}
			if (shown.isEmpty())
			{
				content.add(faintLine("All " + tier.toLowerCase() + " units filled."));
				continue;
			}
			int rows = 0;
			for (ClueStepsPack.Stash unit : shown)
			{
				if (rows++ >= MAX_ROWS_PER_TIER)
				{
					content.add(faintLine("+ " + (shown.size() - MAX_ROWS_PER_TIER) + " more units"));
					break;
				}
				content.add(stashRow(unit));
			}
		}
	}

	/** One unit: green filled / orange built-empty / faint not built, a
	 *  ready badge when its outfit is owned, click = manual filled toggle. */
	private JComponent stashRow(ClueStepsPack.Stash unit)
	{
		JPanel row = bareRow();
		row.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		boolean filled = state.isStashFilled(unit.objectId);
		boolean built = state.isStashBuilt(unit.objectId);
		Color color = filled ? OsrsSkin.VALUE : built ? OsrsSkin.LABEL : OsrsSkin.FAINT;
		OsrsLabel name = new OsrsLabel(unit.name, color, OsrsSkin.font()).leftAligned().squeezable();
		String status = filled ? "Filled" : built ? "Built, empty" : "Not built";
		name.setToolTipText("<html><div style='width:200px'>" + unit.name + " — " + status
			+ ".<br>Click to toggle filled (for STASHes filled before Iron Hub).</div></html>");
		row.add(name);
		row.add(Box.createHorizontalGlue());
		if (module.readyToFill(unit))
		{
			row.add(new OsrsLabel("ready", OsrsSkin.VALUE, OsrsSkin.smallFont()));
		}
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		row.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				module.toggleFilled(unit); // listener rebuilds
			}
		});
		cap(row);
		return row;
	}

	// ── shared bits ───────────────────────────────────────────────────

	private JComponent toggleRow(String label)
	{
		JPanel row = bareRow();
		OsrsLabel toggle = new OsrsLabel(label, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned();
		toggle.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		toggle.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				showAll = !showAll;
				SwingUtilities.invokeLater(CluesTab.this::rebuild);
			}
		});
		row.add(toggle);
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JPanel bareRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent section(String text, String count)
	{
		JPanel row = bareRow();
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(count, OsrsSkin.VALUE, OsrsSkin.font()));
		cap(row);
		return row;
	}

	private JComponent textLine(String text, Color color, java.awt.Font font)
	{
		JPanel holder = bareRow();
		holder.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		holder.add(OsrsLabel.wrapped(text, 195, color, font).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private JComponent faintLine(String text)
	{
		return textLine(text, OsrsSkin.FAINT, OsrsSkin.font());
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
