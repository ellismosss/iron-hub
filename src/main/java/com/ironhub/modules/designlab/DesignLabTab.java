package com.ironhub.modules.designlab;

import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * The OSRS skin proving ground (design/OSRS-SKIN.md): a static recreation of
 * the game's Character Summary built purely from the com.ironhub.ui.osrs
 * atoms, so the skin can be judged in the real sidebar before any module
 * adopts it. Sample data throughout, and labelled as such.
 */
public class DesignLabTab extends JPanel
{
	public DesignLabTab()
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(OsrsSkin.BACKGROUND);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		add(fullWidth(OsrsLabel.title("Iron Hub")));
		add(Box.createVerticalStrut(6));

		add(pair(
			new StatBox("Combat Level:", icon("combat_level"), "112"),
			new StatBox("Total Level:", icon("total_level"), "1842")));
		add(Box.createVerticalStrut(3));

		StonePanel xp = inline(icon("total_xp"), "Total XP:", "47,702,858");
		add(xp);
		add(Box.createVerticalStrut(3));

		add(pair(
			new StatBox("Quests\nCompleted:", icon("quests"), "177/181"),
			new StatBox("Achievements\nCompleted:", icon("achievements"), "397/492")));
		add(Box.createVerticalStrut(3));

		add(pair(
			new StatBox("Combat Tasks\nCompleted:", icon("combat_tasks"), "87/637"),
			new StatBox("Collections\nLogged:", icon("collections_logged"), "207/1706")));
		add(Box.createVerticalStrut(3));

		add(inline(null, "Time Played:", "147 days, 23 hours"));
		add(Box.createVerticalStrut(8));

		add(fullWidth(new OsrsLabel("Static preview · sample data", OsrsSkin.MUTED, OsrsSkin.font())));
	}

	/** Two equal boxes side by side, the game's 2-3px gutter between. */
	private JComponent pair(StatBox left, StatBox right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 3, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(left);
		row.add(right);
		cap(row);
		return row;
	}

	/** Full-width one-line box: [icon] orange label + green value, centered. */
	private StonePanel inline(Icon icon, String label, String value)
	{
		StonePanel box = new StonePanel();
		box.setLayout(new BoxLayout(box, BoxLayout.X_AXIS));
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.add(Box.createHorizontalGlue());
		if (icon != null)
		{
			box.add(new JLabel(icon));
			box.add(Box.createHorizontalStrut(5));
		}
		box.add(OsrsLabel.label(label));
		box.add(Box.createHorizontalStrut(5));
		box.add(OsrsLabel.value(value));
		box.add(Box.createHorizontalGlue());
		cap(box);
		return box;
	}

	/** Stretch a centered-painting atom across the panel width. */
	private JComponent fullWidth(JComponent inner)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(Box.createHorizontalGlue());
		holder.add(inner);
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	private static Icon icon(String name)
	{
		java.net.URL url = DesignLabTab.class.getResource("/data/icons/osrs/" + name + ".png");
		return url == null ? null : new ImageIcon(url);
	}
}
