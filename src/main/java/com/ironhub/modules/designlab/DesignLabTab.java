package com.ironhub.modules.designlab;

import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StatBox;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChecklist;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StoneFrame;
import com.ironhub.ui.osrs.StoneMeter;
import com.ironhub.ui.osrs.StoneNavButton;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneScrollBarUI;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.border.EmptyBorder;

/**
 * The OSRS skin proving ground (design/OSRS-SKIN.md): every atom of the
 * design system, in the theme the config selects, inside the game's thin
 * side-panel frame — so the whole system can be judged in the real sidebar
 * before any module adopts it. Flipping the "OSRS skin theme" setting redraws
 * the lot in the other clothing. Sample data throughout, and labelled as such.
 */
public class DesignLabTab extends JPanel
{
	/** Luke's reference bar is RuneLite's XP tracker blue; fills are semantic. */
	private static final Color BAR_BLUE = new Color(0x3D5FBF);

	private final OsrsTheme theme;

	public DesignLabTab(OsrsTheme theme)
	{
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		// the whole skinned surface lives inside the game's thin frame
		JPanel frame = new JPanel();
		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(true);
		frame.setBackground(theme.background);
		frame.setBorder(new StoneFrame(theme));
		frame.setAlignmentX(LEFT_ALIGNMENT);

		frame.add(summary());
		// both icon weights stay side by side to be judged (per Luke)
		frame.add(section("Navigation · large icons"));
		frame.add(pad(navStones(true)));
		frame.add(section("Navigation · small icons"));
		frame.add(pad(navStones(false)));

		frame.add(section("Buttons"));
		frame.add(pad(new StoneButton(theme, "Start all runs", null)));
		frame.add(strut(3));
		frame.add(pad(buttonPair()));

		frame.add(section("Progress · tall"));
		frame.add(pad(new StoneProgressBar(theme, BAR_BLUE, 0.3059)
			.labels("Lvl. 85", "30.59%", "Lvl. 86")));
		frame.add(strut(3));
		frame.add(pad(new StoneProgressBar(theme, OsrsSkin.VALUE.darker(), 0.62)
			.labels("Elite", "31/50", "Diary")));

		frame.add(section("Progress · thin"));
		frame.add(pad(new StoneMeter(theme, OsrsSkin.VALUE.darker(), 0.62)));
		frame.add(strut(3));
		frame.add(pad(new StoneMeter(theme, BAR_BLUE, 0.18)));

		frame.add(section("Fields"));
		frame.add(pad(new StoneTextField(theme, "Search modules…")));
		frame.add(strut(3));
		frame.add(pad(fieldRow()));

		frame.add(section("Checklist"));
		frame.add(pad(new StoneChecklist(theme)
			.row("Ardougne cloak 4", true)
			.row("Graceful outfit", true)
			.row("Herb sack", false)
			.row("Rune pouch", false)));

		frame.add(section("Fonts"));
		frame.add(pad(fontSpecimen("Bold — plates & titles", OsrsSkin.boldFont(), OsrsSkin.TITLE)));
		frame.add(strut(2));
		frame.add(pad(fontSpecimen("Regular — body & labels", OsrsSkin.font(), OsrsSkin.LABEL)));
		frame.add(strut(2));
		frame.add(pad(fontSpecimen("Small — task text & bar labels", OsrsSkin.smallFont(), OsrsSkin.MUTED)));

		frame.add(section("Status tiles"));
		frame.add(pad(tileRow()));

		frame.add(section("Module plate"));
		frame.add(pad(platePreview()));

		frame.add(strut(6));
		frame.add(pad(centered(new OsrsLabel("Static preview · sample data", OsrsSkin.MUTED, OsrsSkin.font()))));
		frame.add(strut(4));
		add(frame);
	}

	/** One font of the system, labelled by where it's used. */
	private JComponent fontSpecimen(String text, java.awt.Font font, Color color)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new OsrsLabel(text, color, font).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** The slab status tiles (dailies strip / farm overview grammar):
	 *  status bevel, plain, sunken-excluded, and the perimeter progress
	 *  trace that hugs the chamfer. */
	private JComponent tileRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new com.ironhub.ui.osrs.StoneTile(theme, OsrsSkin.VALUE.darker(), false,
			"Ready — status bevel"));
		row.add(Box.createHorizontalStrut(4));
		row.add(new com.ironhub.ui.osrs.StoneTile(theme, null, false, "Tracked — plain bevel"));
		row.add(Box.createHorizontalStrut(4));
		row.add(new com.ironhub.ui.osrs.StoneTile(theme, null, true, "Excluded — sunken"));
		row.add(Box.createHorizontalStrut(4));
		row.add(progressTile());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** A slab with the chamfer-hugging perimeter progress trace (62%). */
	private JComponent progressTile()
	{
		JComponent tile = new JComponent()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
				StoneNavButton.paintSlab(g2, theme, getWidth(), getHeight(),
					theme.boxFill, theme.edgeLight);
				g2.setColor(OsrsSkin.TITLE.darker());
				for (int inset = 0; inset < 2; inset++)
				{
					java.util.List<java.awt.Point> path =
						StoneNavButton.ringPath(inset, getWidth(), getHeight());
					int covered = (int) Math.round(0.62 * path.size());
					for (int i = 0; i < covered; i++)
					{
						java.awt.Point p = path.get(i);
						g2.fillRect(p.x, p.y, 1, 1);
					}
				}
			}
		};
		Dimension size = new Dimension(com.ironhub.ui.osrs.StoneTile.WIDTH,
			com.ironhub.ui.osrs.StoneTile.HEIGHT);
		tile.setPreferredSize(size);
		tile.setMinimumSize(size);
		tile.setMaximumSize(size);
		tile.setToolTipText("Progress trace — growing, 62%");
		return tile;
	}

	/** The hub pages' collapsible module plate: triangle + bold title. */
	private JComponent platePreview()
	{
		StonePanel plate = new StonePanel(theme);
		plate.setLayout(new BoxLayout(plate, BoxLayout.X_AXIS));
		JLabel triangle = new JLabel(new com.ironhub.ui.components.PaintedIcon(
			com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
		triangle.setForeground(OsrsSkin.MUTED);
		plate.add(triangle);
		plate.add(Box.createHorizontalGlue());
		plate.add(new OsrsLabel("Collection log", OsrsSkin.TITLE, OsrsSkin.boldFont()));
		plate.add(Box.createHorizontalGlue());
		plate.add(Box.createHorizontalStrut(10));
		cap(plate);
		return plate;
	}

	/** The Character Summary recreation — the display grammar. */
	private JComponent summary()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(4, 4, 0, 4));
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(centered(OsrsLabel.title("Iron Hub")));
		panel.add(Box.createVerticalStrut(6));
		panel.add(pair(
			new StatBox(theme, "Combat Level:", OsrsIcons.stat(theme, "combat_level"), "112"),
			new StatBox(theme, "Total Level:", OsrsIcons.stat(theme, "total_level"), "1842")));
		panel.add(Box.createVerticalStrut(3));
		panel.add(inline(OsrsIcons.stat(theme, "total_xp"), "Total XP:", "47,702,858"));
		panel.add(Box.createVerticalStrut(3));
		panel.add(pair(
			new StatBox(theme, "Quests\nCompleted:", OsrsIcons.stat(theme, "quests"), "177/181"),
			new StatBox(theme, "Achievements\nCompleted:", OsrsIcons.stat(theme, "achievements"), "397/492")));
		cap(panel);
		return panel;
	}

	/**
	 * The game's own tab stones, flush like the client's own row — once with
	 * the full-size tab icons, once with the 18px Character Summary set.
	 */
	private JComponent navStones(boolean largeIcons)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		String[] tabs = {"combat", "stats", "quests", "inventory", "equipment", "prayer"};
		String[] stats = {"combat_level", "total_level", "quests", "achievements", "collections_logged", "total_xp"};
		row.add(Box.createHorizontalGlue());
		for (int i = 0; i < tabs.length; i++)
		{
			Icon icon = largeIcons ? OsrsIcons.tab(theme, tabs[i]) : OsrsIcons.stat(theme, stats[i]);
			row.add(new StoneNavButton(theme, icon, i == 0, null));
		}
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	/** A dropdown beside the scrollbar, which needs a real component to read. */
	private JComponent fieldRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);

		JComboBox<String> combo = StoneComboBoxUI.skin(
			new JComboBox<>(new String[]{"All tiers", "Elite", "Master"}), theme);
		combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		row.add(combo);
		row.add(Box.createHorizontalStrut(6));

		// a bare JScrollBar, not a scroll pane: the shell already owns the
		// one scroll surface, and nesting panes sticks the wheel
		JScrollBar bar = StoneScrollBarUI.skin(new JScrollBar(JScrollBar.VERTICAL, 30, 40, 0, 100), theme);
		bar.setPreferredSize(new Dimension(StoneScrollBarUI.THICKNESS, 74));
		bar.setMaximumSize(bar.getPreferredSize());
		row.add(bar);
		cap(row);
		return row;
	}

	private JComponent buttonPair()
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 3, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new StoneButton(theme, "Skip", null));
		row.add(new StoneButton(theme, "End run", null));
		cap(row);
		return row;
	}

	/** Section caption on the skin's own backing. */
	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 8, 3, 8));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

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

	private JComponent centered(JComponent inner)
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

	/** Inset a full-width atom to the frame's content margin. */
	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new java.awt.BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, 4, 0, 4));
		holder.add(inner);
		cap(holder);
		return holder;
	}

	private JComponent strut(int height)
	{
		return (JComponent) Box.createVerticalStrut(height);
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

}
