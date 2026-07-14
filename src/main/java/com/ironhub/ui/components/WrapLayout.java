package com.ironhub.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * FlowLayout whose preferred size accounts for wrapping at the container's
 * current width — Swing's FlowLayout reports a single-row preferred size,
 * which clips wrapped rows inside vertical boxes. Used by the alert-chip
 * row (1b) and wrapping tile ladders (2a): never horizontal scroll.
 */
public class WrapLayout extends FlowLayout
{
	public WrapLayout(int align, int hgap, int vgap)
	{
		super(align, hgap, vgap);
	}

	@Override
	public Dimension preferredLayoutSize(Container target)
	{
		return layoutSize(target, true);
	}

	@Override
	public Dimension minimumLayoutSize(Container target)
	{
		Dimension minimum = layoutSize(target, false);
		minimum.width -= getHgap() + 1;
		return minimum;
	}

	private Dimension layoutSize(Container target, boolean preferred)
	{
		synchronized (target.getTreeLock())
		{
			int targetWidth = target.getSize().width;
			if (targetWidth == 0)
			{
				targetWidth = Integer.MAX_VALUE;
			}

			Insets insets = target.getInsets();
			int maxWidth = targetWidth - (insets.left + insets.right + getHgap() * 2);
			Dimension dim = new Dimension(0, 0);
			int rowWidth = 0;
			int rowHeight = 0;

			for (int i = 0; i < target.getComponentCount(); i++)
			{
				Component m = target.getComponent(i);
				if (!m.isVisible())
				{
					continue;
				}
				Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
				if (rowWidth + d.width > maxWidth && rowWidth > 0)
				{
					dim.width = Math.max(dim.width, rowWidth);
					dim.height += rowHeight + getVgap();
					rowWidth = 0;
					rowHeight = 0;
				}
				rowWidth += d.width + getHgap();
				rowHeight = Math.max(rowHeight, d.height);
			}
			dim.width = Math.max(dim.width, rowWidth);
			dim.height += rowHeight;

			dim.width += insets.left + insets.right + getHgap() * 2;
			dim.height += insets.top + insets.bottom + getVgap() * 2;
			return dim;
		}
	}
}
