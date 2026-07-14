package com.ironhub.modules.gear;

import com.ironhub.data.GearLaddersPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.GridTile;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.SegmentedControl;
import com.ironhub.ui.components.WrapLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Gear tab content (frame 2a): style tabs, then per-slot ladders — a
 * caps slot label with an amber "next:" hint and a wrapping row of
 * 30 px tiles in progression order. Never horizontal scroll.
 */
class GearTab extends JPanel
{
	private final AccountState state;
	private final GearLaddersPack pack;
	private final SegmentedControl styles;
	private final JPanel body = new JPanel();
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	GearTab(AccountState state, GearLaddersPack pack)
	{
		this.state = state;
		this.pack = pack;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		styles = new SegmentedControl(true,
			pack.getStyles().stream().map(GearLaddersPack.Style::getStyle).toArray(String[]::new));
		add(styles);
		add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(UiTokens.PANEL_BG);
		body.setAlignmentX(LEFT_ALIGNMENT);
		add(body);
		add(Box.createVerticalGlue());

		styles.onChange(i -> rebuild());
		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	private void rebuild()
	{
		body.removeAll();
		GearLaddersPack.Style style = pack.getStyles().get(styles.getSelected());
		for (GearLaddersPack.Slot slot : style.getSlots())
		{
			List<GridTile.State> states = GearProgressionModule.ladderStates(state, slot.getLadder());

			JPanel header = new JPanel();
			header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
			header.setOpaque(false);
			header.setAlignmentX(LEFT_ALIGNMENT);
			header.add(new SectionLabel(slot.getSlot()));
			header.add(Box.createHorizontalGlue());
			int nextIdx = states.indexOf(GridTile.State.NEXT);
			if (nextIdx >= 0)
			{
				JLabel next = new JLabel("next: " + slot.getLadder().get(nextIdx).getName());
				next.setForeground(UiTokens.STATUS_AVAILABLE);
				next.setFont(next.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
				header.add(next);
			}
			body.add(header);
			body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

			JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, UiTokens.GRID_GAP, UiTokens.GRID_GAP));
			row.setOpaque(false);
			row.setAlignmentX(LEFT_ALIGNMENT);
			for (int i = 0; i < slot.getLadder().size(); i++)
			{
				GearLaddersPack.Rung rung = slot.getLadder().get(i);
				row.add(new GridTile(code(rung.getName()), rung.getName(), states.get(i), false));
			}
			body.add(row);
			body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}
		body.revalidate();
		body.repaint();
	}

	private static String code(String name)
	{
		String[] words = name.split("\\s+");
		return (words.length > 1
			? "" + words[0].charAt(0) + words[1].charAt(0)
			: name.substring(0, Math.min(2, name.length()))).toUpperCase(Locale.ROOT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
