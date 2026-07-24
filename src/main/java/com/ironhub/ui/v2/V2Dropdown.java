package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.IntConsumer;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * A one-of-many picker for lists too long to be chips. Another composition
 * (§9): the Card slice as the closed control, the game's chevron as the
 * affordance, and the options on a Card in a popup.
 *
 * <p>Deliberately not a styled {@code JComboBox}. The Swing control brings a
 * renderer, a UI delegate, a popup border and a scrollbar that each have to
 * be re-skinned and each drift on their own schedule — five surfaces to keep
 * in step where this needs one.
 */
public class V2Dropdown extends JPanel
{
	private final OsrsTheme theme;
	private final NineSlice card = V2Tokens.card();
	private final NineSlice lit = V2Tokens.card().variant("_hovered");
	private final OsrsLabel label;
	private final String[] options;
	private int selected;
	private IntConsumer onChange;
	private boolean hover;

	public V2Dropdown(OsrsTheme theme, String... options)
	{
		this.theme = theme;
		this.options = options.clone();
		this.label = V2Label.body(options.length > 0 ? options[0] : "");
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setLayout(new java.awt.BorderLayout());
		setBorder(new javax.swing.border.EmptyBorder(0, V2Tokens.PAD, 0,
			V2Sprites.meta(V2Glyph.CHEVRON_OPEN).width() + V2Tokens.PAD));
		add(label, java.awt.BorderLayout.CENTER);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				open();
			}

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
				repaint();
			}
		});
	}

	public V2Dropdown onChange(IntConsumer onChange)
	{
		this.onChange = onChange;
		return this;
	}

	public int selected()
	{
		return selected;
	}

	public void setSelected(int index)
	{
		selected = index;
		label.setText(options[index]);
		repaint();
	}

	/** Pick as a click would, listener included. Test seam. */
	public void pick(int index)
	{
		setSelected(index);
		if (onChange != null)
		{
			onChange.accept(index);
		}
	}

	private void open()
	{
		JPopupMenu popup = new JPopupMenu();
		popup.setBorder(null);
		popup.setOpaque(false);
		V2Surface list = V2Surface.card(theme);
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		for (int i = 0; i < options.length; i++)
		{
			final int index = i;
			OsrsLabel row = i == selected ? V2Label.heading(options[i]) : V2Label.body(options[i]);
			JPanel holder = new JPanel(new java.awt.BorderLayout());
			holder.setOpaque(false);
			holder.setAlignmentX(LEFT_ALIGNMENT);
			holder.add(row, java.awt.BorderLayout.CENTER);
			holder.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			holder.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					popup.setVisible(false);
					pick(index);
				}
			});
			list.add(holder);
			if (i < options.length - 1)
			{
				list.add(V2Layout.gap(V2Tokens.ROW));
			}
		}
		popup.add(list);
		popup.show(this, 0, getHeight());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		(hover ? lit : card).paint(g2, theme, 0, 0, getWidth(), getHeight());
		BufferedImage chevron = V2Sprites.get(theme, V2Glyph.CHEVRON_OPEN);
		g2.drawImage(chevron, getWidth() - chevron.getWidth() - V2Tokens.PAD,
			(getHeight() - chevron.getHeight()) / 2, null);
		super.paintComponent(g);
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(V2Tokens.CONTENT_WIDTH, V2Tokens.CONTROL_HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, V2Tokens.CONTROL_HEIGHT);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(4 * V2Tokens.SECTION, V2Tokens.CONTROL_HEIGHT);
	}
}
