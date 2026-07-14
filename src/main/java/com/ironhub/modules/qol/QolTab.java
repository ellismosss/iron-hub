package com.ironhub.modules.qol;

import com.ironhub.data.QolPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.util.LinkBrowser;

/**
 * QoL tab content: summary card + one shared list row per unlock
 * (owned / obtainable now / locked with its blocking requirement).
 */
class QolTab extends JPanel
{
	private final AccountState state;
	private final QolPack pack;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel summary = new JLabel();
	private final HubProgressBar bar = HubProgressBar.bar(0);
	private final JPanel list = new JPanel();

	QolTab(AccountState state, QolPack pack)
	{
		this.state = state;
		this.pack = pack;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(UiTokens.CARD_BG);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(new CompoundBorder(new LineBorder(UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP, UiTokens.ROW_GAP)));
		card.add(new SectionLabel("QoL unlocks"));
		summary.setForeground(UiTokens.TEXT_PRIMARY);
		summary.setFont(summary.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		card.add(summary);
		card.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		card.add(bar);
		add(card);
		add(Box.createVerticalStrut(UiTokens.PAD));

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
		long owned = pack.getUnlocks().stream()
			.filter(u -> QolModule.status(state, u) == com.ironhub.ui.components.Status.OWNED)
			.count();
		summary.setText(owned + "/" + pack.getUnlocks().size() + " unlocked");
		bar.setFraction((double) owned / pack.getUnlocks().size());

		list.removeAll();
		for (QolPack.Unlock unlock : pack.getUnlocks())
		{
			IconButton wiki = IconButton.wiki(() -> LinkBrowser.browse(
				"https://oldschool.runescape.wiki/w/" + unlock.getName().replace(' ', '_')));
			switch (QolModule.status(state, unlock))
			{
				case OWNED:
					list.add(ListRow.owned(unlock.getName(), wiki));
					break;
				case AVAILABLE:
					list.add(ListRow.available(unlock.getName(), wiki));
					break;
				default:
					list.add(ListRow.locked(unlock.getName(),
						QolModule.blockingLine(state, unlock), wiki));
			}
			list.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
		list.revalidate();
		list.repaint();
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
