package com.ironhub.modules.farming;

import com.ironhub.data.HerbPatchesPack;
import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.modules.farming.rl.PatchPrediction;
import com.ironhub.modules.farming.rl.Tab;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.plugins.timetracking.SummaryState;

/**
 * Farming tab content (frame 2e): full-width primary Start/End run button,
 * run-history stats line, a Time Tracking-style patch overview (every
 * category with data, bird houses, the farming contract — each with its
 * ready state or ETA), and the herb-run patch rows with Path buttons.
 * All predictions come from the core Time Tracking plugin's data via
 * FarmTrackingService.
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
	private final JPanel overview = new JPanel();
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

		add(new SectionLabel("Patch overview"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		overview.setLayout(new BoxLayout(overview, BoxLayout.Y_AXIS));
		overview.setOpaque(false);
		overview.setAlignmentX(LEFT_ALIGNMENT);
		add(overview);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		JPanel patchHeader = new JPanel();
		patchHeader.setLayout(new BoxLayout(patchHeader, BoxLayout.X_AXIS));
		patchHeader.setOpaque(false);
		patchHeader.setAlignmentX(LEFT_ALIGNMENT);
		patchHeader.add(new SectionLabel("Herb patches"));
		patchHeader.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
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
		readyCount.setText(module.readyCount() + " of " + module.patches().size() + " ready");

		rebuildOverview();

		HerbPatchesPack.Patch next = module.nextPatch();
		list.removeAll();
		for (HerbPatchesPack.Patch patch : module.patches())
		{
			IconButton path = IconButton.path(() -> pathBridge.pathTo(patch.getLocation()));
			list.add(patchRow(patch, next, path));
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	// ── patch overview (all categories + bird houses + contract) ──────

	private void rebuildOverview()
	{
		overview.removeAll();
		FarmTrackingService tracking = module.tracking();

		if (tracking == null || tracking.coreTrackingDisabled())
		{
			overview.add(hint("Enable the core Time Tracking plugin — Iron Hub "
				+ "reads its patch data.", UiTokens.STATUS_AVAILABLE));
		}
		else if (!tracking.hasAnyData())
		{
			overview.add(hint("No tracking data yet. The Time Tracking plugin "
				+ "records each patch as you visit it.", UiTokens.TEXT_FAINT));
		}

		if (tracking == null)
		{
			overview.revalidate();
			overview.repaint();
			return;
		}

		long now = Instant.now().getEpochSecond();
		for (Tab category : FarmTrackingService.CATEGORIES)
		{
			SummaryState summary = tracking.summary(category);
			if (summary == SummaryState.UNKNOWN)
			{
				continue; // never seen — don't render 20 empty rows
			}
			overview.add(overviewRow(category.getName(),
				statusText(summary, tracking.harvestable(category),
					tracking.completionTime(category), now),
				statusColor(summary, tracking.harvestable(category))));
			overview.add(Box.createVerticalStrut(2));
		}

		SummaryState birds = tracking.birdHouseSummary();
		if (birds != SummaryState.UNKNOWN)
		{
			boolean ready = birds == SummaryState.COMPLETED || birds == SummaryState.EMPTY;
			overview.add(overviewRow("Bird houses",
				birds == SummaryState.EMPTY ? "Empty"
					: statusText(birds, ready, tracking.birdHouseCompletionTime(), now),
				ready ? UiTokens.STATUS_AVAILABLE : UiTokens.TEXT_MUTED));
			overview.add(Box.createVerticalStrut(2));
		}

		if (tracking.contract().hasContract())
		{
			boolean ready = tracking.contractReady();
			overview.add(overviewRow("Contract · " + tracking.contract().getContractName(),
				ready ? "Ready" : "Growing",
				ready ? UiTokens.STATUS_AVAILABLE : UiTokens.TEXT_MUTED));
		}

		overview.revalidate();
		overview.repaint();
	}

	/** "Ready" / ETA ("2h 10m") / "Empty" for one category. */
	static String statusText(SummaryState summary, boolean harvestable, long completionTime, long now)
	{
		if (harvestable || summary == SummaryState.COMPLETED)
		{
			return "Ready";
		}
		if (summary == SummaryState.EMPTY)
		{
			return "Empty";
		}
		if (summary == SummaryState.IN_PROGRESS && completionTime > now)
		{
			return Format.hours((completionTime - now) / 3600.0);
		}
		return "Ready";
	}

	private static Color statusColor(SummaryState summary, boolean harvestable)
	{
		if (harvestable || summary == SummaryState.COMPLETED)
		{
			return UiTokens.STATUS_AVAILABLE;
		}
		if (summary == SummaryState.EMPTY)
		{
			return UiTokens.TEXT_FAINT;
		}
		return UiTokens.TEXT_MUTED;
	}

	private JPanel overviewRow(String name, String value, Color valueColor)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT_DENSE));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT_DENSE));
		JLabel label = new JLabel(name);
		label.setForeground(UiTokens.TEXT_BODY);
		label.setFont(label.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		label.setMinimumSize(new Dimension(0, 0));
		row.add(label);
		row.add(Box.createHorizontalGlue());
		JLabel status = new JLabel(value);
		status.setForeground(valueColor);
		status.setFont(status.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		row.add(status);
		return row;
	}

	private JLabel hint(String text, Color color)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setForeground(color);
		label.setFont(label.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(new EmptyBorder(0, 0, UiTokens.PAD_TIGHT, 0));
		return label;
	}

	// ── herb run rows ─────────────────────────────────────────────────

	private ListRow patchRow(HerbPatchesPack.Patch patch, HerbPatchesPack.Patch next, IconButton path)
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

		PatchPrediction prediction = module.prediction(patch);
		String produce = prediction == null ? "" : prediction.getProduce().getName();
		ListRow row;
		switch (FarmingRunModule.viewOf(prediction, Instant.now().getEpochSecond()))
		{
			case READY:
				row = ListRow.available(patch.getName(), path);
				row.setToolTipText(patch.getName() + " — " + produce + " ready");
				break;
			case PREDICTED_READY:
				row = ListRow.available(patch.getName(), path);
				row.setToolTipText(patch.getName() + " — " + produce + " predicted ready");
				break;
			case GROWING:
				row = ListRow.locked(patch.getName(), path);
				long seconds = Math.max(60, prediction.getDoneEstimate() - Instant.now().getEpochSecond());
				row.setToolTipText(patch.getName() + " — " + produce + " ready in ~"
					+ Format.hours(seconds / 3600.0));
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
