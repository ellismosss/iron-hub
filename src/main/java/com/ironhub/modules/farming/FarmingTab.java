package com.ironhub.modules.farming;

import com.ironhub.data.FarmRunsPack;
import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.modules.farming.rl.Tab;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.plugins.timetracking.SummaryState;

/**
 * Farming tab content (frame 2e): a Time Tracking-style patch overview
 * (every category with data, bird houses, the farming contract), then the
 * run planner — built-in template runs, saved custom runs, a compact run
 * builder, and the live stop checklist while a run is active. Teleports
 * are auto-picked from what the player owns; each stop row's tooltip
 * carries the teleport, missing items and live patch states.
 */
class FarmingTab extends JPanel
{
	private final AccountState state;
	private final FarmingRunModule module;
	private final ShortestPathBridge pathBridge;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel stats = new JLabel();
	private final JPanel overview = new JPanel();
	private final JPanel runs = new JPanel();

	// run builder state
	private boolean builderOpen;
	private final JTextField builderName = new SearchField("Run name…");
	private final Set<String> builderSelection = new LinkedHashSet<>();

	FarmingTab(AccountState state, FarmingRunModule module, ShortestPathBridge pathBridge)
	{
		this.state = state;
		this.module = module;
		this.pathBridge = pathBridge;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(new SectionLabel("Patch overview"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		overview.setLayout(new BoxLayout(overview, BoxLayout.Y_AXIS));
		overview.setOpaque(false);
		overview.setAlignmentX(LEFT_ALIGNMENT);
		add(overview);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		add(new SectionLabel("Runs"));
		add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		stats.setForeground(UiTokens.TEXT_FAINT);
		stats.setFont(stats.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		stats.setAlignmentX(LEFT_ALIGNMENT);
		add(stats);
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		runs.setLayout(new BoxLayout(runs, BoxLayout.Y_AXIS));
		runs.setOpaque(false);
		runs.setAlignmentX(LEFT_ALIGNMENT);
		add(runs);
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
		stats.setText(FarmingRunModule.statsLine(state.getHerbRunsMs()));
		rebuildOverview();
		rebuildRuns();
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

	// ── runs (templates, saved runs, builder, live checklist) ─────────

	private void rebuildRuns()
	{
		runs.removeAll();
		if (module.running())
		{
			buildActiveRun();
		}
		else
		{
			buildRunPicker();
		}
		runs.revalidate();
		runs.repaint();
	}

	private void buildActiveRun()
	{
		JLabel endButton = primaryButton("End run (" + module.runName() + ")");
		endButton.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				module.endRun(false); // abandoned runs are not recorded
				rebuild();
			}
		});
		runs.add(endButton);
		runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		// Remember this run's gear + inventory so the bank shows it (Inventory
		// Setups style) — the whole loadout, in the right slots, to re-gather.
		boolean hasSetup = state.getFarmRunSetup(module.runName()) != null;
		JLabel saveSetup = secondaryButton(
			hasSetup ? "Update bank setup" : "Save gear + inventory for the bank");
		saveSetup.setToolTipText("Snapshot your worn gear and inventory now; it "
			+ "shows over the bank while this run is active so you can re-stock fast");
		saveSetup.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				state.saveFarmRunSetup(module.runName(), state.captureSetup());
				rebuild();
			}
		});
		runs.add(saveSetup);
		if (hasSetup)
		{
			JLabel clear = new JLabel("Clear setup");
			clear.setForeground(UiTokens.TEXT_MUTED);
			clear.setFont(clear.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			clear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			clear.setAlignmentX(LEFT_ALIGNMENT);
			clear.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
			clear.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					state.saveFarmRunSetup(module.runName(), null);
					rebuild();
				}
			});
			runs.add(clear);
		}
		runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		FarmingRunModule.Stop next = module.nextStop();
		for (FarmingRunModule.Stop stop : module.stops())
		{
			IconButton path = IconButton.path(() -> pathBridge.pathTo(stop.location.worldPoint()));
			ListRow row;
			if (module.isVisited(stop.location.id))
			{
				row = ListRow.owned(stop.location.name, path);
			}
			else if (next != null && stop == next)
			{
				row = ListRow.available(stop.location.name, path);
			}
			else
			{
				row = ListRow.locked(stop.location.name, path);
			}
			row.setToolTipText(stopTooltip(stop));
			runs.add(row);
			runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
	}

	/** "Explorers ring · Missing: Law rune · Herb ready" for a stop row. */
	private String stopTooltip(FarmingRunModule.Stop stop)
	{
		StringJoiner tooltip = new StringJoiner(" · ");
		tooltip.add(FarmingRunOverlay.teleportLabel(stop.teleport));
		List<FarmRunsPack.Item> missing = module.missingItems(stop);
		if (!missing.isEmpty())
		{
			tooltip.add("missing " + missing.size()
				+ (missing.size() == 1 ? " item" : " items"));
		}
		String patches = FarmingRunOverlay.patchLine(module.patchesAt(stop.location));
		if (!patches.isEmpty())
		{
			tooltip.add(patches);
		}
		return tooltip.toString();
	}

	private void buildRunPicker()
	{
		for (String template : FarmingRunModule.TEMPLATES.keySet())
		{
			String category = FarmingRunModule.TEMPLATES.get(template);
			int count = module.pack().category(category).size();
			runs.add(runRow(template, count + " stops", () -> module.startTemplate(template), null));
			runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}

		for (String name : new TreeMap<>(state.getFarmRuns()).keySet())
		{
			int count = state.getFarmRuns().get(name).locationIds.size();
			runs.add(runRow(name, count + " stops", () -> module.startCustom(name),
				() -> state.deleteFarmRun(name)));
			runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}

		JLabel newRun = new JLabel(builderOpen ? "Cancel new run" : "New custom run…");
		newRun.setForeground(UiTokens.ACCENT);
		newRun.setFont(newRun.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		newRun.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		newRun.setAlignmentX(LEFT_ALIGNMENT);
		newRun.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				builderOpen = !builderOpen;
				if (!builderOpen)
				{
					builderSelection.clear();
					builderName.setText("");
				}
				rebuildRuns();
			}
		});
		runs.add(newRun);
		if (builderOpen)
		{
			runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			buildRunBuilder();
		}
	}

	/** A run row: name + stop count, click to start, optional delete. */
	private JPanel runRow(String name, String detail, Runnable start, Runnable delete)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Start " + name + " — teleports are picked from what you own");

		JLabel label = new JLabel(name);
		label.setForeground(UiTokens.TEXT_PRIMARY);
		label.setFont(label.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		label.setMinimumSize(new Dimension(0, 0));
		row.add(label);
		row.add(Box.createHorizontalGlue());
		JLabel count = new JLabel(detail);
		count.setForeground(UiTokens.TEXT_MUTED);
		count.setFont(count.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		row.add(count);
		if (delete != null)
		{
			row.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			row.add(new IconButton("×", "Delete this run", () ->
			{
				delete.run();
				rebuild();
			}));
		}
		row.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					start.run();
					rebuild();
				}
			}
		});
		return row;
	}

	/** Compact builder: name, one checkbox per pack location (route order
	 *  within each category), Save. */
	private void buildRunBuilder()
	{
		builderName.setAlignmentX(LEFT_ALIGNMENT);
		runs.add(builderName);
		runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		String lastCategory = "";
		for (FarmRunsPack.Location location : module.pack().locations)
		{
			if (!location.category.equals(lastCategory))
			{
				lastCategory = location.category;
				JLabel header = new JLabel(location.category.toUpperCase(Locale.ROOT));
				header.setForeground(UiTokens.TEXT_MUTED);
				header.setFont(SectionLabel.letterSpaced(
					header.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
					UiTokens.LETTER_SPACING_LABEL));
				header.setAlignmentX(LEFT_ALIGNMENT);
				header.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 2, 0));
				runs.add(header);
			}
			JCheckBox box = new JCheckBox(location.name, builderSelection.contains(location.id));
			box.setOpaque(false);
			box.setForeground(UiTokens.TEXT_BODY);
			box.setFont(box.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
			box.setAlignmentX(LEFT_ALIGNMENT);
			box.addActionListener(e ->
			{
				if (box.isSelected())
				{
					builderSelection.add(location.id);
				}
				else
				{
					builderSelection.remove(location.id);
				}
			});
			runs.add(box);
		}

		runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		JLabel save = primaryButton("Save run");
		save.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				String name = builderName.getText().trim();
				if (name.isEmpty() || builderSelection.isEmpty()
					|| FarmingRunModule.TEMPLATES.containsKey(name))
				{
					return;
				}
				// keep the pack's route order, not click order
				List<String> ordered = new ArrayList<>();
				for (FarmRunsPack.Location location : module.pack().locations)
				{
					if (builderSelection.contains(location.id))
					{
						ordered.add(location.id);
					}
				}
				state.saveFarmRun(name, ordered);
				builderOpen = false;
				builderSelection.clear();
				builderName.setText("");
				rebuild();
			}
		});
		runs.add(save);
	}

	private JLabel primaryButton(String text)
	{
		JLabel button = new JLabel(text, javax.swing.SwingConstants.CENTER);
		button.setOpaque(true);
		button.setBackground(UiTokens.ACCENT);
		button.setForeground(UiTokens.ACCENT_TEXT_ON);
		button.setBorder(new LineBorder(UiTokens.ACCENT));
		button.setFont(button.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setPreferredSize(new Dimension(0, UiTokens.BUTTON_HEIGHT));
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	private JLabel secondaryButton(String text)
	{
		JLabel button = new JLabel(text, javax.swing.SwingConstants.CENTER);
		button.setOpaque(true);
		button.setBackground(UiTokens.ICON_BUTTON_BG);
		button.setForeground(UiTokens.TEXT_BODY);
		button.setBorder(new LineBorder(UiTokens.BORDER_BUTTON));
		button.setFont(button.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setPreferredSize(new Dimension(0, UiTokens.BUTTON_HEIGHT));
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
