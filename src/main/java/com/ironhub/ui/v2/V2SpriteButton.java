package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

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
	/** The wiki mark on its own, square, with no toggle behind it — for a row
	 *  that just wants to open a page (Luke, 2026-07-25). {@link #WIKI} is the
	 *  40x14 two-state button and is a different control. */
	public static final String WIKI_SMALL = "icons/wiki/wiki_small";
	/** The unchecked checkbox, borrowed as a plain framed square to put a
	 *  character in — the same art {@code V2Checkbox} wears with no tick. */
	public static final String EMPTY_BOX = "ui/checkbox/square_bordered_checkbox";
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
	/** Scale-to-fit box, 0 for the sprite's own size. See {@link #fit}. */
	private int box;
	/** Drawn over the art. See {@link #letter}. */
	private String letter;
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
				// left only: surfaces relay presses to themselves for their
				// right-click menus — a button must not also fire on those
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
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

	/**
	 * Render the art scaled to fit {@code box} pixels instead of at its own
	 * size — §2's emblem exception, NEAREST NEIGHBOUR through
	 * {@link V2Sprites#fitted}. A picture may be resized; a tiled surface may
	 * not, which is why this lives on the sprite button and nowhere else.
	 *
	 * <p>For art that ships bigger than the row it has to sit in:
	 * {@code wiki_small} is 26px, and the Goals footer it marks is a line of
	 * detail text (Luke, 2026-07-25: "it needs to be much smaller").
	 */
	public V2SpriteButton fit(int box)
	{
		this.box = box;
		revalidate();
		repaint();
		return this;
	}

	/**
	 * Draw a character over the art, in DETAIL — for a mark the curated set has
	 * no sprite for. A W on the empty checkbox is the wiki link (Luke,
	 * 2026-07-25); the set ships a 26px wiki badge that will not shrink to a
	 * text row cleanly, and a letter in the panel's own font will.
	 *
	 * <p>Centred on the MEASURED ink of the character itself, not on font
	 * metrics — see {@link V2Label#ink}, which exists because metrics put the
	 * progress bar's labels visibly low twice.
	 */
	public V2SpriteButton letter(String letter)
	{
		this.letter = letter;
		repaint();
		return this;
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
			? V2Sprites.highlighted(theme, key, box) : V2Sprites.fitted(theme, key, box);
		// centred in whatever the layout gave us, at its own size unless a fit
		// box was asked for
		g.drawImage(art, (getWidth() - art.getWidth()) / 2,
			(getHeight() - art.getHeight()) / 2, null);
		if (letter != null)
		{
			paintLetter((java.awt.Graphics2D) g);
		}
	}

	private void paintLetter(java.awt.Graphics2D g)
	{
		// the pixel font smears into LCD fringes unless the hint is off, and
		// Swing re-applies the look-and-feel's own over a Graphics hint
		g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setFont(V2Tokens.detailFont());
		int[] ink = V2Label.ink(g.getFont(), letter);
		int width = g.getFontMetrics().stringWidth(letter);
		int x = (getWidth() - width) / 2;
		int baseline = (getHeight() - ink[1]) / 2 - ink[0];
		g.setColor(com.ironhub.ui.osrs.OsrsSkin.TEXT_SHADOW);
		g.drawString(letter, x + 1, baseline + 1);
		g.setColor(hover ? V2Tokens.HEADING : V2Tokens.TEXT);
		g.drawString(letter, x, baseline);
	}

	@Override
	public Dimension getPreferredSize()
	{
		int width;
		int height;
		if (box > 0)
		{
			// the FITTED art's real size, not the box: fit preserves aspect, so
			// a non-square sprite lands inside the box on its short side
			BufferedImage art = V2Sprites.fitted(theme, key, box);
			width = art.getWidth();
			height = art.getHeight();
		}
		else
		{
			V2Sprites.Meta meta = V2Sprites.meta(key);
			width = meta.width();
			height = meta.height();
		}
		if (!uniformCell)
		{
			return new Dimension(width, height);
		}
		return new Dimension(Math.max(width, V2Tokens.UTILITY_CELL),
			Math.max(height, V2Tokens.UTILITY_CELL));
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
