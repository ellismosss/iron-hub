package com.ironhub.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * Headless offscreen render of a component at panel width — the shared
 * verification harness for mockup side-by-sides. Multiple passes because
 * wrap layouts need a real width before preferred heights settle, and
 * invalidation clears cached BoxLayout sizes (validate() is a no-op
 * without a peer, so layout runs manually).
 */
public final class SwingRender
{
	private SwingRender()
	{
	}

	public static BufferedImage render(JComponent component)
	{
		for (int pass = 0; pass < 3; pass++)
		{
			invalidateTree(component);
			component.setSize(new Dimension(UiTokens.PANEL_WIDTH, component.getPreferredSize().height));
			layoutTree(component);
		}

		BufferedImage image = new BufferedImage(
			component.getWidth(), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
		component.paint(image.getGraphics());
		return image;
	}

	private static void invalidateTree(Component c)
	{
		c.invalidate();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				invalidateTree(child);
			}
		}
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}
}
