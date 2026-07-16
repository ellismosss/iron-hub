package com.ironhub.ui.osrs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;

/**
 * The game's dropdown: a sunken field showing the value, with a stone arrow
 * button at its right and a list popup in the same clothing. Sampled from the
 * wiki's File:Settings_interface.png (its "Exact Value" dropdown) and the
 * pack's overrides.toml (dropdown.border.inner #232323 / outer #141414).
 *
 * <p>This skins a real JComboBox rather than replacing it, so the five
 * modules already using one migrate by styling, not rewriting.
 */
public class StoneComboBoxUI extends BasicComboBoxUI
{
	private final OsrsTheme theme;

	public StoneComboBoxUI(OsrsTheme theme)
	{
		this.theme = theme;
	}

	/** Style a combo box in one call. */
	public static <T> JComboBox<T> skin(JComboBox<T> combo, OsrsTheme theme)
	{
		combo.setUI(new StoneComboBoxUI(theme));
		combo.setBackground(theme.fieldFill);
		combo.setForeground(OsrsSkin.LABEL);
		combo.setFont(OsrsSkin.font());
		combo.setFocusable(false);
		combo.setBorder(new javax.swing.border.CompoundBorder(
			new MatteBorder(1, 1, 1, 1, theme.edgeDark),
			new MatteBorder(1, 1, 1, 1, theme.fieldEdge)));
		combo.setRenderer(new Renderer(theme));
		return combo;
	}

	@Override
	protected JButton createArrowButton()
	{
		return new ArrowButton();
	}

	@Override
	public void paintCurrentValueBackground(Graphics g, java.awt.Rectangle bounds, boolean hasFocus)
	{
		g.setColor(theme.fieldFill);
		g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
	}

	/** Stone square with the game's solid down triangle. */
	private class ArrowButton extends JButton
	{
		ArrowButton()
		{
			setBorder(null);
			setFocusable(false);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(16, 16);
		}

		@Override
		public void paint(Graphics g)
		{
			int w = getWidth(), h = getHeight();
			Color fill = getModel().isPressed() ? theme.pressFill
				: getModel().isRollover() ? theme.hoverFill : theme.boxFill;
			g.setColor(theme.edgeDark);
			g.fillRect(0, 0, w, h);
			g.setColor(theme.edgeLight);
			g.fillRect(1, 1, w - 2, h - 2);
			g.setColor(fill);
			g.fillRect(2, 2, w - 4, h - 4);

			g.setColor(theme.edgeDark);
			int cx = w / 2, cy = h / 2;
			for (int row = 0; row <= 3; row++)
			{
				g.fillRect(cx - (3 - row), cy - 1 + row, 1 + 2 * (3 - row), 1);
			}
		}
	}

	/** List rows in the game's font, selection lifting like a stone box. */
	private static class Renderer extends DefaultListCellRenderer
	{
		private final OsrsTheme theme;

		Renderer(OsrsTheme theme)
		{
			this.theme = theme;
		}

		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean selected, boolean focused)
		{
			JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
			label.setFont(OsrsSkin.font());
			label.setOpaque(true);
			label.setBackground(selected ? theme.hoverFill : theme.fieldFill);
			label.setForeground(selected ? OsrsSkin.TITLE : OsrsSkin.LABEL);
			label.setBorder(new EmptyBorder(1, 4, 1, 4));
			return label;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			((Graphics2D) g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			super.paintComponent(g);
		}
	}
}
