package com.ironhub.modules.dailies;

import com.ironhub.data.DailiesPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import com.ironhub.ui.components.SpriteCache;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * Dailies tab: while a run is active, End run is pinned to the TOP with the
 * ordered stop list beneath (the farm-run lesson — the sidebar is the surface,
 * so the control that stops a run must not be buried under it). Otherwise, one
 * checklist of every repeatable event: tick what you want the guided run to
 * include, and the row colour tells you where each one stands right now.
 */
class DailiesTab extends JPanel
{
	private final DailiesModule module;
	private final AccountState state;
	private final Runnable listener = () -> SwingUtilities.invokeLater(this::rebuild);

	private final JPanel topBar = new JPanel();
	private final JPanel body = new JPanel();
	private final SpriteCache sprites;

	DailiesTab(DailiesModule module)
	{
		this.module = module;
		this.state = module.state();
		this.sprites = new SpriteCache(module.itemManager(), this::rebuild);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(UiTokens.PANEL_BG);
		setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		topBar.setLayout(new BoxLayout(topBar, BoxLayout.Y_AXIS));
		topBar.setBackground(UiTokens.PANEL_BG);
		topBar.setAlignmentX(LEFT_ALIGNMENT);
		add(topBar);

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

	void rebuild()
	{
		rebuildTopBar();
		rebuildBody();
		revalidate();
		repaint();
	}

	/** During a run: End run + live progress, pinned above everything. */
	private void rebuildTopBar()
	{
		topBar.removeAll();
		if (module.running())
		{
			JLabel end = primaryButton("End run");
			end.setToolTipText("Stop the daily run now");
			end.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					module.endRun(false);
					rebuild();
				}
			});
			topBar.add(end);
			topBar.add(Box.createVerticalStrut(2));
			JLabel status = new JLabel("Daily run · " + module.visitedCount()
				+ "/" + module.stops().size() + " stops");
			status.setForeground(UiTokens.TEXT_MUTED);
			status.setFont(status.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			status.setAlignmentX(LEFT_ALIGNMENT);
			topBar.add(status);
			topBar.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}
		topBar.revalidate();
		topBar.repaint();
	}

	private void rebuildBody()
	{
		body.removeAll();
		if (module.running())
		{
			buildActiveRun();
		}
		else
		{
			buildChecklist();
		}
		body.revalidate();
		body.repaint();
	}

	// ── active run ───────────────────────────────────────────────────

	private void buildActiveRun()
	{
		body.add(new SectionLabel("Stops"));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));

		DailiesPack.Daily next = module.nextStop();
		for (DailiesPack.Daily daily : module.stops())
		{
			ListRow row;
			if (module.isVisited(daily.id))
			{
				row = ListRow.owned(daily.name); // done — nothing to skip
			}
			else
			{
				String id = daily.id;
				IconButton skip = IconButton.skip(() ->
				{
					module.markThrough(id); // skip this stop (and any before it)
					rebuild();
				});
				row = daily == next
					? ListRow.available(daily.name, skip)
					: ListRow.locked(daily.name, skip);
			}
			row.setToolTipText(daily.name);
			body.add(row);
			body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
	}

	// ── checklist ────────────────────────────────────────────────────

	private void buildChecklist()
	{
		int outstanding = module.outstanding();
		body.add(new SectionLabel("Dailies"));
		body.add(tileStrip());
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		// The count belongs to the button below — one number per fact.
		JLabel summary = new JLabel("Resets daily at 00:00 UTC");
		summary.setForeground(UiTokens.TEXT_FAINT);
		summary.setFont(summary.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		summary.setAlignmentX(LEFT_ALIGNMENT);
		body.add(summary);
		body.add(Box.createVerticalStrut(UiTokens.PAD));

		JLabel start = primaryButton(outstanding > 0
			? "Start daily run · " + outstanding + " stops" : "Nothing to run");
		if (outstanding > 0)
		{
			start.setToolTipText("Guide me through the " + outstanding
				+ " ticked dailies I can do right now");
			start.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					module.startRun();
				}
			});
		}
		else
		{
			// Nothing to do is a real state — say so plainly instead of
			// offering a button that would start an empty run.
			start.setBackground(UiTokens.ICON_BUTTON_BG);
			start.setForeground(UiTokens.TEXT_MUTED);
			start.setBorder(new LineBorder(UiTokens.BORDER_BUTTON));
			start.setCursor(Cursor.getDefaultCursor());
			start.setToolTipText("Every ticked daily is done, locked, or unavailable");
		}
		body.add(start);
		body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		body.add(new SectionLabel("Include in a run"));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		for (DailiesPack.Daily daily : module.pack().dailies)
		{
			body.add(checklistRow(daily));
		}
	}

	/**
	 * One tickable event. The tick is "include this in my runs"; the colour is
	 * where it stands right now (see statusColor).
	 */
	private JComponent checklistRow(DailiesPack.Daily daily)
	{
		DailyTracker.State current = module.stateOf(daily);
		boolean selected = module.selected(daily);
		// Muted grey alone reads as "locked" — an unknown state has to say so,
		// so it carries the house "?" rather than passing for a locked row.
		String label = daily.name
			+ (current == DailyTracker.State.UNKNOWN ? " ?" : "");
		JCheckBox box = new JCheckBox(label, selected);
		box.setOpaque(false);
		// An event you have excluded is not "go and do it", whatever its state.
		box.setForeground(selected ? statusColor(current) : UiTokens.TEXT_MUTED);
		box.setFont(box.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.setToolTipText(rowTooltip(daily, current));
		box.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
		box.addActionListener(e -> onTicked(daily, box.isSelected()));
		if (daily.warning == null)
		{
			return box;
		}
		// A skull beside the name: this one is in the Wilderness. A JCheckBox's
		// icon IS its tick, so the skull has to ride alongside in a row.
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
		// the checkbox must stop at its own width, or a BoxLayout row stretches
		// it and shoves the skull out to the far edge, away from the name
		box.setMaximumSize(box.getPreferredSize());
		row.add(box);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		JLabel skull = new JLabel(SKULL);
		skull.setToolTipText(daily.warning.replace("\n\n", " "));
		row.add(skull);
		row.add(Box.createHorizontalGlue());
		return row;
	}

	/**
	 * Ticking a Wilderness event on says so, out loud, once. Never on untick —
	 * nobody needs warning about not going to the Wilderness.
	 */
	private void onTicked(DailiesPack.Daily daily, boolean selected)
	{
		state.setDailySelected(daily.id, selected);
		if (selected && daily.warning != null)
		{
			JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
				daily.warning, daily.name, JOptionPane.WARNING_MESSAGE);
		}
	}

	/**
	 * Just the name — except when you are short, where the whole point is which
	 * items you are short of.
	 */
	private String rowTooltip(DailiesPack.Daily daily, DailyTracker.State current)
	{
		if (current != DailyTracker.State.SHORT)
		{
			return daily.name;
		}
		return daily.name + " — need " + String.join(", ", module.missing(daily));
	}

	/** The wiki's own Wilderness skull, bundled (never a painted stand-in). */
	private static final javax.swing.Icon SKULL = bundledIcon("/data/icons/wilderness_skull.png");

	private static javax.swing.Icon bundledIcon(String resource)
	{
		java.net.URL url = DailiesTab.class.getResource(resource);
		if (url == null)
		{
			return null;
		}
		javax.swing.ImageIcon icon = new javax.swing.ImageIcon(url);
		return new javax.swing.ImageIcon(
			icon.getImage().getScaledInstance(-1, 14, java.awt.Image.SCALE_SMOOTH));
	}

	/**
	 * Green means "go and get it", exactly as it does on the farm overview's
	 * tiles — these are the farm tiles, so they answer to the farm's palette,
	 * not to UiTokens' green-is-done. A claimed daily is not a success to
	 * celebrate, it is one fewer thing to look at, so it fades instead.
	 */
	private static Color statusColor(DailyTracker.State current)
	{
		switch (current)
		{
			case AVAILABLE:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case SHORT:
				// you could go, but you'd get nothing for the trip
				return ColorScheme.PROGRESS_INPROGRESS_COLOR;
			case DONE:
				return UiTokens.TEXT_FAINT;
			case LOCKED:
				return UiTokens.STATUS_LOCKED;
			default:
				return UiTokens.TEXT_MUTED;
		}
	}

	/** Green ready · a plain border for tracked-but-nothing-to-do (claimed, or
	 *  unknown). Locked/unticked never reach here — they get no border. */
	private static Color tileBorder(DailyTracker.State current)
	{
		switch (current)
		{
			case AVAILABLE:
				return ColorScheme.PROGRESS_COMPLETE_COLOR.darker();
			case SHORT:
				return ColorScheme.PROGRESS_INPROGRESS_COLOR.darker();
			default:
				return UiTokens.BORDER_BUTTON;
		}
	}

	// ── status tiles ─────────────────────────────────────────────────

	/**
	 * A tile per event, in the pack's route order — the Farm runs overview
	 * strip, minus the click-to-expand (a daily has no patch list to open;
	 * the checklist below already carries the detail).
	 */
	private JComponent tileStrip()
	{
		List<DailiesPack.Daily> dailies = module.pack().dailies;
		JPanel strip = new JPanel(new GridLayout(0, 5, 4, 4));
		strip.setOpaque(false);
		strip.setAlignmentX(LEFT_ALIGNMENT);
		int rows = (dailies.size() + 4) / 5;
		strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * (TILE_H + 4)));
		for (DailiesPack.Daily daily : dailies)
		{
			strip.add(tile(daily));
		}
		return strip;
	}

	private DailyTile tile(DailiesPack.Daily daily)
	{
		DailyTracker.State current = module.stateOf(daily);
		// Locked and unticked read the same way — not part of your dailies —
		// so both get the farm strip's nothing-here treatment: dim, no border.
		boolean mine = module.selected(daily) && current != DailyTracker.State.LOCKED;
		DailyTile tile = new DailyTile(mine ? tileBorder(current) : null, !mine, daily.name);
		tile.setIconImage(sprites.get(daily.icon, TILE_ICON));
		return tile;
	}

	private static final int TILE_W = 34;
	private static final int TILE_H = 30;
	private static final int TILE_ICON = 24;

	/**
	 * One event's sprite with a 1px status border, painted like the farm
	 * overview's tiles. A tile you have unticked gets no border at all and a
	 * dimmed fill — the same "not part of your routine" reading the farm's
	 * nothing-planted tiles have, so an amber "go do this" never shows for
	 * something you said you don't care about.
	 */
	private static class DailyTile extends JComponent
	{
		private java.awt.Image icon;
		private final Color border; // null = deselected, no border
		private final boolean dim;

		DailyTile(Color border, boolean dim, String tooltip)
		{
			this.border = border;
			this.dim = dim;
			setPreferredSize(new Dimension(TILE_W, TILE_H));
			setMinimumSize(new Dimension(TILE_W, TILE_H));
			setToolTipText(tooltip);
		}

		void setIconImage(java.awt.Image image)
		{
			this.icon = image;
			repaint();
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
				java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(dim ? UiTokens.TILE_BG_LOCKED : UiTokens.ICON_BUTTON_BG);
			g2.fillRect(0, 0, w, h);
			if (icon != null)
			{
				if (dim)
				{
					g2.setComposite(java.awt.AlphaComposite.getInstance(
						java.awt.AlphaComposite.SRC_OVER, 0.35f));
				}
				g2.drawImage(icon, (w - TILE_ICON) / 2, (h - TILE_ICON) / 2, null);
				g2.setComposite(java.awt.AlphaComposite.SrcOver);
			}
			if (border != null)
			{
				g2.setColor(border);
				g2.setStroke(new java.awt.BasicStroke(1));
				g2.drawRect(0, 0, w - 1, h - 1);
			}
			g2.dispose();
		}
	}

	// ── local atoms (the FarmingTab/LoadoutTab pattern) ───────────────

	private JLabel primaryButton(String text)
	{
		JLabel button = new JLabel(text, javax.swing.SwingConstants.CENTER);
		button.setOpaque(true);
		button.setBackground(UiTokens.ACCENT);
		button.setForeground(UiTokens.ACCENT_TEXT_ON);
		button.setBorder(new LineBorder(UiTokens.ACCENT));
		button.setFont(button.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_SECONDARY));
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setPreferredSize(new Dimension(0, UiTokens.BUTTON_HEIGHT));
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.BUTTON_HEIGHT));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
