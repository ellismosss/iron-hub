package com.ironhub.modules.designlab;

import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneFrame;
import com.ironhub.ui.osrs.StoneMeter;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

/**
 * Goals v2 interface proposal, round 2 (design/GOALS-V2.md §0.1 + §6) —
 * ONE cohesive surface, FOUR sections: hero → CURRENT TASK → GOALS (every
 * Route as an expandable, category-grouped row of Tasks) → SUGGESTIONS.
 * Sample data throughout. Benefits lead the copy; time is one quiet stat,
 * never the headline (Luke's round-2 philosophy call).
 */
class GoalsHubMockup extends JPanel
{
	/** Amber for the pending-update banner — the planner's change colour. */
	private static final Color BANNER_AMBER = new Color(0xE0A23C);

	private final OsrsTheme theme;
	private final SkillIconManager skillIcons = new SkillIconManager();

	GoalsHubMockup(OsrsTheme theme)
	{
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		JPanel frame = new JPanel();
		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(true);
		frame.setBackground(theme.background);
		frame.setBorder(new StoneFrame(theme));
		frame.setAlignmentX(LEFT_ALIGNMENT);

		// ── 1 · hero — Done this month clicks through to the archive ───
		frame.add(strut(4));
		frame.add(pad(pair(
			new StatBox(theme, "Routes\nActive:", OsrsIcons.stat(theme, "quests"), "7"),
			doneThisMonth())));
		frame.add(strut(2));
		frame.add(pad(centered(new OsrsLabel("meta: Jul 2026 · ~212h total",
			OsrsSkin.FAINT, OsrsSkin.smallFont()))));

		// ── 2 · current task — progress first, benefits explained ──────
		frame.add(section("CURRENT TASK"));
		frame.add(pad(currentTask()));

		// ── 3 · goals — every Route, grouped by category ───────────────
		frame.add(section("GOALS"));
		frame.add(pad(updateBanner()));
		frame.add(strut(3));

		frame.add(category("GEAR"));
		frame.add(pad(routeRow(true, true, null, "Bow of Faerdhinen", "12 tasks", 0.32)));
		frame.add(pad(taskRow(Status.AVAILABLE, skillIcon(Skill.AGILITY),
			"Agility to 60  ×2", true)));
		frame.add(pad(taskRow(Status.LOCKED, OsrsIcons.stat(theme, "quests"),
			"Song of the Elves", false)));
		frame.add(pad(taskRow(Status.LOCKED, OsrsIcons.stat(theme, "combat_tasks"),
			"Corrupted Gauntlet", false)));
		frame.add(pad(moreLine("+ 9 more tasks")));
		frame.add(strut(2));
		frame.add(pad(somedayRow("Infernal cape")));
		frame.add(strut(3));

		frame.add(category("QUESTS"));
		frame.add(pad(routeRow(false, false, OsrsIcons.stat(theme, "quests"),
			"Quest cape", "41 tasks", 0.78)));
		frame.add(strut(3));

		frame.add(category("LEVEL UNLOCKS"));
		frame.add(pad(routeRow(false, false, skillIcon(Skill.PRAYER),
			"Prayer 70", "2 tasks", 0.55)));
		frame.add(strut(3));

		frame.add(category("UNLOCKS"));
		frame.add(pad(routeRow(false, false, OsrsIcons.stat(theme, "achievements"),
			"Ornate rejuvenation pool", "5 tasks", 0.40)));
		frame.add(strut(4));
		frame.add(pad(new StoneTextField(theme, "Add a goal — search…")));

		// ── 4 · suggestions — benefit-first, never hours-first ─────────
		frame.add(section("SUGGESTIONS"));
		frame.add(pad(suggestionCard("Fairytale II",
			"advances 3 of your routes at once", "+ Route")));
		frame.add(strut(2));
		frame.add(pad(suggestionCard("Rune pouch",
			"frees 2 inventory slots every trip", "+ Route")));
		frame.add(strut(2));
		frame.add(pad(suggestionCard("Ardougne cloak 2",
			"saves ~3.2h across your routes", "+ Route")));
		frame.add(strut(2));
		frame.add(pad(suggestionCard("Bowfa & Quest cape",
			"share 9 tasks — combine them?", "Merge")));

		frame.add(strut(6));
		frame.add(pad(centered(new OsrsLabel("Static preview · sample data",
			OsrsSkin.MUTED, OsrsSkin.font()))));
		frame.add(strut(4));
		add(frame);
	}

	/** The archive lives behind this block (depth 2, back arrow). */
	private StatBox doneThisMonth()
	{
		StatBox box = new StatBox(theme, "Done this\nMonth:",
			OsrsIcons.stat(theme, "achievements"), "3");
		box.setToolTipText("View completed goals");
		return box;
	}

	/** The single next step: progress bar + number-left stats + why. */
	private JComponent currentTask()
	{
		StonePanel card = new StonePanel(theme);
		card.setBackground(theme.selectFill);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel title = row();
		title.add(new JLabel(skillIcon(Skill.AGILITY)));
		title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		title.add(new OsrsLabel("Train Agility to 60",
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned().squeezable());
		title.add(Box.createHorizontalGlue());
		card.add(title);
		card.add(Box.createVerticalStrut(3));

		card.add(new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, 0.78)
			.labels("Lvl 52", "78%", "Lvl 60"));
		card.add(Box.createVerticalStrut(2));

		JPanel stats = row();
		stats.add(new OsrsLabel("184k xp · ~612 laps left",
			OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned().squeezable());
		stats.add(Box.createHorizontalGlue());
		OsrsLabel pace = new OsrsLabel("~4.2h", OsrsSkin.FAINT, OsrsSkin.smallFont());
		pace.setToolTipText("At your measured pace");
		stats.add(pace);
		card.add(stats);
		card.add(Box.createVerticalStrut(3));

		card.add(OsrsLabel.wrapped(
			"Why: gates Song of the Elves — and the Ardougne Elite diary later.",
			186, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());

		JPanel foot = row();
		foot.add(new OsrsLabel("part of: Bowfa · Quest cape",
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned().squeezable());
		foot.add(Box.createHorizontalGlue());
		StoneButton wiki = new StoneButton(theme, theme.selectFill, "Wiki", null);
		wiki.setMaximumSize(wiki.getPreferredSize());
		foot.add(wiki);
		card.add(foot);

		cap(card);
		return card;
	}

	/** The pending-change banner — never applies itself (house rule). */
	private JComponent updateBanner()
	{
		StonePanel banner = new StonePanel(theme);
		banner.setLayout(new BoxLayout(banner, BoxLayout.X_AXIS));
		banner.setAlignmentX(LEFT_ALIGNMENT);
		banner.add(new OsrsLabel("Routes updated: 2 done",
			BANNER_AMBER, OsrsSkin.font()).leftAligned().squeezable());
		banner.add(Box.createHorizontalGlue());
		banner.add(new OsrsLabel("apply", OsrsSkin.TITLE, OsrsSkin.font()));
		cap(banner);
		return banner;
	}

	/** A Route row: expandable — Goal name over a thin progress meter.
	 *  Pinned Routes wear the select fill and the flag. */
	private JComponent routeRow(boolean expanded, boolean pinned, Icon icon,
		String goal, String tasks, double fraction)
	{
		StonePanel card = new StonePanel(theme);
		if (pinned)
		{
			card.setBackground(theme.selectFill);
		}
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row();
		JLabel triangle = new JLabel(new PaintedIcon(
			expanded ? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		top.add(triangle);
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		if (pinned)
		{
			JLabel flag = new JLabel(new PaintedIcon(PaintedIcon.Shape.FLAG, 10));
			flag.setForeground(OsrsSkin.TITLE);
			flag.setToolTipText("Pinned — this route comes first");
			top.add(flag);
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		else if (icon != null)
		{
			top.add(new JLabel(icon));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		top.add(new OsrsLabel(goal, pinned ? OsrsSkin.TITLE : OsrsSkin.LABEL,
			pinned ? OsrsSkin.boldFont() : OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(tasks, OsrsSkin.FAINT, OsrsSkin.smallFont()));
		card.add(top);
		card.add(Box.createVerticalStrut(3));
		card.add(new StoneMeter(theme, OsrsSkin.PROGRESS_BLUE, fraction));

		cap(card);
		return card;
	}

	/** One Task inside an expanded Route, next-first order: status glyph,
	 *  name (with the ×N shared mark folded in — the PlannerTab grammar),
	 *  and the manual-order affordances (arrows + pin). */
	private JComponent taskRow(Status status, Icon icon, String name, boolean current)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP + 6, 2, UiTokens.ROW_GAP));
		row.add(new JLabel(status.glyph()));
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		if (icon != null)
		{
			row.add(new JLabel(icon));
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		OsrsLabel label = new OsrsLabel(name, current ? OsrsSkin.TITLE : OsrsSkin.MUTED,
			OsrsSkin.font()).leftAligned().squeezable();
		if (name.contains("×"))
		{
			label.setToolTipText("Also a task in: Quest cape");
		}
		row.add(label);
		row.add(Box.createHorizontalGlue());
		row.add(orderAffordances());
		cap(row);
		return row;
	}

	/** Stacked up/down arrows + pin — the farm-picker manual-order grammar. */
	private JComponent orderAffordances()
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);

		JPanel arrows = new JPanel();
		arrows.setLayout(new BoxLayout(arrows, BoxLayout.Y_AXIS));
		arrows.setOpaque(false);
		JLabel up = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_UP, 7));
		up.setForeground(OsrsSkin.FAINT);
		JLabel down = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_DOWN, 7));
		down.setForeground(OsrsSkin.FAINT);
		arrows.add(up);
		arrows.add(down);
		holder.add(arrows);
		holder.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));

		JLabel pin = new JLabel(new PaintedIcon(PaintedIcon.Shape.FLAG, 9));
		pin.setForeground(OsrsSkin.FAINT);
		pin.setToolTipText("Pin to the top of this route");
		holder.add(pin);
		return holder;
	}

	/** The per-Route row cap, honest about what's hidden. */
	private JComponent moreLine(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP + 24, 2, 0));
		row.add(new OsrsLabel(text, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** A parked Route: row parity with the others, dimmed, no meter. */
	private JComponent somedayRow(String name)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		JLabel triangle = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.FAINT);
		row.add(triangle);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		row.add(new OsrsLabel(name, OsrsSkin.FAINT, OsrsSkin.font())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel("someday", OsrsSkin.FAINT, OsrsSkin.smallFont()));
		cap(row);
		return row;
	}

	/** A suggestion: name + one benefit line, phrased by its kind. */
	private JComponent suggestionCard(String name, String benefit, String action)
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row();
		top.add(new OsrsLabel(name, OsrsSkin.LABEL, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		StoneButton add = new StoneButton(theme, theme.boxFill, action, null);
		add.setMaximumSize(add.getPreferredSize());
		top.add(add);
		card.add(top);
		card.add(new OsrsLabel(benefit, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());

		cap(card);
		return card;
	}

	private Icon skillIcon(Skill skill)
	{
		return new ImageIcon(skillIcons.getSkillImage(skill, true));
	}

	// ── layout helpers (the DesignLabTab grammar) ──────────────────────

	private JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent section(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(8, 8, 3, 8));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** A category caption inside GOALS — smaller than a section header. */
	private JComponent category(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(3, 8, 2, 8));
		row.add(new OsrsLabel(text, OsrsSkin.FAINT, OsrsSkin.smallFont()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JComponent pair(StatBox left, StatBox right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 3, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(left);
		row.add(right);
		cap(row);
		return row;
	}

	private JComponent centered(JComponent inner)
	{
		JPanel holder = row();
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
