package com.ironhub.modules.supplies;

import com.ironhub.data.SuppliesPack;
import com.ironhub.state.AccountState;
import com.ironhub.state.GoalSeeds;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2TextField;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
import com.ironhub.ui.v2.V2Well;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;

/**
 * Supplies runway tab: category tiles across the top, and below them either
 * the selected category's watchlist (curated top-20 by default, editable) or
 * — when the search box has text — matching supplies to add.
 *
 * <p>Each watchlist row: item sprite · name · how many you own · an editable
 * TARGET amount · remove · track-restock-goal. Below the target the row goes
 * red; at or above it stays a light colour (never green — the old tab used
 * OsrsSkin.VALUE, which is green, and Luke wanted the "you're fine" state to
 * read as neutral). The target doubles as the red threshold and the restock
 * goal's amount — one number, so the row stays inside 225px.
 *
 * <p>Frameless — the host's header plate names the module.
 */
class RunwayTab extends JPanel
{
	private final AccountState state;
	private final SuppliesRunwayModule module;
	private final OsrsTheme theme;
	private final Runnable listener = RebuildGate.install(this, this::rebuild);
	private final SpriteCache sprites;
	private final SuppliesPack pack;

	private final JPanel list = new JPanel();
	private final V2TextField search;
	private final List<V2Tile> tiles = new ArrayList<>();
	private final List<String> categoryKeys = new ArrayList<>();
	private String selectedCategory;

	/** The target field being edited; rebuilds defer until focus leaves it,
	 *  so a state notification can't wipe the number mid-typing. */
	private JComponent editingField;
	private boolean rebuildDeferred;

	RunwayTab(AccountState state, ItemManager itemManager, SuppliesRunwayModule module, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.sprites = new SpriteCache(itemManager, listener);
		this.pack = module.pack();
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new javax.swing.border.EmptyBorder(4, 4, 4, 4));

		if (pack != null && !pack.categories.isEmpty())
		{
			selectedCategory = pack.categories.get(0).key;
			JPanel strip = new JPanel(new GridLayout(0, 4, V2Tokens.ROW, V2Tokens.ROW));
			strip.setOpaque(false);
			strip.setAlignmentX(LEFT_ALIGNMENT);
			for (SuppliesPack.Category c : pack.categories)
			{
				categoryKeys.add(c.key);
				Image icon = sprites.get(c.icon, -1, 26);
				V2Tile tile = new V2Tile(theme, icon, c.name, TILE_ART,
					() -> selectCategory(c.key)).width(TILE_WIDTH)
					.selected(c.key.equals(selectedCategory));
				tile.setToolTipText(c.name);
				tiles.add(tile);
				strip.add(tile);
			}
			cap(strip);
			add(strip);
			add(Box.createVerticalStrut(4));

			search = new V2TextField(theme, "Search consumables & resources…", null);
			search.editor().getDocument().addDocumentListener(new DocumentListener()
			{
				public void insertUpdate(DocumentEvent e) { rebuild(); }
				public void removeUpdate(DocumentEvent e) { rebuild(); }
				public void changedUpdate(DocumentEvent e) { rebuild(); }
			});
			add(search);
			add(Box.createVerticalStrut(4));
		}
		else
		{
			search = null;
		}

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
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

	// ── test seams ────────────────────────────────────────────────────────
	void selectCategoryForTest(String key)
	{
		selectCategory(key);
	}

	void searchForTest(String query)
	{
		if (search != null)
		{
			search.setText(query);
		}
	}

	private void selectCategory(String key)
	{
		selectedCategory = key;
		for (int i = 0; i < tiles.size(); i++)
		{
			tiles.get(i).selected(categoryKeys.get(i).equals(key));
		}
		if (search != null && !search.getText().isEmpty())
		{
			search.setText("");   // fires the document listener -> rebuild
		}
		else
		{
			rebuild();
		}
	}

	/** The category strip is built once in the constructor — icons that had
	 *  not resolved then (login screen, first mount) must land on the
	 *  existing tiles when the cache's arrival callback rebuilds. */
	private void refreshCategoryIcons()
	{
		if (pack == null)
		{
			return;
		}
		for (int i = 0; i < tiles.size() && i < pack.categories.size(); i++)
		{
			Image icon = sprites.get(pack.categories.get(i).icon, -1, 26);
			if (icon != null)
			{
				tiles.get(i).emblem(icon);
			}
		}
	}

	private void rebuild()
	{
		if (editingField != null && editingField.hasFocus())
		{
			rebuildDeferred = true;
			return;
		}
		refreshCategoryIcons();
		list.removeAll();
		if (pack == null || selectedCategory == null)
		{
			list.add(note("Supplies catalog unavailable."));
			list.revalidate();
			list.repaint();
			return;
		}
		list.add(Box.createVerticalStrut(2));
		String query = search == null ? "" : search.getText().trim();
		if (!query.isEmpty())
		{
			addSearchResults(query);
		}
		else
		{
			addWatchlist(selectedCategory);
		}
		list.revalidate();
		list.repaint();
	}

	/** The tracked items in the selected category (defaults minus removals,
	 *  plus additions), each row editable. */
	private void addWatchlist(String categoryKey)
	{
		List<SuppliesPack.Item> items = module.watchlist(categoryKey);
		if (items.isEmpty())
		{
			list.add(note("Nothing tracked here yet — search above to add a "
				+ "supply, or bring back one you removed."));
			return;
		}
		V2Surface group = group();
		for (SuppliesPack.Item item : items)
		{
			group.add(watchRow(item));
		}
		cap(group);
		list.add(group);
	}

	/** Cross-category matches to add, capped so a broad query stays bounded. */
	private void addSearchResults(String query)
	{
		List<SuppliesPack.Item> results = pack.search(query);
		if (results.isEmpty())
		{
			list.add(note("No supplies match that search."));
			return;
		}
		V2Surface group = group();
		int cap = 40;
		for (int i = 0; i < Math.min(cap, results.size()); i++)
		{
			group.add(searchRow(results.get(i)));
		}
		cap(group);
		list.add(group);
		if (results.size() > cap)
		{
			list.add(note((results.size() - cap) + " more — refine your search."));
		}
	}

	/** sprite · name · owned · target field · remove · restock-goal. */
	private JComponent watchRow(SuppliesPack.Item item)
	{
		boolean isDefault = Boolean.TRUE.equals(item.isDefault);
		int owned = state.canonicalStock(item.id);
		int target = state.getSupplyThreshold(item.id);
		boolean low = target > 0 && owned < target;
		java.awt.Color colour = low ? UiTokens.STATUS_WARNING : OsrsSkin.MUTED;

		JPanel row = row();
		row.add(itemIcon(item.id));
		row.add(Box.createHorizontalStrut(4));
		OsrsLabel name = new OsrsLabel(item.name, colour, OsrsSkin.font());
		String tip = item.name + " — you own " + owned
			+ (target > 0 ? " · target " + target + (low ? " (low)" : "") : "");
		name.setToolTipText(tip);
		row.add(name.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());

		OsrsLabel ownedLabel = new OsrsLabel(String.valueOf(owned), colour, OsrsSkin.smallFont());
		ownedLabel.setToolTipText("You own " + owned);
		row.add(ownedLabel);
		row.add(Box.createHorizontalStrut(5));

		row.add(targetField(item, target));
		row.add(Box.createHorizontalStrut(4));
		row.add(removeGlyph(item, isDefault));
		row.add(Box.createHorizontalStrut(3));
		row.add(goalGlyph(item, target));
		cap(row);
		return row;
	}

	/** sprite · name · its category · add/remove-from-list. */
	private JComponent searchRow(SuppliesPack.Item item)
	{
		boolean isDefault = Boolean.TRUE.equals(item.isDefault);
		boolean tracked = state.isSupplyTracked(item.id, isDefault);
		JPanel row = row();
		row.add(itemIcon(item.id));
		row.add(Box.createHorizontalStrut(4));
		OsrsLabel name = new OsrsLabel(item.name, OsrsSkin.MUTED, OsrsSkin.font());
		row.add(name.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		SuppliesPack.Category cat = pack.category(item.category);
		if (cat != null)
		{
			row.add(new OsrsLabel(cat.name, OsrsSkin.FAINT, OsrsSkin.smallFont()));
			row.add(Box.createHorizontalStrut(5));
		}
		row.add(glyph(tracked ? "×" : "+",
			tracked ? item.name + " — tracked; click to remove"
				: "Add " + item.name + (cat != null ? " to " + cat.name : ""),
			() ->
			{
				if (tracked)
				{
					state.untrackSupply(item.id, isDefault);
				}
				else
				{
					state.trackSupply(item.id, isDefault);
				}
			}));
		cap(row);
		return row;
	}

	/** The editable target amount: below it the row is red, at/above light;
	 *  it is also the amount a restock goal stocks to. */
	private JComponent targetField(SuppliesPack.Item item, int target)
	{
		V2TextField field = V2TextField.plain(theme, "min", null);
		field.setText(target > 0 ? String.valueOf(target) : "");
		field.setToolTipText("Target amount — red below this, and the restock "
			+ "goal's amount");
		Runnable commit = () ->
		{
			int value;
			try
			{
				String t = field.getText().trim();
				value = t.isEmpty() ? 0 : Math.max(0, Integer.parseInt(t));
			}
			catch (NumberFormatException e)
			{
				return;   // ignore junk; the field keeps what was typed
			}
			state.setSupplyThreshold(item.id, value);
		};
		field.editor().addActionListener(e -> commit.run());
		field.editor().addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				editingField = field;
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				commit.run();
				if (editingField == field)
				{
					editingField = null;
				}
				if (rebuildDeferred)
				{
					rebuildDeferred = false;
					rebuild();
				}
			}
		});
		int w = field.getFontMetrics(field.getFont()).stringWidth("9999") + 12;
		Dimension size = new Dimension(w, field.getPreferredSize().height);
		JPanel holder = new JPanel(new java.awt.BorderLayout());
		holder.setOpaque(false);
		holder.add(field);
		holder.setPreferredSize(size);
		holder.setMaximumSize(size);
		holder.setMinimumSize(size);
		return holder;
	}

	private JLabel removeGlyph(SuppliesPack.Item item, boolean isDefault)
	{
		return glyph("×", "Remove " + item.name + " from the list",
			() -> state.untrackSupply(item.id, isDefault));
	}

	/** Track (or untrack) a restock goal to the item's target amount. */
	private JLabel goalGlyph(SuppliesPack.Item item, int target)
	{
		String goalId = "supply:" + item.id;
		boolean isGoal = state.getGoalSeeds().containsKey(goalId);
		if (isGoal)
		{
			return glyph("×", item.name + " — restock goal tracked; click to untrack",
				() -> state.removeGoalSeed(goalId));
		}
		String tip = target > 0
			? "Track restocking " + item.name + " to " + target
			: "Set a target amount first, then track a restock goal";
		return glyph("+", tip, () ->
		{
			if (target > 0)
			{
				state.addGoalSeed(GoalSeeds.supply(item.id, item.name, target));
			}
		});
	}

	// ── small shared bits ─────────────────────────────────────────────────

	private JLabel itemIcon(int itemId)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(16, 16));
		label.setMinimumSize(new Dimension(16, 16));
		label.setMaximumSize(new Dimension(16, 16));
		Image image = sprites.getBox(itemId, 16);
		if (image != null)
		{
			label.setIcon(new javax.swing.ImageIcon(image));
		}
		return label;
	}

	/** The +/× affordance in skin colours (faint, orange on hover). */
	private static JLabel glyph(String text, String tooltip, Runnable onClick)
	{
		JLabel glyph = new JLabel(text);
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tooltip);
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e) { glyph.setForeground(OsrsSkin.TITLE); }

			@Override
			public void mouseExited(MouseEvent e) { glyph.setForeground(OsrsSkin.FAINT); }

			@Override
			public void mousePressed(MouseEvent e) { onClick.run(); }
		});
		return glyph;
	}

	private JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	/** A list Well at the list inset (§4) — a group of supply rows. */
	private V2Surface group()
	{
		V2Surface group = V2Surface.well(theme);
		int inset = V2Well.CAP + V2Tokens.TIGHT;
		group.setBorder(new javax.swing.border.EmptyBorder(inset, inset, inset, inset));
		return group;
	}

	/** The category strip's tile geometry; the caption sits under the art. */
	private static final int TILE_ART = 38;
	private static final int TILE_WIDTH = 50;

	private JComponent note(String text)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(OsrsLabel.wrapped(text, 195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
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
