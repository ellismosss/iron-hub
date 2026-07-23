package com.ironhub.modules.qol;

import com.ironhub.data.QolPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.util.LinkBrowser;

/**
 * QoL tab content in the OSRS stonework skin: unlocked-count progress bar +
 * one row per unlock (owned / obtainable now / locked with its blocking
 * requirement in the tooltip). Same brain as before — only the clothing
 * changed.
 */
class QolTab extends JPanel
{
	private final AccountState state;
	private final QolPack pack;
	private final OsrsTheme theme;
	private final java.util.function.IntPredicate planWantsItem;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final JPanel frame = new JPanel();
	/** Obtained rows hide by default (Luke) — the toggle shows them. */
	private boolean showObtained;
	/** Single expanded unlock (click a row) — its benefit + requirement lines. */
	private String expandedId;

	QolTab(AccountState state, QolPack pack, OsrsTheme theme)
	{
		this(state, pack, theme, id -> false);
	}

	QolTab(AccountState state, QolPack pack, OsrsTheme theme,
		java.util.function.IntPredicate planWantsItem)
	{
		this.state = state;
		this.pack = pack;
		this.theme = theme;
		this.planWantsItem = planWantsItem;
		// frameless: the host's stone header plate names the module
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(false);
		frame.setAlignmentX(LEFT_ALIGNMENT);
		add(frame);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		frame.removeAll();

		long owned = pack.getUnlocks().stream()
			.filter(u -> QolModule.status(state, u) == Status.OWNED)
			.count();
		int total = pack.getUnlocks().size();
		frame.add(section("QoL unlocks"));
		frame.add(pad(new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE,
			total == 0 ? 0 : (double) owned / total)
			.labels("Unlocked", null, owned + "/" + total)));
		frame.add(strut(4));

		// the rows sit inside one notched frame, checklist-style
		StonePanel group = new StonePanel(theme);
		group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
		group.setAlignmentX(LEFT_ALIGNMENT);
		int corner = theme.cornerStamp.length;
		group.setBorder(new StoneBorder(theme, theme.background,
			new Insets(corner, corner, corner, corner)));
		int hidden = 0;
		for (QolPack.Unlock unlock : pack.getUnlocks())
		{
			if (!showObtained && QolModule.status(state, unlock) == Status.OWNED)
			{
				hidden++;
				continue;
			}
			group.add(unlockRow(unlock));
			if (unlock.getId().equals(expandedId))
			{
				group.add(expansion(unlock));
			}
		}
		cap(group);
		frame.add(pad(group));
		// obtained items hide by default — the small-font toggle (the
		// diaries Show-completed grammar)
		if (hidden > 0 || showObtained)
		{
			OsrsLabel toggle = new OsrsLabel(showObtained ? "Hide obtained"
				: "Show obtained (" + hidden + ")", OsrsSkin.FAINT, OsrsSkin.smallFont())
				.leftAligned();
			toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			toggle.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					showObtained = !showObtained;
					SwingUtilities.invokeLater(QolTab.this::rebuild);
				}
			});
			frame.add(pad(toggle));
		}
		frame.add(strut(4));
		revalidate();
		repaint();
	}

	/** The expanded card under a clicked row: what the unlock DOES (the
	 *  KB's benefit prose) and its requirement lines, met-coloured like a
	 *  Task's steps (prose lines stay display-only faint). */
	private JComponent expansion(QolPack.Unlock unlock)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setOpaque(false);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new EmptyBorder(1, 10, 3, 2));
		if (unlock.getBenefit() != null)
		{
			card.add(OsrsLabel.wrapped(unlock.getBenefit(), 190,
				OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		for (String req : unlock.getRequirements())
		{
			com.ironhub.requirements.Requirement parsed =
				com.ironhub.requirements.Requirements.parse(req);
			boolean manual = com.ironhub.requirements.Requirements.isManual(parsed);
			boolean met = !manual && parsed.isMet(state);
			OsrsLabel line = new OsrsLabel("· " + parsed.describe(),
				manual ? OsrsSkin.FAINT : met ? OsrsSkin.VALUE : OsrsSkin.MUTED,
				OsrsSkin.smallFont()).leftAligned().squeezable();
			line.setToolTipText(manual ? parsed.describe()
				: parsed.describe() + (met ? " — met" : " — not met"));
			card.add(line);
		}
		if (unlock.getBenefit() == null && unlock.getRequirements().isEmpty())
		{
			card.add(new OsrsLabel("No further detail known", OsrsSkin.FAINT,
				OsrsSkin.smallFont()).leftAligned());
		}
		cap(card);
		return card;
	}

	/** One unlock: name coloured by status, blocking requirement in the
	 *  tooltip, a compact wiki affordance on the right. */
	private JComponent unlockRow(QolPack.Unlock unlock)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, 2, 1, 2));

		// the old tab's status scale in skin colours:
		// green = owned, orange = doable now, faint = locked
		Status status = QolModule.status(state, unlock);
		Color color = status == Status.OWNED ? OsrsSkin.VALUE
			: status == Status.AVAILABLE ? OsrsSkin.TITLE : OsrsSkin.FAINT;
		// the hover leads with what the unlock DOES (Luke's ask)
		String tooltip = unlock.getName();
		if (unlock.getBenefit() != null)
		{
			tooltip = "<html><div style='width:220px'>" + unlock.getName()
				+ "<br>" + unlock.getBenefit();
		}
		if (status == Status.LOCKED)
		{
			String blocking = QolModule.blockingLine(state, unlock);
			if (blocking != null)
			{
				tooltip += (unlock.getBenefit() != null ? "<br>" : " — ")
					+ "needs " + blocking;
			}
		}
		if (unlock.getBenefit() != null)
		{
			tooltip += "</div></html>";
		}
		OsrsLabel name = new OsrsLabel(unlock.getName(), color, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(tooltip);
		row.setToolTipText(tooltip);
		// click the row to expand its detail (requirements like a Task)
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedId = unlock.getId().equals(expandedId) ? null : unlock.getId();
				SwingUtilities.invokeLater(QolTab.this::rebuild);
			}
		});
		row.add(name);
		row.add(Box.createHorizontalGlue());
		boolean isGoal = state.getGoalSeeds().containsKey("qol:" + unlock.getId());
		boolean planned = !isGoal && !unlock.getItemIds().isEmpty()
			&& planWantsItem.test(unlock.getItemIds().get(0));
		if (planned)
		{
			// already routed by ANOTHER goal (gear chart etc.) — say so
			// instead of offering a duplicate goal
			JLabel mark = new JLabel("·");
			OsrsSkin.crisp(mark);
			mark.setFont(OsrsSkin.font());
			mark.setForeground(OsrsSkin.VALUE);
			mark.setToolTipText(unlock.getName() + " — already in your current plan");
			row.add(mark);
		}
		else
		{
			row.add(goalGlyph(isGoal, isGoal ? unlock.getName() + " — tracked; click to untrack"
				: "Track unlocking " + unlock.getName() + " in Goals",
				() -> toggleGoal(unlock)));
		}
		row.add(Box.createHorizontalStrut(4));
		row.add(wikiGlyph(unlock.getName()));
		cap(row);
		return row;
	}

	/** The '+' action: unlock joins the Goal planner as a "qol:" goal;
	 *  achieved by owning it (fully graph-detectable, no proof flag). */
	private void toggleGoal(QolPack.Unlock unlock)
	{
		String goalId = "qol:" + unlock.getId();
		if (state.getGoalSeeds().containsKey(goalId))
		{
			state.removeGoalSeed(goalId);
		}
		else
		{
			state.addGoalSeed(com.ironhub.state.GoalSeeds.qol(unlock.getId(),
				unlock.getName(), unlock.getItemIds(), unlock.getRequirements()));
		}
	}

	/** The +/× goal affordance in skin colours — its own action, faint
	 *  until hovered (the diaries/GoalsTab glyph grammar). */
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

	/** A small "W" wiki affordance — faint until hovered (GoalsTab grammar). */
	private static JLabel wikiGlyph(String pageName)
	{
		JLabel glyph = new JLabel("W");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText("Open the wiki page");
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
					+ pageName.replace(' ', '_'));
			}
		});
		return glyph;
	}

	// ── layout helpers (the DailiesNewTab grammar) ────────────────────

	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 8, 3, 8));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
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
