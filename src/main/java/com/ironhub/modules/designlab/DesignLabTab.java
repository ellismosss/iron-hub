package com.ironhub.modules.designlab;

import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
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
 * The OSRS skin proving ground (design/OSRS-SKIN.md): static recreations of
 * the game's Character Summary built purely from the com.ironhub.ui.osrs
 * atoms — the default stone, then the Mystic resource pack's grey re-skin
 * below it — so the two clothings can be compared in the real sidebar before
 * any module adopts one. Sample data throughout, and labelled as such.
 */
public class DesignLabTab extends JPanel
{
	public DesignLabTab()
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(UiTokens.PANEL_BG);

		add(caption("OSRS stone"));
		add(summary(OsrsSkin.STONE, ""));
		add(caption("Mystic (resource pack)"));
		add(summary(OsrsSkin.MYSTIC, "mystic/"));

		JPanel note = new JPanel();
		note.setLayout(new BoxLayout(note, BoxLayout.X_AXIS));
		note.setOpaque(false);
		note.setAlignmentX(LEFT_ALIGNMENT);
		note.setBorder(new EmptyBorder(6, 0, 8, 0));
		note.add(Box.createHorizontalGlue());
		note.add(new OsrsLabel("Static preview · sample data", OsrsSkin.MUTED, OsrsSkin.font()));
		note.add(Box.createHorizontalGlue());
		cap(note);
		add(note);
	}

	/** One full Character Summary recreation in the given clothing. */
	private JComponent summary(OsrsTheme theme, String iconPrefix)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(true);
		panel.setBackground(theme.background);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		panel.add(fullWidth(OsrsLabel.title("Iron Hub")));
		panel.add(Box.createVerticalStrut(6));

		panel.add(pair(
			new StatBox(theme, "Combat Level:", icon(iconPrefix + "combat_level"), "112"),
			new StatBox(theme, "Total Level:", icon(iconPrefix + "total_level"), "1842")));
		panel.add(Box.createVerticalStrut(3));

		panel.add(inline(theme, icon(iconPrefix + "total_xp"), "Total XP:", "47,702,858"));
		panel.add(Box.createVerticalStrut(3));

		panel.add(pair(
			new StatBox(theme, "Quests\nCompleted:", icon(iconPrefix + "quests"), "177/181"),
			new StatBox(theme, "Achievements\nCompleted:", icon(iconPrefix + "achievements"), "397/492")));
		panel.add(Box.createVerticalStrut(3));

		panel.add(pair(
			new StatBox(theme, "Combat Tasks\nCompleted:", icon(iconPrefix + "combat_tasks"), "87/637"),
			new StatBox(theme, "Collections\nLogged:", icon(iconPrefix + "collections_logged"), "207/1706")));
		panel.add(Box.createVerticalStrut(3));

		panel.add(inline(theme, null, "Time Played:", "147 days, 23 hours"));

		cap(panel);
		return panel;
	}

	/** Section caption on the plain RuneLite panel backing. */
	private JComponent caption(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 8, 4, 8));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
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
	private StonePanel inline(OsrsTheme theme, Icon icon, String label, String value)
	{
		StonePanel box = new StonePanel(theme);
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
