package com.ironhub.ui.components;

import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Tile;
import java.awt.Dimension;
import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * A three-level tile hierarchy in the stonework skin, the shared design
 * system behind the Gear library and the Build modules (House, Boats):
 *
 * <ol>
 *   <li>a grid of top tiles (rooms / boats),</li>
 *   <li>clicking one expands a sub-grid of its member tiles (hotspots /
 *       facilities) directly beneath its row,</li>
 *   <li>clicking a member opens its full-width detail card below its sub-row
 *       — the details and options for that thing.</li>
 * </ol>
 *
 * <p>One top is expanded and one member selected at a time (the Gear rule).
 * The owning tab feeds a fresh model on every state change via
 * {@link #setModel}; this component keeps the expand/select choice across
 * models and re-renders itself on a tile click. Icons come from the tab's
 * shared {@link SpriteCache} so a late sprite arrival repaints here too.
 */
public class TileTree extends JPanel
{
	/** Top tiles wear the clog page-card grammar since 2026-07-28: two
	 *  perfect-square Cards across the column. */
	private static final int TOP_COLS = 2;
	private static final int TOP_TILE = 106;
	private static final int TOP_EMBLEM = 44;
	/** Member tiles: 2-wide and taller than the old 3-wide smalls (Luke,
	 *  2026-07-28) — two across the indented column, a deeper art band. */
	private static final int SUB_COLS = 2;
	private static final int INDENT = 8;
	private static final int GAP = 3;
	/** The member tile's ART height; the caption sits under it. */
	private static final int TILE_ART = 48;
	private static final int TILE_WIDTH = 102;

	/** A member tile (level 2) and the detail it opens (level 3). */
	public static final class Leaf
	{
		public String id;
		public String label;
		public String tooltip;
		public Integer icon;      // item id, or null for a caption-only tile
		public boolean owned;     // green corner tick
		public boolean tracked;   // orange bevel
		public int badge;         // top-left count, shown when > 1
		public Supplier<JComponent> detail;   // built fresh each render
	}

	/** A top tile (level 1) and its members. */
	public static final class Top
	{
		public String id;
		public String label;
		public String tooltip;
		public Integer icon;
		public boolean owned;
		public boolean tracked;
		public int badge;
		public List<Leaf> leaves = new ArrayList<>();
	}

	private final OsrsTheme theme;
	private final SpriteCache sprites;
	private List<Top> model = List.of();
	private String expandedTop;
	private String selectedLeaf;

	public TileTree(OsrsTheme theme, SpriteCache sprites)
	{
		this.theme = theme;
		this.sprites = sprites;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
	}

	/** Replace the model and re-render, keeping the expand/select choice when
	 *  those ids still exist. */
	public void setModel(List<Top> tops)
	{
		this.model = tops;
		Top open = topById(expandedTop);
		if (open == null)
		{
			expandedTop = null;
			selectedLeaf = null;
		}
		else if (selectedLeaf != null && open.leaves.stream().noneMatch(l -> l.id.equals(selectedLeaf)))
		{
			selectedLeaf = null;
		}
		render();
	}

	// ── test seams ────────────────────────────────────────────────────────
	public void expandForTest(String topId)
	{
		expandedTop = topId;
		selectedLeaf = null;
		render();
	}

	public void selectForTest(String topId, String leafId)
	{
		expandedTop = topId;
		selectedLeaf = leafId;
		render();
	}

	private Top topById(String id)
	{
		if (id == null)
		{
			return null;
		}
		for (Top t : model)
		{
			if (t.id.equals(id))
			{
				return t;
			}
		}
		return null;
	}

	private void render()
	{
		removeAll();
		for (int start = 0; start < model.size(); start += TOP_COLS)
		{
			List<Top> rowTops = model.subList(start, Math.min(start + TOP_COLS, model.size()));
			JPanel row = row(0);
			// glue BOTH sides — card rows centre in the column
			row.add(Box.createHorizontalGlue());
			for (int i = 0; i < rowTops.size(); i++)
			{
				if (i > 0)
				{
					row.add(Box.createHorizontalStrut(com.ironhub.ui.v2.V2Tokens.ROW));
				}
				row.add(topTile(rowTops.get(i)));
			}
			row.add(Box.createHorizontalGlue());
			cap(row);
			add(row);
			add(Box.createVerticalStrut(GAP));
			// the expanded top's sub-grid lands under its whole row (Gear grammar)
			for (Top top : rowTops)
			{
				if (top.id.equals(expandedTop))
				{
					addSubGrid(top);
				}
			}
		}
		revalidate();
		repaint();
	}

	/** A top as a square CARD in the clog page-grid grammar: emblem, bold
	 *  inside caption on the orange/green scale, corner count of built
	 *  members, meter strip, no tooltip. */
	private V2Tile topTile(Top top)
	{
		Image icon = top.icon != null ? sprites.getBox(top.icon, TOP_EMBLEM) : null;
		boolean open = top.id.equals(expandedTop);
		int done = (int) top.leaves.stream().filter(l -> l.owned).count();
		int total = top.leaves.size();
		boolean complete = total > 0 && done >= total;
		java.awt.Color cornerDone = complete ? com.ironhub.ui.v2.V2Tokens.DONE
			: done == 0 ? com.ironhub.ui.v2.V2Tokens.BLOCKED : com.ironhub.ui.v2.V2Tokens.ACTION;
		java.awt.Color cornerRest = complete ? com.ironhub.ui.v2.V2Tokens.DONE
			: com.ironhub.ui.v2.V2Tokens.ACTION;
		return new V2Tile(theme, icon, top.label, TOP_TILE, () ->
			{
				expandedTop = open ? null : top.id;
				selectedLeaf = null;
				render();
			})
			.card().captionLines(2).captionInside()
			.captionStatus(complete ? com.ironhub.ui.v2.V2Tokens.DONE
				: com.ironhub.ui.v2.V2Tokens.ACTION)
			.corner(String.valueOf(done), cornerDone, "/" + total, cornerRest)
			.selected(open)
			.meter(total == 0 ? Double.NaN : (double) done / total);
	}

	/**
	 * The shared tile. V1's {@code IconTile} was a second tile class with its
	 * own painter; it is gone (Luke's Progression pass, 2026-07-26) and this
	 * is {@code V2Tile} wearing the same three states: {@code tracked} is the
	 * READY status edge, {@code owned} the curated corner tick, {@code
	 * selected} the lit bevel.
	 */
	private V2Tile tile(String label, Image icon, boolean owned, boolean tracked,
		boolean selected, int badge, String tooltip, Runnable onClick)
	{
		V2Tile tile = new V2Tile(theme, icon, label, TILE_ART, onClick)
			.width(TILE_WIDTH).captionLines(2).owned(owned).selected(selected).badge(badge);
		tile.status(tracked ? V2Tile.Status.READY : V2Tile.Status.PLAIN);
		tile.setToolTipText(tooltip);
		return tile;
	}

	private void addSubGrid(Top top)
	{
		for (int start = 0; start < top.leaves.size(); start += SUB_COLS)
		{
			List<Leaf> rowLeaves = top.leaves.subList(start, Math.min(start + SUB_COLS, top.leaves.size()));
			JPanel row = row(INDENT);
			for (int i = 0; i < rowLeaves.size(); i++)
			{
				if (i > 0)
				{
					row.add(Box.createHorizontalStrut(GAP));
				}
				row.add(leafTile(rowLeaves.get(i)));
			}
			row.add(Box.createHorizontalGlue());
			cap(row);
			add(row);
			add(Box.createVerticalStrut(GAP));
			// the selected member's detail card lands under its sub-row
			for (Leaf leaf : rowLeaves)
			{
				if (leaf.id.equals(selectedLeaf) && leaf.detail != null)
				{
					JComponent card = leaf.detail.get();
					if (card != null)
					{
						JPanel holder = new JPanel(new java.awt.BorderLayout());
						holder.setOpaque(false);
						holder.setAlignmentX(LEFT_ALIGNMENT);
						holder.setBorder(new EmptyBorder(0, INDENT, GAP, 0));
						holder.add(card, java.awt.BorderLayout.CENTER);
						cap(holder);
						add(holder);
					}
				}
			}
		}
	}

	private V2Tile leafTile(Leaf leaf)
	{
		Image icon = leaf.icon != null ? sprites.get(leaf.icon, -1, 32) : null;
		boolean sel = leaf.id.equals(selectedLeaf);
		return tile(leaf.label, icon, leaf.owned, leaf.tracked, sel, leaf.badge, leaf.tooltip,
			() ->
			{
				selectedLeaf = sel ? null : leaf.id;
				render();
			});
	}

	private JPanel row(int leftIndent)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		if (leftIndent > 0)
		{
			row.setBorder(new EmptyBorder(0, leftIndent, 0, 0));
		}
		return row;
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
