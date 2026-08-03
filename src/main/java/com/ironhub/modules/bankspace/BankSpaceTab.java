package com.ironhub.modules.bankspace;

import com.ironhub.data.BankStoragePack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2Surface;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;
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
 * Bank space saver tab: how many bank slots could move to dedicated
 * storage, an amber meter over your banked items, then one row per
 * storage location (checkbox switches the whole location, click to
 * expand its items — each with an ignore ×), and the ignored items
 * behind a restore row. Frameless — the host names the module.
 */
class BankSpaceTab extends JPanel
{
	private static final Color AMBER = new Color(224, 162, 60);
	private static final int ITEM_CAP = 50;

	private final AccountState state;
	private final BankSpaceModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless — sprites skipped
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	/** Async sprites via the cache: the old sized(getImage(id)) snapshotted
	 *  a still-blank AsyncBufferedImage with no onLoaded hook, so first-time
	 *  expansions rendered iconless until an unrelated rebuild (2026-07-20
	 *  audit). */
	private final com.ironhub.ui.components.SpriteCache sprites;

	private final JPanel content = new JPanel();
	private String expanded; // location id, or "ignored"

	BankSpaceTab(AccountState state, BankSpaceModule module, OsrsTheme theme,
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

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);
		add(Box.createVerticalGlue());

		state.addListener(listener, com.ironhub.state.AccountState.Topic.BANK,
			com.ironhub.state.AccountState.Topic.UNLOCKS);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seam: expand one location (null collapses). */
	void expand(String key)
	{
		expanded = key;
		rebuild();
	}

	void rebuild()
	{
		content.removeAll();
		if (module.pack() == null)
		{
			content.add(line("Bank-storage pack unavailable.", OsrsSkin.FAINT));
			finish();
			return;
		}
		int banked = state.getBankSnapshot().size();
		if (banked == 0)
		{
			content.add(line("Open your bank once and Iron Hub will point out "
				+ "everything that could live in a dedicated storage instead.",
				OsrsSkin.FAINT));
			finish();
			return;
		}
		addHero(banked);
		addBisToggle();
		List<BankSpaceModule.LocationReport> reports = module.reports();
		if (reports.isEmpty())
		{
			content.add(line("Nothing in your bank could move to dedicated "
				+ "storage - tidy!", OsrsSkin.VALUE));
		}
		for (BankSpaceModule.LocationReport report : reports)
		{
			addLocationRow(report);
		}
		addIgnored();
		content.add(line("As of your last bank visit · flagged items glow "
			+ "amber in the bank · shift-right-click there to flag or unflag",
			OsrsSkin.FAINT));
		finish();
	}

	private void finish()
	{
		content.revalidate();
		content.repaint();
	}

	// ── hero ──────────────────────────────────────────────────────────

	private void addHero(int banked)
	{
		int wasted = module.flaggedItems().size();
		// the wasted-slot count is the one live readout on the page — the Card
		V2Surface hero = V2Surface.card(theme);
		JPanel head = rowLine();
		head.add(new OsrsLabel(wasted + " could move to storage",
			wasted > 0 ? OsrsSkin.TITLE : OsrsSkin.VALUE, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		head.add(Box.createHorizontalGlue());
		cap(head);
		hero.add(head);

		hero.add(new V2ProgressBar(theme, V2ProgressBar.Size.METER).fill(AMBER)
			.fraction(banked == 0 ? 0 : (double) wasted / banked));

		JPanel under = rowLine();
		under.add(Box.createHorizontalGlue());
		under.add(new OsrsLabel("of " + banked + " banked items", OsrsSkin.FAINT,
			OsrsSkin.smallFont()));
		cap(under);
		hero.add(under);
		cap(hero);
		content.add(hero);
	}

	private void addBisToggle()
	{
		boolean flagBis = state.isBankStorageFlagBis();
		JPanel row = rowLine();
		row.setBorder(new EmptyBorder(2, 4, 3, 4));
		// the checkbox ATOM carries its own box, label, hover and hit target
		V2Checkbox box = new V2Checkbox(theme, "Flag best-in-slot gear too", flagBis,
			() -> state.setBankStorageFlagBis(!flagBis)); // listener rebuilds
		box.setToolTipText("Best-in-slot gear (armour case, cape rack, magic "
			+ "wardrobe pieces) is usually banked on purpose - tick to flag it too");
		row.add(box);
		cap(row);
		content.add(row);
	}

	// ── location rows ─────────────────────────────────────────────────

	private void addLocationRow(BankSpaceModule.LocationReport report)
	{
		BankStoragePack.Location location = report.location;
		JPanel row = rows();

		JPanel top = rowLine();
		V2Checkbox box = new V2Checkbox(theme, location.name, report.enabled,
			() -> state.toggleBankStorageLocation(location.id)); // listener rebuilds
		box.labelColor(report.enabled ? OsrsSkin.MUTED : OsrsSkin.FAINT);
		box.setToolTipText(report.enabled
			? "Stop flagging " + location.name + " items"
			: "Flag " + location.name + " items again");
		top.add(box);
		top.add(new OsrsLabel(String.valueOf(report.storable.size()),
			report.enabled ? AMBER : OsrsSkin.FAINT, OsrsSkin.font()));
		cap(top);
		row.add(top);

		if (location.id.equals(expanded))
		{
			int shown = 0;
			for (BankStoragePack.Entry entry : report.storable)
			{
				if (shown++ >= ITEM_CAP)
				{
					break;
				}
				row.add(itemRow(entry, false));
			}
			if (report.storable.size() > ITEM_CAP)
			{
				row.add(sub("+ " + (report.storable.size() - ITEM_CAP) + " more",
					OsrsSkin.FAINT));
			}
		}
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expanded = location.id.equals(expanded) ? null : location.id;
				SwingUtilities.invokeLater(BankSpaceTab.this::rebuild);
			}
		});
		cap(row);
		content.add(row);
	}

	// ── ignored items ─────────────────────────────────────────────────

	private void addIgnored()
	{
		List<BankStoragePack.Entry> ignored = module.ignoredInBank();
		if (ignored.isEmpty())
		{
			return;
		}
		JPanel row = rows();
		JPanel top = rowLine();
		top.add(new OsrsLabel("Ignored (" + ignored.size() + ")",
			OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
		top.add(Box.createHorizontalGlue());
		cap(top);
		row.add(top);
		if ("ignored".equals(expanded))
		{
			int shown = 0;
			for (BankStoragePack.Entry entry : ignored)
			{
				if (shown++ >= ITEM_CAP) // the same row-list law as the locations
				{
					row.add(sub("+ " + (ignored.size() - ITEM_CAP) + " more",
						OsrsSkin.FAINT));
					break;
				}
				row.add(itemRow(entry, true));
			}
		}
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expanded = "ignored".equals(expanded) ? null : "ignored";
				SwingUtilities.invokeLater(BankSpaceTab.this::rebuild);
			}
		});
		cap(row);
		content.add(row);
	}

	/** One item line: sprite, name (+ its storage when restoring), and the
	 *  ignore × / restore + glyph. */
	private JComponent itemRow(BankStoragePack.Entry entry, boolean restoring)
	{
		JPanel r = rowLine();
		r.setBorder(new EmptyBorder(1, UiTokens.PAD, 1, 0));
		if (itemManager != null)
		{
			java.awt.Image sprite = sprites.getBox(entry.id, 16);
			Icon icon = sprite == null ? null : new ImageIcon(sprite);
			if (icon != null)
			{
				r.add(new JLabel(icon));
				r.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
		}
		String text = entry.name + (restoring
			? " · " + module.locationNameOf(entry.id)
			: entry.bis ? " · bis" : "");
		OsrsLabel name = new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned().squeezable();
		// the hover answers the real question here: how re-obtainable is it?
		String source = module.itemSources() == null ? null
			: module.itemSources().sourceLine(entry.id, state, state.getItemSourcePref(entry.id));
		if (source != null)
		{
			name.setToolTipText("<html>" + entry.name + "<br>" + source + "</html>");
		}
		r.add(name);
		r.add(Box.createHorizontalGlue());
		r.add(glyph(restoring ? "+" : "×", restoring
				? "Flag " + entry.name + " again"
				: "Ignore " + entry.name + " (never flag it)",
			() -> state.toggleBankStorageIgnored(entry.id)));
		cap(r);
		return r;
	}

	// ── shared bits ───────────────────────────────────────────────────

	private static JComponent glyph(String text, String tooltip, Runnable onClick)
	{
		// the shared letter-glyph atom (unified 2026-08-03)
		return new com.ironhub.ui.v2.V2GlyphButton(text, tooltip, onClick);
	}

	private static Icon sized(java.awt.Image img)
	{
		if (img == null)
		{
			return null;
		}
		int w = img.getWidth(null);
		int h = img.getHeight(null);
		if (w <= 0 || h <= 0)
		{
			return new ImageIcon(img.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH));
		}
		double s = 16.0 / Math.max(w, h);
		return new ImageIcon(img.getScaledInstance(
			Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s)),
			java.awt.Image.SCALE_SMOOTH));
	}

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
