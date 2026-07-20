package com.ironhub.modules.hunter;

import com.ironhub.data.HunterRumoursPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneMeter;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;

/**
 * Hunters' Rumours tab in the OSRS stonework skin: chip views Rumour ·
 * History. Rumour = the hero card (target, trap, catch progress vs the
 * pity rate), the assigning hunter, hunting locations with a persisted
 * preferred choice + Shortest Path routing, and the "check your whistle"
 * hint when no rumour is known. History = the capped rumour records.
 * Frameless — the host's header plate names the module.
 */
class HunterRumoursTab extends JPanel
{
	private static final int MAX_HISTORY = 20;

	private final AccountState state;
	private final HunterRumoursModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final com.ironhub.ui.components.SpriteCache sprites;

	private final StoneChipRow views;
	private final JPanel content = new JPanel();

	HunterRumoursTab(AccountState state, HunterRumoursModule module, OsrsTheme theme,
		ItemManager itemManager)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.itemManager = itemManager;
		this.sprites = new com.ironhub.ui.components.SpriteCache(itemManager, listener);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		views = new StoneChipRow(theme, true, "Rumour", "History");
		views.onChange(i -> rebuild());
		add(views);
		add(Box.createVerticalStrut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	void selectView(int index)
	{
		views.setSelected(index);
		rebuild();
	}

	void rebuild()
	{
		content.removeAll();
		if (module.pack() == null)
		{
			content.add(faintLine("Rumour pack unavailable."));
		}
		else if (views.getSelected() == 1)
		{
			rebuildHistory();
		}
		else
		{
			rebuildRumour();
		}
		content.revalidate();
		content.repaint();
	}

	// ── Rumour view ───────────────────────────────────────────────────

	private void rebuildRumour()
	{
		HunterRumoursPack.Rumour rumour = module.currentRumour();
		PersistedState.RumourRecord active = module.active();

		StonePanel hero = new StonePanel(theme);
		hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
		hero.setAlignmentX(LEFT_ALIGNMENT);
		JPanel title = row(0);
		if (rumour == null)
		{
			title.add(new OsrsLabel("No rumour", OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned());
			title.add(Box.createHorizontalGlue());
			cap(title);
			hero.add(title);
			cap(hero);
			content.add(hero);
			content.add(faintLine("Get a rumour from a Guild Hunter, or click Rumour on your "
				+ "Quetzal whistle to sync the current one."));
			return;
		}

		title.add(icon(rumour.icon, 20));
		title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		title.add(new OsrsLabel(rumour.name, OsrsSkin.LABEL, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		title.add(Box.createHorizontalGlue());
		cap(title);
		hero.add(title);

		int outfit = module.outfitPieces();
		int pity = rumour.pityFor(outfit);
		int caught = active == null ? 0 : active.caught;
		hero.add(Box.createVerticalStrut(3));
		StoneMeter meter = new StoneMeter(theme,
			active != null && active.pieceFound ? OsrsSkin.VALUE : OsrsSkin.PROGRESS_BLUE,
			pity == 0 ? 0 : Math.min(1.0, (double) caught / pity));
		meter.setAlignmentX(LEFT_ALIGNMENT);
		hero.add(meter);
		hero.add(Box.createVerticalStrut(2));
		JPanel prog = row(0);
		String progText = active != null && active.pieceFound
			? "Piece found · " + caught + " caught"
			: caught + " / " + pity + " to guaranteed piece";
		prog.add(new OsrsLabel(progText,
			active != null && active.pieceFound ? OsrsSkin.VALUE : OsrsSkin.FAINT,
			OsrsSkin.smallFont()).leftAligned());
		prog.add(Box.createHorizontalGlue());
		cap(prog);
		hero.add(prog);
		cap(hero);
		content.add(hero);
		content.add(Box.createVerticalStrut(4));

		content.add(statRow("Trap", rumour.trap));
		content.add(Box.createVerticalStrut(4));
		content.add(statRow("Hunter level", String.valueOf(rumour.level)));
		HunterRumoursPack.Hunter hunter = active == null ? null
			: module.pack().hunters.stream().filter(h -> h.id.equals(active.hunterId))
				.findFirst().orElse(null);
		if (hunter != null)
		{
			content.add(Box.createVerticalStrut(4));
			content.add(statRow("Assigned by", "Guild Hunter " + hunter.name));
		}

		String missing = missingText(rumour.reqs);
		if (missing != null)
		{
			content.add(textLine("Needs: " + missing, UiTokens.STATUS_WARNING, OsrsSkin.smallFont()));
		}
		if (outfit > 0)
		{
			content.add(textLine(outfit + "/4 Guild hunter outfit — pity rate " + pity
				+ " (was " + rumour.pity + ")", OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}

		content.add(section("Gear"));
		PersistedState.SavedSetup setup = module.rumourSetup();
		if (setup == null)
		{
			content.add(textLine("No setup saved for " + rumour.trap.toLowerCase() + " rumours",
				OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		else
		{
			content.add(textLine("Setup saved for " + rumour.trap.toLowerCase()
				+ " rumours — the Loadout tab shows it", OsrsSkin.VALUE, OsrsSkin.smallFont()));
		}
		JPanel gearButtons = row(2);
		StoneButton save = new StoneButton(theme,
			setup == null ? "Save current gear" : "Replace with current gear",
			module::saveRumourSetup);
		save.setMaximumSize(save.getPreferredSize());
		gearButtons.add(save);
		if (setup != null)
		{
			gearButtons.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			boolean armed = module.bankShowArmed();
			StoneButton show = new StoneButton(theme,
				armed ? "Stop bank layout" : "Show in bank",
				() ->
				{
					module.setBankShow(!armed);
					javax.swing.SwingUtilities.invokeLater(this::rebuild);
				});
			show.setMaximumSize(show.getPreferredSize());
			gearButtons.add(show);
		}
		gearButtons.add(Box.createHorizontalGlue());
		cap(gearButtons);
		content.add(gearButtons);
		if (module.bankShowArmed())
		{
			content.add(textLine("Opening the bank lays this setup out",
				OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}

		List<HunterRumoursPack.Location> areas = module.pack().locationsFor(rumour.creature);
		if (!areas.isEmpty())
		{
			content.add(section("Hunting locations"));
			String preferredName = preferredName(rumour, areas);
			for (HunterRumoursPack.Location area : areas)
			{
				content.add(locationRow(rumour, area, area.name.equals(preferredName)));
			}
			content.add(textLine("Click a location to prefer it", OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
	}

	private String preferredName(HunterRumoursPack.Rumour rumour, List<HunterRumoursPack.Location> areas)
	{
		HunterRumoursPack.Location pref = module.preferredLocation(rumour);
		return pref == null ? null : pref.name;
	}

	private JComponent locationRow(HunterRumoursPack.Rumour rumour, HunterRumoursPack.Location area,
		boolean preferred)
	{
		JPanel row = row(2);
		String label = area.name + (area.spots > 1 ? " · " + area.spots + " spots" : "")
			+ (area.fairyRing != null ? " · " + area.fairyRing : "");
		OsrsLabel name = new OsrsLabel(label + (preferred ? " · preferred" : ""),
			preferred ? OsrsSkin.TITLE : OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable();
		name.setToolTipText(area.name + (area.fairyRing != null ? " (fairy ring " + area.fairyRing + ")" : ""));
		row.add(name);
		row.add(Box.createHorizontalGlue());
		StoneButton route = new StoneButton(theme, "Route", () -> module.route(area.worldPoint()));
		route.setMaximumSize(route.getPreferredSize());
		row.add(route);
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		row.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				module.setPreferredLocation(rumour.creature, preferred ? null : area.name);
			}
		});
		cap(row);
		return row;
	}

	// ── History view ──────────────────────────────────────────────────

	private void rebuildHistory()
	{
		List<PersistedState.RumourRecord> records = new ArrayList<>(module.records());
		if (records.isEmpty())
		{
			content.add(faintLine("No rumours recorded yet."));
			return;
		}
		java.util.Collections.reverse(records);
		int shown = 0;
		for (PersistedState.RumourRecord record : records)
		{
			if (shown++ >= MAX_HISTORY)
			{
				content.add(faintLine("+ " + (records.size() - MAX_HISTORY) + " older"));
				break;
			}
			content.add(historyRow(record));
			content.add(Box.createVerticalStrut(2));
		}
	}

	private JComponent historyRow(PersistedState.RumourRecord record)
	{
		HunterRumoursPack.Rumour rumour = module.pack().rumour(record.rumourId);
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row(0);
		if (rumour != null)
		{
			top.add(icon(rumour.icon, 16));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		top.add(new OsrsLabel(rumour == null ? record.rumourId : rumour.name,
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(record.end == 0 ? "active" : record.pieceFound ? "done" : "ended",
			record.end == 0 ? OsrsSkin.TITLE : record.pieceFound ? OsrsSkin.VALUE : OsrsSkin.FAINT,
			OsrsSkin.smallFont()));
		cap(top);
		card.add(top);

		JPanel bottom = row(0);
		bottom.add(new OsrsLabel(record.caught + (record.caught == 1 ? " catch" : " catches")
			+ (record.start > 0 ? " · " + durationText(
				(record.end == 0 ? System.currentTimeMillis() : record.end) - record.start) : ""),
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		bottom.add(Box.createHorizontalGlue());
		cap(bottom);
		card.add(bottom);
		cap(card);
		return card;
	}

	static String durationText(long ms)
	{
		long minutes = ms / 60_000;
		if (minutes < 60)
		{
			return Math.max(1, minutes) + "m";
		}
		return (minutes / 60) + "h " + (minutes % 60) + "m";
	}

	// ── shared bits ───────────────────────────────────────────────────

	private String missingText(List<String> reqs)
	{
		List<String> missing = new ArrayList<>();
		for (String req : reqs)
		{
			Requirement parsed = Requirements.parse(req);
			if (!parsed.isMet(state))
			{
				for (Requirement leaf : parsed.missing(state))
				{
					missing.add(leaf.describe());
				}
			}
		}
		return missing.isEmpty() ? null : String.join(", ", missing);
	}

	private JLabel icon(int itemId, int size)
	{
		JLabel holder = new JLabel();
		Dimension d = new Dimension(size, size);
		holder.setPreferredSize(d);
		holder.setMinimumSize(d);
		holder.setMaximumSize(d);
		java.awt.Image sprite = sprites.get(itemId, -1, size);
		if (sprite != null)
		{
			holder.setIcon(new ImageIcon(sprite));
		}
		return holder;
	}

	private JPanel row(int vpad)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(vpad, UiTokens.ROW_GAP, vpad, UiTokens.ROW_GAP));
		return row;
	}

	private JComponent textLine(String text, Color color, java.awt.Font font)
	{
		JPanel holder = row(1);
		holder.add(OsrsLabel.wrapped(text, 195, color, font).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private JComponent faintLine(String text)
	{
		return textLine(text, OsrsSkin.FAINT, OsrsSkin.font());
	}

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

	private JComponent statRow(String label, String value)
	{
		StonePanel row = new StonePanel(theme);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(new OsrsLabel(label, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		row.add(Box.createHorizontalGlue());
		row.add(OsrsLabel.value(value));
		cap(row);
		return row;
	}

	private static void cap(JComponent c)
	{
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
