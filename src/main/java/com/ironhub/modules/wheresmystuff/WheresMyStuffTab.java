package com.ironhub.modules.wheresmystuff;

import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;

/**
 * Where's my stuff tab: every storage Iron Hub has seen you use, grouped by
 * family, each expandable to its last-seen contents with an "as of last visit"
 * provenance line. Honest — a storage you have never opened is simply absent,
 * never shown empty. Frameless (the host names the module).
 */
class WheresMyStuffTab extends JPanel
{
	private static final Color AMBER = new Color(224, 162, 60);
	private static final int ITEM_CAP = 50;

	private final AccountState state;
	private final WheresMyStuffModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless — sprites skipped
	private final Runnable listener = RebuildGate.install(this, this::rebuild);
	private final SpriteCache sprites;

	private final JPanel content = new JPanel();
	private final com.ironhub.ui.v2.V2TextField search;
	private String expanded; // storage id, or null

	WheresMyStuffTab(AccountState state, WheresMyStuffModule module, OsrsTheme theme,
		ItemManager itemManager)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.itemManager = itemManager;
		this.sprites = new SpriteCache(itemManager, listener);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		// mounted once so it keeps focus — only `content` is rebuilt
		search = new com.ironhub.ui.v2.V2TextField(theme, "Find an item across your stuff…", null);
		search.setAlignmentX(LEFT_ALIGNMENT);
		search.editor().getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuild();
			}

			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuild();
			}

			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				rebuild();
			}
		});
		add(search);
		add(Box.createVerticalStrut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		// STORAGE-scoped: the tab renders storage snapshots only, and an
		// unscoped listener rebuilt the visible tab on every LOOT/SKILLS/
		// BANK ingestion change (untagged broadcasts still deliver)
		state.addListener(listener, com.ironhub.state.AccountState.Topic.STORAGE);
		rebuild();
	}

	private String query()
	{
		String q = search == null ? "" : search.getText().trim();
		return q.equalsIgnoreCase("Find an item across your stuff…") ? "" : q;
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seam: expand one storage (null collapses). */
	void expand(String key)
	{
		expanded = key;
		rebuild();
	}

	/** Test seam: run a whole-account search. */
	void searchFor(String q)
	{
		search.setText(q);
		rebuild();
	}

	void rebuild()
	{
		content.removeAll();
		Map<String, PersistedState.StorageSnapshot> all = state.getStorageContents();
		if (all.isEmpty())
		{
			content.add(line("Open a storage — a POH costume case, a STASH unit, a "
				+ "boat's hold — and Iron Hub will remember what you keep there, so it "
				+ "knows where your stuff is even when it's not in your bank.",
				OsrsSkin.FAINT));
			finish();
			return;
		}
		if (!query().isEmpty())
		{
			addSearchResults(all, query());
			finish();
			return;
		}
		addHero(all);

		// group by family (its label), each storage a row, biggest first
		Map<String, List<PersistedState.StorageSnapshot>> byFamily = new LinkedHashMap<>();
		all.forEach((key, snap) -> byFamily
			.computeIfAbsent(snap.family, f -> new ArrayList<>()).add(snap));
		for (Map.Entry<String, List<PersistedState.StorageSnapshot>> fam : byFamily.entrySet())
		{
			List<PersistedState.StorageSnapshot> snaps = fam.getValue();
			snaps.sort(Comparator.comparingInt((PersistedState.StorageSnapshot s) -> s.items.size())
				.reversed());
			content.add(familyHeader(snaps.get(0)));
			for (PersistedState.StorageSnapshot snap : snaps)
			{
				addStorageRow(all, snap);
			}
		}
		content.add(line("As of your last visit to each place.", OsrsSkin.FAINT));
		finish();
	}

	private void finish()
	{
		content.revalidate();
		content.repaint();
	}

	// ── whole-account search: "where is item X?" ──────────────────────

	private void addSearchResults(Map<String, PersistedState.StorageSnapshot> all, String query)
	{
		String q = query.toLowerCase();
		// one row per (storage, item) whose name matches, name-sorted
		List<Object[]> hits = new ArrayList<>(); // {snapshot, itemId, qty}
		for (PersistedState.StorageSnapshot snap : all.values())
		{
			for (Map.Entry<Integer, Integer> item : snap.items.entrySet())
			{
				if (nameOf(snap, item.getKey()).toLowerCase().contains(q))
				{
					hits.add(new Object[]{snap, item.getKey(), item.getValue()});
				}
			}
		}
		hits.sort(Comparator.comparing(h -> nameOf((PersistedState.StorageSnapshot) h[0],
			(Integer) h[1]).toLowerCase()));

		JPanel head = rowLine();
		head.setBorder(new EmptyBorder(2, 4, 3, 4));
		head.add(new OsrsLabel(hits.isEmpty() ? "Nothing tracked matches"
			: hits.size() + (hits.size() == 1 ? " match" : " matches"),
			hits.isEmpty() ? OsrsSkin.FAINT : OsrsSkin.VALUE, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		head.add(Box.createHorizontalGlue());
		cap(head);
		content.add(head);

		int shown = 0;
		for (Object[] hit : hits)
		{
			if (shown++ >= ITEM_CAP)
			{
				content.add(sub("+ " + (hits.size() - ITEM_CAP) + " more — refine your search",
					OsrsSkin.FAINT));
				break;
			}
			PersistedState.StorageSnapshot snap = (PersistedState.StorageSnapshot) hit[0];
			content.add(searchHitRow(snap, (Integer) hit[1], (Integer) hit[2]));
		}
		if (hits.isEmpty())
		{
			content.add(line("Iron Hub only knows the storages you've opened. Open the "
				+ "place you think it's in, or check your bank.", OsrsSkin.FAINT));
		}
	}

	/** A search hit: sprite · item name · the storage it's in (label). */
	private JComponent searchHitRow(PersistedState.StorageSnapshot snap, int id, int qty)
	{
		JPanel r = rows();
		JPanel top = rowLine();
		if (itemManager != null)
		{
			java.awt.Image sprite = sprites.getBox(id, 16);
			if (sprite != null)
			{
				top.add(new JLabel(new ImageIcon(sprite)));
				top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
		}
		top.add(new OsrsLabel(nameOf(snap, id) + (qty > 1 ? " ×" + qty : ""),
			OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		cap(top);
		r.add(top);
		JPanel where = rowLine();
		where.setBorder(new EmptyBorder(0, UiTokens.PAD, 1, 0));
		where.add(new OsrsLabel(snap.label.isEmpty() ? snap.name : snap.label,
			OsrsSkin.TITLE, OsrsSkin.smallFont()).leftAligned());
		where.add(Box.createHorizontalGlue());
		cap(where);
		r.add(where);
		cap(r);
		return r;
	}

	// ── hero ──────────────────────────────────────────────────────────

	private void addHero(Map<String, PersistedState.StorageSnapshot> all)
	{
		int items = 0;
		int nonEmpty = 0;
		for (PersistedState.StorageSnapshot snap : all.values())
		{
			int c = snap.items.values().stream().mapToInt(Integer::intValue).sum();
			items += c;
			if (c > 0)
			{
				nonEmpty++;
			}
		}
		// what is tracked is the one live readout on the page — the Card
		com.ironhub.ui.v2.V2Surface hero = com.ironhub.ui.v2.V2Surface.card(theme);
		JPanel head = rowLine();
		head.add(new OsrsLabel(items + (items == 1 ? " item tracked" : " items tracked"),
			OsrsSkin.VALUE, OsrsSkin.boldFont()).leftAligned().squeezable());
		head.add(Box.createHorizontalGlue());
		cap(head);
		hero.add(head);

		JPanel under = rowLine();
		under.add(new OsrsLabel("across " + nonEmpty
			+ (nonEmpty == 1 ? " storage" : " storages"), OsrsSkin.FAINT,
			OsrsSkin.smallFont()).leftAligned());
		under.add(Box.createHorizontalGlue());
		cap(under);
		hero.add(under);
		cap(hero);
		content.add(hero);
	}

	private JComponent familyHeader(PersistedState.StorageSnapshot any)
	{
		String suffix = any.label.length() > any.name.length()
			? any.label.substring(any.name.length()).trim() // "(PoH)"
			: "";
		JPanel row = rowLine();
		row.setBorder(new EmptyBorder(4, 4, 1, 4));
		row.add(new OsrsLabel(suffix.isEmpty() ? any.family : familyName(any.family),
			OsrsSkin.TITLE, OsrsSkin.smallFont()).leftAligned());
		row.add(Box.createHorizontalGlue());
		cap(row);
		return row;
	}

	private static String familyName(String family)
	{
		switch (family)
		{
			case "playerownedhouse": return "Player-owned house";
			case "stash": return "STASH units";
			case "carryable": return "Carried containers";
			case "coins": return "Coins";
			case "world": return "World storage";
			case "minigames": return "Minigames";
			case "sailing": return "Boat holds";
			case "death": return "On death";
			default: return family;
		}
	}

	// ── storage rows ──────────────────────────────────────────────────

	private void addStorageRow(Map<String, PersistedState.StorageSnapshot> all,
		PersistedState.StorageSnapshot snap)
	{
		String key = all.entrySet().stream()
			.filter(e -> e.getValue() == snap).map(Map.Entry::getKey).findFirst().orElse("");
		JPanel row = rows();

		int count = snap.items.values().stream().mapToInt(Integer::intValue).sum();
		JPanel top = rowLine();
		OsrsLabel name = new OsrsLabel(snap.name, OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable();
		top.add(name);
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(String.valueOf(count),
			count > 0 ? AMBER : OsrsSkin.FAINT, OsrsSkin.font()));
		cap(top);
		row.add(top);

		JPanel prov = rowLine();
		prov.setBorder(new EmptyBorder(0, 0, 1, 0));
		prov.add(new OsrsLabel(count == 0 ? "empty · as of " + ageText(snap.lastSeen)
			: "as of " + ageText(snap.lastSeen), OsrsSkin.FAINT, OsrsSkin.smallFont())
			.leftAligned());
		prov.add(Box.createHorizontalGlue());
		cap(prov);
		row.add(prov);

		if (key.equals(expanded))
		{
			List<Map.Entry<Integer, Integer>> items = new ArrayList<>(snap.items.entrySet());
			items.sort((a, b) -> nameOf(snap, a.getKey()).compareToIgnoreCase(nameOf(snap, b.getKey())));
			int shown = 0;
			for (Map.Entry<Integer, Integer> item : items)
			{
				if (shown++ >= ITEM_CAP)
				{
					break;
				}
				row.add(itemRow(snap, item.getKey(), item.getValue()));
			}
			if (items.size() > ITEM_CAP)
			{
				row.add(sub("+ " + (items.size() - ITEM_CAP) + " more", OsrsSkin.FAINT));
			}
		}
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expanded = key.equals(expanded) ? null : key;
				SwingUtilities.invokeLater(WheresMyStuffTab.this::rebuild);
			}
		});
		cap(row);
		content.add(row);
	}

	private JComponent itemRow(PersistedState.StorageSnapshot snap, int id, int qty)
	{
		JPanel r = rowLine();
		r.setBorder(new EmptyBorder(1, UiTokens.PAD, 1, 0));
		if (itemManager != null)
		{
			java.awt.Image sprite = sprites.getBox(id, 16);
			Icon icon = sprite == null ? null : new ImageIcon(sprite);
			if (icon != null)
			{
				r.add(new JLabel(icon));
				r.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
		}
		r.add(new OsrsLabel(nameOf(snap, id), OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		r.add(Box.createHorizontalGlue());
		if (qty > 1)
		{
			r.add(new OsrsLabel("×" + qty, OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		cap(r);
		return r;
	}

	private String nameOf(PersistedState.StorageSnapshot snap, int id)
	{
		String n = snap.itemNames.get(id);
		if (n != null && !n.isEmpty())
		{
			return n;
		}
		n = state.itemName(id);
		return n != null && !n.isEmpty() ? n : "Item " + id;
	}

	/** Coarse "how long ago", honest to the day above 48h. */
	private static String ageText(long lastSeen)
	{
		if (lastSeen <= 0)
		{
			return "?";
		}
		long ms = Math.max(0, System.currentTimeMillis() - lastSeen);
		long mins = ms / 60_000;
		if (mins < 1)
		{
			return "just now";
		}
		if (mins < 60)
		{
			return mins + (mins == 1 ? " minute ago" : " minutes ago");
		}
		long hours = mins / 60;
		if (hours < 48)
		{
			return hours + (hours == 1 ? " hour ago" : " hours ago");
		}
		long days = hours / 24;
		return days + " days ago";
	}

	// ── shared bits (BankSpaceTab grammar) ────────────────────────────

	private static JPanel rows()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		return row;
	}

	private static JPanel rowLine()
	{
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.setOpaque(false);
		top.setAlignmentX(LEFT_ALIGNMENT);
		return top;
	}

	private JComponent sub(String text, Color color)
	{
		JPanel holder = rowLine();
		holder.setBorder(new EmptyBorder(0, UiTokens.PAD, 1, 0));
		holder.add(OsrsLabel.wrapped(text, 190, color, OsrsSkin.smallFont()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private JComponent line(String text, Color color)
	{
		JPanel holder = rowLine();
		holder.setBorder(new EmptyBorder(2, 4, 2, 4));
		holder.add(OsrsLabel.wrapped(text, 195, color, OsrsSkin.smallFont()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
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
