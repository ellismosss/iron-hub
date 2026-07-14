package com.ironhub.modules.death;

import com.ironhub.integrations.ShortestPathBridge;
import com.ironhub.state.AccountState;
import com.ironhub.ui.Format;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Death tab content: one card per recent death — relative time, location
 * with Path button, and the items carried at the moment of death.
 * Panic-reducing by design: factual, no value judgements.
 */
class DeathTab extends JPanel
{
	private static final int MAX_ITEMS_SHOWN = 6;

	private final AccountState state;
	private final ItemManager itemManager; // null in unit tests
	private final ShortestPathBridge pathBridge;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JPanel list = new JPanel();

	DeathTab(AccountState state, ItemManager itemManager, ShortestPathBridge pathBridge)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.pathBridge = pathBridge;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(new SectionLabel("Recent deaths"));
		add(Box.createVerticalStrut(UiTokens.ROW_GAP));
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(UiTokens.PANEL_BG);
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
			JLabel none = new JLabel("No deaths recorded. Long may it last.");
			none.setForeground(UiTokens.TEXT_FAINT);
			none.setFont(none.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
			none.setAlignmentX(LEFT_ALIGNMENT);
			list.add(none);
		}
		java.util.Collections.reverse(deaths); // newest first
		for (AccountState.Death death : deaths)
		{
			list.add(deathCard(death));
			list.add(Box.createVerticalStrut(UiTokens.PAD));
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel deathCard(AccountState.Death death)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		JLabel when = new JLabel(Format.relativeTime(System.currentTimeMillis() - death.timeMs));
		when.setForeground(UiTokens.TEXT_PRIMARY);
		when.setFont(when.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		header.add(when);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));
		JLabel where = new JLabel("(" + death.where.getX() + ", " + death.where.getY() + ")");
		where.setForeground(UiTokens.TEXT_MUTED);
		where.setFont(where.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		header.add(where);
		header.add(Box.createHorizontalGlue());
		header.add(IconButton.path(() -> pathBridge.pathTo(death.where)));
		card.add(header);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		List<Integer> ids = new ArrayList<>(death.carried.keySet());
		ids.sort(Comparator.comparingInt(id -> -death.carried.get(id)));
		for (Integer id : ids.subList(0, Math.min(ids.size(), MAX_ITEMS_SHOWN)))
		{
			card.add(itemLine(id, death.carried.get(id)));
		}
		if (ids.size() > MAX_ITEMS_SHOWN)
		{
			JLabel more = new JLabel("+ " + (ids.size() - MAX_ITEMS_SHOWN) + " more items carried");
			more.setForeground(UiTokens.TEXT_FAINT);
			more.setFont(more.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			more.setAlignmentX(LEFT_ALIGNMENT);
			card.add(more);
		}
		else if (ids.isEmpty())
		{
			JLabel nothing = new JLabel("nothing carried");
			nothing.setForeground(UiTokens.TEXT_FAINT);
			nothing.setFont(nothing.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
			nothing.setAlignmentX(LEFT_ALIGNMENT);
			card.add(nothing);
		}
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
		line.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		String name = state.itemName(itemId);
		JLabel nameLabel = new JLabel(name);
		nameLabel.setForeground(UiTokens.TEXT_BODY);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_SECONDARY));
		nameLabel.setToolTipText(name);
		nameLabel.setMinimumSize(new Dimension(0, 0));
		line.add(nameLabel);
		line.add(Box.createHorizontalGlue());

		JLabel count = new JLabel("×" + QuantityFormatter.quantityToStackSize(quantity));
		count.setForeground(UiTokens.TEXT_MUTED);
		count.setFont(count.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		line.add(count);
		return line;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
