package com.ironhub.modules.goals;

import com.ironhub.data.GoalsPack;
import com.ironhub.modules.goals.GoalPlannerModule.CompiledStep;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Goal planner tab (frame 2d, checklist-first) in the OSRS stonework skin:
 * active goal card (select-fill emphasis) with its live step checklist,
 * other selected goals, and a browse list of targets. Detectable steps
 * auto-complete; manual steps are click-to-tick.
 */
class GoalsTab extends JPanel
{
	private final AccountState state;
	private final GoalsPack pack;
	private final com.ironhub.data.GearProgressionPack gearPack;
	private final net.runelite.client.game.ItemManager itemManager; // null in headless tests
	private final OsrsTheme theme;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final JPanel content = new JPanel();

	GoalsTab(AccountState state, GoalsPack pack, com.ironhub.data.GearProgressionPack gearPack,
		net.runelite.client.game.ItemManager itemManager, OsrsTheme theme)
	{
		this.state = state;
		this.pack = pack;
		this.gearPack = gearPack;
		this.itemManager = itemManager;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(UiTokens.PAD, 0, 0, 0));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	GoalsPack pack()
	{
		return pack;
	}

	com.ironhub.data.GearProgressionPack gearPack()
	{
		return gearPack;
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Item sprite (scaled to row height) applied async; no-op headless. */
	private static javax.swing.Icon bundledIcon(String resource)
	{
		java.net.URL url = GoalsTab.class.getResource(resource);
		if (url == null)
		{
			return null;
		}
		javax.swing.ImageIcon icon = new javax.swing.ImageIcon(url);
		return new javax.swing.ImageIcon(
			icon.getImage().getScaledInstance(-1, 15, java.awt.Image.SCALE_SMOOTH));
	}

	private static final javax.swing.Icon CA_ICON =
		bundledIcon("/data/icons/combat_achievements.png");
	private static final javax.swing.Icon DIARY_ICON =
		bundledIcon("/data/icons/achievement_diaries.png");

	private void applyIcon(GoalsPack.Goal goal, java.util.function.Consumer<javax.swing.Icon> setter)
	{
		// CA and diary goals wear their system's wiki badge
		if (goal.getId().startsWith("ca:") && CA_ICON != null)
		{
			setter.accept(CA_ICON);
			return;
		}
		if (goal.getId().startsWith("diary:") && DIARY_ICON != null)
		{
			setter.accept(DIARY_ICON);
			return;
		}
		Integer itemId = goal.icon();
		if (itemId == null || itemManager == null)
		{
			return;
		}
		net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(itemId);
		Runnable apply = () -> setter.accept(new javax.swing.ImageIcon(
			image.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH)));
		apply.run();
		image.onLoaded(apply);
	}

	private void rebuild()
	{
		content.removeAll();

		// pack goals + synthetic goals for targeted gear-chart items
		List<GoalsPack.Goal> goals = GoalPlannerModule.allGoals(pack, gearPack, state);

		// achieved goals retire out of Active/Other/Browse into Completed
		List<GoalsPack.Goal> completed = goals.stream()
			.filter(g -> GoalPlannerModule.isAchieved(g, state))
			.collect(java.util.stream.Collectors.toList());

		GoalsPack.Goal active = goals.stream()
			.filter(g -> g.getId().equals(state.getActiveGoal()))
			.findFirst().orElse(null);
		if (active != null && !completed.contains(active))
		{
			content.add(section("Active goal"));
			content.add(activeCard(active));
			content.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}

		List<GoalsPack.Goal> others = goals.stream()
			.filter(g -> state.getSelectedGoals().contains(g.getId()))
			.filter(g -> !g.getId().equals(state.getActiveGoal()))
			.filter(g -> !completed.contains(g))
			.collect(java.util.stream.Collectors.toList());
		if (!others.isEmpty())
		{
			content.add(section("Other goals"));
			for (GoalsPack.Goal goal : others)
			{
				content.add(goalRow(goal, true));
				content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			content.add(Box.createVerticalStrut(UiTokens.PAD));
		}

		List<GoalsPack.Goal> browse = goals.stream()
			.filter(g -> !state.getSelectedGoals().contains(g.getId()))
			.filter(g -> !completed.contains(g))
			.collect(java.util.stream.Collectors.toList());
		if (!browse.isEmpty())
		{
			content.add(section("Browse targets"));
			for (GoalsPack.Goal goal : browse)
			{
				content.add(goalRow(goal, false));
				content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			content.add(Box.createVerticalStrut(UiTokens.PAD));
		}

		if (!completed.isEmpty())
		{
			content.add(section("Completed"));
			for (GoalsPack.Goal goal : completed)
			{
				JPanel row = flatRow();
				JLabel icon = iconHolder(goal);
				row.add(icon);
				OsrsLabel name = new OsrsLabel(goal.getName(), OsrsSkin.VALUE, OsrsSkin.font())
					.leftAligned().squeezable();
				String tooltip = goal.getName() + " — detected on your account";
				name.setToolTipText(tooltip);
				row.setToolTipText(tooltip);
				row.add(name);
				row.add(Box.createHorizontalGlue());
				cap(row);
				content.add(row);
				content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}
		content.revalidate();
		content.repaint();
	}

	/** The emphasized card: select-fill band, name, %, bar, live checklist. */
	private JPanel activeCard(GoalsPack.Goal goal)
	{
		StonePanel card = new StonePanel(theme);
		card.setBackground(theme.selectFill);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		double progress = GoalPlannerModule.progress(goal, state);
		JPanel title = new JPanel();
		title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
		title.setOpaque(false);
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.add(iconHolder(goal));
		OsrsLabel name = new OsrsLabel(goal.getName(), OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable();
		title.add(name);
		title.add(Box.createHorizontalGlue());
		title.add(new OsrsLabel(Math.round(progress * 100) + "%",
			OsrsSkin.TITLE, OsrsSkin.boldFont()));
		title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		title.add(removeGlyph("Remove from your goals",
			() -> GoalPlannerModule.removeGoal(state, goal.getId())));
		card.add(title);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		StoneProgressBar bar = new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE, progress);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		card.add(bar);
		card.add(Box.createVerticalStrut(UiTokens.PAD));

		boolean nextFound = false;
		for (CompiledStep step : GoalPlannerModule.compile(goal, state))
		{
			boolean isNext = !step.met && !nextFound;
			nextFound |= isNext;
			card.add(stepLine(step, isNext));
			card.add(Box.createVerticalStrut(2));
		}
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(OsrsLabel.wrapped("detected steps auto-complete from account state",
			180, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		return card;
	}

	private JPanel stepLine(CompiledStep step, boolean isNext)
	{
		JPanel line = new JPanel();
		line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);

		OsrsLabel label = new OsrsLabel(step.label,
			step.met ? OsrsSkin.FAINT : isNext ? OsrsSkin.TITLE : OsrsSkin.MUTED,
			isNext ? OsrsSkin.boldFont() : OsrsSkin.font()).leftAligned().squeezable();
		String tooltip = step.manual
			? step.label + " — manual step, click to " + (step.met ? "untick" : "tick")
			: step.label;
		label.setToolTipText(tooltip);
		line.add(label);
		line.add(Box.createHorizontalGlue());

		if (step.manual)
		{
			line.add(new OsrsLabel(step.met ? "manual" : "tick",
				OsrsSkin.FAINT, OsrsSkin.font()));
			line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter tick = new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					state.setUnlocked(step.unlockKey, !step.met);
				}
			};
			// the label's tooltip swallows row clicks — it carries both
			line.addMouseListener(tick);
			label.addMouseListener(tick);
		}
		else if (step.met)
		{
			line.add(new OsrsLabel("auto", OsrsSkin.VALUE, OsrsSkin.font()));
		}
		cap(line);
		return line;
	}

	/** Selected goals activate on click; browse targets add on click. */
	private JComponent goalRow(GoalsPack.Goal goal, boolean selected)
	{
		int pct = (int) Math.round(GoalPlannerModule.progress(goal, state) * 100);
		JPanel row = flatRow();
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.add(iconHolder(goal));
		OsrsLabel name = new OsrsLabel(goal.getName(),
			selected ? OsrsSkin.LABEL : OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable();
		String tooltip = selected
			? goal.getName() + " — " + pct + "% · click to make active"
			: goal.getName() + " — click to add to your goals";
		name.setToolTipText(tooltip);
		row.setToolTipText(tooltip);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		if (selected)
		{
			row.add(removeGlyph("Remove from your goals",
				() -> GoalPlannerModule.removeGoal(state, goal.getId())));
		}
		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(theme.hoverFill);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(theme.background);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (selected)
				{
					state.setActiveGoal(goal.getId());
				}
				else
				{
					state.selectGoal(goal.getId(), true);
				}
			}
		};
		// the name's tooltip swallows row clicks — it carries both
		row.addMouseListener(click);
		name.addMouseListener(click);
		cap(row);
		return row;
	}

	/** A flat hoverable row on the tab background (quest-list idiom). */
	private JPanel flatRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(true);
		row.setBackground(theme.background);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(2, UiTokens.ROW_GAP, 2, UiTokens.ROW_GAP));
		return row;
	}

	/** The goal's icon in its own holder, filled async — text stays an
	 *  OsrsLabel (a raw JLabel clips the pixel font's glyph bottoms). */
	private JLabel iconHolder(GoalsPack.Goal goal)
	{
		JLabel icon = new JLabel();
		icon.setBorder(new EmptyBorder(0, 0, 0, UiTokens.PAD_TIGHT));
		applyIcon(goal, image ->
		{
			icon.setIcon(image);
			icon.revalidate();
		});
		return icon;
	}

	/** A small × affordance in skin colours — faint until hovered. */
	private static JLabel removeGlyph(String tooltip, Runnable onClick)
	{
		JLabel glyph = new JLabel("×");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tooltip);
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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

	/** Section header in the skin grammar. */
	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(4, 0, 3, 0));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
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
