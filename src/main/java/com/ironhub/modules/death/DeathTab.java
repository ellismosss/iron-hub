package com.ironhub.modules.death;

import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.UiTokens;
import java.awt.Dimension;
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
	private final com.ironhub.IronHubConfig config;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final JPanel list = new JPanel();

	DeathTab(AccountState state, ItemManager itemManager, ShortestPathBridge pathBridge,
		com.ironhub.IronHubConfig config)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.pathBridge = pathBridge;
		this.config = config;
		this.theme = config.osrsTheme();
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
		// one death is a titled block, not the page's live readout — the Slab
		V2Surface card = V2Surface.slab(theme);

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
		// the chip ATOM — this row sits among detail lines, so DETAIL font.
		// Gated like every other bridge consumer (DR2 2026-08-03): with the
		// integration off there is no dead button, and with Shortest Path
		// not installed the PluginMessage is simply unheard (hub rule).
		if (config.shortestPathBridge())
		{
			JComponent route = V2ChipRow.action(theme, "Route", null, null,
				OsrsSkin.smallFont(), () -> pathBridge.pathTo(death.where));
			route.setToolTipText("Shortest Path to this grave");
			header.add(route);
		}
		cap(header);
		card.add(header);
		// the estimated grave fee, honestly sourced (DR1 2026-08-03):
		// unknown — legacy record, or a stack the bands don't specify — is "?"
		JPanel fee = new JPanel();
		fee.setLayout(new BoxLayout(fee, BoxLayout.X_AXIS));
		fee.setOpaque(false);
		fee.setAlignmentX(LEFT_ALIGNMENT);
		OsrsLabel feeLabel = new OsrsLabel("Reclaim fee: "
			+ (death.reclaimFeeGp < 0 ? "?"
				: "~" + QuantityFormatter.quantityToStackSize(death.reclaimFeeGp) + " gp"),
			OsrsSkin.MUTED, OsrsSkin.smallFont());
		feeLabel.setToolTipText(death.reclaimFeeGp < 0
			? "Unknown — recorded before fee tracking, or carrying a valuable stack the fee bands don't specify"
			: "Estimated from the grave fee bands (free under 100k GE; 1k, 10k, 100k per item; "
				+ "capped 500k; irons pay half), assuming the usual 3 items kept");
		fee.add(feeLabel.leftAligned());
		fee.add(Box.createHorizontalGlue());
		cap(fee);
		card.add(fee);
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
