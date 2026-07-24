package com.ironhub.ui.v2;

import java.awt.Component;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * The containers and the gaps. Small, and load-bearing.
 *
 * <p><b>Never add a bare {@code Box.createVerticalStrut} to a V2 column.</b>
 * A strut's default alignmentX is CENTER, and BoxLayout aligns a column's
 * children by making their alignment points coincide — so one centre-aligned
 * child, even a zero-width spacer, shifts every left-aligned sibling. In the
 * first render of Design lab V2 that pushed whole tile rows 56px to the
 * right while the labels above them stayed put, which is precisely the
 * "things don't line up" reading this system exists to remove.
 *
 * <p>{@link #gap} returns a strut that claims LEFT_ALIGNMENT, so a column
 * built from these helpers has one left edge by construction.
 */
public final class V2Layout
{
	private V2Layout()
	{
	}

	/** A vertical stack — the default container for a section. */
	public static JPanel column()
	{
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		column.setAlignmentX(Component.LEFT_ALIGNMENT);
		return column;
	}

	/** A horizontal run of controls. Add {@link #glue()} last so they pack
	 *  left instead of spreading. */
	public static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	/** Vertical space, left-aligned so it cannot drag the column off its edge. */
	public static Component gap(int px)
	{
		Box.Filler strut = (Box.Filler) Box.createVerticalStrut(px);
		strut.setAlignmentX(Component.LEFT_ALIGNMENT);
		return strut;
	}

	/** Horizontal space inside a row. */
	public static Component hgap(int px)
	{
		return Box.createHorizontalStrut(px);
	}

	/** Pushes what precedes it to the left. */
	public static Component glue()
	{
		return Box.createHorizontalGlue();
	}
}
