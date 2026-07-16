package com.ironhub.ui.osrs;

import java.awt.Component;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The Character Summary stat unit: centered orange label line(s) over a
 * centered icon + green value line, in a stone box. Multi-line labels split
 * on '\n' exactly as the game wraps its own ("Quests\nCompleted:").
 */
public class StatBox extends StonePanel
{
	public StatBox(String label, Icon icon, String value)
	{
		this(OsrsSkin.STONE, label, icon, value);
	}

	public StatBox(OsrsTheme theme, String label, Icon icon, String value)
	{
		super(theme);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		add(OsrsLabel.label(label));
		add(Box.createVerticalStrut(2));

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.CENTER_ALIGNMENT);
		if (icon != null)
		{
			JLabel sprite = new JLabel(icon);
			sprite.setAlignmentY(Component.CENTER_ALIGNMENT);
			row.add(sprite);
			row.add(Box.createHorizontalStrut(5));
		}
		OsrsLabel valueLabel = OsrsLabel.value(value);
		valueLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
		row.add(valueLabel);
		add(row);
	}
}
