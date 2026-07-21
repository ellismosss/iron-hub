package com.ironhub.ui;

import com.ironhub.state.AccountState;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneFrame;
import com.ironhub.ui.osrs.StoneNavButton;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * The reworked home (2026-07-16, Luke's nav spec): the player's name over
 * SIX nav blocks — Goals, Gear & Combat, Dailies, Progression, Bank,
 * Settings — in the OSRS stonework skin. The stones are the whole
 * navigation; the summary tiles (combat/total/xp/goal/achievements) were
 * CUT 2026-07-21 on Luke's word — the hub is navigation, not a dashboard.
 */
public class HomePanel extends JPanel
{
	/**
	 * name → tooltip, in Luke's order; icon files live at data/icons/osrs/nav/.
	 * SIX blocks at the Design lab's full 33×36 stone size, flush like the
	 * game's own tab row — Current task was cut so nothing gets squashed and
	 * icons stay at native size, never rescaled (LANCZOS smoothing is what
	 * made the first pass read soft; Luke: "much nicer and crisper").
	 */
	private static final String[][] NAV = {
		{"goals", "Goals"},
		{"combat", "Gear & Combat"},
		{"dailies", "Dailies"},
		{"progression", "Progression"},
		{"bank", "Bank"},
		{"settings", "Settings"},
	};

	private final AccountState state;
	private final OsrsTheme theme;
	private final java.util.function.Consumer<String> onBlock;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::refresh);

	private final JPanel frame = new JPanel();
	/**
	 * The open block's content, BELOW the stone frame on the main backing
	 * (Luke, 2026-07-17: the raised frame envelops only the hub proper —
	 * module sections carry their own hierarchy via their header plates),
	 * still one connected scroll. Owned by IronHubPanel, which mounts hub
	 * pages into it; empty when nothing is open.
	 */
	private final JPanel contentSlot = new JPanel(new java.awt.BorderLayout());
	private final java.util.Map<String, StoneNavButton> stones = new java.util.LinkedHashMap<>();
	private String selectedBlock;
	private long statsFingerprint = -1;

	public HomePanel(AccountState state, OsrsTheme theme,
		java.util.function.Consumer<String> onBlock)
	{
		this.state = state;
		this.theme = theme;
		this.onBlock = onBlock;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		// the MAIN backing is the theme's own — the hub content below the
		// frame sits directly on it (Luke, 2026-07-17: the raised stone
		// frame envelops only the hub proper, ending above module headers)
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		frame.setLayout(new BoxLayout(frame, BoxLayout.Y_AXIS));
		frame.setOpaque(true);
		frame.setBackground(theme.background);
		frame.setBorder(new StoneFrame(theme));
		frame.setAlignmentX(LEFT_ALIGNMENT);
		contentSlot.setOpaque(false);
		contentSlot.setAlignmentX(LEFT_ALIGNMENT);
		add(frame);
		add(Box.createVerticalStrut(6));
		add(contentSlot);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	public void dispose()
	{
		state.removeListener(listener);
	}

	/** State fires constantly; only the name matters here now. */
	private void refresh()
	{
		long now = fingerprint();
		if (now != statsFingerprint)
		{
			rebuild();
		}
	}

	private long fingerprint()
	{
		return state.playerName().hashCode();
	}

	private void rebuild()
	{
		statsFingerprint = fingerprint();
		frame.removeAll();
		frame.add(strut(4));
		// the player's in-game name; the plugin's until first seen logged in
		String name = state.playerName();
		frame.add(centered(OsrsLabel.title(name.isEmpty() ? "Iron Hub" : name)));
		frame.add(strut(4));
		frame.add(navRow());
		frame.add(strut(4));
		// the open block's content lives BELOW the frame, on the main
		// backing — its stone header plates carry the hierarchy from here
		revalidate();
		repaint();
	}

	/** Where the open block's page lives — inside the frame, one block. */
	public JPanel contentSlot()
	{
		return contentSlot;
	}

	/**
	 * A stone was pressed: select it (or deselect, clicking the open one
	 * again — the game's own tab stones close the same way).
	 */
	private void toggleBlock(String name)
	{
		selectedBlock = name.equals(selectedBlock) ? null : name;
		for (java.util.Map.Entry<String, StoneNavButton> stone : stones.entrySet())
		{
			stone.getValue().setSelected(stone.getKey().equals(selectedBlock));
		}
		rebuild();
		if (onBlock != null)
		{
			onBlock.accept(selectedBlock);
		}
	}

	/** UI-only reset (theme flips, back navigation) — never fires the callback. */
	public void clearSelection()
	{
		selectedBlock = null;
		for (StoneNavButton stone : stones.values())
		{
			stone.setSelected(false);
		}
		rebuild();
	}

	/** Test seam. */
	public String selectedBlock()
	{
		return selectedBlock;
	}

	/** Test seam: press a block by name, exactly as a stone click would. */
	public void pressBlock(String name)
	{
		toggleBlock(name);
	}

	/** Test seam: the nav block names in stone order — each needs a hub page. */
	static java.util.List<String> blockNames()
	{
		java.util.List<String> names = new java.util.ArrayList<>();
		for (String[] block : NAV)
		{
			names.add(block[1]);
		}
		return names;
	}

	/** The six stones, flush at full size — the Design lab row. Not wired yet. */
	private JComponent navRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(Box.createHorizontalGlue());
		for (String[] block : NAV)
		{
			String name = block[1];
			StoneNavButton stone = stones.computeIfAbsent(name, key ->
				new StoneNavButton(theme, OsrsIcons.nav(theme, block[0]),
					false, () -> toggleBlock(key)));
			stone.setToolTipText(name);
			row.add(stone);
		}
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
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
