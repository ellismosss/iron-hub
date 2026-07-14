package com.ironhub.ui;

import com.ironhub.ui.components.AlertChip;
import com.ironhub.ui.components.ChipRow;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.StatusGlyph;
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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;

/**
 * Dashboard — panel home (mockup frame 1b). Stacked strips top-to-bottom by
 * decision order: header, account score, "What now?", active goal, alert
 * chips, next best upgrades, all-modules footer.
 *
 * M1: hardcoded placeholder data; real values arrive with AccountState (M2+).
 */
public class DashboardPanel extends JPanel
{
	public DashboardPanel(Runnable onAllModules)
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);

		add(header());
		add(scoreSection());
		add(whatNowSection());
		add(activeGoalSection());
		add(alertsSection());
		add(upgradesSection());
		add(footer(onAllModules));
		add(Box.createVerticalGlue());
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

		JLabel settings = new JLabel("⋮", SwingConstants.CENTER);
		settings.setForeground(UiTokens.TEXT_MUTED);
		settings.setFont(settings.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
		settings.setToolTipText("Settings");
		settings.setPreferredSize(new Dimension(UiTokens.ICON_BUTTON_SIZE, UiTokens.ICON_BUTTON_SIZE));
		settings.setMaximumSize(new Dimension(UiTokens.ICON_BUTTON_SIZE, UiTokens.ICON_BUTTON_SIZE));
		settings.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		hoverForeground(settings, UiTokens.TEXT_MUTED);
		header.add(settings);
		return header;
	}

	private JComponent scoreSection()
	{
		JPanel section = strip(UiTokens.PAD);

		JPanel top = row();
		JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
		left.setOpaque(false);
		left.add(new SectionLabel("Account score"));

		JPanel figure = row();
		JLabel pct = new JLabel("61%");
		pct.setForeground(UiTokens.TEXT_PRIMARY);
		pct.setFont(pct.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SCORE));
		figure.add(pct);
		figure.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel trend = new JLabel("▲ 1.2 this wk");
		trend.setForeground(UiTokens.STATUS_OWNED);
		trend.setFont(trend.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL));
		figure.add(trend);
		left.add(figure);
		left.setAlignmentY(Component.CENTER_ALIGNMENT);
		top.add(left);
		top.add(Box.createHorizontalGlue());
		top.add(new Sparkline());
		section.add(top);
		section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		JLabel components = new JLabel("quests 74 · diaries 58 · CA 41 · QoL 63 · log 22");
		components.setForeground(UiTokens.TEXT_FAINT);
		components.setFont(components.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		components.setAlignmentX(LEFT_ALIGNMENT);
		components.setMinimumSize(new Dimension(0, 0));
		section.add(components);
		return section;
	}

	private JComponent whatNowSection()
	{
		JPanel section = strip(UiTokens.PAD);
		section.add(new SectionLabel("What now?"));
		section.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		ChipRow time = new ChipRow("5m", "30m", "1h", "2h+");
		time.setSelected(2);
		section.add(time);
		section.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

		section.add(new SuggestionCard(1, "Herb run", "~6 min", "7 patches ready · crops decay"));
		section.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		section.add(new SuggestionCard(2, "Sepulchre laps", "40 min", "feeds 70 Agility → Bowfa goal"));
		section.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		section.add(new SuggestionCard(3, "Tears of Guthix", "5 min", "weekly · resets in 2 d 4 h"));
		return section;
	}

	private JComponent activeGoalSection()
	{
		JPanel section = strip(UiTokens.PAD);
		section.add(new SectionLabel("Active goal"));
		section.add(Box.createVerticalStrut(5));

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel titleLine = row();
		JLabel goal = new JLabel("Bow of Faerdhinen");
		goal.setForeground(UiTokens.TEXT_PRIMARY);
		goal.setFont(goal.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		goal.setMinimumSize(new Dimension(0, 0));
		titleLine.add(goal);
		titleLine.add(Box.createHorizontalGlue());
		JLabel pct = new JLabel("34%");
		pct.setForeground(UiTokens.ACCENT);
		pct.setFont(pct.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		titleLine.add(pct);
		card.add(titleLine);
		card.add(Box.createVerticalStrut(5));

		card.add(HubProgressBar.bar(0.34));
		card.add(Box.createVerticalStrut(5));

		JPanel next = row();
		JLabel dot = new JLabel(new StatusGlyph(Status.AVAILABLE));
		next.add(dot);
		next.add(Box.createHorizontalStrut(5));
		JLabel step = new JLabel("Next: Song of the Elves");
		step.setForeground(UiTokens.TEXT_BODY);
		step.setFont(step.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		step.setMinimumSize(new Dimension(0, 0));
		next.add(step);
		next.add(Box.createHorizontalGlue());
		next.add(IconButton.path(null));
		card.add(next);

		section.add(card);
		return section;
	}

	private JComponent alertsSection()
	{
		JPanel section = strip(UiTokens.PAD);
		JPanel chips = new JPanel(new WrapLayout(java.awt.FlowLayout.LEFT, UiTokens.PAD_TIGHT, UiTokens.PAD_TIGHT));
		chips.setOpaque(false);
		chips.setAlignmentX(LEFT_ALIGNMENT);
		chips.add(new AlertChip("4 dailies", Status.AVAILABLE));
		chips.add(new AlertChip("7 patches ready", Status.AVAILABLE));
		chips.add(new AlertChip("! runway 6 h", Status.WARNING));
		section.add(chips);
		return section;
	}

	private JComponent upgradesSection()
	{
		JPanel section = strip(UiTokens.PAD);
		section.add(new SectionLabel("Next best upgrades"));
		section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		section.add(ListRow.available("Barrows gloves", IconButton.path(null), IconButton.wiki(null)));
		section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		section.add(ListRow.available("Dragon boots", IconButton.path(null), IconButton.wiki(null)));
		section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		section.add(ListRow.locked("Ava's assembler", "Dragon Slayer II",
			IconButton.path(null), IconButton.wiki(null)));
		return section;
	}

	private JComponent footer(Runnable onAllModules)
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
		JLabel chevron = new JLabel("›");
		chevron.setForeground(UiTokens.TEXT_FAINT);
		chevron.setFont(chevron.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
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

	// a vertical section strip with bottom rule; pad = 0 for the header
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

	private static void hoverForeground(JLabel label, java.awt.Color normal)
	{
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(UiTokens.ACCENT);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(normal);
			}
		});
	}

	/** 64×20 single green polyline — trend direction only, no axes. */
	private static class Sparkline extends JComponent
	{
		// mockup frame 1b polyline, normalized to the 64×20 viewBox
		private static final int[][] POINTS = {
			{1, 16}, {9, 15}, {17, 15}, {25, 13}, {33, 12}, {41, 10}, {49, 8}, {57, 7}, {63, 5},
		};

		Sparkline()
		{
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
			for (int i = 1; i < POINTS.length; i++)
			{
				g2.drawLine(POINTS[i - 1][0], POINTS[i - 1][1], POINTS[i][0], POINTS[i][1]);
			}
			g2.dispose();
		}
	}

	/** Ranked "What now?" card: accent rank · bold title + duration · why line. */
	private static class SuggestionCard extends JPanel
	{
		SuggestionCard(int rank, String title, String duration, String why)
		{
			setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
			setBackground(UiTokens.CARD_BG);
			setAlignmentX(LEFT_ALIGNMENT);
			setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
				new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			JLabel rankLabel = new JLabel(String.valueOf(rank), SwingConstants.CENTER);
			rankLabel.setForeground(UiTokens.ACCENT);
			rankLabel.setFont(rankLabel.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
			rankLabel.setPreferredSize(new Dimension(UiTokens.STATUS_GLYPH_SIZE, 0));
			rankLabel.setMaximumSize(new Dimension(UiTokens.STATUS_GLYPH_SIZE, Integer.MAX_VALUE));
			add(rankLabel);
			add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);

			JPanel titleLine = row();
			JLabel titleLabel = new JLabel(title);
			titleLabel.setForeground(UiTokens.TEXT_PRIMARY);
			titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
			titleLabel.setToolTipText(title);
			titleLabel.setMinimumSize(new Dimension(0, 0));
			titleLine.add(titleLabel);
			titleLine.add(Box.createHorizontalGlue());
			JLabel durationLabel = new JLabel(duration);
			durationLabel.setForeground(UiTokens.TEXT_MUTED);
			durationLabel.setFont(durationLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			titleLine.add(durationLabel);
			text.add(titleLine);

			JLabel whyLabel = new JLabel(why);
			whyLabel.setForeground(UiTokens.TEXT_MUTED);
			whyLabel.setFont(whyLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			whyLabel.setToolTipText(why); // one line, ellipsized; full text in tooltip
			whyLabel.setMinimumSize(new Dimension(0, 0));
			whyLabel.setAlignmentX(LEFT_ALIGNMENT);
			text.add(whyLabel);
			add(text);
			add(Box.createHorizontalGlue());

			JLabel chevron = new JLabel("›");
			chevron.setForeground(UiTokens.TEXT_FAINT);
			chevron.setFont(chevron.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
			add(chevron);

			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					setBorder(new CompoundBorder(new LineBorder(UiTokens.ACCENT),
						new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
						new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
				}
			});
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		private static JPanel row()
		{
			JPanel row = new JPanel();
			row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
			row.setOpaque(false);
			row.setAlignmentX(LEFT_ALIGNMENT);
			return row;
		}
	}
}
