package com.ironhub.modules.goals;

import com.ironhub.data.GoalsPack;
import com.ironhub.modules.goals.GoalPlannerModule.CompiledStep;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.StatusGlyph;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * Goal planner tab (frame 2d, checklist-first): active goal card (accent
 * outline) with its live step checklist, other selected goals, and a
 * browse list of targets. Detectable steps auto-complete; manual steps
 * are click-to-tick.
 */
class GoalsTab extends JPanel
{
	private final AccountState state;
	private final GoalsPack pack;
	private final com.ironhub.data.GearProgressionPack gearPack;
	private final net.runelite.client.game.ItemManager itemManager; // null in headless tests
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final JPanel content = new JPanel();

	GoalsTab(AccountState state, GoalsPack pack, com.ironhub.data.GearProgressionPack gearPack,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.state = state;
		this.pack = pack;
		this.gearPack = gearPack;
		this.itemManager = itemManager;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(UiTokens.PANEL_BG);
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
	private void applyIcon(GoalsPack.Goal goal, java.util.function.Consumer<javax.swing.Icon> setter)
	{
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
			content.add(new SectionLabel("Active goal"));
			content.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
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
			content.add(new SectionLabel("Other goals"));
			content.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
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
			content.add(new SectionLabel("Browse targets"));
			content.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
			for (GoalsPack.Goal goal : browse)
			{
				content.add(goalRow(goal, false));
				content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			content.add(Box.createVerticalStrut(UiTokens.PAD));
		}

		if (!completed.isEmpty())
		{
			content.add(new SectionLabel("Completed"));
			content.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
			for (GoalsPack.Goal goal : completed)
			{
				ListRow row = ListRow.owned(goal.getName());
				row.setToolTipText(goal.getName() + " — detected on your account");
				applyIcon(goal, row::setNameIcon);
				content.add(row);
				content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}
		content.revalidate();
		content.repaint();
	}

	/** Accent-outlined card: name, %, bar, live checklist. */
	private JPanel activeCard(GoalsPack.Goal goal)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.ACCENT),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));

		double progress = GoalPlannerModule.progress(goal, state);
		JPanel title = new JPanel();
		title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
		title.setOpaque(false);
		title.setAlignmentX(LEFT_ALIGNMENT);
		JLabel name = new JLabel(goal.getName());
		name.setForeground(UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setIconTextGap(UiTokens.PAD_TIGHT);
		applyIcon(goal, name::setIcon);
		title.add(name);
		title.add(Box.createHorizontalGlue());
		JLabel pct = new JLabel(Math.round(progress * 100) + "%");
		pct.setForeground(UiTokens.ACCENT);
		pct.setFont(pct.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		title.add(pct);
		title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		title.add(new com.ironhub.ui.components.IconButton("\u00d7",
			"Remove from your goals",
			() -> GoalPlannerModule.removeGoal(state, goal.getId())));
		card.add(title);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(HubProgressBar.bar(progress));
		card.add(Box.createVerticalStrut(UiTokens.PAD));

		boolean nextFound = false;
		for (CompiledStep step : GoalPlannerModule.compile(goal, state))
		{
			boolean isNext = !step.met && !nextFound;
			nextFound |= isNext;
			card.add(stepLine(step, isNext));
			card.add(Box.createVerticalStrut(2));
		}
		JLabel note = new JLabel("detected steps auto-complete from account state");
		note.setForeground(UiTokens.TEXT_FAINT);
		note.setFont(note.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		note.setAlignmentX(LEFT_ALIGNMENT);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(note);
		return card;
	}

	private JPanel stepLine(CompiledStep step, boolean isNext)
	{
		JPanel line = new JPanel();
		line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);

		Status status = step.met ? Status.OWNED : isNext ? Status.AVAILABLE : Status.LOCKED;
		line.add(new JLabel(new StatusGlyph(status)));
		line.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));

		JLabel label = new JLabel(step.label);
		label.setForeground(step.met ? UiTokens.TEXT_MUTED
			: isNext ? UiTokens.TEXT_PRIMARY : UiTokens.TEXT_BODY);
		label.setFont(label.getFont().deriveFont(
			isNext ? Font.BOLD : Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		label.setMinimumSize(new Dimension(0, 0));
		label.setToolTipText(step.manual
			? step.label + " — manual step, click to " + (step.met ? "untick" : "tick")
			: step.label);
		line.add(label);
		line.add(Box.createHorizontalGlue());

		if (step.manual)
		{
			JLabel tag = new JLabel(step.met ? "manual" : "tick");
			tag.setForeground(UiTokens.TEXT_FAINT);
			tag.setFont(tag.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			line.add(tag);
			line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			line.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					state.setUnlocked(step.unlockKey, !step.met);
				}
			});
		}
		else if (step.met)
		{
			JLabel tag = new JLabel("auto");
			tag.setForeground(UiTokens.STATUS_OWNED);
			tag.setFont(tag.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			line.add(tag);
		}
		return line;
	}

	/** Selected goals activate on click; browse targets add on click. */
	private ListRow goalRow(GoalsPack.Goal goal, boolean selected)
	{
		int pct = (int) Math.round(GoalPlannerModule.progress(goal, state) * 100);
		ListRow row = selected
			? ListRow.available(goal.getName(), new com.ironhub.ui.components.IconButton("\u00d7",
				"Remove from your goals", () -> GoalPlannerModule.removeGoal(state, goal.getId())))
			: ListRow.locked(goal.getName());
		applyIcon(goal, row::setNameIcon);
		row.setToolTipText(selected
			? goal.getName() + " — " + pct + "% · click to make active"
			: goal.getName() + " — click to add to your goals");
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
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
		});
		return row;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
