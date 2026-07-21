package com.ironhub.ui;

import com.ironhub.state.AccountState;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StoneFrame;
import com.ironhub.ui.osrs.StoneNavButton;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

/**
 * The reworked home (2026-07-16, Luke's nav spec): the player's name over a
 * brief Character-Summary-style card, then SIX nav blocks — Goals, Combat,
 * Dailies, Progression, Bank, Settings — in the OSRS stonework skin. Every
 * block is wired (2026-07-17), so the stones are the whole navigation; the
 * interim Modules button is gone.
 */
public class HomePanel extends JPanel
{
	/** RuneLite's XP-tracker blue — the goal bar reads as progress, not status. */
	private static final java.awt.Color GOAL_BLUE = OsrsSkin.PROGRESS_BLUE;

	/**
	 * name → tooltip, in Luke's order; icon files live at data/icons/osrs/nav/.
	 * SIX blocks at the Design lab's full 33×36 stone size, flush like the
	 * game's own tab row — Current task was cut so nothing gets squashed and
	 * icons stay at native size, never rescaled (LANCZOS smoothing is what
	 * made the first pass read soft; Luke: "much nicer and crisper").
	 */
	private static final String[][] NAV = {
		{"goals", "Goals"},
		{"combat", "Gear & Combat"},
		{"dailies", "Dailies"},
		{"progression", "Progression"},
		{"bank", "Bank"},
		{"settings", "Settings"},
	};

	private final AccountState state;
	private final com.ironhub.modules.goals.GoalPlannerModule planner; // null headless
	private final OsrsTheme theme;
	private final java.util.function.Consumer<String> onBlock;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::refresh);

	private final JPanel frame = new JPanel();
	/**
	 * The open block's content, BELOW the stone frame on the main backing
	 * (Luke, 2026-07-17: the raised frame envelops only the hub proper —
	 * module sections carry their own hierarchy via their header plates),
	 * still one connected scroll. Owned by IronHubPanel, which mounts hub
	 * pages into it; empty when nothing is open.
	 */
	private final JPanel contentSlot = new JPanel(new java.awt.BorderLayout());
	private final java.util.Map<String, StoneNavButton> stones = new java.util.LinkedHashMap<>();
	private String selectedBlock;
	private long statsFingerprint = -1;

	public HomePanel(AccountState state, com.ironhub.modules.goals.GoalPlannerModule planner,
		OsrsTheme theme, java.util.function.Consumer<String> onBlock)
	{
		this.state = state;
		this.planner = planner;
		this.theme = theme;
		this.onBlock = onBlock;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		// the MAIN backing is the theme's own — the hub content below the
		// frame sits directly on it (Luke, 2026-07-17: the raised stone
		// frame envelops only the hub proper, ending above module headers)
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(true);
		frame.setBackground(theme.background);
		frame.setBorder(new StoneFrame(theme));
		frame.setAlignmentX(LEFT_ALIGNMENT);
		contentSlot.setOpaque(false);
		contentSlot.setAlignmentX(LEFT_ALIGNMENT);
		add(frame);
		add(Box.createVerticalStrut(6));
		add(contentSlot);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		if (planner != null)
		{
			// replans land off the state-listener path — hear them too, or the
			// goal bar lags the plan by one unrelated state change
			planner.addPlanListener(listener);
		}
		rebuild();
	}

	public void dispose()
	{
		state.removeListener(listener);
		if (planner != null)
		{
			planner.removePlanListener(listener);
		}
	}

	/** Skill ingestion fires constantly; only re-render when the summary moved. */
	private void refresh()
	{
		long now = fingerprint();
		if (now != statsFingerprint)
		{
			rebuild();
		}
	}

	private long fingerprint()
	{
		long fp = 0;
		for (Skill skill : Skill.values())
		{
			fp += state.getXp(skill);
		}
		fp = fp * 31 + state.playerName().hashCode();
		com.ironhub.modules.goals.GoalPlannerModule.NextGoal next =
			planner == null ? null : planner.nextGoal();
		if (next != null)
		{
			fp = fp * 31 + next.name.hashCode();
			fp = fp * 31 + Math.round(next.fraction * 1000);
		}
		return fp;
	}

	private void rebuild()
	{
		statsFingerprint = fingerprint();
		frame.removeAll();
		frame.add(strut(4));
		// the player's in-game name; the plugin's until first seen logged in
		String name = state.playerName();
		frame.add(centered(OsrsLabel.title(name.isEmpty() ? "Iron Hub" : name)));
		frame.add(strut(4));
		frame.add(summary());
		frame.add(strut(6));
		frame.add(navRow());
		frame.add(strut(4));
		// the open block's content lives BELOW the frame, on the main
		// backing — its stone header plates carry the hierarchy from here
		revalidate();
		repaint();
	}

	/** Where the open block's page lives — inside the frame, one block. */
	public JPanel contentSlot()
	{
		return contentSlot;
	}

	/**
	 * A stone was pressed: select it (or deselect, clicking the open one
	 * again — the game's own tab stones close the same way).
	 */
	private void toggleBlock(String name)
	{
		selectedBlock = name.equals(selectedBlock) ? null : name;
		for (java.util.Map.Entry<String, StoneNavButton> stone : stones.entrySet())
		{
			stone.getValue().setSelected(stone.getKey().equals(selectedBlock));
		}
		rebuild();
		if (onBlock != null)
		{
			onBlock.accept(selectedBlock);
		}
	}

	/** UI-only reset (theme flips, back navigation) — never fires the callback. */
	public void clearSelection()
	{
		selectedBlock = null;
		for (StoneNavButton stone : stones.values())
		{
			stone.setSelected(false);
		}
		rebuild();
	}

	/** Test seam. */
	public String selectedBlock()
	{
		return selectedBlock;
	}

	/** Test seam: press a block by name, exactly as a stone click would. */
	public void pressBlock(String name)
	{
		toggleBlock(name);
	}

	/** Test seam: the nav block names in stone order — each needs a hub page. */
	static java.util.List<String> blockNames()
	{
		java.util.List<String> names = new java.util.ArrayList<>();
		for (String[] block : NAV)
		{
			names.add(block[1]);
		}
		return names;
	}

	/** Combat level, total level and total XP — live, from the shared state. */
	private JComponent summary()
	{
		int total = 0;
		long totalXp = 0;
		for (Skill skill : Skill.values())
		{
			total += state.getRealLevel(skill);
			totalXp += state.getXp(skill);
		}
		int combat = Experience.getCombatLevel(
			state.getRealLevel(Skill.ATTACK), state.getRealLevel(Skill.STRENGTH),
			state.getRealLevel(Skill.DEFENCE), state.getRealLevel(Skill.HITPOINTS),
			state.getRealLevel(Skill.MAGIC), state.getRealLevel(Skill.RANGED),
			state.getRealLevel(Skill.PRAYER));

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(0, 4, 0, 4));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		JPanel pair = new JPanel(new GridLayout(1, 2, 3, 0));
		pair.setOpaque(false);
		pair.setAlignmentX(LEFT_ALIGNMENT);
		pair.add(new StatBox(theme, "Combat Level:", OsrsIcons.stat(theme, "combat_level"),
			String.valueOf(combat)));
		pair.add(new StatBox(theme, "Total Level:", OsrsIcons.stat(theme, "total_level"),
			String.format(Locale.ROOT, "%,d", total)));
		cap(pair);
		panel.add(pair);
		panel.add(Box.createVerticalStrut(3));

		StonePanel xp = new StonePanel(theme);
		xp.setLayout(new BoxLayout(xp, BoxLayout.X_AXIS));
		xp.setAlignmentX(LEFT_ALIGNMENT);
		xp.add(Box.createHorizontalGlue());
		Icon coin = OsrsIcons.stat(theme, "total_xp");
		if (coin != null)
		{
			xp.add(new JLabel(coin));
			xp.add(Box.createHorizontalStrut(5));
		}
		xp.add(OsrsLabel.label("Total XP:"));
		xp.add(Box.createHorizontalStrut(5));
		xp.add(OsrsLabel.value(String.format(Locale.ROOT, "%,d", totalXp)));
		xp.add(Box.createHorizontalGlue());
		cap(xp);
		panel.add(xp);
		panel.add(Box.createVerticalStrut(3));

		// goal progress + achievements — SAMPLE data until their wiring lands
		JPanel pair2 = new JPanel(new GridLayout(1, 2, 3, 0));
		pair2.setOpaque(false);
		pair2.setAlignmentX(LEFT_ALIGNMENT);
		pair2.add(goalBox());
		pair2.add(new StatBox(theme, "Achievements\nCompleted:",
			OsrsIcons.stat(theme, "achievements"), "397/492"));
		cap(pair2);
		panel.add(pair2);

		cap(panel);
		return panel;
	}

	/**
	 * "Progress to next goal": the goal the plan finishes FIRST, at the same
	 * route progress the Goals hub shows for it; the goal's name rides the
	 * tooltip (225px has no room for it beside the bar). No plan or no goals
	 * = an honest empty bar, never an invented figure.
	 */
	private JComponent goalBox()
	{
		StonePanel box = new StonePanel(theme);
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.add(OsrsLabel.label("Progress to\nnext goal:"));
		box.add(Box.createVerticalStrut(3));
		com.ironhub.modules.goals.GoalPlannerModule.NextGoal next =
			planner == null ? null : planner.nextGoal();
		if (next == null)
		{
			box.add(new StoneProgressBar(theme, GOAL_BLUE, 0).labels(null, "—", null));
			box.setToolTipText("No active goal — add one under Goals");
		}
		else
		{
			box.add(new StoneProgressBar(theme, GOAL_BLUE, next.fraction)
				.labels(null, Math.round(next.fraction * 100) + "%", null));
			box.setToolTipText(next.name);
		}
		return box;
	}

	/** The six stones, flush at full size — the Design lab row. Not wired yet. */
	private JComponent navRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(Box.createHorizontalGlue());
		for (String[] block : NAV)
		{
			String name = block[1];
			StoneNavButton stone = stones.computeIfAbsent(name, key ->
				new StoneNavButton(theme, OsrsIcons.nav(theme, block[0]),
					false, () -> toggleBlock(key)));
			stone.setToolTipText(name);
			row.add(stone);
		}
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
