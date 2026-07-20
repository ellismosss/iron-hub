package com.ironhub.modules.bankspace;

import com.ironhub.data.BankStoragePack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneCheckbox;
import com.ironhub.ui.osrs.StoneMeter;
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

	private final JPanel content = new JPanel();
	private String expanded; // location id, or "ignored"

	BankSpaceTab(AccountState state, BankSpaceModule module, OsrsTheme theme,
		ItemManager itemManager)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.itemManager = itemManager;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

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
		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setBorder(new EmptyBorder(2, 4, 3, 4));
		head.add(new OsrsLabel(wasted + " could move to storage",
			wasted > 0 ? OsrsSkin.TITLE : OsrsSkin.VALUE, OsrsSkin.boldFont())
			.leftAligned().squeezable());
		head.add(Box.createHorizontalGlue());
		cap(head);
		content.add(head);

		StoneMeter meter = new StoneMeter(theme, AMBER,
			banked == 0 ? 0 : (double) wasted / banked);
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(1, 4, 1, 4));
		holder.add(meter);
		cap(holder);
		content.add(holder);

		JPanel under = new JPanel();
		under.setLayout(new BoxLayout(under, BoxLayout.X_AXIS));
		under.setOpaque(false);
		under.setAlignmentX(LEFT_ALIGNMENT);
		under.setBorder(new EmptyBorder(0, 4, 3, 4));
		under.add(Box.createHorizontalGlue());
		under.add(new OsrsLabel("of " + banked + " banked items", OsrsSkin.FAINT,
			OsrsSkin.smallFont()));
		cap(under);
		content.add(under);
	}

	private void addBisToggle()
	{
		boolean flagBis = state.isBankStorageFlagBis();
		JPanel row = rowLine();
		row.setBorder(new EmptyBorder(2, 4, 3, 4));
		StoneCheckbox box = new StoneCheckbox(theme, flagBis);
		box.setToolTipText("Best-in-slot gear (armour case, cape rack, magic "
			+ "wardrobe pieces) is usually banked on purpose - tick to flag it too");
		row.add(box);
		row.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		row.add(new OsrsLabel("Flag best-in-slot gear too", OsrsSkin.MUTED,
			OsrsSkin.smallFont()).leftAligned());
		row.add(Box.createHorizontalGlue());
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				state.setBankStorageFlagBis(!flagBis); // listener rebuilds
			}
		});
		cap(row);
		content.add(row);
	}

	// ── location rows ─────────────────────────────────────────────────

	private void addLocationRow(BankSpaceModule.LocationReport report)
	{
		BankStoragePack.Location location = report.location;
		JPanel row = rows();

		JPanel top = rowLine();
		StoneCheckbox box = new StoneCheckbox(theme, report.enabled);
		box.setToolTipText(report.enabled
			? "Stop flagging " + location.name + " items"
			: "Flag " + location.name + " items again");
		box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		box.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				state.toggleBankStorageLocation(location.id); // listener rebuilds
				e.consume();
			}
		});
		top.add(box);
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		OsrsLabel name = new OsrsLabel(location.name,
			report.enabled ? OsrsSkin.MUTED : OsrsSkin.FAINT, OsrsSkin.font())
			.leftAligned().squeezable();
		top.add(name);
		top.add(Box.createHorizontalGlue());
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
			for (BankStoragePack.Entry entry : ignored)
			{
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
			Icon icon = sized(itemManager.getImage(entry.id));
			if (icon != null)
			{
				r.add(new JLabel(icon));
				r.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
		}
		String text = entry.name + (restoring
			? " · " + module.locationNameOf(entry.id)
			: entry.bis ? " · bis" : "");
		r.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.smallFont())
			.leftAligned().squeezable());
		r.add(Box.createHorizontalGlue());
		r.add(glyph(restoring ? "+" : "×", restoring
				? "Flag " + entry.name + " again"
				: "Ignore " + entry.name + " (never flag it)",
			() -> state.toggleBankStorageIgnored(entry.id)));
		cap(r);
		return r;
	}

	// ── shared bits ───────────────────────────────────────────────────

	private static JLabel glyph(String text, String tooltip, Runnable onClick)
	{
		JLabel glyph = new JLabel(text);
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tooltip);
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.TITLE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
				e.consume();
			}
		});
		return glyph;
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
