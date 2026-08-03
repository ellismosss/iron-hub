package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * One equipment or inventory slot, in the game's own shape: the 36px slot
 * tile, the item on top of it, and the slot's silhouette when it is empty —
 * the same reading the Worn Equipment interface gives.
 *
 * <p>The item image comes from the caller (ItemManager at runtime), never
 * from the curated set: item sprites are game content, not interface art.
 * A null item is an EMPTY slot and shows the silhouette; a slot with no
 * silhouette (an inventory square) just shows the tile.
 */
public class V2ItemSlot extends JComponent
{
	private static final String TILE = "icons/equipment/slot_tile";
	private static final String SELECTED = "icons/equipment/slot_selected";

	/** The eleven worn slots, by the curated silhouette that names them. */
	public enum Slot
	{
		HEAD, CAPE, NECK, WEAPON, TORSO, SHIELD, LEGS, HANDS, FEET, RING, AMMUNITION;

		String sprite()
		{
			return "icons/equipment/slot_" + name().toLowerCase();
		}
	}

	private final OsrsTheme theme;
	private final Slot slot;
	private BufferedImage item;
	private boolean selected;

	/** An inventory square: no silhouette, just the tile. */
	public V2ItemSlot(OsrsTheme theme, Runnable onPress)
	{
		this(theme, null, onPress);
	}

	public V2ItemSlot(OsrsTheme theme, Slot slot, Runnable onPress)
	{
		this.theme = theme;
		this.slot = slot;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onPress != null)
				{
					onPress.run();
				}
			}
		});
	}

	/** null = empty. */
	public V2ItemSlot item(BufferedImage item)
	{
		this.item = item;
		repaint();
		return this;
	}

	public V2ItemSlot selected(boolean selected)
	{
		this.selected = selected;
		repaint();
		return this;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		BufferedImage tile = V2Sprites.get(theme, selected ? SELECTED : TILE);
		g.drawImage(tile, 0, 0, null);
		BufferedImage content = item != null || slot == null ? item
			: V2Sprites.get(theme, slot.sprite());
		if (content != null)
		{
			g.drawImage(content, (tile.getWidth() - content.getWidth()) / 2,
				(tile.getHeight() - content.getHeight()) / 2, null);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		V2Sprites.Meta meta = V2Sprites.meta(TILE);
		return new Dimension(meta.width(), meta.height());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}
}
