package com.ironhub.modules.gear;

import com.ironhub.data.GearProgressionPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2ChipRow;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Gear progression chart (Ladlor-style) in the OSRS stonework skin: phases
 * of grouped item sprites connected by subtle arrows in recommended order.
 * Green bevel = obtained (any variant), orange = targeted in the goal
 * planner (left-click toggles), hover lists missing requirements,
 * right-click opens the wiki. Filter chips cut the chart to one combat
 * style / utility / POH / boat. Same brain as the classic tab — only the
 * clothing changed.
 */
class GearTab extends JPanel
{
	private static final String[] FILTERS_TOP = {"All", "Melee", "Ranged", "Magic"};
	/** Combat categories the progression chart shows — Utility, POH and Boat
	 *  were split out to their own modules (Luke, 2026-07-24). */
	private static final java.util.Set<String> COMBAT_CATEGORIES =
		java.util.Set.of("melee", "ranged", "magic");
	/** Tile-row budget: the panel minus the home border + stone frame +
	 *  this tab's own padding — the hub slot is narrower than 225px. */
	private static final int ROW_WIDTH = UiTokens.PANEL_WIDTH - 24;

	private final AccountState state;
	private final GearProgressionPack pack;
	private final com.ironhub.data.BoostsPack boostsPack;
	private final ItemManager itemManager; // null in headless tests
	private final OsrsTheme theme;
	private final V2ChipRow filterTop;
	private final JPanel body = new JPanel();
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	// ask once per sprite and rebuild through the gate when it lands — raw
	// getImage+onLoaded per tile stacked a listener per tile per rebuild
	// (~180 on the login screen, all firing at once mid-login)
	private final com.ironhub.ui.components.SpriteCache sprites;
	private final java.util.function.Consumer<Boolean> onHideCompleteChange;
	private String filter; // lower-case category, null = all
	private boolean hideComplete;

	/** itemId -> the current goal plan obtains it (null = no planner). */
	private final java.util.function.IntPredicate plannedItem;

	/** Where-from lines on unobtained tiles (null-tolerant for old tests). */
	private final com.ironhub.data.ItemSourcesPack itemSources;

	GearTab(AccountState state, GearProgressionPack pack, com.ironhub.data.BoostsPack boostsPack,
		ItemManager itemManager,
		boolean hideComplete, java.util.function.Consumer<Boolean> onHideCompleteChange,
		OsrsTheme theme, java.util.function.IntPredicate plannedItem,
		com.ironhub.data.ItemSourcesPack itemSources)
	{
		this.itemSources = itemSources;
		this.plannedItem = plannedItem == null ? id -> false : plannedItem;
		this.state = state;
		this.pack = pack;
		this.boostsPack = boostsPack;
		this.itemManager = itemManager;
		this.sprites = new com.ironhub.ui.components.SpriteCache(itemManager, listener);
		this.theme = theme;
		this.hideComplete = hideComplete;
		this.onHideCompleteChange = onHideCompleteChange;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		// frameless: content directly on the theme's backing so a hub host
		// connects with it as one block
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		filterTop = new V2ChipRow(theme, true, FILTERS_TOP);
		filterTop.onChange(this::selectFilter);
		add(filterTop);
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		add(hideCompleteToggle());
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setAlignmentX(LEFT_ALIGNMENT);
		add(body);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** The latching chip atom — this was a hand-rolled StonePanel chip. */
	private JComponent hideCompleteToggle()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(V2ChipRow.toggle(theme, "Hide complete", hideComplete, on ->
		{
			hideComplete = on;
			onHideCompleteChange.accept(on);
			rebuild();
		}));
		row.add(Box.createHorizontalGlue());
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private void selectFilter(int index)
	{
		filter = index <= 0 ? null : FILTERS_TOP[index].toLowerCase(Locale.ROOT);
		rebuild();
	}

	/** Obtained entries for the current rebuild, including implied predecessors. */
	private java.util.Set<String> obtained = java.util.Set.of();
	/** Usable temporary boost per skill (sources whose own gates are met). */
	private java.util.Map<net.runelite.api.Skill, Integer> boosts = java.util.Map.of();

	private void rebuild()
	{
		body.removeAll();
		obtained = GearProgressionModule.obtainedNames(pack, state);
		boosts = com.ironhub.requirements.Boosts.available(boostsPack, state);
		for (GearProgressionPack.Phase phase : pack.getPhases())
		{
			// the POH & Sailing phase moved to its own modules (Luke,
			// 2026-07-24) — the chart is the combat-gear path only
			if (phase.getName().contains("POH") || phase.getName().contains("Sailing"))
			{
				continue;
			}
			boolean phaseHasContent = false;
			boolean firstGroup = true;
			for (GearProgressionPack.Group group : phase.getGroups())
			{
				List<GearProgressionPack.Item> items = group.getItems().stream()
					.filter(this::matchesFilter)
					.filter(i -> !hideComplete || !isObtained(i))
					.collect(java.util.stream.Collectors.toList());
				if (items.isEmpty())
				{
					continue;
				}
				if (!phaseHasContent)
				{
					body.add(section(phase.getName()));
					phaseHasContent = true;
				}
				if (!firstGroup)
				{
					body.add(new Arrow(theme));
				}
				firstGroup = false;

				OsrsLabel label = new OsrsLabel(group.getLabel(), OsrsSkin.MUTED, OsrsSkin.font())
					.leftAligned().squeezable();
				body.add(label);
				body.add(Box.createVerticalStrut(2));

				// deterministic chunked rows: WrapLayout's height inside the
				// scroll view goes stale and clips everything past one row
				int perRow = (ROW_WIDTH + UiTokens.CHIP_GAP) / (TILE_WIDTH + UiTokens.CHIP_GAP);
				for (int start = 0; start < items.size(); start += perRow)
				{
					JPanel row = new JPanel();
					row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
					row.setOpaque(false);
					row.setAlignmentX(LEFT_ALIGNMENT);
					for (GearProgressionPack.Item item : items.subList(start,
						Math.min(start + perRow, items.size())))
					{
						row.add(tile(item));
						row.add(Box.createHorizontalStrut(UiTokens.CHIP_GAP));
					}
					row.add(Box.createHorizontalGlue());
					body.add(row);
					body.add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
				}
			}
			if (phaseHasContent)
			{
				body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
			}
		}
		body.revalidate();
		body.repaint();
	}

	/** Phase header in the skin's section grammar. */
	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(2, 0, 3, 0));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.boldFont()).leftAligned());
		row.add(Box.createHorizontalGlue());
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private boolean matchesFilter(GearProgressionPack.Item item)
	{
		// only combat gear here — utility/poh/boat moved to their own modules
		if (item.getCategories().stream().noneMatch(COMBAT_CATEGORIES::contains))
		{
			return false;
		}
		return filter == null || item.getCategories().contains(filter);
	}

	/**
	 * One chart node. This was {@code ItemTile}, a third hand-painted tile
	 * class; it is gone (Luke's Progression pass, 2026-07-26) and the node is
	 * the {@code V2Tile} atom in the system's own vocabulary: DONE when you
	 * own it, READY when its requirements are met, PLAIN while it is still
	 * ahead of you, and SELECTED when the goal planner is targeting it.
	 *
	 * <p>One reading did not survive: V1 drew a DARK orange corner triangle
	 * for "reachable only with a boost you have access to" against the bright
	 * one for "reachable now". The status vocabulary has three colours, not
	 * three-and-a-half, so both are READY and the difference lives in the
	 * tooltip.
	 */
	private V2Tile tile(GearProgressionPack.Item item)
	{
		boolean obtained = isObtained(item);
		boolean targeted = state.getSelectedGoals().contains(item.goalId());
		Requirement requirement = requirement(item);
		boolean ready = !obtained && requirement.isMet(state);
		boolean boostReady = !obtained && !ready && requirement.isMetWithBoosts(state, boosts);
		V2Tile tile = new V2Tile(theme, null, null, TILE_ART, () ->
		{
			if (!obtained || targeted) // nothing to target once obtained
			{
				state.selectGoal(item.goalId(), !targeted);
			}
		}).width(TILE_WIDTH).placeholder(code(item.getName())).selected(targeted);
		tile.status(obtained ? V2Tile.Status.DONE
			: ready || boostReady ? V2Tile.Status.READY : V2Tile.Status.PLAIN);
		tile.owned(obtained);
		tile.onRightClick(e -> contextMenu(item).show(e.getComponent(), e.getX(), e.getY()));
		tile.setToolTipText(tooltip(item, obtained, targeted, ready));
		if (item.getIconFile() != null)
		{
			tile.emblem(bundledIcon(item.getIconFile()));
		}
		else if (itemManager != null)
		{
			java.awt.Image emblem = sprites.get(item.icon(), -1, 32);
			if (emblem != null)
			{
				tile.emblem(emblem); // null keeps the tile's honest code placeholder
			}
		}
		return tile;
	}

	/** The chart node's geometry, and its no-art fallback code. */
	private static final int TILE_ART = 34;
	private static final int TILE_WIDTH = 38;

	private static String code(String name)
	{
		String[] words = name.split("\\s+");
		return (words.length > 1
			? "" + words[0].charAt(0) + words[1].charAt(0)
			: name.substring(0, Math.min(2, name.length())))
			.toUpperCase(java.util.Locale.ROOT);
	}

	private static final java.util.Map<String, java.awt.image.BufferedImage> ICON_CACHE = new java.util.HashMap<>();

	/** Bundled wiki object icon from /data/icons/ (POH furniture etc.). */
	private static java.awt.image.BufferedImage bundledIcon(String file)
	{
		return ICON_CACHE.computeIfAbsent(file, f ->
		{
			try (java.io.InputStream in = GearTab.class.getResourceAsStream("/data/icons/" + f))
			{
				return in != null ? javax.imageio.ImageIO.read(in) : null;
			}
			catch (java.io.IOException e)
			{
				return null; // letter-code fallback paints instead
			}
		});
	}

	/** Detected, manually marked, or implied by an obtained successor. */
	private boolean isObtained(GearProgressionPack.Item item)
	{
		return obtained.contains(item.getName());
	}

	private String tooltip(GearProgressionPack.Item item, boolean obtained, boolean targeted, boolean ready)
	{
		StringBuilder html = new StringBuilder("<html><b>")
			.append(item.getName()).append("</b>");
		// itemId is null for manual entries (POH furniture) — no plan line
		if (!obtained && item.getItemId() != null && plannedItem.test(item.getItemId()))
		{
			html.append("<br>In your current plan");
		}
		if (obtained)
		{
			html.append("<br>Obtained");
		}
		else if (ready)
		{
			html.append("<br>Requirements met - ready to obtain");
		}
		else
		{
			html.append("<br>Missing:");
			for (Requirement req : requirement(item).missing(state))
			{
				html.append("<br>- ").append(req.describe());
				// a boost you already have access to closes this gap
				if (!req.isMet(state) && req.isMetWithBoosts(state, boosts))
				{
					net.runelite.api.Skill skill = req.boostableSkill();
					List<String> sources = skill == null ? List.of()
						: com.ironhub.requirements.Boosts.describe(boostsPack, state, skill);
					html.append(" - boostable")
						.append(sources.isEmpty() ? "" : " with " + String.join(", ", sources));
				}
			}
		}
		// where the item comes from (the KB projection) — the chart names the
		// TARGET, this names the road to it
		if (!obtained && item.getItemId() != null && itemSources != null)
		{
			String sources = itemSources.sourceLine(item.getItemId(), state,
				state.getItemSourcePref(item.getItemId()));
			if (sources != null)
			{
				html.append("<br>").append(sources);
			}
		}
		if (!obtained && item.isManual())
		{
			html.append("<br>Not auto-detected - right-click to mark obtained");
		}
		if (targeted)
		{
			html.append("<br><i>Targeted - click to remove from goal planner</i>");
		}
		else if (!obtained)
		{
			html.append("<br><i>Click to add to goal planner</i>");
		}
		return html.append("</html>").toString();
	}

	private Requirement requirement(GearProgressionPack.Item item)
	{
		return Requirements.allOf(item.getRequirements().stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}

	private JPopupMenu contextMenu(GearProgressionPack.Item item)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem wiki = new JMenuItem("Open wiki page");
		wiki.addActionListener(e ->
			LinkBrowser.browse("https://oldschool.runescape.wiki/w/" + item.wikiPage()));
		menu.add(wiki);
		boolean marked = state.isUnlocked(item.markKey());
		if (marked)
		{
			JMenuItem unmark = new JMenuItem("Unmark as obtained");
			unmark.addActionListener(e -> state.setUnlocked(item.markKey(), false));
			menu.add(unmark);
		}
		else if (!isObtained(item)) // detected/implied ownership needs no manual mark
		{
			JMenuItem mark = new JMenuItem("Mark as obtained");
			mark.addActionListener(e ->
			{
				state.setUnlocked(item.markKey(), true);
				state.selectGoal(item.goalId(), false); // an obtained item is no longer a target
			});
			menu.add(mark);
		}
		return menu;
	}

	/** Subtle down-arrow between consecutive groups (the progression flow). */
	private static class Arrow extends JComponent
	{
		private static final int HEIGHT = 11;
		private final OsrsTheme theme;

		Arrow(OsrsTheme theme)
		{
			this.theme = theme;
			setPreferredSize(new Dimension(TILE_WIDTH, HEIGHT));
			setMinimumSize(new Dimension(TILE_WIDTH, HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
			setAlignmentX(LEFT_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			int x = TILE_WIDTH / 2; // aligned under the first tile column
			g.setColor(theme.edgeLight);
			g.drawLine(x, 1, x, HEIGHT - 4);
			g.drawLine(x - 3, HEIGHT - 6, x, HEIGHT - 3);
			g.drawLine(x + 3, HEIGHT - 6, x, HEIGHT - 3);
		}
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
