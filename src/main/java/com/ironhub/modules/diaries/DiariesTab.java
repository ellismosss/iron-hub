package com.ironhub.modules.diaries;

import com.ironhub.data.DiariesPack;
import com.ironhub.modules.diaries.DiariesModule.DiaryRegion;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneMeter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
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
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Diaries tab in the OSRS stonework skin: built-in-journal-style region rows
 * (task count + four-segment tier bar), expanding into the full task list
 * with requirement lines — orange marks tasks that are incomplete but doable
 * right now — plus a collapsible per-diary Rewards section. Same brain and
 * interactions as before the skin; only the clothing changed.
 */
class DiariesTab extends JPanel
{
	/** Wrap widths inside a region card (card interior minus the + column). */
	private static final int TASK_WRAP = 170;
	private static final int REWARD_WRAP = 185;
	/** The thin-meter height — the small bar variant (Luke, 2026-07-17). */
	private static final int TIER_BAR_HEIGHT = 5;

	private final DiariesModule module;
	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final StonePanel card;
	private final StoneMeter bar;
	private final JPanel list = new JPanel();

	/** Region whose task list is open (one at a time, like the in-game journal). */
	private String expandedRegion;
	/** Expanded task lists show only incomplete tasks unless toggled. */
	private boolean showAllTasks;
	/** Regions whose Rewards section is open. */
	private final Set<String> rewardsOpen = new HashSet<>();

	DiariesTab(DiariesModule module, AccountState state, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		add(section("Progress"));
		card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		bar = new StoneMeter(theme, OsrsSkin.PROGRESS_BLUE, 0);
		add(pad(card));
		add(Box.createVerticalStrut(6));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
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
		card.removeAll();
		// OsrsLabel text is immutable — the card refills each rebuild
		card.add(new OsrsLabel(done + "/" + total + " tasks · " + tiersDone + "/"
			+ (DiariesModule.REGIONS.length * 4) + " tiers",
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		card.add(Box.createVerticalStrut(3));
		bar.setFraction(total == 0 ? 0 : (double) done / total);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		card.add(bar);
		cap(card);

		list.removeAll();
		for (DiariesPack.Region region : pack.regions)
		{
			list.add(pad(regionCard(region)));
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		revalidate();
		repaint();
	}

	// ── region card ───────────────────────────────────────────────────

	private JPanel regionCard(DiariesPack.Region region)
	{
		boolean open = region.name.equals(expandedRegion);
		StonePanel regionCard = new StonePanel(theme)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		regionCard.setLayout(new BoxLayout(regionCard, BoxLayout.Y_AXIS));
		regionCard.setAlignmentX(LEFT_ALIGNMENT);

		int done = module.regionDone(region);
		int total = DiariesModule.regionTotal(region);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		// the open region's header wears the theme's select band
		header.setOpaque(open);
		if (open)
		{
			header.setBackground(theme.selectFill);
		}
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel chevron = new JLabel(new PaintedIcon(
			open ? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		chevron.setForeground(OsrsSkin.MUTED);
		header.add(chevron);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		// regular weight (Luke, 2026-07-17): the bold read too heavy per card
		OsrsLabel name = new OsrsLabel(region.name, OsrsSkin.TITLE, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(region.name); // long names ellipsize at 225 px
		header.add(name);
		header.add(Box.createHorizontalGlue());

		OsrsLabel count = new OsrsLabel(done + "/" + total,
			done >= total ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.boldFont());
		header.add(count);

		MouseAdapter press = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedRegion = open ? null : region.name;
				rebuild();
			}
		};
		header.addMouseListener(press);
		// the name's tooltip registers its own mouse listeners, which would
		// swallow the header's — the children carry the press listener too
		name.addMouseListener(press);
		chevron.addMouseListener(press);
		count.addMouseListener(press);
		cap(header);
		regionCard.add(header);
		regionCard.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		regionCard.add(tierBar(region));

		if (open)
		{
			regionCard.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
			// incomplete-only by default (Luke, 2026-07-17): the finished
			// tasks are noise once ticked — a toggle brings them back
			regionCard.add(taskFilterRow());
			for (int i = 0; i < region.tiers.size(); i++)
			{
				DiariesPack.Tier tier = region.tiers.get(i);
				regionCard.add(tierHeader(region, i));
				for (DiariesPack.Task task : tier.tasks)
				{
					if (!showAllTasks && module.taskComplete(region, i, task))
					{
						continue;
					}
					regionCard.add(taskEntry(region, i, task));
				}
				regionCard.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			regionCard.add(rewardsSection(region));
		}
		return regionCard;
	}

	/** The completed-tasks toggle, right-aligned above the tiers. */
	private JComponent taskFilterRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		OsrsLabel toggle = new OsrsLabel(showAllTasks ? "Hide completed" : "Show all",
			OsrsSkin.LABEL, OsrsSkin.smallFont());
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggle.setToolTipText(showAllTasks
			? "Hide the tasks you've already completed"
			: "Show completed tasks too");
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showAllTasks = !showAllTasks;
				rebuild();
			}
		});
		row.add(Box.createHorizontalGlue());
		row.add(toggle);
		cap(row);
		return row;
	}

	/** Four tier segments, in-game-journal style: green complete, orange partial. */
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
					g.setColor(OsrsSkin.BAR_TROUGH);
					g.fillRect(x, 0, w, h);
					g.setColor(complete[i] ? OsrsSkin.VALUE.darker() : OsrsSkin.TITLE.darker());
					g.fillRect(x + 1, 1, (int) Math.round((w - 2) * fractions[i]), h - 2);
					OsrsSkin.outline(g, theme.edgeDark, x, 0, w, h);
				}
			}
		};
		// the thin-meter height (Luke, 2026-07-17): the tall bar crowded the card
		segments.setPreferredSize(new Dimension(0, TIER_BAR_HEIGHT));
		segments.setMinimumSize(new Dimension(0, TIER_BAR_HEIGHT));
		segments.setMaximumSize(new Dimension(Integer.MAX_VALUE, TIER_BAR_HEIGHT));
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
		Color color = done >= total ? OsrsSkin.VALUE : OsrsSkin.MUTED;
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, UiTokens.PAD_TIGHT, 0));

		row.add(new OsrsLabel(tier.tier.toUpperCase(), color, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(done + "/" + total, color, OsrsSkin.font()));
		cap(row);
		return row;
	}

	private JComponent taskEntry(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		boolean complete = module.taskComplete(region, tierIndex, task);
		boolean doable = !complete && module.taskDoable(region, tierIndex, task);
		// the classic scale in skin colours: green done, orange doable now,
		// muted otherwise (the status glyph dropped in favour of row colour)
		Color textColor = complete ? OsrsSkin.VALUE
			: doable ? OsrsSkin.TITLE : OsrsSkin.MUTED;

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

		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);

		// small font (Luke, 2026-07-17) — the progress-bar label size
		OsrsLabel text = OsrsLabel.wrapped(task.task, TASK_WRAP, textColor, OsrsSkin.smallFont())
			.leftAligned();
		text.setToolTipText(task.note != null && !task.note.isEmpty()
			? "<html><body style='width:200px'>" + task.note + "</body></html>"
			: complete ? "Complete"
			: doable ? "Incomplete — requirements met, doable now" : "Incomplete");
		column.add(text);

		if (!complete)
		{
			for (DiariesPack.Req req : task.reqs)
			{
				Boolean met = module.reqMet(req);
				OsrsLabel line = new OsrsLabel("· " + req.text,
					met == null ? OsrsSkin.FAINT : met ? OsrsSkin.VALUE : OsrsSkin.MUTED,
					OsrsSkin.smallFont()).leftAligned().squeezable();
				line.setToolTipText(req.text + (met == null ? ""
					: met ? " — met" : " — not met"));
				column.add(line);
			}
		}
		entry.add(column, BorderLayout.CENTER);

		boolean isGoal = state.getGoalSeeds().containsKey("diary:" + DiariesModule.slug(task));
		JPanel buttonAnchor = new JPanel(new BorderLayout());
		buttonAnchor.setOpaque(false);
		buttonAnchor.add(goalGlyph(isGoal,
			isGoal ? "Remove this task from the goal planner" : "Add this task to the goal planner",
			() -> toggleGoal(region, tierIndex, task)), BorderLayout.NORTH);
		entry.add(buttonAnchor, BorderLayout.EAST);
		return entry;
	}

	/** The per-task +/× affordance in skin colours — faint until hovered
	 *  (the GoalsTab glyph grammar; a dedicated control, never a row click). */
	private static JLabel goalGlyph(boolean isGoal, String tooltip, Runnable onClick)
	{
		JLabel glyph = new JLabel(isGoal ? "×" : "+");
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

	private void toggleGoal(DiariesPack.Region region, int tierIndex, DiariesPack.Task task)
	{
		String slug = DiariesModule.slug(task);
		if (state.getGoalSeeds().containsKey("diary:" + slug))
		{
			state.removeGoalSeed("diary:" + slug);
			return;
		}
		state.addGoalSeed(com.ironhub.state.GoalSeeds.diary(slug, task.task, region.name,
			region.tiers.get(tierIndex).tier));
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

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JLabel triangle = new JLabel(new PaintedIcon(open
			? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		OsrsLabel label = new OsrsLabel("Rewards", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
		header.add(triangle);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(label);
		header.add(Box.createHorizontalGlue());
		cap(header);
		MouseAdapter press = new MouseAdapter()
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
		};
		header.addMouseListener(press);
		// a tooltip registers the label's own mouse listeners, which would
		// swallow the header's — the children carry the press listener too
		String tooltip = "Show or hide this diary's rewards per tier";
		header.setToolTipText(tooltip);
		label.setToolTipText(tooltip);
		label.addMouseListener(press);
		triangle.addMouseListener(press);
		section.add(header);

		if (open)
		{
			section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			for (DiariesPack.Tier tier : region.tiers)
			{
				section.add(new OsrsLabel(tier.tier, OsrsSkin.TITLE, OsrsSkin.boldFont())
					.leftAligned());
				for (String reward : tier.rewards)
				{
					OsrsLabel line = OsrsLabel.wrapped("· " + reward, REWARD_WRAP,
						OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
					section.add(line);
					section.add(Box.createVerticalStrut(2));
				}
				section.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}
		return section;
	}

	// ── layout helpers (the DailiesNewTab/FarmingTab grammar) ─────────

	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, 4, 0, 4));
		holder.add(inner);
		cap(holder);
		return holder;
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
