package com.ironhub.modules.clues;

import com.ironhub.data.ClueItemsPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Clues tab: per-tier emote-clue readiness — doable now vs missing items
 * (first blocker on the needs: line). Tracked set grows with the pack.
 */
class CluesTab extends JPanel
{
	private final AccountState state;
	private final ClueItemsPack pack;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private final JPanel content = new JPanel();

	CluesTab(AccountState state, ClueItemsPack pack)
	{
		this.state = state;
		this.pack = pack;
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
			JPanel header = new JPanel();
			header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
			header.setOpaque(false);
			header.setAlignmentX(LEFT_ALIGNMENT);
			header.add(new SectionLabel(tier));
			header.add(Box.createHorizontalGlue());
			JLabel count = new JLabel(ClueStashModule.doableCount(clues, state)
				+ "/" + clues.size() + " doable");
			count.setForeground(UiTokens.STATUS_AVAILABLE);
			count.setFont(count.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			header.add(count);
			content.add(header);
			content.add(Box.createVerticalStrut(UiTokens.ROW_GAP));

			for (ClueItemsPack.Clue clue : clues)
			{
				ListRow row = ClueStashModule.doable(clue, state)
					? ListRow.available(clue.getClue())
					: ListRow.locked(clue.getClue(), ClueStashModule.blocking(clue, state));
				content.add(row);
				content.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
			content.add(Box.createVerticalStrut(UiTokens.PAD));
		});

		JLabel stash = new JLabel("STASH tracking arrives when the client API exposes it.");
		stash.setForeground(UiTokens.TEXT_FAINT);
		stash.setFont(stash.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		stash.setAlignmentX(LEFT_ALIGNMENT);
		content.add(stash);
		content.revalidate();
		content.repaint();
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
