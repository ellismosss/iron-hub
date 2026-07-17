package com.ironhub.modules.farming;

import com.ironhub.data.FarmRunsPack;
import com.ironhub.modules.farming.rl.Tab;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneCheckbox;
import com.ironhub.ui.osrs.StoneChecklist;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.plugins.timetracking.SummaryState;

/**
 * Farming tab content in the OSRS stonework skin (design/OSRS-SKIN.md): a
 * Time Tracking-style patch overview (every category with data), then the
 * run planner — built-in template runs, saved custom runs, a compact run
 * builder, and the live stop checklist while a run is active. Teleports
 * are auto-picked from what the player owns; each stop row's tooltip
 * carries the teleport, missing items and live patch states.
 *
 * <p>FRAMELESS and title-less: the host (the Dailies hub) provides the stone
 * frame and the header plate. The per-patch rows inside the overview stay
 * RuneLite's own TimeablePanel with ColorScheme colours — the sanctioned
 * Time Tracking clone Luke walked to the pixel; only the chrome around them
 * wears the skin.
 */
class FarmingTab extends JPanel
{
	private final AccountState state;
	private final FarmingRunModule module;
	private final net.runelite.client.game.ItemManager itemManager; // null in headless tests
	private final OsrsTheme theme;
	private final SpriteCache sprites;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	/** The one category expanded under the overview tile strip (null = none). */
	private Tab expandedOverview;

	private final JPanel topBar = new JPanel();
	private final JPanel statsHolder = new JPanel();
	private final JPanel xpStats = new JPanel();
	private final JPanel overview = new JPanel();
	private final JPanel runs = new JPanel();
	private final JLabel teleportTriangle = triangle();
	private final JPanel teleportPanel = new JPanel();
	private boolean teleportsOpen;
	// "Configure gear & inventory" is the ask, but it clips past 225 px —
	// the tooltip carries the longer intent
	private final JLabel setupTriangle = triangle();
	private final JPanel setupPanel = new JPanel();
	private boolean setupsOpen;

	// run builder state
	private boolean builderOpen;
	private final StoneTextField builderName;
	private final Set<String> builderSelection = new LinkedHashSet<>();

	FarmingTab(AccountState state, FarmingRunModule module,
		net.runelite.client.game.ItemManager itemManager, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.itemManager = itemManager;
		this.theme = theme;
		this.sprites = new SpriteCache(itemManager, this::rebuild);
		this.builderName = new StoneTextField(theme, "Run name…");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		// The active-run cockpit lives at the very top so End run is the
		// first thing you see during a run, not buried under the overview.
		topBar.setLayout(new BoxLayout(topBar, BoxLayout.Y_AXIS));
		topBar.setOpaque(false);
		topBar.setAlignmentX(LEFT_ALIGNMENT);
		add(topBar);

		add(section("Patch overview"));
		overview.setLayout(new BoxLayout(overview, BoxLayout.Y_AXIS));
		overview.setOpaque(false);
		overview.setAlignmentX(LEFT_ALIGNMENT);
		add(overview);

		add(section("Runs"));
		statsHolder.setLayout(new BoxLayout(statsHolder, BoxLayout.Y_AXIS));
		statsHolder.setOpaque(false);
		statsHolder.setAlignmentX(LEFT_ALIGNMENT);
		add(statsHolder);
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		xpStats.setLayout(new BoxLayout(xpStats, BoxLayout.Y_AXIS));
		xpStats.setOpaque(false);
		xpStats.setAlignmentX(LEFT_ALIGNMENT);
		add(xpStats);
		runs.setLayout(new BoxLayout(runs, BoxLayout.Y_AXIS));
		runs.setOpaque(false);
		runs.setAlignmentX(LEFT_ALIGNMENT);
		add(runs);

		add(buildTeleportSection());
		add(buildSetupSection());
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	/** Section header in the skin grammar (the DailiesNewTab pattern). */
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

	private static JLabel triangle()
	{
		JLabel label = new JLabel(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		label.setForeground(OsrsSkin.MUTED);
		return label;
	}

	/** Collapsible section header: triangle + skin label, whole row toggles. */
	private JComponent collapsibleHeader(JLabel triangleLabel, String title, String tooltip,
		Runnable onToggle)
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setBorder(new EmptyBorder(8, 4, 3, 4));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		OsrsLabel label = new OsrsLabel(title, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
		header.add(triangleLabel);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		header.add(label);
		header.add(Box.createHorizontalGlue());
		cap(header);
		MouseAdapter press = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onToggle.run();
			}
		};
		header.addMouseListener(press);
		// a tooltip registers the label's own mouse listeners, which would
		// swallow the row's — so the children carry the press listener too
		header.setToolTipText(tooltip);
		label.setToolTipText(tooltip);
		label.addMouseListener(press);
		triangleLabel.addMouseListener(press);
		return header;
	}

	/** Collapsible "Teleport preferences": choose the teleport used to reach
	 *  each patch (Easy Farming-style), overriding the owned-first auto-pick. */
	private JPanel buildTeleportSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		section.add(collapsibleHeader(teleportTriangle, "Teleport preferences",
			"Pick the teleport used to reach each patch", () ->
		{
			teleportsOpen = !teleportsOpen;
			teleportTriangle.setIcon(new PaintedIcon(teleportsOpen
				? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
			teleportPanel.setVisible(teleportsOpen);
			rebuildTeleports();
		}));

		teleportPanel.setLayout(new BoxLayout(teleportPanel, BoxLayout.Y_AXIS));
		teleportPanel.setOpaque(false);
		teleportPanel.setAlignmentX(LEFT_ALIGNMENT);
		teleportPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 4, 0, 4));
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
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

		JPanel nameHolder = new JPanel(new BorderLayout());
		nameHolder.setOpaque(false);
		nameHolder.setPreferredSize(new Dimension(82, 22));
		OsrsLabel name = new OsrsLabel(rep.name, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
		name.setToolTipText(rep.name);
		nameHolder.add(name, BorderLayout.CENTER);
		row.add(nameHolder, BorderLayout.WEST);

		JComboBox<String> combo = StoneComboBoxUI.skin(new JComboBox<>(), theme);
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
	 * Collapsible "Gear & inventory": one button per run type (Trees / Herbs /
	 * Birdhouses / Others) that snapshots the player's current gear +
	 * inventory as that type's bank setup. During a run of that type the bank
	 * lays the setup out (a run's own saved setup, made from the active-run
	 * view, still wins).
	 */
	private JPanel buildSetupSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(LEFT_ALIGNMENT);

		section.add(collapsibleHeader(setupTriangle, "Gear & inventory",
			"Save your current gear + inventory as the bank setup for each type of run", () ->
		{
			setupsOpen = !setupsOpen;
			setupTriangle.setIcon(new PaintedIcon(setupsOpen
				? PaintedIcon.Shape.TRIANGLE_DOWN : PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
			setupPanel.setVisible(setupsOpen);
			rebuildSetups();
		}));

		setupPanel.setLayout(new BoxLayout(setupPanel, BoxLayout.Y_AXIS));
		setupPanel.setOpaque(false);
		setupPanel.setAlignmentX(LEFT_ALIGNMENT);
		setupPanel.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 4, 0, 4));
		setupPanel.setVisible(false);
		section.add(setupPanel);
		return section;
	}

	private void rebuildSetups()
	{
		setupPanel.removeAll();
		if (setupsOpen)
		{
			setupPanel.add(hint("Wear and carry the loadout you restock with, "
				+ "then click its run type. The bank shows it during those runs.",
				OsrsSkin.FAINT));
			JPanel grid = new JPanel(new java.awt.GridLayout(0, 2, UiTokens.PAD_TIGHT, UiTokens.PAD_TIGHT));
			grid.setOpaque(false);
			grid.setAlignmentX(LEFT_ALIGNMENT);
			for (String bucket : FarmingRunModule.SETUP_BUCKETS)
			{
				grid.add(setupButton(bucket));
			}
			grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
			setupPanel.add(grid);
		}
		setupPanel.revalidate();
		setupPanel.repaint();
	}

	/** Bucket just saved — its button reads "Saved" for a moment, because a
	 *  click on an already-green button otherwise looks like nothing happened. */
	private String justSavedBucket;

	/** One run type's capture button — green text once a setup is saved, with
	 *  the saved item count in the tooltip so an overwrite is verifiable. */
	private JComponent setupButton(String bucket)
	{
		com.ironhub.state.PersistedState.SavedSetup existing =
			state.getFarmRunSetup(FarmingRunModule.bucketKey(bucket));
		boolean flash = bucket.equals(justSavedBucket);
		StoneButton button = new StoneButton(theme, flash ? "Saved" : bucket, () ->
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
		});
		if (existing != null)
		{
			button.labelColor(OsrsSkin.VALUE);
		}
		button.setToolTipText(existing != null
			? bucket + " setup saved · " + setupItemCount(existing)
				+ " items — click to replace it with your current gear + inventory"
			: "Save your current gear + inventory as the " + bucket + " setup");
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
		setupTriangle.setIcon(new PaintedIcon(PaintedIcon.Shape.TRIANGLE_DOWN, 10));
		setupPanel.setVisible(true);
		rebuildSetups();
	}

	void rebuild()
	{
		statsHolder.removeAll();
		OsrsLabel stats = new OsrsLabel(FarmingRunModule.statsLine(state.getHerbRunsMs()),
			OsrsSkin.FAINT, OsrsSkin.font()).leftAligned();
		stats.setAlignmentX(LEFT_ALIGNMENT);
		statsHolder.add(pad(stats));
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
		JComponent row = overviewRow(label, compactXp(avg) + " " + unit);
		row.setToolTipText(tooltip);
		xpStats.add(pad(row));
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
			JComponent line = hint(text, OsrsSkin.FAINT);
			if (!unlocks.isEmpty())
			{
				line.setToolTipText("<html>" + String.join("<br>", unlocks) + "</html>");
			}
			xpStats.add(pad(line));
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
			topBar.add(Box.createVerticalStrut(4));
			StoneButton end = new StoneButton(theme, "End run", () ->
			{
				module.endRun(false);
				rebuild();
			});
			end.setToolTipText("Stop " + module.runName() + " now (an abandoned run isn't logged)");
			topBar.add(pad(end));
			topBar.add(Box.createVerticalStrut(3));
			OsrsLabel status = new OsrsLabel(module.runName() + " · "
				+ module.visitedCount() + "/" + module.stops().size() + " stops",
				OsrsSkin.MUTED, OsrsSkin.font()).leftAligned();
			topBar.add(pad(status));
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
			overview.add(pad(hint("Enable the core Time Tracking plugin — Iron Hub "
				+ "reads its patch data.", OsrsSkin.TITLE)));
		}
		else if (!tracking.hasAnyData())
		{
			overview.add(pad(hint("No tracking data yet. The Time Tracking plugin "
				+ "records each patch as you visit it.", OsrsSkin.FAINT)));
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
			overview.add(pad(overviewTileStrip(byCategory, now)));
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
						overview.add(pad(overviewSubLabel(patch.sourceTab.getName())));
					}
					overview.add(pad(overviewPatchPanel(patch, now)));
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
	private JLabel runIcon(String name)
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
	/** Run rows are 23 px: ODD, so the 15 px checkbox and the label's odd ink
	 *  block both centre exactly (the skin's parity rule). */
	private static final int RUN_ROW_HEIGHT = 23;
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

	/** One category tile: its icon, a green bevel when every patch is ready/
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
	private JComponent overviewSubLabel(String name)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 0, 0, 0));
		row.add(new OsrsLabel(name, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/**
	 * Category tile with a status bevel/arc, on the nav stone's chamfered
	 * slab (Luke, 2026-07-17): green inner bevel when every patch is ready,
	 * an orange perimeter trace hugging the chamfer for progress, recess
	 * fill while expanded. A weeds-only tile paints the bare silhouette —
	 * nothing planted, no status.
	 */
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
			int w = getWidth();
			int h = getHeight();
			java.awt.Color fill = expanded ? theme.recess : theme.boxFill;
			if (onlyWeeds)
			{
				// nothing planted — the bare chamfered silhouette, no engraving
				com.ironhub.ui.osrs.StoneNavButton.paintSilhouette(g2, w, h, fill);
			}
			else
			{
				com.ironhub.ui.osrs.StoneNavButton.paintSlab(g2, theme, w, h, fill,
					ready ? OsrsSkin.VALUE.darker() : theme.edgeLight);
			}
			if (icon != null)
			{
				g2.drawImage(icon, (w - 24) / 2, (h - 24) / 2, null);
			}
			if (!onlyWeeds && !ready && progress > 0)
			{
				g2.setColor(OsrsSkin.TITLE.darker());
				paintPerimeterProgress(g2, w, h, progress);
			}
			g2.dispose();
		}
	}

	/** Trace an orange line clockwise from the top-centre around the tile,
	 *  hugging the slab's chamfered outline (both ring paths = 2px weight),
	 *  for `fraction` of the way round. */
	private static void paintPerimeterProgress(java.awt.Graphics2D g, int w, int h, double fraction)
	{
		for (int inset = 0; inset < 2; inset++)
		{
			java.util.List<java.awt.Point> path =
				com.ironhub.ui.osrs.StoneNavButton.ringPath(inset, w, h);
			int covered = (int) Math.round(fraction * path.size());
			for (int i = 0; i < covered && i < path.size(); i++)
			{
				java.awt.Point p = path.get(i);
				g.fillRect(p.x, p.y, 1, 1);
			}
		}
	}

	/** One patch line — the core plugin's TimeablePanel, coloured identically
	 *  (progress bar = crop-state colour, "Done"/"Diseased"/… estimate text).
	 *  The sanctioned Time Tracking clone: never restyle these rows. */
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

	/** A label · value line in a stone box (the stat-row grammar). */
	private JComponent overviewRow(String name, String value)
	{
		StonePanel row = new StonePanel(theme);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new OsrsLabel(name, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		row.add(OsrsLabel.value(value));
		cap(row);
		return row;
	}

	/** The width hints wrap at: the panel minus the tab's side padding. */
	private static final int HINT_WIDTH = UiTokens.PANEL_WIDTH - 20;

	/** Wrapping secondary text as a multi-line OsrsLabel — html measurement
	 *  and the pixel font disagree (lines lay out wider than they paint and
	 *  clip mid-word), so the wrapping is done with real FontMetrics. */
	private JComponent hint(String text, Color color)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, 0, UiTokens.PAD_TIGHT, 0));
		holder.add(OsrsLabel.wrapped(text, HINT_WIDTH, color, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
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
			StoneButton saveSetup = new StoneButton(theme,
				hasSetup ? "Update bank setup" : "Save gear + inventory as bank setup", () ->
			{
				state.saveFarmRunSetup(module.runName(), state.captureSetup());
				rebuild();
			});
			saveSetup.setToolTipText("Snapshot your worn gear and inventory now; while this "
				+ "run is active, opening the bank lays it out for you to re-stock fast");
			runs.add(pad(saveSetup));
			if (hasSetup)
			{
				OsrsLabel clear = new OsrsLabel("Clear setup", OsrsSkin.FAINT, OsrsSkin.font())
					.leftAligned();
				clear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				clear.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						state.saveFarmRunSetup(module.runName(), null);
						rebuild();
					}
				});
				runs.add(Box.createVerticalStrut(3));
				runs.add(pad(clear));
			}
			runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}

		// the stop checklist, one stone box (the DailiesNewTab run grammar)
		StonePanel list = new StonePanel(theme);
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setAlignmentX(LEFT_ALIGNMENT);
		FarmingRunModule.Stop next = module.nextStop();
		for (FarmingRunModule.Stop stop : module.stops())
		{
			list.add(stopRow(stop, next != null && stop == next));
		}
		cap(list);
		runs.add(pad(list));
	}

	/** One run stop: name coloured by progress, Skip on what is still to do. */
	private JComponent stopRow(FarmingRunModule.Stop stop, boolean isNext)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(isNext);
		if (isNext)
		{
			row.setBackground(theme.selectFill);
		}
		row.setAlignmentX(LEFT_ALIGNMENT);
		boolean visited = module.isVisited(stop.location.id);
		Color color = visited ? OsrsSkin.VALUE : isNext ? OsrsSkin.TITLE : OsrsSkin.MUTED;
		OsrsLabel name = new OsrsLabel(module.stopLabel(stop), color, OsrsSkin.font()).leftAligned();
		String tooltip = stopTooltip(stop);
		name.setToolTipText(tooltip);
		row.setToolTipText(tooltip);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		if (!visited)
		{
			String id = stop.location.id;
			StoneButton skip = new StoneButton(theme, isNext ? theme.selectFill : theme.boxFill,
				"Skip", () ->
			{
				module.markThrough(id); // skip this stop (and any before it)
				rebuild();
			});
			skip.setToolTipText("Skip this stop (and any before it)");
			skip.setMaximumSize(skip.getPreferredSize());
			row.add(skip);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
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
		if (stops > 0)
		{
			StoneButton startAll = new StoneButton(theme,
				"Start all runs · " + stops + " stops", module::startAllRuns);
			startAll.setToolTipText("Every ticked run, as one sequence, trimmed to "
				+ "the stops worth doing right now");
			runs.add(pad(startAll));
		}
		else
		{
			// nothing to do is a real state — a dead button would lie
			StonePanel none = new StonePanel(theme);
			none.setLayout(new BoxLayout(none, BoxLayout.X_AXIS));
			none.setAlignmentX(LEFT_ALIGNMENT);
			none.add(Box.createHorizontalGlue());
			none.add(new OsrsLabel("Nothing to run", OsrsSkin.FAINT, OsrsSkin.font()));
			none.add(Box.createHorizontalGlue());
			none.setToolTipText("Nothing ticked is worth a trip right now");
			cap(none);
			runs.add(pad(none));
		}
		// what the ticked runs are short on — compost, seeds, saplings — so a
		// shortage is a warning you read, never a stop that silently vanishes
		for (String warning : module.supplyWarnings())
		{
			runs.add(Box.createVerticalStrut(2));
			JComponent line = hint(warning, UiTokens.STATUS_WARNING);
			line.setToolTipText("The ticked runs need more than you own "
				+ "(bank + inventory + worn)");
			runs.add(pad(line));
		}
		runs.add(Box.createVerticalStrut(UiTokens.PAD));

		// picker order = the order "start all" walks (ready first, then the
		// pack's); the rows sit inside one notched frame, checklist-style
		StonePanel group = new StonePanel(theme);
		group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
		group.setAlignmentX(LEFT_ALIGNMENT);
		int corner = theme.cornerStamp.length;
		group.setBorder(new StoneBorder(theme, theme.background,
			new Insets(corner, corner, corner, corner)));
		java.util.Set<String> custom = state.getFarmRuns().keySet();
		for (String name : module.pickerOrder())
		{
			group.add(new RunRow(name, () ->
			{
				if (custom.contains(name))
				{
					module.startCustom(name);
				}
				else
				{
					module.startTemplate(name);
				}
			}, custom.contains(name) ? () -> state.deleteFarmRun(name) : null));
		}
		cap(group);
		runs.add(pad(group));
		runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		StoneButton newRun = new StoneButton(theme,
			builderOpen ? "Cancel new run" : "New custom run…", () ->
		{
			builderOpen = !builderOpen;
			if (!builderOpen)
			{
				builderSelection.clear();
				builderName.setText("");
			}
			rebuildRuns();
		});
		runs.add(pad(newRun));
		if (builderOpen)
		{
			runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
			buildRunBuilder();
		}
	}

	/**
	 * One run in the picker: reorder arrows, an include checkbox, its icon,
	 * its name (green when there is something waiting), and "Ready" — or
	 * nothing at all, because a run with no work is better said with silence
	 * than with a stop count nobody reads. Click anywhere else starts it.
	 * Laid out by hand (the StoneChecklist.Row precedent): the row must
	 * centre the 15 px box and the odd-height ink block in the same height.
	 */
	private class RunRow extends JPanel
	{
		private final StoneCheckbox box;
		private final JComponent arrows;
		private final JLabel icon;
		private final OsrsLabel name;
		private final OsrsLabel ready; // null when the run has no work waiting
		private final JLabel delete;   // null for template runs

		RunRow(String runName, Runnable start, Runnable onDelete)
		{
			// selected but covered by a bigger ticked run (All trees over Tree
			// run): greyed out of the combined sequence until the big run is
			// unticked — derived, so the small run's own choice survives.
			String supersededBy = module.supersededBy(runName);
			boolean selected = module.runSelected(runName) && supersededBy == null;
			boolean isReady = module.runReady(runName) && selected;

			setLayout(null);
			setOpaque(true);
			setBackground(theme.boxFill);
			setAlignmentX(LEFT_ALIGNMENT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			arrows = reorderArrows(runName);
			add(arrows);
			box = new StoneCheckbox(theme, selected);
			box.setToolTipText(supersededBy != null
				? "Covered by " + supersededBy : "Include in Start all runs");
			if (supersededBy == null)
			{
				box.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						box.setChecked(!box.isChecked());
						state.setFarmRunSelected(runName, box.isChecked());
					}
				});
			}
			add(box);
			icon = runIcon(runName);
			add(icon);
			ready = isReady ? new OsrsLabel("Ready", OsrsSkin.VALUE, OsrsSkin.font()) : null;
			if (ready != null)
			{
				add(ready);
			}
			delete = onDelete == null ? null : deleteGlyph(() ->
			{
				onDelete.run();
				rebuild();
			});
			if (delete != null)
			{
				add(delete);
			}
			// OsrsLabel ellipsizes at paint time when the row runs short of
			// room; the row's tooltip carries the full name (dailies parity)
			name = new OsrsLabel(runName,
				supersededBy != null ? OsrsSkin.FAINT
					: isReady ? OsrsSkin.VALUE : OsrsSkin.MUTED,
				OsrsSkin.font()).leftAligned();
			add(name);
			setToolTipText(runName);

			MouseAdapter clicks = new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					setBackground(theme.hoverFill);
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setBackground(theme.boxFill);
					repaint();
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						start.run();
						rebuild();
					}
				}
			};
			addMouseListener(clicks);
			// the name has no tooltip, so clicks on it fall through to the row
		}

		@Override
		public void doLayout()
		{
			int h = getHeight();
			arrows.setBounds(1, 0, ARROWS_WIDTH, h);
			Dimension bp = box.getPreferredSize();
			int x = 1 + ARROWS_WIDTH + 3;
			box.setBounds(x, (h - bp.height) / 2, bp.width, bp.height);
			x += bp.width + 6;
			icon.setBounds(x, (h - RUN_ICON) / 2, RUN_ICON, RUN_ICON);
			x += RUN_ICON + 4;
			int right = getWidth() - 4;
			if (delete != null)
			{
				Dimension dp = delete.getPreferredSize();
				delete.setBounds(right - dp.width, 0, dp.width, h);
				right -= dp.width + 6;
			}
			if (ready != null)
			{
				Dimension rp = ready.getPreferredSize();
				ready.setBounds(right - rp.width, 0, rp.width, h);
				right -= rp.width + 6;
			}
			name.setBounds(x, 0, Math.max(0, right - x), h);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(0, RUN_ROW_HEIGHT);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, RUN_ROW_HEIGHT);
		}
	}

	/** Two stacked triangles left of a run's checkbox: move it up/down the
	 *  picker (and so the combined run's order). Persisted per profile. */
	private JComponent reorderArrows(String name)
	{
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		column.add(Box.createVerticalGlue());
		column.add(arrow(PaintedIcon.Shape.TRIANGLE_UP, "Move up", () -> module.moveRun(name, -1)));
		column.add(arrow(PaintedIcon.Shape.TRIANGLE_DOWN, "Move down", () -> module.moveRun(name, 1)));
		column.add(Box.createVerticalGlue());
		return column;
	}

	private JLabel arrow(PaintedIcon.Shape shape, String tooltip, Runnable onClick)
	{
		JLabel arrow = new JLabel(new PaintedIcon(shape, 9));
		arrow.setForeground(OsrsSkin.FAINT);
		arrow.setToolTipText(tooltip);
		arrow.setAlignmentX(LEFT_ALIGNMENT);
		arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		arrow.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				arrow.setForeground(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				arrow.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run(); // setFarmRunOrder notifies -> the tab rebuilds
			}
		});
		return arrow;
	}

	/** A small × affordance in skin colours — faint until hovered. */
	private JLabel deleteGlyph(Runnable onDelete)
	{
		JLabel glyph = new JLabel("×", javax.swing.SwingConstants.CENTER);
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText("Delete this run");
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
				onDelete.run();
			}
		});
		return glyph;
	}

	/** Compact builder: name, one checkbox per pack location (route order
	 *  within each category, each category in its own notched frame), Save. */
	private void buildRunBuilder()
	{
		builderName.setAlignmentX(LEFT_ALIGNMENT);
		runs.add(pad(builderName));
		runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		String lastCategory = "";
		StoneChecklist list = null;
		for (FarmRunsPack.Location location : module.pack().locations)
		{
			if (!location.category.equals(lastCategory))
			{
				lastCategory = location.category;
				JPanel header = new JPanel();
				header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
				header.setOpaque(false);
				header.setAlignmentX(LEFT_ALIGNMENT);
				header.setBorder(new EmptyBorder(UiTokens.PAD_TIGHT, 4, 2, 4));
				header.add(new OsrsLabel(location.category.toUpperCase(Locale.ROOT),
					OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
				header.add(Box.createHorizontalGlue());
				cap(header);
				runs.add(header);
				list = new StoneChecklist(theme);
				cap(list);
				runs.add(pad(list));
			}
			String id = location.id;
			list.row(location.name, builderSelection.contains(id), null, null, null, ticked ->
			{
				if (ticked)
				{
					builderSelection.add(id);
				}
				else
				{
					builderSelection.remove(id);
				}
			});
		}

		runs.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		StoneButton save = new StoneButton(theme, "Save run", () ->
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
		});
		runs.add(pad(save));
	}

	/** 4 px side inset for content rows (the DailiesNewTab grammar). */
	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, 0, 0, 0));
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
