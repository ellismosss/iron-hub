package com.ironhub.modules.qol;

import com.ironhub.data.QolPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.util.LinkBrowser;

/**
 * QoL tab content in the OSRS stonework skin: unlocked-count progress bar +
 * one row per unlock (owned / obtainable now / locked with its blocking
 * requirement in the tooltip). Same brain as before — only the clothing
 * changed.
 */
class QolTab extends JPanel
{
	private final AccountState state;
	private final QolPack pack;
	private final OsrsTheme theme;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JPanel frame = new JPanel();

	QolTab(AccountState state, QolPack pack, OsrsTheme theme)
	{
		this.state = state;
		this.pack = pack;
		this.theme = theme;
		// frameless: the host's stone header plate names the module
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(false);
		frame.setAlignmentX(LEFT_ALIGNMENT);
		add(frame);
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
		frame.removeAll();

		long owned = pack.getUnlocks().stream()
			.filter(u -> QolModule.status(state, u) == Status.OWNED)
			.count();
		int total = pack.getUnlocks().size();
		frame.add(section("QoL unlocks"));
		frame.add(pad(new StoneProgressBar(theme, OsrsSkin.PROGRESS_BLUE,
			total == 0 ? 0 : (double) owned / total)
			.labels("Unlocked", null, owned + "/" + total)));
		frame.add(strut(4));

		// the rows sit inside one notched frame, checklist-style
		StonePanel group = new StonePanel(theme);
		group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
		group.setAlignmentX(LEFT_ALIGNMENT);
		int corner = theme.cornerStamp.length;
		group.setBorder(new StoneBorder(theme, theme.background,
			new Insets(corner, corner, corner, corner)));
		for (QolPack.Unlock unlock : pack.getUnlocks())
		{
			group.add(unlockRow(unlock));
		}
		cap(group);
		frame.add(pad(group));
		frame.add(strut(4));
		revalidate();
		repaint();
	}

	/** One unlock: name coloured by status, blocking requirement in the
	 *  tooltip, a compact wiki affordance on the right. */
	private JComponent unlockRow(QolPack.Unlock unlock)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, 2, 1, 2));

		// the old tab's status scale in skin colours:
		// green = owned, orange = doable now, faint = locked
		Status status = QolModule.status(state, unlock);
		Color color = status == Status.OWNED ? OsrsSkin.VALUE
			: status == Status.AVAILABLE ? OsrsSkin.TITLE : OsrsSkin.FAINT;
		String tooltip = unlock.getName();
		if (status == Status.LOCKED)
		{
			String blocking = QolModule.blockingLine(state, unlock);
			if (blocking != null)
			{
				tooltip += " — needs " + blocking;
			}
		}
		OsrsLabel name = new OsrsLabel(unlock.getName(), color, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(tooltip);
		row.setToolTipText(tooltip);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(wikiGlyph(unlock.getName()));
		cap(row);
		return row;
	}

	/** A small "W" wiki affordance — faint until hovered (GoalsTab grammar). */
	private static JLabel wikiGlyph(String pageName)
	{
		JLabel glyph = new JLabel("W");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText("Open the wiki page");
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
					+ pageName.replace(' ', '_'));
			}
		});
		return glyph;
	}

	// ── layout helpers (the DailiesNewTab grammar) ────────────────────

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
