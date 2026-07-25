package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * Every fixed-art button in the system: the utility squares (wrench, help,
 * menu, close, cancel), the steppers, the arrows, the wiki button, the icon
 * squares. They differ only in which sprite they wear, so they are one atom
 * parameterised by art rather than six near-identical classes — six classes
 * is how two buttons end up behaving differently.
 *
 * <p><b>States come from the art, never from a tint.</b> The constructor
 * looks for {@code <key>_hovered} and {@code <key>_selected} in the curated
 * set and offers exactly what it finds. OSRS has no mouse pointer, so the
 * game never drew most hover states; inventing one is how the panel stops
 * feeling like the client (DESIGN-SYSTEM-V2 §8).
 */
public class V2SpriteButton extends JComponent
{
	// the curated art, named once so no module types a sprite path
	public static final String WRENCH = "ui/buttons_square/wrench";
	public static final String HELP = "ui/buttons_square/help";
	public static final String MENU = "ui/buttons_square/menu";
	public static final String CLOSE = "ui/buttons_square/close_small";
	public static final String CANCEL = "ui/buttons_square/cancel_button";
	public static final String INCREMENT = "ui/buttons_square/increment_button";
	public static final String DECREMENT = "ui/buttons_square/decrement_button";
	public static final String PLUS = "ui/plus_minus/plus";
	public static final String MINUS = "ui/plus_minus/minus";
	public static final String PLUS_LARGE = "ui/plus_minus/plus_icon";
	public static final String MINUS_LARGE = "ui/plus_minus/minus_icon";
	public static final String ARROW_UP = "ui/arrows/arrow_up";
	public static final String ARROW_DOWN = "ui/arrows/arrow_down";
	public static final String ARROW_LEFT = "ui/arrows/arrow_left";
	public static final String ARROW_RIGHT = "ui/arrows/arrow_right";
	public static final String BACK = "ui/arrows/left_arrow";
	public static final String WIKI = "icons/wiki/wiki_deselected";
	public static final String SQUARE = "ui/buttons/button";
	public static final String SQUARE_SMALL = "ui/buttons/unknown_square_small";
	public static final String SQUARE_LARGE = "ui/buttons/options_square";

	/**
	 * The art the game ships as a second state. Luke's reading (2026-07-25):
	 * these are the PRESSED look, not a pointer state — OSRS has no pointer —
	 * so they show while the button is held, and hover is a highlight wash
	 * over the resting art instead. {@code menu_selected} is the menu
	 * button's pressed art under a misleading name, so it resolves here too.
	 */
	private static final String[] PRESS_SUFFIXES = {"_hovered", "_selected"};

	private final OsrsTheme theme;
	private final String key;
	private final String pressKey;
	private final String selectedKey;
	private final boolean uniformCell;
	private boolean hover;
	private boolean down;
	private boolean selected;

	public V2SpriteButton(OsrsTheme theme, String key, Runnable onPress)
	{
		this(theme, key, true, onPress);
	}

	/**
	 * @param uniformCell true renders the art centred in one shared cell so a
	 *                    row of utility buttons lines up even though the
	 *                    sprites are 16-21px (Luke, 2026-07-25); false keeps
	 *                    the sprite's own footprint, for the big squares.
	 */
	public V2SpriteButton(OsrsTheme theme, String key, boolean uniformCell, Runnable onPress)
	{
		this.theme = theme;
		this.key = key;
		this.uniformCell = uniformCell;
		String press = null;
		for (String suffix : PRESS_SUFFIXES)
		{
			if (V2Sprites.has(key + suffix))
			{
				press = key + suffix;
				break;
			}
		}
		this.pressKey = press;
		// the wiki button genuinely toggles, and its second state is named for
		// what it means rather than for the pointer
		this.selectedKey = key.equals(WIKI) ? "icons/wiki/wiki_selected"
			: V2Sprites.has(key + "_selected") && !key.startsWith("ui/buttons_square/")
			? key + "_selected" : null;
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hover = true;
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = false;
				down = false;
				repaint();
			}

			// mousePressed, not mouseClicked: a click that drifts a pixel
			// between press and release never fires
			@Override
			public void mousePressed(MouseEvent e)
			{
				down = true;
				repaint();
				if (onPress != null)
				{
					onPress.run();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				down = false;
				repaint();
			}
		});
	}

	/** True when the curated art has a pressed state for this button. */
	public boolean hasPressArt()
	{
		return pressKey != null;
	}

	/** True when the art has a selected state — a toggle can only be built
	 *  on art that can show it. */
	public boolean canSelect()
	{
		return selectedKey != null;
	}

	public void setSelected(boolean selected)
	{
		if (selectedKey == null)
		{
			throw new IllegalStateException(key + " has no selected state in the curated set");
		}
		if (this.selected != selected)
		{
			this.selected = selected;
			repaint();
		}
	}

	public boolean isSelected()
	{
		return selected;
	}

	private String currentKey()
	{
		if (selected && selectedKey != null)
		{
			return selectedKey;
		}
		return down && pressKey != null ? pressKey : key;
	}

	/** V2 tooltips wear the Card (§9) — Swing routes them through here. */
	@Override
	public javax.swing.JToolTip createToolTip()
	{
		V2Tooltip tip = new V2Tooltip(theme);
		tip.setComponent(this);
		return tip;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		String key = currentKey();
		// the wash is baked into a copy of the sprite, so it lights the glyph
		// and not the transparent air around it
		BufferedImage art = hover && !down
			? V2Sprites.highlighted(theme, key) : V2Sprites.get(theme, key);
		// native size, centred in whatever the layout gave us — never scaled
		g.drawImage(art, (getWidth() - art.getWidth()) / 2,
			(getHeight() - art.getHeight()) / 2, null);
	}

	@Override
	public Dimension getPreferredSize()
	{
		V2Sprites.Meta meta = V2Sprites.meta(key);
		if (!uniformCell)
		{
			return new Dimension(meta.width(), meta.height());
		}
		return new Dimension(Math.max(meta.width(), V2Tokens.UTILITY_CELL),
			Math.max(meta.height(), V2Tokens.UTILITY_CELL));
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
