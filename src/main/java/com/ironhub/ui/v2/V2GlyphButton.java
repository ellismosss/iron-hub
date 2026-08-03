package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The pressable letter glyph — the bare "+" / "×" affordance a row carries
 * for track-this / remove-this actions: faint until hovered (then heading
 * orange), pixel-font crisp, a tooltip naming the action.
 *
 * <p>ONE atom because thirteen files had hand-rolled this exact control
 * with drifting details (crisp calls, hover colours, event consumption) —
 * the X1 sweep's report-only finding, unified on Luke's word (2026-08-03).
 * The boxed letters (the wiki "W") are {@code V2SpriteButton.letter}; the
 * Goals hub's red remove-cross and painted pin keep their own approved
 * looks and are not this atom.
 *
 * <p>Presses fire on LEFT press only and consume the event, so a row that
 * relays clicks to itself can never also fire under the glyph (the
 * V2ChipRow rule). Callers whose row-click helpers skip marked children
 * still set their own marker property on the instance, exactly as before.
 */
public class V2GlyphButton extends OsrsLabel
{
	public V2GlyphButton(String letter, String tooltip, Runnable onPress)
	{
		super(letter, OsrsSkin.FAINT, OsrsSkin.font());
		setToolTipText(tooltip);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				setColor(OsrsSkin.TITLE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setColor(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || onPress == null)
				{
					return;
				}
				onPress.run();
				e.consume();
			}
		});
	}
}
