package com.ironhub.modules.gear;

import com.ironhub.data.GearProgressionPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StonePanel;
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
import net.runelite.client.util.AsyncBufferedImage;
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
	private static final String[] FILTERS_BOTTOM = {"Utility", "POH", "Boat"};
	/** Tile-row budget: the panel minus the home border + stone frame +
	 *  this tab's own padding — the hub slot is narrower than 225px. */
	private static final int ROW_WIDTH = UiTokens.PANEL_WIDTH - 24;

	private final AccountState state;
	private final GearProgressionPack pack;
	private final com.ironhub.data.BoostsPack boostsPack;
	private final ItemManager itemManager; // null in headless tests
	private final OsrsTheme theme;
	private final StoneChipRow filterTop;
	private final StoneChipRow filterBottom;
	private final JPanel body = new JPanel();
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
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
		this.theme = theme;
		this.hideComplete = hideComplete;
		this.onHideCompleteChange = onHideCompleteChange;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		// frameless: content directly on the theme's backing so a hub host
		// connects with it as one block
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		filterTop = new StoneChipRow(theme, true, FILTERS_TOP);
		filterBottom = new StoneChipRow(theme, true, FILTERS_BOTTOM);
		filterTop.onChange(i -> selectFilter(true, i));
		filterBottom.onChange(i -> selectFilter(false, i));
		filterBottom.setSelected(-1);
		add(filterTop);
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		add(filterBottom);
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

	/** Toggle chip in the StoneChipRow grammar: select fill + title text when on. */
	private JComponent hideCompleteToggle()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new ToggleChip(theme, "Hide complete", hideComplete, on ->
		{
			hideComplete = on;
			onHideCompleteChange.accept(on);
			rebuild();
		}));
		row.add(Box.createHorizontalGlue());
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private void selectFilter(boolean top, int index)
	{
		if (index < 0) // programmatic deselect of the other row
		{
			return;
		}
		if (top)
		{
			filterBottom.setSelected(-1);
			filter = index == 0 ? null : FILTERS_TOP[index].toLowerCase(Locale.ROOT);
		}
		else
		{
			filterTop.setSelected(-1);
			filter = FILTERS_BOTTOM[index].toLowerCase(Locale.ROOT);
		}
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
				int perRow = (ROW_WIDTH + UiTokens.CHIP_GAP) / (ItemTile.W + UiTokens.CHIP_GAP);
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
		return filter == null || item.getCategories().contains(filter);
	}

	private ItemTile tile(GearProgressionPack.Item item)
	{
		boolean obtained = isObtained(item);
		boolean targeted = state.getSelectedGoals().contains(item.goalId());
		Requirement requirement = requirement(item);
		boolean ready = !obtained && requirement.isMet(state);
		boolean boostReady = !obtained && !ready && requirement.isMetWithBoosts(state, boosts);
		ItemTile tile = new ItemTile(theme, item.getName(), obtained, targeted, ready, boostReady,
			tooltip(item, obtained, targeted, ready),
			() ->
			{
				if (!obtained || targeted) // nothing to target once obtained
				{
					state.selectGoal(item.goalId(), !targeted);
				}
			},
			e -> contextMenu(item).show(e.getComponent(), e.getX(), e.getY()));
		if (item.getIconFile() != null)
		{
			tile.setIcon(bundledIcon(item.getIconFile()));
		}
		else if (itemManager != null)
		{
			AsyncBufferedImage icon = itemManager.getImage(item.icon());
			tile.setIcon(icon);
			icon.onLoaded(tile::repaint);
		}
		return tile;
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
			String sources = itemSources.sourceLine(item.getItemId());
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

	/** A single on/off chip in StoneChipRow's exact visual grammar. */
	private static class ToggleChip extends StonePanel
	{
		private final OsrsLabel label;
		private final java.util.function.Consumer<Boolean> onToggle;
		private boolean on;
		private boolean hover;

		ToggleChip(OsrsTheme theme, String text, boolean on, java.util.function.Consumer<Boolean> onToggle)
		{
			super(theme);
			this.on = on;
			this.onToggle = onToggle;
			setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			label = new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font());
			add(label);
			add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
			refresh();
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					refresh();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					refresh();
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					ToggleChip.this.on = !ToggleChip.this.on;
					refresh();
					ToggleChip.this.onToggle.accept(ToggleChip.this.on);
				}
			});
		}

		private void refresh()
		{
			setBackground(on ? theme.selectFill : hover ? theme.hoverFill : theme.boxFill);
			label.setColor(on ? OsrsSkin.TITLE : OsrsSkin.MUTED);
			repaint();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return getPreferredSize();
		}
	}

	/** Subtle down-arrow between consecutive groups (the progression flow). */
	private static class Arrow extends JComponent
	{
		private static final int HEIGHT = 11;
		private final OsrsTheme theme;

		Arrow(OsrsTheme theme)
		{
			this.theme = theme;
			setPreferredSize(new Dimension(ItemTile.W, HEIGHT));
			setMinimumSize(new Dimension(ItemTile.W, HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
			setAlignmentX(LEFT_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			int x = ItemTile.W / 2; // aligned under the first tile column
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
