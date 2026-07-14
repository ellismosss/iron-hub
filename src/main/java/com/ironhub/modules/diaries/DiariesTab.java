package com.ironhub.modules.diaries;

import com.ironhub.data.DiariesPack;
import com.ironhub.modules.diaries.DiariesModule.DiaryRegion;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.StatusGlyph;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * Diaries tab: built-in-journal-style region rows (task count + four-segment
 * tier bar), expanding into the full task list with requirement lines —
 * amber marks tasks that are incomplete but doable right now — plus a
 * collapsible per-diary Rewards section.
 */
class DiariesTab extends JPanel
{
	private final DiariesModule module;
	private final AccountState state;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel summary = new JLabel();
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final JPanel list = new JPanel();

	/** Region whose task list is open (one at a time, like the in-game journal). */
	private String expandedRegion;
	/** Regions whose Rewards section is open. */
	private final Set<String> rewardsOpen = new HashSet<>();

	DiariesTab(DiariesModule module, AccountState state)
	{
		this.module = module;
		this.state = state;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.add(new SectionLabel("Progress"));
		summary.setForeground(UiTokens.TEXT_PRIMARY);
		summary.setFont(summary.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		card.add(summary);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(bar);
		add(card);
		add(Box.createVerticalStrut(UiTokens.PAD));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(UiTokens.PANEL_BG);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seam: open a region's task list and its rewards section. */
	void expandForTest(String regionName)
	{
		expandedRegion = regionName;
		rewardsOpen.add(regionName);
		rebuild();
	}

	private void rebuild()
	{
		DiariesPack pack = module.pack();
		int total = 0;
		int done = 0;
		for (DiariesPack.Region region : pack.regions)
		{
			total += DiariesModule.regionTotal(region);
			done += module.regionDone(region);
		}
		int tiersDone = DiariesModule.totalTiersComplete(state);
		summary.setText(done + "/" + total + " tasks · " + tiersDone + "/"
			+ (DiariesModule.REGIONS.length * 4) + " tiers");
		bar.setFraction(total == 0 ? 0 : (double) done / total);

		list.removeAll();
		for (DiariesPack.Region region : pack.regions)
		{
			list.add(regionCard(region));
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	// ── region card ───────────────────────────────────────────────────

	private JPanel regionCard(DiariesPack.Region region)
	{
		boolean open = region.name.equals(expandedRegion);
		JPanel card = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));

		int done = module.regionDone(region);
		int total = DiariesModule.regionTotal(region);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel chevron = new JLabel(new PaintedIcon(
			open ? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		chevron.setForeground(UiTokens.GLYPH_MUTED);
		header.add(chevron);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		JLabel name = new JLabel(region.name);
		name.setForeground(UiTokens.TEXT_PRIMARY);
		name.setFont(name.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		name.setMinimumSize(new Dimension(0, 0));
		name.setToolTipText(region.name); // long names ellipsize at 225 px
		header.add(name);
		header.add(Box.createHorizontalGlue());

		JLabel count = new JLabel(done + "/" + total);
		count.setForeground(done >= total ? UiTokens.STATUS_OWNED : UiTokens.TEXT_BODY);
		count.setFont(count.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		header.add(count);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedRegion = open ? null : region.name;
				rebuild();
			}
		});
		card.add(header);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(tierBar(region));

		if (open)
		{
			card.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
			for (int i = 0; i < region.tiers.size(); i++)
			{
				DiariesPack.Tier tier = region.tiers.get(i);
				card.add(tierHeader(region, i));
				for (DiariesPack.Task task : tier.tasks)
				{
					card.add(taskEntry(region, i, task));
				}
				card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			card.add(rewardsSection(region));
		}
		return card;
	}

	/** Four tier segments, in-game-journal style: green complete, amber partial. */
	private JComponent tierBar(DiariesPack.Region region)
	{
		DiaryRegion meta = DiariesModule.regionMeta(region.name);
		double[] fractions = new double[4];
		boolean[] complete = new boolean[4];
		StringBuilder tip = new StringBuilder("<html>");
		for (int i = 0; i < region.tiers.size() && i < 4; i++)
		{
			DiariesPack.Tier tier = region.tiers.get(i);
			int done = module.tierDone(region, i);
			fractions[i] = tier.tasks.isEmpty() ? 0 : (double) done / tier.tasks.size();
			complete[i] = meta != null && state.getVarbit(meta.tierVarbits[i]) >= 1;
			tip.append(i > 0 ? "<br>" : "").append(tier.tier).append(" — ")
				.append(done).append("/").append(tier.tasks.size())
				.append(complete[i] ? " (complete)" : "");
		}
		JComponent segments = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				int gap = 2;
				int w = (getWidth() - 3 * gap) / 4;
				int h = getHeight();
				for (int i = 0; i < 4; i++)
				{
					int x = i * (w + gap);
					g.setColor(UiTokens.INSET_BG);
					g.fillRect(x, 0, w, h);
					g.setColor(complete[i] ? UiTokens.STATUS_OWNED : UiTokens.STATUS_AVAILABLE);
					g.fillRect(x + 1, 1, (int) Math.round((w - 2) * fractions[i]), h - 2);
					g.setColor(UiTokens.BORDER_DIM);
					g.drawRect(x, 0, w - 1, h - 1);
				}
			}
		};
		segments.setPreferredSize(new Dimension(0, UiTokens.PROGRESS_BAR_HEIGHT));
		segments.setMinimumSize(new Dimension(0, UiTokens.PROGRESS_BAR_HEIGHT));
		segments.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.PROGRESS_BAR_HEIGHT));
		segments.setAlignmentX(LEFT_ALIGNMENT);
		segments.setToolTipText(tip.append("</html>").toString());
		return segments;
	}

	// ── expanded task list ────────────────────────────────────────────

	private JComponent tierHeader(DiariesPack.Region region, int tierIndex)
	{
		DiariesPack.Tier tier = region.tiers.get(tierIndex);
		int done = module.tierDone(region, tierIndex);
		int total = tier.tasks.size();
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, UiTokens.PAD_TIGHT, 0));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.CATEGORY_HEADER_HEIGHT));

		JLabel label = new JLabel(tier.tier.toUpperCase());
		label.setForeground(done >= total ? UiTokens.STATUS_OWNED : UiTokens.TEXT_MUTED);
		label.setFont(SectionLabel.letterSpaced(
			label.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
			UiTokens.LETTER_SPACING_LABEL));
		row.add(label);
		row.add(Box.createHorizontalGlue());

		JLabel count = new JLabel(done + "/" + total);
		count.setForeground(done >= total ? UiTokens.STATUS_OWNED : UiTokens.TEXT_MUTED);
		count.setFont(count.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		row.add(count);
		return row;
	}

	private JComponent taskEntry(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		boolean complete = module.taskComplete(region, tierIndex, task);
		boolean doable = !complete && module.taskDoable(region, tierIndex, task);
		Status status = complete ? Status.OWNED : doable ? Status.AVAILABLE : Status.LOCKED;
		Color textColor = complete ? UiTokens.TEXT_MUTED
			: doable ? UiTokens.STATUS_AVAILABLE : UiTokens.TEXT_BODY;

		JPanel entry = new JPanel(new BorderLayout(UiTokens.ROW_GAP, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		entry.setOpaque(false);
		entry.setAlignmentX(LEFT_ALIGNMENT);
		entry.setBorder(new EmptyBorder(0, 0, UiTokens.PAD_TIGHT, 0));

		JPanel glyphAnchor = new JPanel(new BorderLayout());
		glyphAnchor.setOpaque(false);
		JLabel glyph = new JLabel(new StatusGlyph(status));
		glyph.setToolTipText(complete ? "Complete"
			: doable ? "Incomplete — requirements met, doable now" : "Incomplete");
		glyphAnchor.add(glyph, BorderLayout.NORTH);
		entry.add(glyphAnchor, BorderLayout.WEST);

		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);

		JTextArea text = new JTextArea(task.task);
		text.setFont(text.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		text.setForeground(textColor);
		text.setBackground(UiTokens.CARD_BG);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setEditable(false);
		text.setFocusable(false);
		text.setBorder(new EmptyBorder(0, 0, 0, 0));
		text.setAlignmentX(LEFT_ALIGNMENT);
		if (task.note != null && !task.note.isEmpty())
		{
			text.setToolTipText("<html><body style='width:200px'>" + task.note + "</body></html>");
		}
		column.add(text);

		if (!complete)
		{
			for (DiariesPack.Req req : task.reqs)
			{
				Boolean met = module.reqMet(req);
				JLabel line = new JLabel("· " + req.text);
				line.setForeground(met == null ? UiTokens.TEXT_FAINT
					: met ? UiTokens.STATUS_OWNED : UiTokens.TEXT_MUTED);
				line.setFont(line.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
				line.setAlignmentX(LEFT_ALIGNMENT);
				line.setMinimumSize(new Dimension(0, 0));
				line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
				line.setToolTipText(req.text + (met == null ? ""
					: met ? " — met" : " — not met"));
				column.add(line);
			}
		}
		entry.add(column, BorderLayout.CENTER);

		boolean isGoal = state.getDiaryGoals().containsKey(DiariesModule.slug(task));
		JPanel buttonAnchor = new JPanel(new BorderLayout());
		buttonAnchor.setOpaque(false);
		buttonAnchor.add(new IconButton(isGoal ? "\u00d7" : "+",
			isGoal ? "Remove this task from the goal planner" : "Add this task to the goal planner",
			() -> toggleGoal(region, tierIndex, task)), BorderLayout.NORTH);
		entry.add(buttonAnchor, BorderLayout.EAST);
		return entry;
	}

	private void toggleGoal(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		String slug = DiariesModule.slug(task);
		if (state.getDiaryGoals().containsKey(slug))
		{
			state.removeDiaryGoal(slug);
			return;
		}
		state.addDiaryGoal(slug, task.task, region.name,
			region.tiers.get(tierIndex).tier);
		if (module.taskComplete(region, tierIndex, task))
		{
			// already done in-game: prove the goal immediately
			state.setUnlocked("diarytask_" + slug, true);
		}
	}

	// ── rewards ───────────────────────────────────────────────────────

	private JComponent rewardsSection(DiariesPack.Region region)
	{
		boolean open = rewardsOpen.contains(region.name);
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		JLabel header = new JLabel("REWARDS");
		header.setForeground(UiTokens.TEXT_MUTED);
		header.setFont(SectionLabel.letterSpaced(
			header.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
			UiTokens.LETTER_SPACING_LABEL));
		header.setIcon(new PaintedIcon(open
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		header.setIconTextGap(UiTokens.ROW_GAP);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setToolTipText("Show or hide this diary's rewards per tier");
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!rewardsOpen.remove(region.name))
				{
					rewardsOpen.add(region.name);
				}
				rebuild();
			}
		});
		section.add(header);

		if (open)
		{
			section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			for (DiariesPack.Tier tier : region.tiers)
			{
				JLabel tierName = new JLabel(tier.tier);
				tierName.setForeground(UiTokens.TEXT_PRIMARY);
				tierName.setFont(tierName.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
				tierName.setAlignmentX(LEFT_ALIGNMENT);
				section.add(tierName);
				for (String reward : tier.rewards)
				{
					JTextArea line = new JTextArea("· " + reward);
					line.setFont(line.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
					line.setForeground(UiTokens.TEXT_MUTED);
					line.setBackground(UiTokens.CARD_BG);
					line.setLineWrap(true);
					line.setWrapStyleWord(true);
					line.setEditable(false);
					line.setFocusable(false);
					line.setBorder(new EmptyBorder(0, 0, 2, 0));
					line.setAlignmentX(LEFT_ALIGNMENT);
					section.add(line);
				}
				section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}
		return section;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
