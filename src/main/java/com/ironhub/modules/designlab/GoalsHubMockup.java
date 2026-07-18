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
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneFrame;
import com.ironhub.ui.osrs.StoneMeter;
import com.ironhub.ui.osrs.StonePanel;
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
 * Goals v2 interface proposal (design/GOALS-V2.md §6) — the ONE cohesive
 * Goals surface that replaces the planner's three views, mocked with sample
 * data so the whole shape can be judged before any wiring. Reads top to
 * bottom as: stats → do this now → the route → your goals → ideas → history.
 * Every idiom here already exists in a live tab (nowCard, denseRow, chapter
 * plates, chip rows) — this arranges them, it invents nothing.
 */
class GoalsHubMockup extends JPanel
{
	/** Amber for the update banner — the planner's pending-change colour. */
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

		// ── 1 · hero: the stats centre ─────────────────────────────────
		frame.add(strut(4));
		frame.add(pad(horizon()));
		frame.add(strut(3));
		frame.add(pad(pair(
			new StatBox(theme, "Goals\nActive:", OsrsIcons.stat(theme, "quests"), "7"),
			new StatBox(theme, "Done this\nMonth:", OsrsIcons.stat(theme, "achievements"), "3"))));
		frame.add(strut(2));
		frame.add(pad(centered(new OsrsLabel("completed goals: est 42h · took 37h",
			OsrsSkin.FAINT, OsrsSkin.smallFont()))));

		// ── 2 · now: the plan head, fitted to the session ──────────────
		frame.add(section("NOW"));
		frame.add(pad(budgetChips()));
		frame.add(strut(3));
		frame.add(pad(nowCard()));
		frame.add(strut(2));
		frame.add(pad(stepRow(2, skillIcon(Skill.AGILITY), "to 60 · gates Bowfa", "~4.2h", false)));
		frame.add(pad(stepRow(3, OsrsIcons.stat(theme, "quests"), "Ghosts Ahoy", "~40m", false)));

		// ── 3 · route: the whole plan as collapsible chapters ──────────
		frame.add(section("ROUTE · ~212h · 41 STEPS"));
		frame.add(pad(updateBanner()));
		frame.add(strut(3));
		frame.add(pad(chapterPlate(false, "Quests & unlocks", "~9h")));
		frame.add(strut(2));
		frame.add(pad(chapterPlate(true, "Stat blocks", "~41h")));
		frame.add(pad(stepRow(5, skillIcon(Skill.HERBLORE), "to 38  ×2", "~2.1h", true)));
		frame.add(pad(stepRow(6, skillIcon(Skill.AGILITY), "to 70  ×3", "~11h", false)));
		frame.add(pad(stepRow(7, OsrsIcons.stat(theme, "total_level"), "Kingdom setup", "?", false)));
		frame.add(strut(2));
		frame.add(pad(chapterPlate(false, "Gear & bosses", "~162h +?")));
		frame.add(strut(2));
		JPanel snoozed = row();
		snoozed.setBorder(new EmptyBorder(0, UiTokens.ROW_GAP, 0, 0));
		snoozed.add(new OsrsLabel("SNOOZED (2)", OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		snoozed.add(Box.createHorizontalGlue());
		cap(snoozed);
		frame.add(pad(snoozed));

		// ── 4 · goals: the running account ─────────────────────────────
		frame.add(section("GOALS"));
		frame.add(pad(filterChips()));
		frame.add(strut(3));
		frame.add(pad(goalCard(true, null, "Bow of Faerdhinen", null, "~86h",
			OsrsSkin.PROGRESS_BLUE, 0.32)));
		frame.add(strut(2));
		frame.add(pad(goalCard(false, OsrsIcons.stat(theme, "quests"), "Quest cape", "HIGH", "~54h",
			OsrsSkin.VALUE.darker(), 0.78)));
		frame.add(strut(2));
		frame.add(pad(goalCard(false, OsrsIcons.stat(theme, "achievements"),
			"Ornate rejuvenation pool", null, "~12h", OsrsSkin.PROGRESS_BLUE, 0.40)));
		frame.add(strut(2));
		frame.add(pad(somedayRow("Infernal cape")));
		frame.add(strut(4));
		frame.add(pad(new StoneTextField(theme, "Add a goal — search…")));

		// ── 5 · worth a detour: computed stepping stones ───────────────
		frame.add(section("WORTH A DETOUR"));
		frame.add(pad(detourCard("Ardougne cloak 2",
			"costs ~1.5h · saves ~3.2h · 11 steps")));
		frame.add(strut(2));
		frame.add(pad(detourCard("Kandarin diary: Medium",
			"costs ~2.0h · saves ~2.6h · 9 steps")));

		// ── 6 · completed: the archive ─────────────────────────────────
		frame.add(section("COMPLETED · 3 THIS MONTH"));
		frame.add(pad(doneRow("Fire cape", "Jul 12", "est 8h · took 6.5h")));
		frame.add(pad(doneRow("Dragon defender", "Jul 3", "est 3h · took 4h")));
		frame.add(pad(doneRow("Graceful outfit", "detected", null)));

		frame.add(strut(6));
		frame.add(pad(centered(new OsrsLabel("Static preview · sample data",
			OsrsSkin.MUTED, OsrsSkin.font()))));
		frame.add(strut(4));
		add(frame);
	}

	/** `All goals: ~212h` with the pack-freshness line under it. */
	private JComponent horizon()
	{
		StonePanel box = new StonePanel(theme);
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row();
		top.add(Box.createHorizontalGlue());
		top.add(OsrsLabel.label("All goals:"));
		top.add(Box.createHorizontalStrut(5));
		top.add(OsrsLabel.value("~212h"));
		top.add(Box.createHorizontalGlue());
		box.add(top);

		JPanel bottom = row();
		bottom.add(Box.createHorizontalGlue());
		bottom.add(new OsrsLabel("meta: Jul 2026 · pace 5.1h/week",
			OsrsSkin.FAINT, OsrsSkin.smallFont()));
		bottom.add(Box.createHorizontalGlue());
		box.add(bottom);

		cap(box);
		return box;
	}

	private JComponent budgetChips()
	{
		StoneChipRow chips = new StoneChipRow(theme, true, "30m", "1h", "2h+", "AFK");
		chips.setSelected(1);
		return chips;
	}

	private JComponent filterChips()
	{
		// three chips, not four — "Someday" needs the width at 225px
		StoneChipRow chips = new StoneChipRow(theme, true, "Active", "Someday", "Done");
		chips.setSelected(0);
		return chips;
	}

	/** The accent plan-head card — PlannerTab's nowCard, verbatim grammar. */
	private JComponent nowCard()
	{
		StonePanel card = new StonePanel(theme);
		card.setBackground(theme.selectFill);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel title = row();
		title.add(new OsrsLabel("Fairytale II",
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned().squeezable());
		title.add(Box.createHorizontalGlue());
		title.add(new OsrsLabel("~45m", OsrsSkin.TITLE, OsrsSkin.font()));
		card.add(title);

		card.add(OsrsLabel.wrapped(
			"Unlocks fairy rings — saves ~3.5h across 14 later steps.",
			186, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());

		JPanel foot = row();
		foot.add(new OsrsLabel("serves 3 goals", OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		foot.add(Box.createHorizontalGlue());
		StoneButton wiki = new StoneButton(theme, theme.selectFill, "Wiki", null);
		wiki.setMaximumSize(wiki.getPreferredSize());
		foot.add(wiki);
		card.add(foot);

		cap(card);
		return card;
	}

	/** A route step in the flat quest-list idiom (PlannerTab denseRow). */
	private JComponent stepRow(int position, Icon icon, String name, String time, boolean lead)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		row.add(new OsrsLabel(String.valueOf(position), OsrsSkin.FAINT, OsrsSkin.font()));
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		if (icon != null)
		{
			row.add(new JLabel(icon));
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		row.add(new OsrsLabel(name, lead ? OsrsSkin.TITLE : OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(time, OsrsSkin.MUTED, OsrsSkin.font()));
		cap(row);
		return row;
	}

	/** The pending-change banner — never applies itself (house rule). */
	private JComponent updateBanner()
	{
		StonePanel banner = new StonePanel(theme);
		banner.setLayout(new BoxLayout(banner, BoxLayout.X_AXIS));
		banner.setAlignmentX(LEFT_ALIGNMENT);
		banner.add(new OsrsLabel("Plan updated: -1.2h",
			BANNER_AMBER, OsrsSkin.font()).leftAligned().squeezable());
		banner.add(Box.createHorizontalGlue());
		banner.add(new OsrsLabel("apply", OsrsSkin.TITLE, OsrsSkin.font()));
		cap(banner);
		return banner;
	}

	/** Collapsible chapter plate — the hub pages' module-plate grammar. */
	private JComponent chapterPlate(boolean open, String name, String hours)
	{
		StonePanel plate = new StonePanel(theme);
		plate.setLayout(new BoxLayout(plate, BoxLayout.X_AXIS));
		plate.setAlignmentX(LEFT_ALIGNMENT);
		JLabel triangle = new JLabel(new PaintedIcon(
			open ? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		plate.add(triangle);
		plate.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		plate.add(new OsrsLabel(name, OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned().squeezable());
		plate.add(Box.createHorizontalGlue());
		plate.add(new OsrsLabel(hours, OsrsSkin.MUTED, OsrsSkin.font()));
		cap(plate);
		return plate;
	}

	/** A goal card: identity row over a thin meter. Pinned = select fill +
	 *  the flag; tier shows as a small amber tag. */
	private JComponent goalCard(boolean pinned, Icon icon, String name, String tier,
		String hours, Color meterFill, double fraction)
	{
		StonePanel card = new StonePanel(theme);
		if (pinned)
		{
			card.setBackground(theme.selectFill);
		}
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row();
		if (pinned)
		{
			JLabel flag = new JLabel(new PaintedIcon(PaintedIcon.Shape.FLAG, 10));
			flag.setForeground(OsrsSkin.TITLE);
			flag.setToolTipText("Pinned active — routed first");
			top.add(flag);
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		else if (icon != null)
		{
			top.add(new JLabel(icon));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		top.add(new OsrsLabel(name, pinned ? OsrsSkin.TITLE : OsrsSkin.LABEL,
			pinned ? OsrsSkin.boldFont() : OsrsSkin.font()).leftAligned().squeezable());
		if (tier != null)
		{
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			top.add(new OsrsLabel(tier, BANNER_AMBER, OsrsSkin.smallFont()));
		}
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(hours, OsrsSkin.MUTED, OsrsSkin.font()));
		card.add(top);
		card.add(Box.createVerticalStrut(3));
		card.add(new StoneMeter(theme, meterFill, fraction));

		cap(card);
		return card;
	}

	/** A parked goal: stays in the dedupe math, contributes no urgency. */
	private JComponent somedayRow(String name)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		row.add(new OsrsLabel(name + " · someday", OsrsSkin.FAINT, OsrsSkin.font())
			.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel("?", OsrsSkin.FAINT, OsrsSkin.font()));
		cap(row);
		return row;
	}

	/** A computed stepping-stone suggestion with its net figures. */
	private JComponent detourCard(String name, String figures)
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row();
		top.add(new OsrsLabel(name, OsrsSkin.LABEL, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		StoneButton add = new StoneButton(theme, theme.boxFill, "+ Goal", null);
		add.setMaximumSize(add.getPreferredSize());
		top.add(add);
		card.add(top);
		card.add(new OsrsLabel(figures, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());

		cap(card);
		return card;
	}

	/** An archive row: painted check, name + date, and the honest
	 *  estimate-vs-actual line beneath when the goal completed dated. */
	private JComponent doneRow(String name, String date, String estTook)
	{
		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(LEFT_ALIGNMENT);
		block.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));

		JPanel top = row();
		top.add(new JLabel(Status.OWNED.glyph()));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		top.add(new OsrsLabel(name, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(date, OsrsSkin.FAINT, OsrsSkin.smallFont()));
		block.add(top);

		if (estTook != null)
		{
			JPanel bottom = row();
			bottom.add(Box.createHorizontalStrut(UiTokens.STATUS_GLYPH_SIZE + UiTokens.PAD_TIGHT));
			bottom.add(new OsrsLabel(estTook, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
			bottom.add(Box.createHorizontalGlue());
			block.add(bottom);
		}

		cap(block);
		return block;
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
