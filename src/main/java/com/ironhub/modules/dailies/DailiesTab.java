package com.ironhub.modules.dailies;

import com.ironhub.data.DailiesPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Dailies tab content: click a row to tick it off until its reset.
 * ✓ done this reset · ● available (requirements met) · ○ locked with
 * the blocking requirement.
 */
class DailiesTab extends JPanel
{
	private final AccountState state;
	private final DailiesPack pack;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JLabel summary = new JLabel();
	private final JPanel list = new JPanel();

	DailiesTab(AccountState state, DailiesPack pack)
	{
		this.state = state;
		this.pack = pack;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		add(new SectionLabel("Dailies"));
		summary.setForeground(UiTokens.TEXT_FAINT);
		summary.setFont(summary.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		add(summary);
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
		long now = System.currentTimeMillis();
		long outstanding = pack.getDailies().stream()
			.filter(d -> !DailiesModule.isDone(state, d, now)
				&& DailiesModule.requirement(d).isMet(state))
			.count();
		summary.setText(outstanding + " outstanding · click a row to tick it off");

		list.removeAll();
		for (DailiesPack.Daily daily : pack.getDailies())
		{
			ListRow row;
			boolean done = DailiesModule.isDone(state, daily, now);
			if (done)
			{
				row = ListRow.owned(daily.getName());
			}
			else if (DailiesModule.requirement(daily).isMet(state))
			{
				row = ListRow.available(daily.getName());
			}
			else
			{
				row = ListRow.locked(daily.getName(),
					DailiesModule.requirement(daily).missing(state).get(0).describe());
			}
			row.setToolTipText(daily.getName() + " — " + daily.getReset()
				+ (done ? " · done, resets automatically" : " · click to mark done"));
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			row.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					state.markDaily(daily.getId(), !done);
				}
			});
			list.add(row);
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
