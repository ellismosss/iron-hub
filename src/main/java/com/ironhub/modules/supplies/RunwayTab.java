package com.ironhub.modules.supplies;

import com.ironhub.modules.supplies.SuppliesRunwayModule.Runway;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneBorder;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;

/**
 * Runway tab content in the OSRS stonework skin: consumables sorted
 * shortest-runway-first; rows inside the warning threshold render red
 * with the hours left. Frameless — the host's header plate names it.
 */
class RunwayTab extends JPanel
{
	private final AccountState state;
	private final ItemManager itemManager; // unused until restock links; kept for symmetry
	private final SuppliesRunwayModule module;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final JPanel list = new JPanel();

	RunwayTab(AccountState state, ItemManager itemManager, SuppliesRunwayModule module, OsrsTheme theme)
	{
		this.state = state;
		this.itemManager = itemManager;
		this.module = module;
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

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

	/** The stock-target field being edited; rebuilds defer until focus
	 *  leaves it — a per-kill notification used to replace the field under
	 *  the cursor and wipe the number mid-typing (2026-07-20 audit). */
	private javax.swing.JComponent editingField;
	private boolean rebuildDeferred;

	private void rebuild()
	{
		if (editingField != null && editingField.hasFocus())
		{
			rebuildDeferred = true;
			return;
		}
		list.removeAll();
		Map<Integer, Runway> runways = SuppliesRunwayModule.compute(state);
		// no title of its own: the host's stone header plate names the module
		list.add(Box.createVerticalStrut(4));
		if (runways.isEmpty())
		{
			JPanel holder = new JPanel();
			holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
			holder.setOpaque(false);
			holder.setAlignmentX(LEFT_ALIGNMENT);
			holder.add(OsrsLabel.wrapped("Consumption rates build up as you play.",
				195, OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
			holder.add(Box.createHorizontalGlue());
			cap(holder);
			list.add(holder);
		}
		else
		{
			// the rows sit inside one notched frame, checklist-style
			StonePanel group = new StonePanel(theme);
			group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
			group.setAlignmentX(LEFT_ALIGNMENT);
			int corner = theme.cornerStamp.length;
			group.setBorder(new StoneBorder(theme, theme.background,
				new Insets(corner, corner, corner, corner)));
			for (Runway runway : runways.values())
			{
				group.add(row(runway));
			}
			cap(group);
			list.add(group);
		}
		list.revalidate();
		list.repaint();
	}

	/** One consumable: name (+ hours left when inside the warning window),
	 *  an editable stock-target field and a "+ Goal" affordance. */
	private JComponent row(Runway runway)
	{
		String name = state.itemName(runway.itemId);
		String hours = SuppliesRunwayModule.formatHours(runway.hoursLeft());
		boolean low = runway.hoursLeft() < module.warningHours();
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);

		OsrsLabel label = new OsrsLabel(name, low ? UiTokens.STATUS_WARNING : OsrsSkin.VALUE, OsrsSkin.font());
		String tip = name + " — " + hours + " of stock at your usage rate";
		// a LOW row's hover answers the next question: where to restock from
		String sources = low && module.itemSources() != null
			? module.itemSources().sourceLine(runway.itemId, state,
			state.getItemSourcePref(runway.itemId)) : null;
		if (sources != null)
		{
			tip = "<html>" + tip + "<br>" + sources + "</html>";
		}
		label.setToolTipText(tip);
		row.setToolTipText(tip);
		row.add(label.leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		if (low)
		{
			row.add(new OsrsLabel(hours + " left", UiTokens.STATUS_WARNING, OsrsSkin.smallFont()));
			row.add(Box.createHorizontalStrut(4));
		}

		// stock-target field: a one-shot "stock N × item" goal. No sourced
		// "suggested stock" exists, so default to ~2 warning-windows of
		// runway — a comfortable restock, editable.
		// ponytail: heuristic default; a real restock target lands with planning mode.
		String goalId = "supply:" + runway.itemId;
		boolean isGoal = state.getGoalSeeds().containsKey(goalId);
		int suggested = Math.max(1, (int) Math.ceil(runway.perHour * Math.max(1, module.warningHours()) * 2));
		com.ironhub.ui.osrs.StoneTextField qty = new com.ironhub.ui.osrs.StoneTextField(theme, null);
		qty.setText(String.valueOf(isGoal ? trackedQty(goalId, suggested) : suggested));
		qty.setToolTipText("Stock target");
		qty.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusGained(java.awt.event.FocusEvent e)
			{
				editingField = qty;
			}

			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				if (editingField == qty)
				{
					editingField = null;
				}
				if (rebuildDeferred)
				{
					rebuildDeferred = false;
					rebuild();
				}
			}
		});
		JPanel qtyCap = new JPanel(new java.awt.BorderLayout());
		qtyCap.setOpaque(false);
		qtyCap.add(qty);
		int w = qty.getFontMetrics(qty.getFont()).stringWidth("99999") + 12;
		Dimension qs = new Dimension(w, qty.getPreferredSize().height);
		qtyCap.setPreferredSize(qs);
		qtyCap.setMaximumSize(qs);
		row.add(qtyCap);
		row.add(Box.createHorizontalStrut(4));
		row.add(goalGlyph(isGoal, isGoal ? name + " — tracked; click to untrack"
			: "Track stocking " + name, () -> toggleGoal(runway.itemId, name, qty)));
		cap(row);
		return row;
	}

	/** The tracked stock target from a supply goal's item requirement
	 *  ("item:&lt;id&gt;:&lt;qty&gt;:&lt;name&gt;"), or the fallback. */
	private int trackedQty(String goalId, int fallback)
	{
		com.ironhub.state.PersistedState.GoalSeed seed = state.getGoalSeeds().get(goalId);
		if (seed != null && !seed.achieved.isEmpty())
		{
			String[] parts = seed.achieved.get(0).split(":");
			if (parts.length >= 3)
			{
				try
				{
					return Integer.parseInt(parts[2]);
				}
				catch (NumberFormatException ignored)
				{
					// fall through to the default
				}
			}
		}
		return fallback;
	}

	/** Toggle a one-shot supply goal; reads the qty field when adding. */
	private void toggleGoal(int itemId, String name, com.ironhub.ui.osrs.StoneTextField qtyField)
	{
		String goalId = "supply:" + itemId;
		if (state.getGoalSeeds().containsKey(goalId))
		{
			state.removeGoalSeed(goalId);
			return;
		}
		int qty;
		try
		{
			qty = Math.max(1, Integer.parseInt(qtyField.getText().trim()));
		}
		catch (NumberFormatException e)
		{
			return; // ignore a non-numeric field, nothing to track
		}
		state.addGoalSeed(com.ironhub.state.GoalSeeds.supply(itemId, name, qty));
	}

	/** The +/× goal affordance in skin colours (the diaries glyph grammar). */
	private static javax.swing.JLabel goalGlyph(boolean isGoal, String tooltip, Runnable onClick)
	{
		javax.swing.JLabel glyph = new javax.swing.JLabel(isGoal ? "×" : "+");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tooltip);
		glyph.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		glyph.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.TITLE);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				onClick.run();
			}
		});
		return glyph;
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
