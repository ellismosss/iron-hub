package com.ironhub.ui;

import com.ironhub.data.BankedXpPack;
import com.ironhub.data.DailiesPack;
import com.ironhub.data.DataPack;
import com.ironhub.data.GearLaddersPack;
import com.ironhub.data.GoalsPack;
import com.ironhub.data.HerbPatchesPack;
import com.ironhub.data.QolPack;
import com.ironhub.modules.dailies.DailiesModule;
import com.ironhub.modules.dashboard.AccountScore;
import com.ironhub.modules.farming.FarmingRunModule;
import com.ironhub.modules.gear.GearProgressionModule;
import com.ironhub.modules.goals.GoalPlannerModule;
import com.ironhub.modules.suggest.WhatNowModule;
import com.ironhub.modules.supplies.SuppliesRunwayModule;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.AlertChip;
import com.ironhub.ui.components.ChipRow;
import com.ironhub.ui.components.GridTile;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.StatusGlyph;
import com.ironhub.ui.components.SuggestionCard;
import com.ironhub.ui.components.WrapLayout;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;

/**
 * Dashboard — panel home (frame 1b), live: composite account score with
 * clickable components, snapshot-driven sparkline + weekly trend, real
 * What Now cards, active goal strip, alert chips, next best upgrades.
 */
public class DashboardPanel extends JPanel
{
	private static final int[] BUDGETS = {5, 30, 60, 180};

	private final AccountState state;
	private final Consumer<String> openModule;
	private final Runnable onAllModules;
	private final QolPack qolPack;
	private final GoalsPack goalsPack;
	private final DailiesPack dailiesPack;
	private final HerbPatchesPack herbPack;
	private final GearLaddersPack gearPack;
	private final WhatNowModule.Packs whatNowPacks;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JPanel content = new JPanel();
	private int timeBudget = 2; // 1h default

	public DashboardPanel(AccountState state, DataPack dataPack,
		Consumer<String> openModule, Runnable onAllModules)
	{
		this.state = state;
		this.openModule = openModule;
		this.onAllModules = onAllModules;
		this.qolPack = dataPack.load("qol", QolPack.class);
		this.goalsPack = dataPack.load("goals", GoalsPack.class);
		this.dailiesPack = dataPack.load("dailies", DailiesPack.class);
		this.herbPack = dataPack.load("herb-patches", HerbPatchesPack.class);
		this.gearPack = dataPack.load("gear-ladders", GearLaddersPack.class);
		this.whatNowPacks = new WhatNowModule.Packs(dailiesPack, herbPack,
			dataPack.load("banked-xp", BankedXpPack.class), goalsPack);

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(UiTokens.PANEL_BG);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	public void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		content.removeAll();
		content.add(header());
		content.add(scoreSection());
		content.add(whatNowSection());
		content.add(activeGoalSection());
		content.add(alertsSection());
		content.add(upgradesSection());
		content.add(footer());
		content.revalidate();
		content.repaint();
	}

	private JComponent header()
	{
		JPanel header = strip(0);
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.PAD, 0, UiTokens.PAD)));
		header.setBackground(UiTokens.CARD_BG);
		header.setPreferredSize(new Dimension(0, UiTokens.HEADER_HEIGHT));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.HEADER_HEIGHT));

		JLabel title = new JLabel("IRON HUB");
		title.setForeground(UiTokens.TEXT_PRIMARY);
		title.setFont(SectionLabel.letterSpaced(
			title.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY),
			UiTokens.LETTER_SPACING_TITLE));
		header.add(title);
		header.add(Box.createHorizontalGlue());
		return header;
	}

	private JComponent scoreSection()
	{
		JPanel section = strip(UiTokens.PAD);
		Map<String, Integer> components = AccountScore.components(state, qolPack);
		int score = AccountScore.composite(components);
		state.maybeSnapshotScore(score);

		JPanel top = row();
		JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
		left.setOpaque(false);
		left.add(new SectionLabel("Account score"));

		JPanel figure = row();
		JLabel pct = new JLabel(score + "%");
		pct.setForeground(UiTokens.TEXT_PRIMARY);
		pct.setFont(pct.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SCORE));
		figure.add(pct);

		double weeklyDelta = weeklyDelta(score);
		if (weeklyDelta != 0)
		{
			figure.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			boolean up = weeklyDelta > 0;
			JLabel trend = new JLabel(String.format(java.util.Locale.ROOT, "%.1f this wk", Math.abs(weeklyDelta)),
				new PaintedIcon(up ? PaintedIcon.Shape.TRIANGLE_UP : PaintedIcon.Shape.TRIANGLE_DOWN,
					(int) UiTokens.FONT_SIZE_LABEL),
				SwingConstants.LEADING);
			trend.setIconTextGap(UiTokens.PAD_TIGHT);
			trend.setForeground(up ? UiTokens.STATUS_OWNED : UiTokens.STATUS_WARNING);
			trend.setFont(trend.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL));
			figure.add(trend);
		}
		left.add(figure);
		left.setAlignmentY(Component.CENTER_ALIGNMENT);
		top.add(left);
		top.add(Box.createHorizontalGlue());
		top.add(new Sparkline(state.getScoreSnapshots()));
		section.add(top);
		section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		// component labels click through to their modules (exit criterion)
		JPanel line = row();
		boolean first = true;
		for (Map.Entry<String, Integer> component : components.entrySet())
		{
			if (!first)
			{
				JLabel dot = new JLabel(" · ");
				dot.setForeground(UiTokens.TEXT_FAINT);
				dot.setFont(dot.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
				line.add(dot);
			}
			first = false;
			String text = component.getKey()
				+ " " + (component.getValue() < 0 ? "-" : component.getValue());
			JLabel label = new JLabel(text);
			label.setForeground(UiTokens.TEXT_FAINT);
			label.setFont(label.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			String module = AccountScore.COMPONENT_MODULES.get(component.getKey());
			label.setToolTipText("Open " + module);
			label.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					openModule.accept(module);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					label.setForeground(UiTokens.ACCENT);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					label.setForeground(UiTokens.TEXT_FAINT);
				}
			});
			line.add(label);
		}
		line.add(Box.createHorizontalGlue());
		section.add(line);
		return section;
	}

	private double weeklyDelta(int score)
	{
		List<long[]> snapshots = state.getScoreSnapshots();
		long weekAgo = System.currentTimeMillis() - 7L * 24 * 3_600_000;
		return snapshots.stream()
			.filter(s -> s[0] <= weekAgo)
			.reduce((a, b) -> b) // latest snapshot at least a week old
			.map(s -> (double) score - s[1])
			.orElse(0.0);
	}

	private JComponent whatNowSection()
	{
		JPanel section = strip(UiTokens.PAD);
		section.add(new SectionLabel("What now?"));
		section.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		ChipRow time = new ChipRow("5m", "30m", "1h", "2h+");
		time.setSelected(timeBudget);
		time.onChange(i ->
		{
			timeBudget = i;
			rebuild();
		});
		section.add(time);
		section.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		List<WhatNowModule.Suggestion> suggestions =
			WhatNowModule.suggest(state, whatNowPacks, BUDGETS[timeBudget]);
		if (suggestions.isEmpty())
		{
			JLabel none = new JLabel("Nothing urgent right now.");
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			none.setAlignmentX(LEFT_ALIGNMENT);
			section.add(none);
		}
		int rank = 1;
		for (WhatNowModule.Suggestion suggestion : suggestions.subList(0, Math.min(3, suggestions.size())))
		{
			SuggestionCard card = new SuggestionCard(rank++, suggestion.title,
				"~" + suggestion.minutes + " min", suggestion.why);
			card.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					openModule.accept("What now?");
				}
			});
			section.add(card);
			section.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		}
		return section;
	}

	private JComponent activeGoalSection()
	{
		JPanel section = strip(UiTokens.PAD);
		section.add(new SectionLabel("Active goal"));
		section.add(Box.createVerticalStrut(5));

		GoalsPack.Goal active = goalsPack.getGoals().stream()
			.filter(g -> g.getId().equals(state.getActiveGoal()))
			.filter(g -> !GoalPlannerModule.isAchieved(g, state)) // done → prompt for a new one
			.findFirst().orElse(null);
		if (active == null)
		{
			JLabel none = new JLabel("Pick a goal in the Goal planner.");
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			none.setAlignmentX(LEFT_ALIGNMENT);
			none.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			none.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					openModule.accept("Goal planner");
				}
			});
			section.add(none);
			return section;
		}

		double progress = GoalPlannerModule.progress(active, state);
		GoalPlannerModule.CompiledStep next = GoalPlannerModule.nextStep(active, state);

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				openModule.accept("Goal planner");
			}
		});

		JPanel titleLine = row();
		JLabel goal = new JLabel(active.getName());
		goal.setForeground(UiTokens.TEXT_PRIMARY);
		goal.setFont(goal.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		goal.setMinimumSize(new Dimension(0, 0));
		titleLine.add(goal);
		titleLine.add(Box.createHorizontalGlue());
		JLabel pct = new JLabel(Math.round(progress * 100) + "%");
		pct.setForeground(UiTokens.ACCENT);
		pct.setFont(pct.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		titleLine.add(pct);
		card.add(titleLine);
		card.add(Box.createVerticalStrut(5));
		card.add(HubProgressBar.bar(progress));

		if (next != null)
		{
			card.add(Box.createVerticalStrut(5));
			JPanel nextLine = row();
			nextLine.add(new JLabel(new StatusGlyph(Status.AVAILABLE)));
			nextLine.add(Box.createHorizontalStrut(5));
			JLabel step = new JLabel("Next: " + next.label);
			step.setForeground(UiTokens.TEXT_BODY);
			step.setFont(step.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			step.setMinimumSize(new Dimension(0, 0));
			nextLine.add(step);
			nextLine.add(Box.createHorizontalGlue());
			card.add(nextLine);
		}
		section.add(card);
		return section;
	}

	private JComponent alertsSection()
	{
		List<JComponent> chips = new ArrayList<>();
		int dailies = DailiesModule.outstanding(state, dailiesPack);
		if (dailies > 0)
		{
			chips.add(alertChip(dailies + (dailies == 1 ? " daily" : " dailies"),
				Status.AVAILABLE, "Dailies"));
		}
		int ready = FarmingRunModule.readyPatches(state, herbPack);
		if (ready > 0)
		{
			chips.add(alertChip(ready + " patches ready", Status.AVAILABLE, "Farming runs"));
		}
		SuppliesRunwayModule.compute(state).values().stream()
			.filter(r -> r.hoursLeft() < 6)
			.findFirst()
			.ifPresent(r -> chips.add(alertChip(
				"! runway " + SuppliesRunwayModule.formatHours(r.hoursLeft()),
				Status.WARNING, "Supplies runway")));
		if (chips.isEmpty())
		{
			return new JPanel()
			{
				{
					setVisible(false);
					setMaximumSize(new Dimension(0, 0));
				}
			};
		}

		JPanel section = strip(UiTokens.PAD);
		JPanel wrap = new JPanel(new WrapLayout(java.awt.FlowLayout.LEFT, UiTokens.PAD_TIGHT, UiTokens.PAD_TIGHT));
		wrap.setOpaque(false);
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		chips.forEach(wrap::add);
		section.add(wrap);
		return section;
	}

	private AlertChip alertChip(String text, Status status, String module)
	{
		AlertChip chip = new AlertChip(text, status);
		chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chip.setToolTipText("Open " + module);
		chip.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				openModule.accept(module);
			}
		});
		return chip;
	}

	private JComponent upgradesSection()
	{
		// top obtainable-now rungs across styles and slots
		List<String> upgrades = new ArrayList<>();
		for (GearLaddersPack.Style style : gearPack.getStyles())
		{
			for (GearLaddersPack.Slot slot : style.getSlots())
			{
				List<GridTile.State> states =
					GearProgressionModule.ladderStates(state, slot.getLadder());
				int next = states.indexOf(GridTile.State.NEXT);
				if (next >= 0)
				{
					upgrades.add(slot.getLadder().get(next).getName());
				}
			}
		}
		JPanel section = strip(UiTokens.PAD);
		section.add(new SectionLabel("Next best upgrades"));
		section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		if (upgrades.isEmpty())
		{
			JLabel none = new JLabel("No obtainable upgrades tracked right now.");
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			none.setAlignmentX(LEFT_ALIGNMENT);
			section.add(none);
		}
		for (String upgrade : upgrades.subList(0, Math.min(3, upgrades.size())))
		{
			ListRow row = ListRow.available(upgrade);
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			row.setToolTipText(upgrade + " — open Gear progression");
			row.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					openModule.accept("Gear progression");
				}
			});
			section.add(row);
			section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		return section;
	}

	private JComponent footer()
	{
		JPanel footer = new JPanel();
		footer.setLayout(new BoxLayout(footer, BoxLayout.X_AXIS));
		footer.setBackground(UiTokens.PANEL_BG);
		footer.setAlignmentX(LEFT_ALIGNMENT);
		footer.setBorder(new CompoundBorder(
			new MatteBorder(1, 0, 0, 0, UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.PAD, 0, UiTokens.PAD)));
		footer.setPreferredSize(new Dimension(0, UiTokens.FOOTER_ROW_HEIGHT));
		footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.FOOTER_ROW_HEIGHT));
		footer.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel label = new JLabel("All modules");
		label.setForeground(UiTokens.TEXT_BODY);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		footer.add(label);
		footer.add(Box.createHorizontalGlue());
		JLabel chevron = new JLabel(new PaintedIcon(
			PaintedIcon.Shape.CHEVRON_RIGHT, (int) UiTokens.FONT_SIZE_LABEL));
		chevron.setForeground(UiTokens.TEXT_FAINT);
		footer.add(chevron);

		footer.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				footer.setBackground(UiTokens.NAV_ROW_HOVER_BG);
				label.setForeground(UiTokens.ACCENT);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				footer.setBackground(UiTokens.PANEL_BG);
				label.setForeground(UiTokens.TEXT_BODY);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onAllModules != null)
				{
					onAllModules.run();
				}
			}
		});
		return footer;
	}

	private static JPanel strip(int pad)
	{
		JPanel strip = new JPanel();
		strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
		strip.setBackground(UiTokens.PANEL_BG);
		strip.setAlignmentX(LEFT_ALIGNMENT);
		strip.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, UiTokens.BORDER_ROW),
			new EmptyBorder(pad, pad, pad, pad)));
		return strip;
	}

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	/** 64×20 green polyline over real score snapshots; flat when sparse. */
	private static class Sparkline extends JComponent
	{
		private final List<long[]> snapshots;

		Sparkline(List<long[]> snapshots)
		{
			this.snapshots = snapshots;
			Dimension size = new Dimension(UiTokens.SPARKLINE_WIDTH, UiTokens.SPARKLINE_HEIGHT);
			setPreferredSize(size);
			setMinimumSize(size);
			setMaximumSize(size);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(UiTokens.STATUS_OWNED);
			g2.setStroke(new BasicStroke(1.5f));

			int w = UiTokens.SPARKLINE_WIDTH;
			int h = UiTokens.SPARKLINE_HEIGHT;
			if (snapshots.size() < 2)
			{
				g2.drawLine(1, h / 2, w - 1, h / 2);
			}
			else
			{
				long min = snapshots.stream().mapToLong(s -> s[1]).min().orElse(0);
				long max = Math.max(min + 1, snapshots.stream().mapToLong(s -> s[1]).max().orElse(1));
				int n = snapshots.size();
				int prevX = 1;
				int prevY = yFor(snapshots.get(0)[1], min, max, h);
				for (int i = 1; i < n; i++)
				{
					int x = 1 + (int) ((w - 2) * (i / (double) (n - 1)));
					int y = yFor(snapshots.get(i)[1], min, max, h);
					g2.drawLine(prevX, prevY, x, y);
					prevX = x;
					prevY = y;
				}
			}
			g2.dispose();
		}

		private static int yFor(long score, long min, long max, int h)
		{
			return (int) ((h - 3) - (h - 5) * ((score - min) / (double) (max - min))) + 1;
		}
	}
}
