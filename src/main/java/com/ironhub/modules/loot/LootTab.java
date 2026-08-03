package com.ironhub.modules.loot;

import com.ironhub.data.SlayerTasksPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Loot & supplies (reworked 2026-08-03, L1-L6): a monster tile grid by
 * recency (click a tile for its drops), the ECONOMICS card as the
 * centrepiece — drops value vs supplies cost vs net, priced at drop/use
 * time on the client thread — an all-time vs session scope, a picked-up
 * filter fed by {@link LootPickupTracker}'s confirmed classifications,
 * and the drops themselves as a Loot-Tracker-style grid of item tiles
 * (sprite with its stack count baked in). Frameless — the host's header
 * plate names the module.
 */
class LootTab extends JPanel
{
	/** The row-list law: honest cap + "+ N more". */
	private static final int MAX_ITEMS = 20;
	private static final int MAX_MONSTERS = 12;
	private static final int GRID_COLS = 5;
	private static final int CELL = 34;
	private static final int MONSTER_TILE = 30;

	private final AccountState state;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final com.ironhub.ui.components.SpriteCache sprites;
	private final SlayerTasksPack slayerPack; // monster icons where known

	private String selectedSource;
	/** 0 = all-time, 1 = session (L5). */
	private final V2ChipRow scope;
	/** Latching picked-up filter (L3). */
	private boolean pickedOnly;

	private final JPanel monsters = new JPanel();
	private final JPanel economics = new JPanel();
	private final JPanel controls = new JPanel();
	private final JPanel list = new JPanel();

	LootTab(AccountState state, ItemManager itemManager,
		com.ironhub.data.DataPack dataPack, OsrsTheme theme)
	{
		this.state = state;
		this.theme = theme;
		this.sprites = new com.ironhub.ui.components.SpriteCache(itemManager, listener);
		SlayerTasksPack pack = null;
		try
		{
			pack = dataPack == null ? null : dataPack.load("slayer-tasks", SlayerTasksPack.class);
		}
		catch (RuntimeException ignored)
		{
			// icons degrade to placeholders; the tab still works
		}
		this.slayerPack = pack;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		monsters.setLayout(new BoxLayout(monsters, BoxLayout.Y_AXIS));
		monsters.setOpaque(false);
		monsters.setAlignmentX(LEFT_ALIGNMENT);
		add(monsters);
		add(Box.createVerticalStrut(4));

		economics.setLayout(new BoxLayout(economics, BoxLayout.Y_AXIS));
		economics.setOpaque(false);
		economics.setAlignmentX(LEFT_ALIGNMENT);
		add(economics);
		add(Box.createVerticalStrut(4));

		scope = new V2ChipRow(theme, true, "All-time", "Session");
		scope.onChange(i -> rebuild());
		add(scope);
		add(Box.createVerticalStrut(4));

		controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
		controls.setOpaque(false);
		controls.setAlignmentX(LEFT_ALIGNMENT);
		add(controls);
		add(Box.createVerticalStrut(4));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		state.addListener(listener, AccountState.Topic.LOOT);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private boolean session()
	{
		return scope.selected() == 1;
	}

	void rebuild()
	{
		List<String> sources = new ArrayList<>(state.lootSources());
		// most recently killed first (L4) — recency reorders as kills happen
		sources.sort(Comparator.comparingLong((String s) -> -state.lootLastKill(s))
			.thenComparing(s -> s));
		if (selectedSource == null || !sources.contains(selectedSource))
		{
			selectedSource = sources.isEmpty() ? null : sources.get(0);
		}
		rebuildMonsters(sources);
		rebuildEconomics();
		rebuildControls();
		rebuildList();
		revalidate();
		repaint();
	}

	/** The monster tile grid (L4): icon tiles, recency-first, click to view. */
	private void rebuildMonsters(List<String> sources)
	{
		monsters.removeAll();
		if (sources.isEmpty())
		{
			monsters.add(faintLine("Kill something — drops are tracked automatically."));
			return;
		}
		JPanel grid = new JPanel(new java.awt.GridLayout(0, GRID_COLS, V2Tokens.ROW, V2Tokens.ROW));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		int shown = 0;
		for (String source : sources)
		{
			if (shown++ >= MAX_MONSTERS)
			{
				break;
			}
			int icon = monsterIconId(source);
			V2Tile tile = icon > 0
				? new V2Tile(theme, sprites.get(icon, -1, V2Tokens.TILE_ICON),
					null, MONSTER_TILE, () -> selectSource(source))
				: new V2Tile(theme, (java.awt.Image) null, null, MONSTER_TILE,
					() -> selectSource(source)).placeholder(initials(source));
			tile.selected(source.equals(selectedSource));
			tile.setToolTipText(source + " · " + kills(source)
				+ (kills(source) == 1 ? " kill" : " kills"));
			grid.add(tile);
		}
		int rows = (Math.min(sources.size(), MAX_MONSTERS) + GRID_COLS - 1) / GRID_COLS;
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			rows * (MONSTER_TILE + V2Tokens.ROW)));
		monsters.add(grid);
		if (sources.size() > MAX_MONSTERS)
		{
			monsters.add(Box.createVerticalStrut(2));
			monsters.add(faintLine("+ " + (sources.size() - MAX_MONSTERS)
				+ " more — killing one brings it to the front"));
		}
		monsters.revalidate();
		monsters.repaint();
	}

	private void selectSource(String source)
	{
		selectedSource = source;
		javax.swing.SwingUtilities.invokeLater(this::rebuild);
	}

	/** The economics card (L6) — the centrepiece: drops value, supplies
	 *  cost, net. Values are accumulated at drop/use time on the client
	 *  thread; drops recorded before value tracking began aren't priced,
	 *  and the tooltip says so. */
	private void rebuildEconomics()
	{
		economics.removeAll();
		if (selectedSource == null)
		{
			return;
		}
		long drops = session() ? state.sessionLootValueFor(selectedSource)
			: state.lootValueFor(selectedSource);
		long cost = session() ? state.sessionSuppliesValueFor(selectedSource)
			: state.suppliesValueFor(selectedSource);
		long net = drops - cost;
		com.ironhub.ui.v2.V2Surface card = com.ironhub.ui.v2.V2Surface.card(theme);
		JPanel head = row();
		head.add(new OsrsLabel(selectedSource, OsrsSkin.TITLE, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		head.add(Box.createHorizontalGlue());
		head.add(new OsrsLabel(kills(selectedSource)
			+ (kills(selectedSource) == 1 ? " kill" : " kills"),
			OsrsSkin.FAINT, OsrsSkin.smallFont()));
		cap(head);
		card.add(head);
		card.add(Box.createVerticalStrut(2));
		card.add(valueLine("Drops value", drops, OsrsSkin.BAR_TEXT));
		card.add(valueLine("Supplies cost", cost, OsrsSkin.BAR_TEXT));
		card.add(valueLine("Net", net,
			net >= 0 ? V2Tokens.DONE : V2Tokens.BLOCKED));
		card.setToolTipText("GE prices at drop/use time. Drops recorded before"
			+ " value tracking began aren't priced — the figures cover what"
			+ " the plugin watched.");
		cap(card);
		economics.add(card);
		economics.revalidate();
		economics.repaint();
	}

	private JComponent valueLine(String label, long gp, java.awt.Color colour)
	{
		JPanel line = row();
		line.add(new OsrsLabel(label, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		line.add(Box.createHorizontalGlue());
		line.add(new OsrsLabel(QuantityFormatter.quantityToStackSize(gp) + " gp",
			colour, OsrsSkin.boldFont()));
		cap(line);
		return line;
	}

	private void rebuildControls()
	{
		controls.removeAll();
		if (selectedSource == null)
		{
			return;
		}
		JComponent picked = V2ChipRow.toggle(theme, "Picked up only", null,
			OsrsSkin.smallFont(), pickedOnly, false, on ->
		{
			pickedOnly = on;
			javax.swing.SwingUtilities.invokeLater(this::rebuild);
		});
		picked.setToolTipText("Show only drops CONFIRMED picked up (tracking"
			+ " starts with this update; a drop whose fate the client could"
			+ " not see — teleporting away, hopping — stays unknown and is"
			+ " never guessed into either bucket)");
		controls.add(picked);
		controls.add(Box.createHorizontalGlue());
		cap(controls);
		controls.revalidate();
		controls.repaint();
	}

	/** The drop grid (L1): Loot-Tracker-style item tiles, sprite with the
	 *  stack count baked in, capped with an honest more-line. */
	private void rebuildList()
	{
		list.removeAll();
		if (selectedSource == null)
		{
			list.revalidate();
			list.repaint();
			return;
		}
		Map<Integer, Integer> loot = session()
			? state.sessionLootFor(selectedSource) : state.lootFor(selectedSource);
		if (pickedOnly)
		{
			// confirmed pickups only — all-time scope (classification has
			// no session split; honesty over symmetry)
			loot = state.lootPickedFor(selectedSource);
		}
		list.add(section(pickedOnly ? "Picked-up drops" : "All drops"));
		if (loot.isEmpty())
		{
			list.add(faintLine(pickedOnly
				? "No confirmed pickups yet — tracking began with this update."
				: session() ? "No drops this session." : "No drops recorded."));
		}
		else
		{
			list.add(itemGrid(loot));
			int more = loot.size() - MAX_ITEMS;
			if (more > 0)
			{
				list.add(Box.createVerticalStrut(2));
				list.add(faintLine("+ " + more + " more items"));
			}
		}

		Map<Integer, Integer> used = session()
			? state.sessionSuppliesFor(selectedSource) : state.suppliesFor(selectedSource);
		if (!used.isEmpty() && !pickedOnly)
		{
			list.add(section("Supplies used"));
			list.add(itemGrid(used));
			int more = used.size() - MAX_ITEMS;
			if (more > 0)
			{
				list.add(Box.createVerticalStrut(2));
				list.add(faintLine("+ " + more + " more items"));
			}
		}
		list.revalidate();
		list.repaint();
	}

	private JComponent itemGrid(Map<Integer, Integer> items)
	{
		List<Integer> ids = new ArrayList<>(items.keySet());
		Map<Integer, Integer> counts = items;
		ids.sort(Comparator.comparingInt((Integer id) -> -counts.get(id))
			.thenComparing(id -> state.itemName(id).toLowerCase(Locale.ROOT)));
		JPanel grid = new JPanel(new java.awt.GridLayout(0, GRID_COLS, V2Tokens.ROW, V2Tokens.ROW));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		int shown = 0;
		for (Integer id : ids)
		{
			if (shown++ >= MAX_ITEMS)
			{
				break;
			}
			int qty = counts.get(id);
			JLabel cell = new JLabel();
			Dimension size = new Dimension(CELL, CELL);
			cell.setPreferredSize(size);
			cell.setMinimumSize(size);
			cell.setMaximumSize(size);
			cell.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
			// the stack count bakes into the sprite — the Loot Tracker look
			java.awt.Image sprite = sprites.get(id, qty, CELL - 6);
			if (sprite != null)
			{
				cell.setIcon(new ImageIcon(sprite));
			}
			cell.setToolTipText(state.itemName(id) + " ×"
				+ QuantityFormatter.formatNumber(qty));
			grid.add(cell);
		}
		int rows = (Math.min(ids.size(), MAX_ITEMS) + GRID_COLS - 1) / GRID_COLS;
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * (CELL + V2Tokens.ROW)));
		return grid;
	}

	private int kills(String source)
	{
		return session() ? state.sessionKillCount(source) : state.getKillCount(source);
	}

	/** The slayer pack's task sprite for a monster name, or 0. */
	private int monsterIconId(String source)
	{
		if (slayerPack == null)
		{
			return 0;
		}
		SlayerTasksPack.Task task = slayerPack.task(source);
		if (task == null)
		{
			task = slayerPack.task(source + "s");
		}
		if (task == null && source.endsWith("s"))
		{
			task = slayerPack.task(source.substring(0, source.length() - 1));
		}
		return task == null ? 0 : task.icon;
	}

	/** "Kalphite Queen" -> "KQ" for a tile with no known sprite. */
	static String initials(String source)
	{
		StringBuilder out = new StringBuilder();
		for (String word : source.split("\\s+"))
		{
			if (!word.isEmpty() && Character.isLetter(word.charAt(0)))
			{
				out.append(Character.toUpperCase(word.charAt(0)));
			}
			if (out.length() == 2)
			{
				break;
			}
		}
		return out.length() == 0 ? "?" : out.toString();
	}

	private JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JComponent faintLine(String text)
	{
		JPanel holder = row();
		holder.add(OsrsLabel.wrapped(text, 195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	/** Section header in the skin grammar (the FarmingTab pattern). */
	private JComponent section(String text)
	{
		JPanel row = row();
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
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
