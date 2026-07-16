package com.ironhub.modules.farming;

import com.ironhub.data.FarmRunsPack;
import com.ironhub.modules.farming.rl.Tab;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.SpriteCache;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
	private final net.runelite.client.game.ItemManager itemManager; // null in headless tests
	private final SpriteCache sprites;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	/** The one category expanded under the overview tile strip (null = none). */
	private Tab expandedOverview;

	private final JPanel topBar = new JPanel();
	private final JLabel stats = new JLabel();
	private final JPanel xpStats = new JPanel();
	private final JPanel overview = new JPanel();
	private final JPanel runs = new JPanel();
	private final JLabel teleportHeader = new JLabel("Teleport preferences");
	private final JPanel teleportPanel = new JPanel();
	private boolean teleportsOpen;
	// "Configure gear & inventory" is the ask, but the letter-spaced header
	// clips past 225 px — the tooltip carries the longer intent
	private final JLabel setupHeader = new JLabel("Gear & inventory");
	private final JPanel setupPanel = new JPanel();
	private boolean setupsOpen;

	// run builder state
	private boolean builderOpen;
	private final JTextField builderName = new SearchField("Run name…");
	private final Set<String> builderSelection = new LinkedHashSet<>();

	FarmingTab(AccountState state, FarmingRunModule module, net.runelite.client.game.ItemManager itemManager)
	{
		this.state = state;
		this.module = module;
		this.itemManager = itemManager;
		this.sprites = new SpriteCache(itemManager, this::rebuild);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		// The active-run cockpit lives at the very top so End run is the
		// first thing you see during a run, not buried under the overview.
		topBar.setLayout(new BoxLayout(topBar, BoxLayout.Y_AXIS));
		topBar.setOpaque(false);
		topBar.setAlignmentX(LEFT_ALIGNMENT);
		add(topBar);

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
		xpStats.setLayout(new BoxLayout(xpStats, BoxLayout.Y_AXIS));
		xpStats.setOpaque(false);
		xpStats.setAlignmentX(LEFT_ALIGNMENT);
		add(xpStats);
		runs.setLayout(new BoxLayout(runs, BoxLayout.Y_AXIS));
		runs.setOpaque(false);
		runs.setAlignmentX(LEFT_ALIGNMENT);
		add(runs);

		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		add(buildTeleportSection());
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		add(buildSetupSection());
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	/** Collapsible "Teleport preferences": choose the teleport used to reach
	 *  each patch (Easy Farming-style), overriding the owned-first auto-pick. */
	private JPanel buildTeleportSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		teleportHeader.setForeground(UiTokens.TEXT_MUTED);
		teleportHeader.setFont(SectionLabel.letterSpaced(
			teleportHeader.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
			UiTokens.LETTER_SPACING_LABEL));
		teleportHeader.setIcon(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		teleportHeader.setIconTextGap(UiTokens.ROW_GAP);
		teleportHeader.setAlignmentX(LEFT_ALIGNMENT);
		teleportHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		teleportHeader.setToolTipText("Pick the teleport used to reach each patch");
		teleportHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				teleportsOpen = !teleportsOpen;
				teleportHeader.setIcon(new PaintedIcon(teleportsOpen
					? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
				teleportPanel.setVisible(teleportsOpen);
				rebuildTeleports();
			}
		});
		section.add(teleportHeader);

		teleportPanel.setLayout(new BoxLayout(teleportPanel, BoxLayout.Y_AXIS));
		teleportPanel.setOpaque(false);
		teleportPanel.setAlignmentX(LEFT_ALIGNMENT);
		teleportPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		teleportPanel.setVisible(false);
		section.add(teleportPanel);
		return section;
	}

	private void rebuildTeleports()
	{
		teleportPanel.removeAll();
		if (teleportsOpen)
		{
			// one row per physical location (grouped by name), so co-located
			// patches — allotment/flower/herb at the same place — share a single
			// teleport preference instead of a box each.
			java.util.Map<String, List<FarmRunsPack.Location>> byPlace = new java.util.LinkedHashMap<>();
			for (FarmRunsPack.Location location : module.pack().locations)
			{
				if (!module.isUnlocked(location))
				{
					continue; // no point choosing a teleport to a patch you can't use
				}
				byPlace.computeIfAbsent(location.name, k -> new ArrayList<>()).add(location);
			}
			for (List<FarmRunsPack.Location> group : byPlace.values())
			{
				teleportPanel.add(teleportRow(group));
				teleportPanel.add(Box.createVerticalStrut(2));
			}
		}
		teleportPanel.revalidate();
		teleportPanel.repaint();
	}

	/** Place name + a combo of its teleports ("Auto" = owned-first pick); the
	 *  chosen teleport applies to every co-located patch that offers it. */
	private JPanel teleportRow(List<FarmRunsPack.Location> group)
	{
		FarmRunsPack.Location rep = group.get(0);
		JPanel row = new JPanel(new BorderLayout(UiTokens.ROW_GAP, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));

		JLabel name = new JLabel(rep.name);
		name.setForeground(UiTokens.TEXT_BODY);
		name.setFont(name.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		name.setPreferredSize(new Dimension(82, UiTokens.BUTTON_HEIGHT));
		name.setToolTipText(rep.name);
		row.add(name, BorderLayout.WEST);

		JComboBox<String> combo = new JComboBox<>();
		combo.setFont(combo.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		combo.addItem("Auto");
		for (FarmRunsPack.Teleport teleport : rep.teleports)
		{
			combo.addItem(FarmingRunOverlay.teleportLabel(teleport));
		}
		combo.setSelectedIndex(prefIndex(rep, state.getFarmTeleportPref(rep.id)));
		combo.addActionListener(e ->
		{
			int i = combo.getSelectedIndex();
			String teleportId = i <= 0 ? null : rep.teleports.get(i - 1).id;
			for (FarmRunsPack.Location loc : group)
			{
				// only apply to co-located patches that actually offer this
				// teleport; others fall back to their own auto-pick
				if (teleportId == null || hasTeleport(loc, teleportId))
				{
					state.setFarmTeleportPref(loc.id, teleportId);
				}
			}
			module.refreshStopTeleports(); // an active run's overlay follows immediately
		});
		row.add(combo, BorderLayout.CENTER);
		return row;
	}

	private static boolean hasTeleport(FarmRunsPack.Location location, String teleportId)
	{
		for (FarmRunsPack.Teleport teleport : location.teleports)
		{
			if (teleport.id.equals(teleportId))
			{
				return true;
			}
		}
		return false;
	}

	private static int prefIndex(FarmRunsPack.Location location, String pref)
	{
		if (pref != null)
		{
			for (int i = 0; i < location.teleports.size(); i++)
			{
				if (location.teleports.get(i).id.equals(pref))
				{
					return i + 1; // +1 for the leading "Auto"
				}
			}
		}
		return 0;
	}

	/**
	 * Collapsible "Configure gear & inventory": one button per run type
	 * (Trees / Herbs / Birdhouses / Others) that snapshots the player's
	 * current gear + inventory as that type's bank setup. During a run of
	 * that type the bank lays the setup out (a run's own saved setup, made
	 * from the active-run view, still wins).
	 */
	private JPanel buildSetupSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		setupHeader.setForeground(UiTokens.TEXT_MUTED);
		setupHeader.setFont(SectionLabel.letterSpaced(
			setupHeader.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_LABEL),
			UiTokens.LETTER_SPACING_LABEL));
		setupHeader.setIcon(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		setupHeader.setIconTextGap(UiTokens.ROW_GAP);
		setupHeader.setAlignmentX(LEFT_ALIGNMENT);
		setupHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setupHeader.setToolTipText("Save your current gear + inventory as the bank "
			+ "setup for each type of run");
		setupHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				setupsOpen = !setupsOpen;
				setupHeader.setIcon(new PaintedIcon(setupsOpen
					? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
				setupPanel.setVisible(setupsOpen);
				rebuildSetups();
			}
		});
		section.add(setupHeader);

		setupPanel.setLayout(new BoxLayout(setupPanel, BoxLayout.Y_AXIS));
		setupPanel.setOpaque(false);
		setupPanel.setAlignmentX(LEFT_ALIGNMENT);
		setupPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		setupPanel.setVisible(false);
		section.add(setupPanel);
		return section;
	}

	private void rebuildSetups()
	{
		setupPanel.removeAll();
		if (setupsOpen)
		{
			// the width pin makes the html label report its true wrapped height
			// (BoxLayout otherwise sizes it for one line and clips the rest)
			setupPanel.add(hint("<div style='width:180px'>Wear and carry the loadout "
				+ "you restock with, then click its run type. The bank shows it "
				+ "during those runs.</div>", UiTokens.TEXT_FAINT));
			JPanel grid = new JPanel(new java.awt.GridLayout(0, 2, UiTokens.PAD_TIGHT, UiTokens.PAD_TIGHT));
			grid.setOpaque(false);
			grid.setAlignmentX(LEFT_ALIGNMENT);
			int rows = (FarmingRunModule.SETUP_BUCKETS.size() + 1) / 2;
			grid.setMaximumSize(new Dimension(Integer.MAX_VALUE,
				rows * (UiTokens.BUTTON_HEIGHT + UiTokens.PAD_TIGHT)));
			for (String bucket : FarmingRunModule.SETUP_BUCKETS)
			{
				grid.add(setupButton(bucket));
			}
			setupPanel.add(grid);
		}
		setupPanel.revalidate();
		setupPanel.repaint();
	}

	/** Bucket just saved — its button reads "Saved" for a moment, because a
	 *  click on an already-green button otherwise looks like nothing happened. */
	private String justSavedBucket;

	/** One run type's capture button — green once a setup is saved, with the
	 *  saved item count in the tooltip so an overwrite is verifiable. */
	private JLabel setupButton(String bucket)
	{
		com.ironhub.state.PersistedState.SavedSetup existing =
			state.getFarmRunSetup(FarmingRunModule.bucketKey(bucket));
		boolean flash = bucket.equals(justSavedBucket);
		JLabel button = secondaryButton(flash ? "Saved" : bucket);
		if (existing != null)
		{
			button.setForeground(net.runelite.client.ui.ColorScheme.PROGRESS_COMPLETE_COLOR);
		}
		button.setToolTipText(existing != null
			? bucket + " setup saved · " + setupItemCount(existing)
				+ " items — click to replace it with your current gear + inventory"
			: "Save your current gear + inventory as the " + bucket + " setup");
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				state.saveFarmRunSetup(FarmingRunModule.bucketKey(bucket), state.captureSetup());
				justSavedBucket = bucket;
				javax.swing.Timer restore = new javax.swing.Timer(1500, done ->
				{
					justSavedBucket = null;
					rebuildSetups();
				});
				restore.setRepeats(false);
				restore.start();
			}
		});
		return button;
	}

	/** Worn pieces + occupied inventory slots in a setup — the number that
	 *  proves a fresh capture actually replaced the old one. */
	static int setupItemCount(com.ironhub.state.PersistedState.SavedSetup setup)
	{
		int count = 0;
		if (setup.equipment != null)
		{
			for (Integer id : setup.equipment.values())
			{
				if (id != null && id > 0)
				{
					count++;
				}
			}
		}
		if (setup.inventory != null)
		{
			for (int id : setup.inventory)
			{
				if (id > 0)
				{
					count++;
				}
			}
		}
		return count;
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seam: expand a category's patch list in the overview. */
	void expandOverview(Tab category)
	{
		expandedOverview = category;
		rebuildOverview();
	}

	/** Test seam: open the Gear & inventory section (render checks). */
	void expandSetups()
	{
		setupsOpen = true;
		setupHeader.setIcon(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		setupPanel.setVisible(true);
		rebuildSetups();
	}

	void rebuild()
	{
		stats.setText(FarmingRunModule.statsLine(state.getHerbRunsMs()));
		rebuildTopBar();
		rebuildOverview();
		rebuildRuns();
		rebuildTeleports();
		rebuildSetups();
		rebuildXpStats();
	}

	/**
	 * "Tree runs · 12.4k xp · 3 to 76" over the persisted completed-run log:
	 * average Farming xp your tree stops earn per run and how many such runs
	 * the next Farming level costs; likewise the potential Herblore xp your
	 * herb runs pick (cleaning + standard potions) toward the next Herblore
	 * level. A next level that unlocks something says so. Silent without
	 * history — never an invented rate.
	 */
	private void rebuildXpStats()
	{
		xpStats.removeAll();
		addSkillStat("Tree runs", module.avgTreeRunXp(), "xp/run",
			net.runelite.api.Skill.FARMING, "Farming",
			"Average Farming xp your tree stops earn per completed run");
		addSkillStat("Herb runs", module.avgHerbPotentialXp(), "pot. xp/run",
			net.runelite.api.Skill.HERBLORE, "Herblore",
			"Average potential Herblore xp per completed run — cleaning each "
				+ "picked herb and making its standard potion");
		xpStats.revalidate();
		xpStats.repaint();
	}

	private void addSkillStat(String label, double avg, String unit,
		net.runelite.api.Skill apiSkill, String skillName, String tooltip)
	{
		if (Double.isNaN(avg))
		{
			return;
		}
		int xp = state.getXp(apiSkill);
		int level = net.runelite.api.Experience.getLevelForXp(xp);
		int runs = FarmingRunModule.runsToNextLevel(avg, xp);
		JPanel row = overviewRow(label, compactXp(avg) + " " + unit, UiTokens.TEXT_BODY);
		row.setToolTipText(tooltip);
		xpStats.add(row);
		xpStats.add(Box.createVerticalStrut(2));

		// "Farming 76 in ~11 runs: Grow attas plants …" — the countdown and
		// what the level is worth, in one wrapped line under the rate
		if (runs > 0)
		{
			List<String> unlocks = module.nextLevelUnlocks(skillName, apiSkill);
			String text = skillName + " " + (level + 1) + " in ~" + runs
				+ (runs == 1 ? " run" : " runs");
			if (!unlocks.isEmpty())
			{
				text += ": " + unlocks.get(0) + (unlocks.size() > 1 ? " …" : "");
			}
			JLabel line = hint("<div style='width:180px'>" + text + "</div>", UiTokens.TEXT_FAINT);
			if (!unlocks.isEmpty())
			{
				line.setToolTipText("<html>" + String.join("<br>", unlocks) + "</html>");
			}
			xpStats.add(line);
		}
	}

	/** "9,850" below ten thousand, "12.4k" above — the row must fit 225px. */
	static String compactXp(double xp)
	{
		return xp < 10_000 ? String.format(Locale.ROOT, "%,d", Math.round(xp))
			: String.format(Locale.ROOT, "%.1fk", xp / 1000);
	}

	/** During a run: a prominent End run button + live status, pinned to the
	 *  top of the tab. Empty otherwise. */
	private void rebuildTopBar()
	{
		topBar.removeAll();
		if (module.running())
		{
			JLabel end = primaryButton("End run");
			end.setToolTipText("Stop " + module.runName() + " now (an abandoned run isn't logged)");
			end.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					module.endRun(false);
					rebuild();
				}
			});
			topBar.add(end);
			topBar.add(Box.createVerticalStrut(2));
			JLabel status = new JLabel(module.runName() + " · "
				+ module.visitedCount() + "/" + module.stops().size() + " stops");
			status.setForeground(UiTokens.TEXT_MUTED);
			status.setFont(status.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			status.setAlignmentX(LEFT_ALIGNMENT);
			topBar.add(status);
			topBar.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}
		topBar.revalidate();
		topBar.repaint();
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
		// Time Tracking's layout: a strip of clickable category icon tiles;
		// clicking a tile toggles that category's patch list (nothing expanded
		// = just the strip, minimal). Only categories with data get a tile.
		java.util.Map<Tab, List<FarmingRunModule.OverviewPatch>> byCategory = module.overviewByCategory();
		if (expandedOverview != null && !byCategory.containsKey(expandedOverview))
		{
			expandedOverview = null; // its data went away
		}
		if (!byCategory.isEmpty())
		{
			overview.add(overviewTileStrip(byCategory, now));
			overview.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
			for (java.util.Map.Entry<Tab, List<FarmingRunModule.OverviewPatch>> entry : byCategory.entrySet())
			{
				if (entry.getKey() != expandedOverview)
				{
					continue;
				}
				// merged tiles (Tree = tree/calquat/celastrus, Special = …) sub-label
				boolean subLabels = entry.getValue().stream()
					.map(p -> p.sourceTab).distinct().count() > 1;
				Tab lastTab = null;
				for (FarmingRunModule.OverviewPatch patch : entry.getValue())
				{
					if (subLabels && patch.sourceTab != lastTab)
					{
						lastTab = patch.sourceTab;
						overview.add(overviewSubLabel(patch.sourceTab.getName()));
					}
					overview.add(overviewPatchPanel(patch, now));
					overview.add(Box.createVerticalStrut(2));
				}
				overview.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			}
		}

		// Bird houses and the farming contract live in the Runs list now — they
		// are things you go and do, not patches to read.
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

	/** A run's sprite, sized to sit inside a row. Blank (but still spaced) when
	 *  we have no icon for it, so the names stay aligned. */
	private JComponent runIcon(String name)
	{
		return spriteLabel(module.runIcon(name));
	}

	private JLabel spriteLabel(int itemId)
	{
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(RUN_ICON, RUN_ICON));
		icon.setMinimumSize(new Dimension(RUN_ICON, RUN_ICON));
		icon.setMaximumSize(new Dimension(RUN_ICON, RUN_ICON));
		java.awt.Image sprite = sprites.get(itemId, RUN_ICON);
		if (sprite != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(sprite));
		}
		return icon;
	}

	private static final int RUN_ICON = 18;
	/** A checkbox at its natural width — pinning it to a hardcoded 16 px
	 *  squashed the check icon (the LAF's box plus insets is wider). */
	private static final int CHECK_WIDTH = new JCheckBox().getPreferredSize().width;
	private static final int ARROWS_WIDTH = 11;

	/** Grid of clickable category icon tiles — the Time Tracking tab strip. */
	private JComponent overviewTileStrip(java.util.Map<Tab, List<FarmingRunModule.OverviewPatch>> byCategory, long now)
	{
		JPanel strip = new JPanel(new java.awt.GridLayout(0, 5, 4, 4));
		strip.setOpaque(false);
		strip.setAlignmentX(LEFT_ALIGNMENT);
		int rows = (byCategory.size() + 4) / 5;
		strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * 34));
		for (java.util.Map.Entry<Tab, List<FarmingRunModule.OverviewPatch>> entry : byCategory.entrySet())
		{
			strip.add(overviewTile(entry.getKey(), entry.getValue(), now));
		}
		return strip;
	}

	/** One category tile: its icon, a green border when every patch is ready/
	 *  dead/empty, else an orange clockwise arc for progress toward the next
	 *  ready patch. Click toggles the category's patch list. */
	private JComponent overviewTile(Tab category, List<FarmingRunModule.OverviewPatch> patches, long now)
	{
		int seen = 0;
		int done = 0;
		int weeded = 0;
		double progress = 0;
		for (FarmingRunModule.OverviewPatch p : patches)
		{
			if (p.cropState == null)
			{
				continue;
			}
			seen++;
			if (p.weeds)
			{
				weeded++; // nothing planted here
			}
			switch (p.cropState)
			{
				case HARVESTABLE:
				case DEAD:
				case EMPTY:
					done++;
					break;
				case GROWING:
					// a grown patch reads GROWING with a past estimate until it's
					// checked ("Done") — that's ready, not 100%-growing
					if (p.doneEstimate <= now)
					{
						done++;
					}
					else if (p.stages > 1)
					{
						progress = Math.max(progress, p.stage / (double) (p.stages - 1));
					}
					break;
				default:
					break;
			}
		}
		// nothing planted anywhere in this category — no status border at all
		boolean onlyWeeds = seen > 0 && weeded == seen;
		boolean ready = !onlyWeeds && seen > 0 && done == seen;
		OverviewTile tile = new OverviewTile(onlyWeeds ? 0 : progress, ready, onlyWeeds,
			category == expandedOverview, category.getName(), () ->
			{
				// single expansion: a second click on the open tile closes it
				expandedOverview = category == expandedOverview ? null : category;
				rebuildOverview();
			});
		tile.setIconImage(sprites.get(category.getItemID(), 24));
		return tile;
	}

	/** Sub-section label inside a merged tile (e.g. "Calquat Patches"). */
	private JLabel overviewSubLabel(String name)
	{
		JLabel label = new JLabel(name);
		label.setForeground(UiTokens.TEXT_MUTED);
		label.setFont(label.getFont().deriveFont(UiTokens.FONT_SIZE_LABEL));
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		return label;
	}

	/** Category tile with a status-coloured border/arc (see overviewTile). */
	private class OverviewTile extends JComponent
	{
		private java.awt.Image icon;
		private final double progress;
		private final boolean ready;
		private final boolean onlyWeeds;
		private final boolean expanded;

		OverviewTile(double progress, boolean ready, boolean onlyWeeds, boolean expanded,
			String tooltip, Runnable onClick)
		{
			this.progress = progress;
			this.ready = ready;
			this.onlyWeeds = onlyWeeds;
			this.expanded = expanded;
			setPreferredSize(new Dimension(34, 30));
			setMinimumSize(new Dimension(34, 30));
			setToolTipText(tooltip);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					onClick.run();
				}
			});
		}

		void setIconImage(java.awt.Image image)
		{
			this.icon = image;
			repaint();
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
				java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(expanded ? UiTokens.INSET_BG : UiTokens.ICON_BUTTON_BG);
			g2.fillRect(0, 0, w, h);
			if (icon != null)
			{
				g2.drawImage(icon, (w - 24) / 2, (h - 24) / 2, null);
			}
			if (onlyWeeds)
			{
				g2.dispose(); // nothing planted — no border of any kind
				return;
			}
			if (ready)
			{
				g2.setColor(net.runelite.client.ui.ColorScheme.PROGRESS_COMPLETE_COLOR.darker());
				g2.setStroke(new java.awt.BasicStroke(1));
				g2.drawRect(0, 0, w - 1, h - 1);
			}
			else
			{
				g2.setColor(UiTokens.BORDER_BUTTON);
				g2.setStroke(new java.awt.BasicStroke(1));
				g2.drawRect(0, 0, w - 1, h - 1);
				if (progress > 0)
				{
					g2.setColor(net.runelite.client.ui.ColorScheme.PROGRESS_INPROGRESS_COLOR);
					g2.setStroke(new java.awt.BasicStroke(2));
					paintPerimeterProgress(g2, w, h, progress);
				}
			}
			g2.dispose();
		}
	}

	/** Trace an orange line clockwise from the top-centre around the tile
	 *  perimeter for `fraction` of the way round. */
	private static void paintPerimeterProgress(java.awt.Graphics2D g, int w, int h, double fraction)
	{
		double target = fraction * 2.0 * (w + h);
		int cx = w / 2;
		double[][] segments = {
			{cx, 0, w, 0},   // top-centre -> top-right
			{w, 0, w, h},    // -> bottom-right
			{w, h, 0, h},    // -> bottom-left
			{0, h, 0, 0},    // -> top-left
			{0, 0, cx, 0},   // -> top-centre
		};
		double drawn = 0;
		for (double[] s : segments)
		{
			double len = Math.hypot(s[2] - s[0], s[3] - s[1]);
			if (len <= 0)
			{
				continue;
			}
			if (drawn + len <= target)
			{
				g.drawLine((int) s[0], (int) s[1], (int) s[2], (int) s[3]);
				drawn += len;
			}
			else
			{
				double t = (target - drawn) / len;
				g.drawLine((int) s[0], (int) s[1],
					(int) (s[0] + (s[2] - s[0]) * t), (int) (s[1] + (s[3] - s[1]) * t));
				break;
			}
		}
	}

	/** One patch line — the core plugin's TimeablePanel, coloured identically
	 *  (progress bar = crop-state colour, "Done"/"Diseased"/… estimate text). */
	private JComponent overviewPatchPanel(FarmingRunModule.OverviewPatch patch, long now)
	{
		net.runelite.client.plugins.timetracking.TimeablePanel<String> panel =
			new net.runelite.client.plugins.timetracking.TimeablePanel<>(patch.name, patch.name, 1);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.getNotifyButton().setVisible(false); // ready notifications are per-category config here
		if (itemManager != null && patch.produceItemId > 0)
		{
			itemManager.getImage(patch.produceItemId).addTo(panel.getIcon());
		}
		// hovering a patch says what's planted there
		String planted = patch.cropState == null ? "Unknown state"
			: (patch.weeds ? "Nothing planted" : patch.produceName);
		String tooltip = patch.name + " — " + planted;
		panel.setToolTipText(tooltip);
		panel.getIcon().setToolTipText(tooltip);
		panel.getText().setToolTipText(tooltip);
		panel.getEstimate().setToolTipText(tooltip);
		net.runelite.client.ui.components.ThinProgressBar bar = panel.getProgress();
		if (patch.cropState == null)
		{
			panel.getEstimate().setText("Unknown");
			bar.setVisible(false);
		}
		else
		{
			panel.getEstimate().setText(estimateText(patch, now));
			// hide the bar for fully-grown weeds (no crop) — Time Tracking parity
			if (patch.weeds && patch.stage >= patch.stages - 1)
			{
				bar.setVisible(false);
			}
			else
			{
				bar.setForeground(patch.cropState.getColor().darker());
				bar.setMaximumValue(Math.max(0, patch.stages - 1));
				bar.setValue(patch.stage);
				bar.setVisible(true);
			}
		}
		return panel;
	}

	/** Time Tracking's estimate text ("Done" / "Done 3h 20m" / "Diseased" …). */
	private static String estimateText(FarmingRunModule.OverviewPatch patch, long now)
	{
		switch (patch.cropState)
		{
			case HARVESTABLE:
				return "Done";
			case GROWING:
				return patch.doneEstimate <= now ? "Done"
					: "Done " + Format.hours((patch.doneEstimate - now) / 3600.0);
			case DISEASED:
				return "Diseased";
			case DEAD:
				return "Dead";
			case EMPTY:
				return "Empty";
			case FILLING:
				return "Filling";
			default:
				return "Unknown";
		}
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
		// End run lives in the top bar; here we keep the setup control + stops.
		// Remember this run's gear + inventory so the bank shows it (Inventory
		// Setups style) — the whole loadout, in the right slots, to re-gather.
		// The combined all-runs sequence gets no run-level setup: each stop's
		// type bucket (Gear & inventory section) serves it instead.
		if (!module.combinedRun())
		{
			boolean hasSetup = state.getFarmRunSetup(module.runName()) != null;
			JLabel saveSetup = secondaryButton(
				hasSetup ? "Update bank setup" : "Save gear + inventory as bank setup");
			saveSetup.setToolTipText("Snapshot your worn gear and inventory now; while this "
				+ "run is active, opening the bank lays it out for you to re-stock fast");
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
		}

		FarmingRunModule.Stop next = module.nextStop();
		for (FarmingRunModule.Stop stop : module.stops())
		{
			String label = module.stopLabel(stop);
			ListRow row;
			if (module.isVisited(stop.location.id))
			{
				row = ListRow.owned(label); // done — nothing to skip
			}
			else
			{
				String id = stop.location.id;
				IconButton skip = IconButton.skip(() ->
				{
					module.markThrough(id); // skip this stop (and any before it)
					rebuild();
				});
				row = (next != null && stop == next)
					? ListRow.available(label, skip)
					: ListRow.locked(label, skip);
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
		String patch = FarmingRunOverlay.patchState(module.patchesAt(stop.location),
			FarmingRunModule.categoryTab(stop.location.category));
		if (patch != null)
		{
			tooltip.add(patch);
		}
		return tooltip.toString();
	}

	private void buildRunPicker()
	{
		int stops = module.selectedRunStops();
		JLabel startAll = primaryButton(stops > 0
			? "Start all runs · " + stops + " stops" : "Nothing to run");
		if (stops > 0)
		{
			startAll.setToolTipText("Every ticked run, as one sequence, trimmed to "
				+ "the stops worth doing right now");
			startAll.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					module.startAllRuns();
				}
			});
		}
		else
		{
			startAll.setBackground(UiTokens.ICON_BUTTON_BG);
			startAll.setForeground(UiTokens.TEXT_MUTED);
			startAll.setBorder(new LineBorder(UiTokens.BORDER_BUTTON));
			startAll.setCursor(Cursor.getDefaultCursor());
			startAll.setToolTipText("Nothing ticked is worth a trip right now");
		}
		runs.add(startAll);
		runs.add(Box.createVerticalStrut(UiTokens.PAD));

		// picker order = the order "start all" walks (ready first, then the pack's)
		java.util.Set<String> custom = state.getFarmRuns().keySet();
		for (String name : module.pickerOrder())
		{
			runs.add(runRow(name, () -> {
				if (custom.contains(name))
				{
					module.startCustom(name);
				}
				else
				{
					module.startTemplate(name);
				}
			}, custom.contains(name) ? () -> state.deleteFarmRun(name) : null));
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
	/**
	 * One run: its icon, its name (green when there is something waiting), and
	 * "Ready" — or nothing at all, because a run with no work is better said
	 * with silence than with a stop count nobody reads.
	 */
	private JPanel runRow(String name, Runnable start, Runnable delete)
	{
		// selected but covered by a bigger ticked run (All trees over Tree run):
		// greyed out of the combined sequence until the big run is unticked —
		// derived, so the small run's own choice survives untouched.
		String supersededBy = module.supersededBy(name);
		boolean selected = module.runSelected(name) && supersededBy == null;
		boolean ready = module.runReady(name) && selected;
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.CARD_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.ROW_GAP, 0, UiTokens.ROW_GAP)));
		row.setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		row.add(reorderArrows(name));
		JCheckBox include = new JCheckBox("", selected);
		include.setOpaque(false);
		include.setEnabled(supersededBy == null);
		include.setToolTipText(supersededBy != null
			? "Covered by " + supersededBy : "Include in Start all runs");
		include.setBorder(new EmptyBorder(0, 0, 0, 0));
		include.setPreferredSize(new Dimension(CHECK_WIDTH, UiTokens.ROW_HEIGHT));
		include.setMaximumSize(new Dimension(CHECK_WIDTH, UiTokens.ROW_HEIGHT));
		include.addActionListener(e -> state.setFarmRunSelected(name, include.isSelected()));
		row.add(include);
		row.add(runIcon(name));
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		JLabel label = new JLabel(name);
		label.setForeground(supersededBy != null ? UiTokens.TEXT_FAINT
			: ready ? net.runelite.client.ui.ColorScheme.PROGRESS_COMPLETE_COLOR
			: UiTokens.TEXT_PRIMARY);
		label.setFont(label.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		label.setMinimumSize(new Dimension(0, 0));
		row.add(label);
		row.add(Box.createHorizontalGlue());
		if (ready)
		{
			JLabel readyLabel = new JLabel("Ready");
			readyLabel.setForeground(net.runelite.client.ui.ColorScheme.PROGRESS_COMPLETE_COLOR);
			readyLabel.setFont(readyLabel.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			row.add(readyLabel);
		}
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

	/** Two stacked triangles left of a run's checkbox: move it up/down the
	 *  picker (and so the combined run's order). Persisted per profile. */
	private JComponent reorderArrows(String name)
	{
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		Dimension size = new Dimension(ARROWS_WIDTH, UiTokens.ROW_HEIGHT);
		column.setPreferredSize(size);
		column.setMaximumSize(size);
		column.add(Box.createVerticalGlue());
		column.add(arrow(PaintedIcon.Shape.TRIANGLE_UP, "Move up", () -> module.moveRun(name, -1)));
		column.add(arrow(PaintedIcon.Shape.TRIANGLE_DOWN, "Move down", () -> module.moveRun(name, 1)));
		column.add(Box.createVerticalGlue());
		return column;
	}

	private JLabel arrow(PaintedIcon.Shape shape, String tooltip, Runnable onClick)
	{
		JLabel arrow = new JLabel(new PaintedIcon(shape, 9));
		arrow.setForeground(UiTokens.GLYPH_MUTED);
		arrow.setToolTipText(tooltip);
		arrow.setAlignmentX(LEFT_ALIGNMENT);
		arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		arrow.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				arrow.setForeground(UiTokens.ACCENT);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				arrow.setForeground(UiTokens.GLYPH_MUTED);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run(); // setFarmRunOrder notifies -> the tab rebuilds
			}
		});
		return arrow;
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
