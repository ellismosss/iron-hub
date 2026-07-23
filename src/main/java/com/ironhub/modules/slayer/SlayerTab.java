package com.ironhub.modules.slayer;

import com.ironhub.data.SlayerTasksPack;
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
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StoneMeter;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.QuantityFormatter;

/**
 * The slayer suite tab in the OSRS stonework skin: chip views Task ·
 * History · Unlocks · Blocks. Task = the hero card (progress, master,
 * area, points/streak), locations with a persisted preferred choice and
 * Shortest Path routing, the bring list with live ownership, bundled
 * monster stats, a Turael-skip hint and a per-task note. History = the
 * capped task records. Unlocks = the Slayer Rewards catalog against live
 * varbits. Blocks = per-master live block slots (the game's own varbits)
 * beside the player's preferred block/skip lists with point-cost advice.
 * Frameless — the host's header plate names the module.
 */
class SlayerTab extends JPanel
{
	private static final int MAX_HISTORY_ROWS = 20;
	private static final int BLOCK_COST = 100; // points, all masters
	private static final int SKIP_COST = 30;
	private static final Color ADVISE = ColorScheme.PROGRESS_INPROGRESS_COLOR;

	private final AccountState state;
	private final SlayerOptimizerModule module;
	private final OsrsTheme theme;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	/** One sprite request per id for the tab's life (2026-07-20 audit —
	 *  per-rebuild getImage+onLoaded stacked client-thread listeners,
	 *  SpriteCache's own documented failure mode). */
	private final com.ironhub.ui.components.SpriteCache sprites;

	private final StoneChipRow views;
	private final JPanel content = new JPanel();

	/** Blocks-view master selection, kept across rebuilds (name). */
	private String masterChoice;
	/** Notes field kept across rebuilds so typing survives state chatter. */
	private StoneTextField noteField;
	private String noteTask = "";

	SlayerTab(AccountState state, SlayerOptimizerModule module, OsrsTheme theme)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.sprites = new com.ironhub.ui.components.SpriteCache(module.itemManager(), listener);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		views = new StoneChipRow(theme, true, "Task", "History", "Unlocks", "Blocks");
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

	/** Test seam: switch the chip view (0 Task · 1 History · 2 Unlocks · 3 Blocks). */
	void selectView(int index)
	{
		views.setSelected(index);
		rebuild();
	}

	void rebuild()
	{
		saveNote(); // typing never lost to a state-driven rebuild
		content.removeAll();
		switch (views.getSelected())
		{
			case 1:
				rebuildHistory();
				break;
			case 2:
				rebuildUnlocks();
				break;
			case 3:
				rebuildBlocks();
				break;
			default:
				rebuildTask();
				break;
		}
		content.revalidate();
		content.repaint();
	}

	// ── Task view ─────────────────────────────────────────────────────

	private void rebuildTask()
	{
		String task = module.taskName();
		int remaining = module.remaining();
		SlayerTasksPack pack = module.pack();
		SlayerTasksPack.Task entry = pack == null ? null : pack.task(task);

		StonePanel hero = new StonePanel(theme);
		hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
		hero.setAlignmentX(LEFT_ALIGNMENT);
		JPanel title = row(0);
		if (entry != null && entry.icon > 0)
		{
			title.add(icon(entry.icon, 20));
			title.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		if (remaining > 0)
		{
			title.add(new OsrsLabel(task.isEmpty() ? "Task in progress" : task,
				OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned().squeezable());
			title.add(Box.createHorizontalGlue());
			title.add(new OsrsLabel(remaining + " left", OsrsSkin.VALUE, OsrsSkin.boldFont()));
		}
		else
		{
			title.add(new OsrsLabel("No task", OsrsSkin.LABEL, OsrsSkin.boldFont()).leftAligned());
			title.add(Box.createHorizontalGlue());
		}
		cap(title);
		hero.add(title);

		int assigned = module.initialAmount();
		if (remaining > 0 && assigned >= remaining)
		{
			hero.add(Box.createVerticalStrut(3));
			StoneMeter meter = new StoneMeter(theme, OsrsSkin.PROGRESS_BLUE,
				(double) (assigned - remaining) / assigned);
			meter.setAlignmentX(LEFT_ALIGNMENT);
			hero.add(meter);
			hero.add(Box.createVerticalStrut(2));
			JPanel counts = row(0);
			counts.add(new OsrsLabel((assigned - remaining) + " of " + assigned + " killed",
				OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
			counts.add(Box.createHorizontalGlue());
			cap(counts);
			hero.add(counts);
		}
		JPanel meta = row(0);
		String master = module.masterName();
		String area = module.areaName();
		String metaText = (master.isEmpty() ? "" : master)
			+ (area.isEmpty() ? "" : (master.isEmpty() ? "" : " · ") + area);
		if (remaining > 0 && !metaText.isEmpty())
		{
			meta.add(new OsrsLabel(metaText, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
			meta.add(Box.createHorizontalGlue());
			cap(meta);
			hero.add(Box.createVerticalStrut(2));
			hero.add(meta);
		}
		cap(hero);
		content.add(hero);
		content.add(Box.createVerticalStrut(4));

		// the task's setup now renders through Gear & Combat's shared viewer
		// (the "Slayer" source chip) — the old top-of-tab viewers are gone
		// (Luke, 2026-07-21); the missing-item bank glow stays module-side

		// Slayer points + Task streak share one row (Luke, 2026-07-21)
		JPanel stats = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
		stats.setOpaque(false);
		stats.setAlignmentX(LEFT_ALIGNMENT);
		stats.add(statBox("Slayer points", QuantityFormatter.formatNumber(module.points())));
		stats.add(statBox("Task streak", QuantityFormatter.formatNumber(module.streak())));
		stats.setMaximumSize(new Dimension(Integer.MAX_VALUE, stats.getPreferredSize().height));
		content.add(stats);

		PersistedState.SlayerTaskRecord active = module.activeRecord();
		if (active != null && remaining > 0)
		{
			content.add(Box.createVerticalStrut(4));
			content.add(faintLine(taskStatsLine(active, System.currentTimeMillis())));
		}

		if (entry == null)
		{
			return;
		}

		if (entry.turael != null)
		{
			content.add(section("Turael skip"));
			for (SlayerTasksPack.TuraelLocation location : entry.turael.locations)
			{
				content.add(textLine(location.name, OsrsSkin.MUTED, OsrsSkin.font()));
				for (String teleport : location.teleports)
				{
					content.add(textLine("- " + teleport, OsrsSkin.FAINT, OsrsSkin.smallFont()));
				}
			}
			if (entry.turael.note != null)
			{
				content.add(textLine(entry.turael.note, OsrsSkin.FAINT, OsrsSkin.smallFont()));
			}
			if (entry.turael.worldPoint() != null)
			{
				content.add(Box.createVerticalStrut(3));
				StoneButton route = new StoneButton(theme, "Route",
					() -> module.route(entry.turael.worldPoint()));
				route.setAlignmentX(LEFT_ALIGNMENT);
				content.add(route);
			}
		}

		if (entry.locations != null && !entry.locations.isEmpty())
		{
			// section rows ride a stone slab now (Luke, 2026-07-21); the
			// click-to-prefer hint lives in the row tooltips, not a line
			StonePanel locations = slab("Locations");
			String preferred = state.getSlayerLocationPref(entry.name);
			for (SlayerTasksPack.Location location : entry.locations)
			{
				locations.add(locationRow(entry, location,
					location.name.equalsIgnoreCase(preferred)));
			}
			addSlab(locations);
		}

		if (entry.bring != null && !entry.bring.isEmpty())
		{
			StonePanel bring = slab("Bring");
			// protection alternatives render as ONE any-of row — the slayer
			// helmet replaces the facemask family (Luke, 2026-07-21)
			for (List<SlayerTasksPack.BringItem> group
				: SlayerOptimizerModule.bringGroups(entry.bring))
			{
				bring.add(bringRow(group));
			}
			addSlab(bring);
		}
		if (entry.finisher != null)
		{
			content.add(textLine("Finish with the item at " + entry.finisher.threshold
				+ " hp or below", OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}

		if (entry.stats != null)
		{
			StonePanel monster = slab("Monster");
			for (String line : statsLines(entry.stats))
			{
				monster.add(textLine(line, OsrsSkin.MUTED, OsrsSkin.smallFont()));
			}
			if (Boolean.TRUE.equals(entry.stats.cannonImmune))
			{
				monster.add(textLine("Cannon immune", UiTokens.STATUS_WARNING, OsrsSkin.smallFont()));
			}
			addSlab(monster);
		}

		// no Gear section at all (Luke, 2026-07-21): the task's setup lives
		// in Gear & Combat's "Slayer" view — saving under the task's name
		// there IS the task setup

		content.add(section("Notes"));
		if (noteField == null || !noteTask.equals(entry.name))
		{
			noteField = new StoneTextField(theme, "Add a note for this task…");
			noteField.setText(state.getSlayerNote(entry.name));
			noteTask = entry.name;
			noteField.addActionListener(e -> saveNote());
			noteField.addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusLost(java.awt.event.FocusEvent e)
				{
					saveNote();
				}
			});
		}
		noteField.setAlignmentX(LEFT_ALIGNMENT);
		noteField.setMaximumSize(new Dimension(Integer.MAX_VALUE, noteField.getPreferredSize().height));
		content.add(noteField);
	}

	private void saveNote()
	{
		if (noteField != null && !noteTask.isEmpty()
			&& !noteField.getText().equals(state.getSlayerNote(noteTask)))
		{
			state.setSlayerNote(noteTask, noteField.getText());
		}
	}

	private JComponent locationRow(SlayerTasksPack.Task entry, SlayerTasksPack.Location location,
		boolean preferred)
	{
		JPanel row = row(2);
		String missing = missingText(location.reqs);
		Color color = missing == null ? OsrsSkin.MUTED : OsrsSkin.FAINT;
		String text = location.name + (preferred ? " · preferred" : "");
		OsrsLabel name = new OsrsLabel(text, preferred ? OsrsSkin.TITLE : color, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(missing != null ? "Needs: " + missing
			: preferred ? "Preferred — click to unprefer" : "Click to prefer this location");
		row.add(name);
		row.add(Box.createHorizontalGlue());
		if (location.worldPoint() != null)
		{
			StoneButton route = new StoneButton(theme, "Route",
				() -> module.route(location.worldPoint()));
			route.setMaximumSize(route.getPreferredSize());
			row.add(route);
		}
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		row.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				state.setSlayerLocationPref(entry.name,
					preferred ? null : location.name); // toggles; listener rebuilds
			}
		});
		cap(row);
		return row;
	}

	/** One bring row — a singleton item, or a group of protection
	 *  ALTERNATIVES rendered "Facemask or Slayer helmet" and satisfied by
	 *  owning ANY member (variants count — an imbued helm still counts). */
	private JComponent bringRow(List<SlayerTasksPack.BringItem> group)
	{
		JPanel row = row(2);
		boolean owned = false;
		boolean required = false;
		SlayerTasksPack.BringItem iconItem = null;
		for (SlayerTasksPack.BringItem item : group)
		{
			required |= item.required;
			boolean has = item.id != null && state.canonicalStock(item.id) > 0;
			owned |= has;
			if (iconItem == null || (has && !(iconItem.id != null
				&& state.canonicalStock(iconItem.id) > 0)))
			{
				iconItem = item; // the first owned member's sprite, else the first
			}
		}
		if (iconItem.id != null)
		{
			row.add(icon(iconItem.id, 16));
			row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		String label = group.stream().map(i -> i.name)
			.collect(java.util.stream.Collectors.joining(" or "));
		Color color = owned ? OsrsSkin.VALUE
			: required ? UiTokens.STATUS_WARNING : OsrsSkin.FAINT;
		OsrsLabel name = new OsrsLabel(label, color, OsrsSkin.font()).leftAligned().squeezable();
		String tip = group.size() > 1
			? (owned ? "Any one of these works — you own one"
				: "Any one of these works — you own none")
			: group.get(0).id == null ? label
			: owned ? "Owned" : required ? "Required — you own none" : "Suggested — you own none";
		if (!owned)
		{
			// where an unowned bring item comes from (the KB projection)
			String sources = null;
			for (SlayerTasksPack.BringItem item : group)
			{
				sources = item.id == null || module.itemSources() == null
					? null : module.itemSources().sourceLine(item.id, state,
						state.getItemSourcePref(item.id));
				if (sources != null)
				{
					break;
				}
			}
			if (sources != null)
			{
				tip = "<html>" + tip + "<br>" + sources + "</html>";
			}
		}
		name.setToolTipText(tip);
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(required ? "required" : "suggested",
			OsrsSkin.FAINT, OsrsSkin.smallFont()));
		cap(row);
		return row;
	}

	// ── History view ──────────────────────────────────────────────────

	private void rebuildHistory()
	{
		List<PersistedState.SlayerTaskRecord> records = new ArrayList<>(module.records());
		if (records.isEmpty())
		{
			content.add(faintLine("No tasks recorded yet — get an assignment."));
			return;
		}
		java.util.Collections.reverse(records); // newest first
		int shown = 0;
		for (PersistedState.SlayerTaskRecord record : records)
		{
			if (shown++ >= MAX_HISTORY_ROWS)
			{
				content.add(faintLine("+ " + (records.size() - MAX_HISTORY_ROWS) + " older tasks"));
				break;
			}
			content.add(historyRow(record));
			content.add(Box.createVerticalStrut(2));
		}
	}

	private JComponent historyRow(PersistedState.SlayerTaskRecord record)
	{
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = row(0);
		SlayerTasksPack pack = module.pack();
		SlayerTasksPack.Task entry = pack == null ? null : pack.task(record.name);
		if (entry != null && entry.icon > 0)
		{
			top.add(icon(entry.icon, 16));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		String title = record.name + (record.assigned > 0 ? " ×" + record.assigned : "");
		top.add(new OsrsLabel(title, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(statusText(record),
			record.end == 0 ? OsrsSkin.TITLE : record.completed ? OsrsSkin.VALUE : ADVISE,
			OsrsSkin.smallFont()));
		cap(top);
		card.add(top);

		JPanel bottom = row(0);
		String master = record.master.isEmpty() ? "" : record.master + " · ";
		bottom.add(new OsrsLabel(master + taskStatsLine(record,
			record.end == 0 ? System.currentTimeMillis() : record.end),
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned().squeezable());
		bottom.add(Box.createHorizontalGlue());
		cap(bottom);
		card.add(bottom);
		cap(card);
		return card;
	}

	/** "132 kills · 45.2K xp · 210K gp · 1h 12m" — honest zeros dropped. */
	static String taskStatsLine(PersistedState.SlayerTaskRecord record, long nowMs)
	{
		List<String> parts = new ArrayList<>();
		parts.add(record.killed + (record.killed == 1 ? " kill" : " kills"));
		if (record.xpGained > 0)
		{
			parts.add(QuantityFormatter.quantityToStackSize(record.xpGained) + " xp");
		}
		if (record.lootValue > 0)
		{
			parts.add(QuantityFormatter.quantityToStackSize(record.lootValue) + " gp");
		}
		if (record.start > 0 && nowMs > record.start)
		{
			parts.add(durationText(nowMs - record.start));
		}
		return String.join(" · ", parts);
	}

	static String statusText(PersistedState.SlayerTaskRecord record)
	{
		if (record.end == 0)
		{
			return "active";
		}
		return record.completed ? "done" : "skipped";
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

	// ── Unlocks view ──────────────────────────────────────────────────

	/** Redesigned 2026-07-21 (Luke: "dull and boring, way too much text"):
	 *  a points hero with an unlock-progress meter, then Unlocks/Extends
	 *  slabs of icon rows — the wiki's own reward icons, green when owned,
	 *  cost orange when affordable right now; the prose lives in tooltips. */
	private void rebuildUnlocks()
	{
		SlayerTasksPack pack = module.pack();
		if (pack == null)
		{
			content.add(faintLine("Slayer pack unavailable."));
			return;
		}
		int owned = 0;
		for (SlayerTasksPack.Unlock unlock : pack.unlocks)
		{
			if (module.unlockOwned(unlock))
			{
				owned++;
			}
		}
		StonePanel hero = new StonePanel(theme);
		hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
		hero.setAlignmentX(LEFT_ALIGNMENT);
		JPanel head = row(0);
		head.add(new OsrsLabel("Slayer points", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		head.add(Box.createHorizontalGlue());
		head.add(new OsrsLabel(QuantityFormatter.formatNumber(module.points()),
			OsrsSkin.VALUE, OsrsSkin.boldFont()));
		cap(head);
		hero.add(head);
		hero.add(Box.createVerticalStrut(3));
		StoneMeter meter = new StoneMeter(theme, OsrsSkin.PROGRESS_BLUE,
			pack.unlocks.isEmpty() ? 0 : owned / (double) pack.unlocks.size());
		meter.setAlignmentX(LEFT_ALIGNMENT);
		hero.add(meter);
		hero.add(Box.createVerticalStrut(2));
		JPanel counts = row(0);
		counts.add(new OsrsLabel(owned + " of " + pack.unlocks.size() + " unlocked",
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		counts.add(Box.createHorizontalGlue());
		cap(counts);
		hero.add(counts);
		cap(hero);
		content.add(hero);

		for (String category : List.of("unlock", "extend"))
		{
			StonePanel slab = slab(category.equals("unlock") ? "Unlocks" : "Extends");
			for (SlayerTasksPack.Unlock unlock : pack.unlocks)
			{
				if (category.equals(unlock.category))
				{
					slab.add(unlockRow(unlock));
				}
			}
			addSlab(slab);
		}
	}

	private JComponent unlockRow(SlayerTasksPack.Unlock unlock)
	{
		boolean has = module.unlockOwned(unlock);
		boolean affordable = !has && module.points() >= unlock.points;
		JPanel row = row(2);
		JLabel iconHolder = new JLabel();
		Dimension d = new Dimension(16, 16);
		iconHolder.setPreferredSize(d);
		iconHolder.setMinimumSize(d);
		iconHolder.setMaximumSize(d);
		iconHolder.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		javax.swing.Icon wiki = unlockIcon(unlock.icon);
		if (wiki != null)
		{
			iconHolder.setIcon(wiki);
		}
		row.add(iconHolder);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		OsrsLabel name = new OsrsLabel(unlock.name,
			has ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable();
		String tip = unlock.desc == null ? unlock.name : unlock.desc;
		name.setToolTipText("<html><div style='width:180px'>" + tip
			+ (has ? "<br>Unlocked" : affordable ? "<br>You can afford this now" : "")
			+ "</div></html>");
		row.add(name);
		row.add(Box.createHorizontalGlue());
		row.add(new OsrsLabel(unlock.points + " pts",
			has ? OsrsSkin.FAINT : affordable ? OsrsSkin.TITLE : OsrsSkin.LABEL,
			OsrsSkin.smallFont()));
		cap(row);
		return row;
	}

	/** Bundled wiki reward icons, box-fit to 16px, cached per tab (a miss
	 *  caches null — honest empty holder, never a placeholder guess). */
	private final java.util.Map<String, javax.swing.ImageIcon> unlockIcons = new java.util.HashMap<>();

	private javax.swing.Icon unlockIcon(String file)
	{
		if (file == null)
		{
			return null;
		}
		if (unlockIcons.containsKey(file))
		{
			return unlockIcons.get(file);
		}
		javax.swing.ImageIcon icon = null;
		try (java.io.InputStream in =
			SlayerTab.class.getResourceAsStream("/data/icons/slayer/" + file))
		{
			if (in != null)
			{
				java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
				if (img != null)
				{
					int max = Math.max(img.getWidth(), img.getHeight());
					icon = max <= 16 ? new javax.swing.ImageIcon(img)
						: new javax.swing.ImageIcon(img.getScaledInstance(
							Math.max(1, img.getWidth() * 16 / max),
							Math.max(1, img.getHeight() * 16 / max),
							java.awt.Image.SCALE_SMOOTH));
				}
			}
		}
		catch (java.io.IOException ignored)
		{
		}
		unlockIcons.put(file, icon);
		return icon;
	}

	// ── Blocks view ───────────────────────────────────────────────────

	/** Redesigned 2026-07-21 (same brief as Unlocks): master selector over a
	 *  master hero card, then Blocked-now / Preferred-blocks / Always-skip
	 *  slabs whose rows carry each task's own item sprite. */
	private void rebuildBlocks()
	{
		SlayerTasksPack pack = module.pack();
		if (pack == null)
		{
			content.add(faintLine("Slayer pack unavailable."));
			return;
		}
		if (masterChoice == null)
		{
			String current = module.masterName();
			masterChoice = current.isEmpty() ? pack.masters.get(pack.masters.size() - 1).name : current;
		}
		JComboBox<String> masterBox = new JComboBox<>();
		for (SlayerTasksPack.Master master : pack.masters)
		{
			masterBox.addItem(master.name);
		}
		masterBox.setSelectedItem(masterChoice);
		StoneComboBoxUI.skin(masterBox, theme);
		masterBox.setAlignmentX(LEFT_ALIGNMENT);
		masterBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, masterBox.getPreferredSize().height));
		masterBox.addActionListener(e ->
		{
			String picked = (String) masterBox.getSelectedItem();
			if (picked != null && !picked.equals(masterChoice))
			{
				masterChoice = picked;
				javax.swing.SwingUtilities.invokeLater(this::rebuild);
			}
		});
		content.add(masterBox);

		SlayerTasksPack.Master master = pack.masterByName(masterChoice);
		if (master == null)
		{
			return;
		}
		content.add(Box.createVerticalStrut(4));
		StonePanel masterCard = new StonePanel(theme);
		masterCard.setLayout(new BoxLayout(masterCard, BoxLayout.Y_AXIS));
		masterCard.setAlignmentX(LEFT_ALIGNMENT);
		JPanel points = row(0);
		points.add(new OsrsLabel("Points per task", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		points.add(Box.createHorizontalGlue());
		points.add(new OsrsLabel(String.valueOf(master.points),
			OsrsSkin.VALUE, OsrsSkin.boldFont()));
		cap(points);
		masterCard.add(points);
		if (master.wilderness)
		{
			JPanel wildy = row(0);
			wildy.add(new OsrsLabel("Separate wilderness streak",
				OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
			wildy.add(Box.createHorizontalGlue());
			cap(wildy);
			masterCard.add(wildy);
		}
		String missing = missingText(master.reqs);
		if (missing != null)
		{
			JPanel needs = row(0);
			OsrsLabel needsLabel = new OsrsLabel("Needs: " + missing, ADVISE, OsrsSkin.smallFont())
				.leftAligned().squeezable();
			needsLabel.setToolTipText("<html><div style='width:180px'>Needs: "
				+ missing + "</div></html>");
			needs.add(needsLabel);
			needs.add(Box.createHorizontalGlue());
			cap(needs);
			masterCard.add(needs);
		}
		cap(masterCard);
		content.add(masterCard);

		// live blocked slots from the game's own varbits
		StonePanel blocked = slab("Blocked now");
		List<Integer> blockedIds = module.blockedTaskIds(master.focusId);
		List<String> blockedNames = new ArrayList<>();
		if (blockedIds.isEmpty())
		{
			blocked.add(textLine("No blocked tasks at " + master.name + ".",
				OsrsSkin.FAINT, OsrsSkin.font()));
		}
		for (Integer taskId : blockedIds)
		{
			String name = module.taskNameById(taskId);
			if (name != null)
			{
				blockedNames.add(name.toLowerCase(Locale.ROOT));
			}
			blocked.add(taskRow(name == null ? "Task #" + taskId : name,
				taskIconId(name), OsrsSkin.MUTED, null));
		}
		addSlab(blocked);

		StonePanel blocks = slab("Preferred blocks");
		List<String> prefs = state.getSlayerBlockPref(master.name);
		prefList(blocks, master, prefs, blockedNames,
			list -> state.setSlayerBlockPref(master.name, list),
			"Block", BLOCK_COST);
		addSlab(blocks);

		StonePanel skipSlab = slab("Always skip");
		List<String> skips = state.getSlayerSkipPref(master.name);
		prefList(skipSlab, master, skips, List.of(),
			list -> state.setSlayerSkipPref(master.name, list),
			null, SKIP_COST);
		if (!skips.isEmpty())
		{
			skipSlab.add(textLine("Skipping costs " + SKIP_COST + " pts per task",
				OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		addSlab(skipSlab);
	}

	/** The pack's item-sprite id for a task name, or 0. */
	private int taskIconId(String taskName)
	{
		SlayerTasksPack pack = module.pack();
		SlayerTasksPack.Task entry = pack == null || taskName == null
			? null : pack.task(taskName);
		return entry == null ? 0 : entry.icon;
	}

	/** One task row: 16px task sprite · name · optional trailing control. */
	private JComponent taskRow(String name, int iconId, Color color, JComponent trailing)
	{
		JPanel row = row(2);
		JLabel holder = new JLabel();
		Dimension d = new Dimension(16, 16);
		holder.setPreferredSize(d);
		holder.setMinimumSize(d);
		holder.setMaximumSize(d);
		if (iconId > 0)
		{
			java.awt.Image sprite = sprites.get(iconId, -1, 16);
			if (sprite != null)
			{
				holder.setIcon(new ImageIcon(sprite));
			}
		}
		row.add(holder);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		row.add(new OsrsLabel(name, color, OsrsSkin.font()).leftAligned().squeezable());
		row.add(Box.createHorizontalGlue());
		if (trailing != null)
		{
			row.add(trailing);
		}
		cap(row);
		return row;
	}

	/** A preferred-task list inside its slab: icon rows with remove, an add
	 *  selector, and (for blocks) advice lines vs the live blocked slots. */
	private void prefList(StonePanel slab, SlayerTasksPack.Master master, List<String> current,
		List<String> liveBlockedLower, java.util.function.Consumer<List<String>> save,
		String adviseVerb, int cost)
	{
		for (String task : current)
		{
			boolean live = liveBlockedLower.contains(task.toLowerCase(Locale.ROOT));
			StoneButton remove = new StoneButton(theme, "×", () ->
			{
				List<String> next = new ArrayList<>(current);
				next.remove(task);
				save.accept(next);
			});
			remove.setMaximumSize(remove.getPreferredSize());
			slab.add(taskRow(task, taskIconId(task),
				live ? OsrsSkin.VALUE : OsrsSkin.MUTED, remove));
			if (adviseVerb != null && !live)
			{
				slab.add(textLine(adviseVerb + " " + task + " at " + master.name
					+ " — " + cost + " pts", ADVISE, OsrsSkin.smallFont()));
			}
		}

		// add selector: the master's assignable tasks by weight, heaviest first
		JComboBox<String> add = new JComboBox<>();
		add.addItem("Add a task…");
		List<SlayerTasksPack.Assignment> rows = new ArrayList<>(master.tasks);
		rows.sort(Comparator.comparingInt((SlayerTasksPack.Assignment a) -> -a.weight));
		for (SlayerTasksPack.Assignment assignment : rows)
		{
			SlayerTasksPack.Task entry = module.pack().task(assignment.task);
			String name = entry == null ? assignment.task : entry.name;
			if (current.stream().noneMatch(name::equalsIgnoreCase))
			{
				add.addItem(name + "  (w" + assignment.weight + ")");
			}
		}
		StoneComboBoxUI.skin(add, theme);
		add.setAlignmentX(LEFT_ALIGNMENT);
		add.setMaximumSize(new Dimension(Integer.MAX_VALUE, add.getPreferredSize().height));
		add.addActionListener(e ->
		{
			String picked = (String) add.getSelectedItem();
			if (picked != null && add.getSelectedIndex() > 0)
			{
				List<String> next = new ArrayList<>(current);
				next.add(picked.replaceAll("\\s+\\(w\\d+\\)$", ""));
				save.accept(next);
			}
		});
		slab.add(add);
	}

	// ── shared bits ───────────────────────────────────────────────────

	/** Unmet leaves of a req list as a comma line, or null when all met. */
	private String missingText(List<String> reqs)
	{
		if (reqs == null || reqs.isEmpty())
		{
			return null;
		}
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

	/** A half-width stone stat box: small muted label over the value
	 *  (points + streak share one row — Luke, 2026-07-21). */
	private JComponent statBox(String label, String value)
	{
		StonePanel box = new StonePanel(theme);
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.add(new OsrsLabel(label, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		box.add(Box.createVerticalStrut(1));
		box.add(OsrsLabel.value(value).leftAligned());
		return box;
	}

	/** A titled stone slab holding a section's rows (Luke, 2026-07-21). */
	private StonePanel slab(String title)
	{
		StonePanel slab = new StonePanel(theme);
		slab.setLayout(new BoxLayout(slab, BoxLayout.Y_AXIS));
		slab.setAlignmentX(LEFT_ALIGNMENT);
		JPanel head = row(0);
		head.add(new OsrsLabel(title, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		head.add(Box.createHorizontalGlue());
		cap(head);
		slab.add(head);
		slab.add(Box.createVerticalStrut(2));
		return slab;
	}

	private void addSlab(StonePanel slab)
	{
		content.add(Box.createVerticalStrut(6));
		cap(slab);
		content.add(slab);
	}

	/** Compact monster stat lines — only what the pack actually knows. */
	static List<String> statsLines(SlayerTasksPack.Stats stats)
	{
		List<String> lines = new ArrayList<>();
		List<String> top = new ArrayList<>();
		if (stats.level != null)
		{
			top.add("Combat " + stats.level);
		}
		if (stats.hp != null)
		{
			top.add("HP " + stats.hp);
		}
		if (stats.maxHit != null)
		{
			top.add("Max hit " + stats.maxHit);
		}
		if (stats.attackSpeed != null)
		{
			top.add("Speed " + stats.attackSpeed);
		}
		if (!top.isEmpty())
		{
			lines.add(String.join(" · ", top));
		}
		if (stats.attackStyle != null)
		{
			lines.add("Style " + stats.attackStyle);
		}
		List<String> def = new ArrayList<>();
		if (stats.defStab != null)
		{
			def.add("stab " + stats.defStab);
		}
		if (stats.defSlash != null)
		{
			def.add("slash " + stats.defSlash);
		}
		if (stats.defCrush != null)
		{
			def.add("crush " + stats.defCrush);
		}
		if (stats.defMagic != null)
		{
			def.add("magic " + stats.defMagic);
		}
		if (stats.defRanged != null)
		{
			def.add("ranged " + stats.defRanged);
		}
		if (!def.isEmpty())
		{
			lines.add("Def " + String.join(" · ", def));
		}
		if (stats.weakness != null)
		{
			lines.add("Weak to " + stats.weakness
				+ (stats.weaknessPercent != null ? " (" + stats.weaknessPercent + "%)" : ""));
		}
		if (stats.slayerXp != null && stats.slayerXp > 0)
		{
			String xp = stats.slayerXp == Math.floor(stats.slayerXp)
				? String.valueOf(stats.slayerXp.intValue()) : String.valueOf(stats.slayerXp);
			lines.add(xp + " Slayer xp per kill");
		}
		return lines;
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
