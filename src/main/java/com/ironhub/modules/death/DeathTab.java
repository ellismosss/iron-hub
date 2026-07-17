package com.ironhub.modules.death;

import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.UiTokens;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Death tab content in the OSRS stonework skin: one stone card per recent
 * death — relative time, location with Path button, and the items carried
 * at the moment of death. Panic-reducing by design: factual, no value
 * judgements. Frameless — the host's header plate names the module.
 */
class DeathTab extends JPanel
{
	private static final int MAX_ITEMS_SHOWN = 6;

	private final AccountState state;
	private final ItemManager itemManager; // null in unit tests
	private final ShortestPathBridge pathBridge;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final JPanel list = new JPanel();

	DeathTab(AccountState state, ItemManager itemManager, ShortestPathBridge pathBridge, OsrsTheme theme)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.pathBridge = pathBridge;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		add(section("Recent deaths"));
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(LEFT_ALIGNMENT);
		add(list);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		list.removeAll();
		List<AccountState.Death> deaths = new ArrayList<>(state.getDeaths());
		if (deaths.isEmpty())
		{
			list.add(OsrsLabel.wrapped("No deaths recorded. Long may it last.",
				195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		}
		java.util.Collections.reverse(deaths); // newest first
		boolean newest = true;
		for (AccountState.Death death : deaths)
		{
			list.add(deathCard(death, newest));
			list.add(Box.createVerticalStrut(4));
			newest = false;
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel deathCard(AccountState.Death death, boolean newest)
	{
		StonePanel card = new StonePanel(theme, theme.background);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		int corner = theme.cornerStamp.length;
		card.setBorder(new StoneBorder(theme, theme.background,
			new Insets(corner, corner, corner, corner)));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		// the freshest death is the one you might still act on: attention orange
		OsrsLabel when = new OsrsLabel(Format.relativeTime(System.currentTimeMillis() - death.timeMs),
			newest ? OsrsSkin.TITLE : OsrsSkin.MUTED, OsrsSkin.boldFont());
		header.add(when.leftAligned());
		header.add(Box.createHorizontalStrut(4));
		OsrsLabel where = new OsrsLabel("(" + death.where.getX() + ", " + death.where.getY() + ")",
			OsrsSkin.MUTED, OsrsSkin.font());
		header.add(where.leftAligned().squeezable());
		header.add(Box.createHorizontalGlue());
		StoneButton path = new StoneButton(theme, theme.boxFill,
			"Path", () -> pathBridge.pathTo(death.where));
		path.setToolTipText("Shortest Path to this spot");
		path.setMaximumSize(path.getPreferredSize());
		header.add(path);
		cap(header);
		card.add(header);
		card.add(Box.createVerticalStrut(3));

		List<Integer> ids = new ArrayList<>(death.carried.keySet());
		ids.sort(Comparator.comparingInt(id -> -death.carried.get(id)));
		for (Integer id : ids.subList(0, Math.min(ids.size(), MAX_ITEMS_SHOWN)))
		{
			card.add(itemLine(id, death.carried.get(id)));
		}
		if (ids.size() > MAX_ITEMS_SHOWN)
		{
			card.add(new OsrsLabel("+ " + (ids.size() - MAX_ITEMS_SHOWN) + " more items carried",
				OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		}
		else if (ids.isEmpty())
		{
			card.add(new OsrsLabel("nothing carried",
				OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		}
		cap(card);
		return card;
	}

	private JPanel itemLine(int itemId, int quantity)
	{
		JPanel line = new JPanel();
		line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		Dimension iconSize = new Dimension(UiTokens.NAV_ICON_SIZE, UiTokens.NAV_ICON_SIZE);
		icon.setPreferredSize(iconSize);
		icon.setMinimumSize(iconSize);
		icon.setMaximumSize(iconSize);
		if (itemManager != null)
		{
			AsyncBufferedImage sprite = itemManager.getImage(itemId, quantity, quantity > 1);
			icon.setIcon(new ImageIcon(sprite));
			sprite.onLoaded(icon::repaint);
		}
		line.add(icon);
		line.add(Box.createHorizontalStrut(4));

		String name = state.itemName(itemId);
		OsrsLabel nameLabel = new OsrsLabel(name, OsrsSkin.MUTED, OsrsSkin.font());
		nameLabel.setToolTipText(name);
		line.add(nameLabel.leftAligned().squeezable());
		line.add(Box.createHorizontalGlue());

		line.add(new OsrsLabel("×" + QuantityFormatter.quantityToStackSize(quantity),
			OsrsSkin.FAINT, OsrsSkin.font()));
		cap(line);
		return line;
	}

	// ── layout helpers (the DesignLabTab grammar) ─────────────────────

	private JComponent section(String text)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(8, 4, 3, 4));
		row.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()));
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
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
