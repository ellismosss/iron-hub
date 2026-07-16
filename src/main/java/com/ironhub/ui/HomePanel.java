package com.ironhub.ui;

import com.ironhub.state.AccountState;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StoneButton;
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
 * brief Character-Summary-style card, then SEVEN nav blocks — Goals, Combat,
 * Dailies, Current task (dynamic later; slayer helm for now), Progression,
 * Bank, Settings — in the OSRS stonework skin. The blocks are NOT wired yet:
 * this round builds the interface; the Modules button below keeps every
 * existing area reachable through the classic nav in the meantime.
 */
public class HomePanel extends JPanel
{
	/** RuneLite's XP-tracker blue — the goal bar reads as progress, not status. */
	private static final java.awt.Color GOAL_BLUE = new java.awt.Color(0x3D5FBF);

	/**
	 * name → tooltip, in Luke's order; icon files live at data/icons/osrs/nav/.
	 * SIX blocks at the Design lab's full 33×36 stone size, flush like the
	 * game's own tab row — Current task was cut so nothing gets squashed and
	 * icons stay at native size, never rescaled (LANCZOS smoothing is what
	 * made the first pass read soft; Luke: "much nicer and crisper").
	 */
	private static final String[][] NAV = {
		{"goals", "Goals"},
		{"combat", "Combat"},
		{"dailies", "Dailies"},
		{"progression", "Progression"},
		{"bank", "Bank"},
		{"settings", "Settings"},
	};

	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable onModules;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::refresh);

	private final JPanel frame = new JPanel();
	private long statsFingerprint = -1;

	public HomePanel(AccountState state, OsrsTheme theme, Runnable onModules)
	{
		this.state = state;
		this.theme = theme;
		this.onModules = onModules;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(true);
		frame.setBackground(theme.background);
		frame.setBorder(new StoneFrame(theme));
		frame.setAlignmentX(LEFT_ALIGNMENT);
		add(frame);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	public void dispose()
	{
		state.removeListener(listener);
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
		long xp = 0;
		for (Skill skill : Skill.values())
		{
			xp += state.getXp(skill);
		}
		return xp;
	}

	private void rebuild()
	{
		statsFingerprint = fingerprint();
		frame.removeAll();
		frame.add(strut(4));
		// the player's in-game name once the wiring lands; the plugin's until then
		frame.add(centered(OsrsLabel.title("Iron Hub")));
		frame.add(strut(4));
		frame.add(summary());
		frame.add(strut(6));
		frame.add(navRow());
		frame.add(strut(8));
		frame.add(centered(modulesButton()));
		frame.add(strut(4));
		revalidate();
		repaint();
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

	/** "Progress to next goal": a bar where a stat box would carry its value. */
	private JComponent goalBox()
	{
		StonePanel box = new StonePanel(theme);
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.add(OsrsLabel.label("Progress to\nnext goal:"));
		box.add(Box.createVerticalStrut(3));
		box.add(new StoneProgressBar(theme, GOAL_BLUE, 0.31).labels(null, "31%", null));
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
			StoneNavButton stone = new StoneNavButton(theme,
				OsrsIcons.nav(theme, block[0]), false, null);
			stone.setToolTipText(block[1]);
			row.add(stone);
		}
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JComponent modulesButton()
	{
		StoneButton modules = new StoneButton(theme, "Modules", onModules);
		modules.setToolTipText("All module tabs (the classic navigation)");
		Dimension pref = new Dimension(modules.getPreferredSize().width + 24,
			modules.getPreferredSize().height);
		modules.setPreferredSize(pref);
		modules.setMaximumSize(pref);
		return modules;
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
