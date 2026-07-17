package com.ironhub.modules.clues;

import com.ironhub.data.ClueItemsPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Clues tab: per-tier emote-clue readiness — doable now vs missing items
 * (first blocker on the needs: line). Tracked set grows with the pack.
 * Worn in the OSRS stonework skin (design/OSRS-SKIN.md).
 */
class CluesTab extends JPanel
{
	/** Wrap width for the free-standing STASH hint (FarmingTab's HINT_WIDTH). */
	private static final int HINT_WIDTH = UiTokens.PANEL_WIDTH - 20;

	private final AccountState state;
	private final ClueItemsPack pack;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final JPanel content = new JPanel();

	CluesTab(AccountState state, ClueItemsPack pack, OsrsTheme theme)
	{
		this.state = state;
		this.pack = pack;
		this.theme = theme;
		// frameless: the host provides the stone frame and names the module
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
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
		content.removeAll();

		Map<String, List<ClueItemsPack.Clue>> byTier = new LinkedHashMap<>();
		pack.getClues().forEach(c ->
			byTier.computeIfAbsent(c.getTier(), t -> new java.util.ArrayList<>()).add(c));

		byTier.forEach((tier, clues) ->
		{
			content.add(section(tier, ClueStashModule.doableCount(clues, state)
				+ "/" + clues.size() + " doable"));

			// the tier's clues share one notched frame (FarmingTab's run picker)
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			for (ClueItemsPack.Clue clue : clues)
			{
				group.add(clueRow(clue));
			}
			cap(group);
			content.add(pad(group));
		});

		content.add(strut(8));
		content.add(pad(hint("STASH tracking arrives when the client API exposes it.")));
		content.revalidate();
		content.repaint();
	}

	/** One clue: green = doable now, muted + faint needs: line = blocked. */
	private JComponent clueRow(ClueItemsPack.Clue clue)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, 2, 1, 2));

		boolean doable = ClueStashModule.doable(clue, state);
		Color color = doable ? OsrsSkin.VALUE : OsrsSkin.MUTED;
		OsrsLabel name = new OsrsLabel(clue.getClue(), color, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(clue.getClue());
		row.add(name);

		if (!doable)
		{
			String needs = "needs: " + ClueStashModule.blocking(clue, state);
			OsrsLabel blocker = new OsrsLabel(needs, OsrsSkin.FAINT, OsrsSkin.font())
				.leftAligned().squeezable();
			blocker.setToolTipText(needs);
			blocker.setBorder(new EmptyBorder(0, 8, 0, 0));
			row.add(blocker);
		}
		cap(row);
		return row;
	}

	// ── layout helpers (the DailiesNewTab / FarmingTab grammar) ───────

	/** Section header: tier name left, doable count right in ready-green. */
	private JComponent section(String text, String count)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(count, OsrsSkin.VALUE, OsrsSkin.font()));
		cap(row);
		return row;
	}

	private JComponent hint(String text)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(OsrsLabel.wrapped(text, HINT_WIDTH, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
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
