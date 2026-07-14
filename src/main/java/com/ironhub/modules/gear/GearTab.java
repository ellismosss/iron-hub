package com.ironhub.modules.gear;

import com.ironhub.data.GearProgressionPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.ChipRow;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.WrapLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

/**
 * Gear progression chart (Ladlor-style): phases of grouped item sprites
 * connected by subtle arrows in recommended order. Green = obtained (any
 * variant), accent = targeted in the goal planner (left-click toggles),
 * hover lists missing requirements, right-click opens the wiki. Filter
 * chips cut the chart to one combat style / utility / POH / boat.
 */
class GearTab extends JPanel
{
	private static final String[] FILTERS_TOP = {"All", "Melee", "Ranged", "Magic"};
	private static final String[] FILTERS_BOTTOM = {"Utility", "POH", "Boat"};

	private final AccountState state;
	private final GearProgressionPack pack;
	private final ItemManager itemManager; // null in headless tests
	private final ChipRow filterTop = new ChipRow(FILTERS_TOP);
	private final ChipRow filterBottom = new ChipRow(FILTERS_BOTTOM);
	private final JPanel body = new JPanel();
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);
	private String filter; // lower-case category, null = all

	GearTab(AccountState state, GearProgressionPack pack, ItemManager itemManager)
	{
		this.state = state;
		this.pack = pack;
		this.itemManager = itemManager;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		filterTop.onChange(i -> selectFilter(true, i));
		filterBottom.onChange(i -> selectFilter(false, i));
		filterBottom.setSelected(-1);
		add(filterTop);
		add(Box.createVerticalStrut(UiTokens.CHIP_GAP));
		add(filterBottom);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(UiTokens.PANEL_BG);
		body.setAlignmentX(LEFT_ALIGNMENT);
		add(body);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void selectFilter(boolean top, int index)
	{
		if (index < 0) // programmatic deselect of the other row
		{
			return;
		}
		if (top)
		{
			filterBottom.setSelected(-1);
			filter = index == 0 ? null : FILTERS_TOP[index].toLowerCase(Locale.ROOT);
		}
		else
		{
			filterTop.setSelected(-1);
			filter = FILTERS_BOTTOM[index].toLowerCase(Locale.ROOT);
		}
		rebuild();
	}

	private void rebuild()
	{
		body.removeAll();
		for (GearProgressionPack.Phase phase : pack.getPhases())
		{
			boolean phaseHasContent = false;
			boolean firstGroup = true;
			for (GearProgressionPack.Group group : phase.getGroups())
			{
				List<GearProgressionPack.Item> items = group.getItems().stream()
					.filter(this::matchesFilter)
					.collect(java.util.stream.Collectors.toList());
				if (items.isEmpty())
				{
					continue;
				}
				if (!phaseHasContent)
				{
					body.add(new SectionLabel(phase.getName()));
					body.add(Box.createVerticalStrut(UiTokens.ROW_GAP));
					phaseHasContent = true;
				}
				if (!firstGroup)
				{
					body.add(new Arrow());
				}
				firstGroup = false;

				JLabel label = new JLabel(group.getLabel());
				label.setForeground(UiTokens.TEXT_FAINT);
				label.setFont(label.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
				label.setAlignmentX(LEFT_ALIGNMENT);
				body.add(label);
				body.add(Box.createVerticalStrut(2));

				JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, UiTokens.CHIP_GAP, UiTokens.CHIP_GAP));
				row.setOpaque(false);
				row.setAlignmentX(LEFT_ALIGNMENT);
				for (GearProgressionPack.Item item : items)
				{
					row.add(tile(item));
				}
				body.add(row);
			}
			if (phaseHasContent)
			{
				body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
			}
		}
		body.revalidate();
		body.repaint();
	}

	private boolean matchesFilter(GearProgressionPack.Item item)
	{
		return filter == null || item.getCategories().contains(filter);
	}

	private ItemTile tile(GearProgressionPack.Item item)
	{
		boolean obtained = isObtained(item);
		boolean targeted = state.getSelectedGoals().contains(item.goalId());
		ItemTile tile = new ItemTile(item.getName(), obtained, targeted,
			tooltip(item, obtained, targeted),
			() -> state.selectGoal(item.goalId(), !targeted),
			e -> contextMenu(item, obtained).show(e.getComponent(), e.getX(), e.getY()));
		if (itemManager != null)
		{
			AsyncBufferedImage icon = itemManager.getImage(item.icon());
			tile.setIcon(icon);
			icon.onLoaded(tile::repaint);
		}
		return tile;
	}

	private boolean isObtained(GearProgressionPack.Item item)
	{
		return item.isManual()
			? state.isUnlocked(item.markKey())
			: state.canonicalStock(item.getItemId()) > 0;
	}

	private String tooltip(GearProgressionPack.Item item, boolean obtained, boolean targeted)
	{
		StringBuilder html = new StringBuilder("<html><b>")
			.append(item.getName()).append("</b>");
		if (obtained)
		{
			html.append("<br>Obtained");
		}
		else
		{
			List<Requirement> missing = requirement(item).missing(state);
			if (missing.isEmpty())
			{
				html.append("<br>Requirements met");
			}
			else
			{
				html.append("<br>Missing:");
				for (Requirement req : missing)
				{
					html.append("<br>- ").append(req.describe());
				}
			}
			if (item.isManual())
			{
				html.append("<br>Not auto-detected - right-click to mark obtained");
			}
		}
		html.append("<br><i>").append(targeted
			? "Targeted - click to remove from goal planner"
			: "Click to add to goal planner").append("</i>");
		return html.append("</html>").toString();
	}

	private Requirement requirement(GearProgressionPack.Item item)
	{
		return Requirements.allOf(item.getRequirements().stream()
			.map(Requirements::parse)
			.toArray(Requirement[]::new));
	}

	private JPopupMenu contextMenu(GearProgressionPack.Item item, boolean obtained)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem wiki = new JMenuItem("Open wiki page");
		wiki.addActionListener(e ->
			LinkBrowser.browse("https://oldschool.runescape.wiki/w/" + item.wikiPage()));
		menu.add(wiki);
		if (item.isManual())
		{
			JMenuItem mark = new JMenuItem(obtained ? "Unmark obtained" : "Mark obtained");
			mark.addActionListener(e -> state.setUnlocked(item.markKey(), !obtained));
			menu.add(mark);
		}
		return menu;
	}

	/** Subtle down-arrow between consecutive groups (the progression flow). */
	private static class Arrow extends JComponent
	{
		private static final int HEIGHT = 11;

		Arrow()
		{
			setPreferredSize(new Dimension(ItemTile.W, HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
			setAlignmentX(LEFT_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			int x = ItemTile.W / 2; // aligned under the first tile column
			g.setColor(UiTokens.GLYPH_MUTED);
			g.drawLine(x, 1, x, HEIGHT - 4);
			g.drawLine(x - 3, HEIGHT - 6, x, HEIGHT - 3);
			g.drawLine(x + 3, HEIGHT - 6, x, HEIGHT - 3);
		}
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
