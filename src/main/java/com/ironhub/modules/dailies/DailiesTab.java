package com.ironhub.modules.dailies;

import com.ironhub.data.DailiesPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.SectionLabel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.StringJoiner;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

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

	DailiesTab(DailiesModule module)
	{
		this.module = module;
		this.state = module.state();
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
			row.setToolTipText(stopTooltip(daily));
			body.add(row);
			body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		}
	}

	/** "Zaff, Varrock · Varrock teleport · bring 840,000 coins". */
	private String stopTooltip(DailiesPack.Daily daily)
	{
		StringJoiner tooltip = new StringJoiner(" · ");
		tooltip.add(daily.where);
		if (daily.travel != null)
		{
			tooltip.add(daily.travel);
		}
		String bring = module.bringLine(daily);
		if (!bring.isEmpty())
		{
			tooltip.add("bring " + bring);
		}
		return tooltip.toString();
	}

	// ── checklist ────────────────────────────────────────────────────

	private void buildChecklist()
	{
		int outstanding = module.outstanding();
		body.add(new SectionLabel("Dailies"));
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
	 * where it stands right now (green done · amber claimable · grey locked ·
	 * muted unknown), and everything that will not fit 225 px is in the tooltip.
	 */
	private JCheckBox checklistRow(DailiesPack.Daily daily)
	{
		DailyTracker.State current = module.stateOf(daily);
		// Muted grey alone reads as "locked" — an unknown state has to say so,
		// so it carries the house "?" rather than passing for a locked row.
		String label = daily.name
			+ (current == DailyTracker.State.UNKNOWN ? " ?" : "");
		JCheckBox box = new JCheckBox(label, state.isDailySelected(daily.id));
		box.setOpaque(false);
		box.setForeground(statusColor(current));
		box.setFont(box.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.setToolTipText(checklistTooltip(daily, current));
		// Buttons clip their own text with "…" once space runs out, so a long
		// wiki name degrades gracefully rather than widening the panel.
		box.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
		box.addActionListener(e -> state.setDailySelected(daily.id, box.isSelected()));
		return box;
	}

	private static Color statusColor(DailyTracker.State current)
	{
		switch (current)
		{
			case DONE:
				return UiTokens.STATUS_OWNED;
			case AVAILABLE:
				return UiTokens.STATUS_AVAILABLE;
			case LOCKED:
				return UiTokens.STATUS_LOCKED;
			default:
				return UiTokens.TEXT_MUTED;
		}
	}

	private String checklistTooltip(DailiesPack.Daily daily, DailyTracker.State current)
	{
		StringJoiner tooltip = new StringJoiner(" · ");
		tooltip.add(daily.name);
		tooltip.add(daily.where);
		switch (current)
		{
			case DONE:
				tooltip.add("done" + ("rolling7".equals(daily.reset)
					? ", back 7 days after your visit" : " today"));
				break;
			case AVAILABLE:
				int qty = module.quantity(daily);
				tooltip.add(qty > 0 ? "claimable now (" + qty + ")" : "claimable now");
				break;
			case LOCKED:
				tooltip.add("locked: " + DailyTracker.requirement(daily)
					.missing(state).get(0).describe());
				break;
			default:
				// Honest about the one thing we cannot know (see DailyTracker).
				tooltip.add("unknown — play it once with Iron Hub running and "
					+ "the 7-day timer starts tracking");
				break;
		}
		String bring = module.bringLine(daily);
		if (!bring.isEmpty() && current != DailyTracker.State.LOCKED)
		{
			tooltip.add("bring " + bring);
		}
		if (daily.travel != null)
		{
			tooltip.add(daily.travel);
		}
		if (daily.note != null)
		{
			tooltip.add(daily.note);
		}
		return "<html><body style='width:240px'>" + tooltip + "</body></html>";
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
