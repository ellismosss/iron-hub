package com.ironhub.modules.farming;

import com.ironhub.data.HerbPatchesPack;
import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * Farming tab content (frame 2e): full-width primary Start/End run button,
 * run-history stats line, herb patch rows with Path buttons. Patch
 * readiness states arrive with crop varbit mapping.
 */
class FarmingTab extends JPanel
{
	private final AccountState state;
	private final FarmingRunModule module;
	private final ShortestPathBridge pathBridge;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel runButton = new JLabel("", javax.swing.SwingConstants.CENTER);
	private final JLabel stats = new JLabel();
	private final JLabel readyCount = new JLabel();
	private final JPanel list = new JPanel();

	FarmingTab(AccountState state, FarmingRunModule module, ShortestPathBridge pathBridge)
	{
		this.state = state;
		this.module = module;
		this.pathBridge = pathBridge;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		// primary button: accent bg, dark bold text (1a §6)
		runButton.setOpaque(true);
		runButton.setBackground(UiTokens.ACCENT);
		runButton.setForeground(UiTokens.ACCENT_TEXT_ON);
		runButton.setBorder(new LineBorder(UiTokens.ACCENT));
		runButton.setFont(runButton.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		runButton.setAlignmentX(LEFT_ALIGNMENT);
		runButton.setPreferredSize(new Dimension(0, UiTokens.BUTTON_HEIGHT));
		runButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		runButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		runButton.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				if (module.running())
				{
					module.endRun(false); // abandoned runs are not recorded
				}
				else
				{
					module.startRun();
				}
				rebuild();
			}
		});
		add(runButton);
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		stats.setForeground(UiTokens.TEXT_FAINT);
		stats.setFont(stats.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		stats.setAlignmentX(LEFT_ALIGNMENT);
		add(stats);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		JPanel patchHeader = new JPanel();
		patchHeader.setLayout(new BoxLayout(patchHeader, BoxLayout.X_AXIS));
		patchHeader.setOpaque(false);
		patchHeader.setAlignmentX(LEFT_ALIGNMENT);
		patchHeader.add(new SectionLabel("Herb patches"));
		patchHeader.add(Box.createHorizontalGlue());
		readyCount.setForeground(UiTokens.STATUS_AVAILABLE);
		readyCount.setFont(readyCount.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		patchHeader.add(readyCount);
		add(patchHeader);
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
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

	void rebuild()
	{
		runButton.setText(module.running() ? "End run" : "Start herb run");
		stats.setText(FarmingRunModule.statsLine(state.getHerbRunsMs()));

		long now = System.currentTimeMillis();
		readyCount.setText(module.readyCount() + " of " + module.patches().size() + " ready");

		HerbPatchesPack.Patch next = module.nextPatch();
		list.removeAll();
		for (HerbPatchesPack.Patch patch : module.patches())
		{
			IconButton path = IconButton.path(() -> pathBridge.pathTo(patch.getLocation()));
			list.add(patchRow(patch, next, now, path));
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	private ListRow patchRow(HerbPatchesPack.Patch patch, HerbPatchesPack.Patch next,
		long now, IconButton path)
	{
		if (module.running())
		{
			if (module.isVisited(patch.getId()))
			{
				return ListRow.owned(patch.getName(), path);
			}
			if (next != null && next.getId().equals(patch.getId()))
			{
				return ListRow.available(patch.getName(), path);
			}
			return ListRow.locked(patch.getName(), path);
		}

		AccountState.HerbPatchSeen seen = state.herbPatchSeen(patch.getId());
		ListRow row;
		switch (FarmingRunModule.predict(seen, now))
		{
			case READY:
				row = ListRow.available(patch.getName(), path);
				row.setToolTipText(patch.getName() + " — " + seen.herb + " ready");
				break;
			case PREDICTED_READY:
				row = ListRow.available(patch.getName(), path);
				row.setToolTipText(patch.getName() + " — " + seen.herb + " predicted ready");
				break;
			case GROWING:
				row = ListRow.locked(patch.getName(), path);
				long minutes = Math.max(1, (FarmingRunModule.readyAtMs(seen) - now) / 60_000);
				row.setToolTipText(patch.getName() + " — " + seen.herb + " ready ~ in " + minutes + " min");
				break;
			case DISEASED:
				row = ListRow.warning(patch.getName(), "diseased", path);
				break;
			case DEAD:
				row = ListRow.warning(patch.getName(), "dead", path);
				break;
			case EMPTY:
				row = ListRow.locked(patch.getName(), path);
				row.setToolTipText(patch.getName() + " — empty");
				break;
			default:
				row = ListRow.locked(patch.getName(), path);
				row.setToolTipText(patch.getName() + " — not seen yet");
		}
		return row;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
