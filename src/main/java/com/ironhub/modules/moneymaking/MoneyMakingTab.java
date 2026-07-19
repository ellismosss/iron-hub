package com.ironhub.modules.moneymaking;

import com.ironhub.data.MoneyMakingPack;
import com.ironhub.data.MoneyMakingPack.Method;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StonePanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.util.LinkBrowser;

/**
 * Money making tab (Bank section): the OSRS wiki Money making guide, filtered
 * to what the account can do. Category dropdown + Availability/Sort chips over
 * a favourites-first list; each method expands to its requirements, inputs and
 * wiki link. Frameless — the host provides the stone frame + header plate.
 */
class MoneyMakingTab extends JPanel
{
	private static final String[] CATEGORIES =
		{"All", "Collecting", "Combat", "Processing", "Skilling", "Recurring", "Free-to-play"};
	private static final int ROW_CAP = 50;

	private final MoneyMakingModule module;
	private final AccountState state;
	private final MoneyMakingPack pack;
	private final net.runelite.client.game.ItemManager itemManager; // null headless
	private final net.runelite.client.game.SkillIconManager skillIcons; // null headless
	private final OsrsTheme theme;

	private final JPanel content = new JPanel();
	private final Runnable listener = RebuildGate.install(this, this::rebuild);

	private int category;                 // index into CATEGORIES
	private int availability;             // 0 All · 1 Available · 2 Unavailable · 3 Favourites
	private boolean sortByIntensity;
	private String expandedId;

	MoneyMakingTab(MoneyMakingModule module, AccountState state, MoneyMakingPack pack,
		net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.game.SkillIconManager skillIcons, OsrsTheme theme)
	{
		this.module = module;
		this.state = state;
		this.pack = pack;
		this.itemManager = itemManager;
		this.skillIcons = skillIcons;
		this.theme = theme;
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

	/** Test seam. */
	void filter(int categoryIndex, int availabilityIndex)
	{
		this.category = categoryIndex;
		this.availability = availabilityIndex;
		rebuild();
	}

	private void rebuild()
	{
		content.removeAll();

		// header — count + freshness (the profit is a GE-price snapshot)
		content.add(pad(new OsrsLabel(pack.methods.size() + " methods · profit as of "
			+ (pack.gePricesAsOf == null ? pack.generated : pack.gePricesAsOf),
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned()));
		content.add(strut(3));

		// category dropdown (7 options don't fit as chips at 225px)
		JComboBox<String> cat = StoneComboBoxUI.skin(new JComboBox<>(CATEGORIES), theme);
		cat.setSelectedIndex(category);
		cat.setAlignmentX(LEFT_ALIGNMENT);
		cat.addActionListener(e ->
		{
			if (cat.getSelectedIndex() != category)
			{
				category = cat.getSelectedIndex();
				rebuild();
			}
		});
		content.add(pad(cat));
		content.add(strut(3));

		// availability filter
		StoneChipRow avail = new StoneChipRow(theme, true, "All", "Can do", "Can't", "Favs");
		avail.setSelected(availability);
		avail.onChange(i ->
		{
			availability = i;
			rebuild();
		});
		content.add(pad(avail));
		content.add(strut(2));

		// sort
		StoneChipRow sort = new StoneChipRow(theme, true, "Sort: profit", "Sort: intensity");
		sort.setSelected(sortByIntensity ? 1 : 0);
		sort.onChange(i ->
		{
			sortByIntensity = i == 1;
			rebuild();
		});
		content.add(pad(sort));
		content.add(strut(4));

		// the list
		List<Method> visible = visible();
		if (availability == 2)
		{
			content.add(pad(new OsrsLabel("Closest to unlocking first", OsrsSkin.FAINT,
				OsrsSkin.smallFont()).leftAligned()));
			content.add(strut(2));
		}
		if (visible.isEmpty())
		{
			content.add(pad(new OsrsLabel("No methods match.", OsrsSkin.FAINT, OsrsSkin.font()).leftAligned()));
		}
		int shown = 0;
		for (Method m : visible)
		{
			content.add(pad(methodRow(m)));
			if (m.id.equals(expandedId))
			{
				content.add(pad(detail(m)));
			}
			content.add(strut(2));
			if (++shown >= ROW_CAP && visible.size() > shown)
			{
				content.add(pad(new OsrsLabel("+ " + (visible.size() - shown) + " more — refine the filters",
					OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned()));
				break;
			}
		}
		content.add(strut(6));
		revalidate();
		repaint();
	}

	/** The filtered + sorted method list, favourites floated to the top. */
	private List<Method> visible()
	{
		Comparator<Method> order = availability == 2
			? Comparator.comparingDouble(m -> MoneyMakingModule.distance(state, m)) // closest first
			: sortByIntensity
				? Comparator.comparingInt((Method m) -> intensityRank(m.intensity))
					.thenComparing(m -> -orZero(m.profit))
				: Comparator.comparingLong(m -> -orZero(m.profit));
		return pack.methods.stream()
			.filter(this::matchesCategory)
			.filter(this::matchesAvailability)
			.sorted(order.thenComparing(m -> !state.isMoneyFavourite(m.id))) // ties: favs up
			.sorted(Comparator.comparing(m -> !state.isMoneyFavourite(m.id))) // stable: favs to top
			.collect(Collectors.toList());
	}

	private boolean matchesCategory(Method m)
	{
		if (category == 0)
		{
			return true;
		}
		if ("Free-to-play".equals(CATEGORIES[category]))
		{
			return !m.members;
		}
		return CATEGORIES[category].equals(m.category);
	}

	private boolean matchesAvailability(Method m)
	{
		switch (availability)
		{
			case 1:
				return MoneyMakingModule.available(state, m);
			case 2:
				return !MoneyMakingModule.available(state, m);
			case 3:
				return state.isMoneyFavourite(m.id);
			default:
				return true;
		}
	}

	// ── rows ─────────────────────────────────────────────────────────────

	private JComponent methodRow(Method m)
	{
		boolean can = MoneyMakingModule.available(state, m);
		boolean fav = state.isMoneyFavourite(m.id);
		boolean expanded = m.id.equals(expandedId);
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel top = row();
		top.add(heart(m, fav));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		Icon icon = icon(m);
		if (icon != null)
		{
			top.add(new JLabel(icon));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		// green = can do now, faint = can't (yet)
		top.add(new OsrsLabel(m.name, can ? OsrsSkin.VALUE : OsrsSkin.MUTED, OsrsSkin.font())
			.leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel(profitText(m), OsrsSkin.TITLE, OsrsSkin.smallFont()));
		card.add(top);

		JPanel sub = row();
		String meta = m.category + (m.recurring ? " · " + m.time
			: m.intensity.isEmpty() ? "" : " · " + m.intensity)
			+ (m.members ? "" : " · F2P");
		sub.add(new OsrsLabel(meta, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned().squeezable());
		sub.add(Box.createHorizontalGlue());
		if (!can)
		{
			List<String> miss = MoneyMakingModule.missing(state, m);
			if (!miss.isEmpty())
			{
				sub.add(new OsrsLabel("needs " + miss.get(0)
					+ (miss.size() > 1 ? " +" + (miss.size() - 1) : ""),
					OsrsSkin.FAINT, OsrsSkin.smallFont()));
			}
		}
		card.add(sub);

		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedId = expanded ? null : m.id;
				rebuild();
			}
		};
		card.addMouseListener(click);
		for (java.awt.Component c : card.getComponents())
		{
			c.addMouseListener(click);
		}
		cap(card);
		return card;
	}

	/** A clickable favourite heart — filled title-colour when favourited. */
	private JLabel heart(Method m, boolean fav)
	{
		JLabel h = new JLabel(new PaintedIcon(
			fav ? PaintedIcon.Shape.HEART : PaintedIcon.Shape.HEART_OUTLINE, 12));
		h.setForeground(fav ? OsrsSkin.TITLE : OsrsSkin.FAINT);
		h.setToolTipText(fav ? "Favourited — click to unfavourite" : "Favourite (keeps it at the top)");
		h.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		h.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				state.setMoneyFavourite(m.id, !fav);
				e.consume();
			}
		});
		return h;
	}

	private JComponent detail(Method m)
	{
		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(LEFT_ALIGNMENT);
		block.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP + 6, 3, UiTokens.ROW_GAP));

		for (String req : m.reqs)
		{
			boolean met = com.ironhub.requirements.Requirements.parse(req).isMet(state);
			block.add(line("· " + com.ironhub.requirements.Requirements.parse(req).describe(),
				met ? OsrsSkin.VALUE : OsrsSkin.MUTED));
		}
		if (!m.recommends.isEmpty())
		{
			block.add(line("recommended: " + m.recommends.stream()
				.map(r -> com.ironhub.requirements.Requirements.parse(r).describe())
				.collect(Collectors.joining(", ")), OsrsSkin.FAINT));
		}
		if (m.inputs != null && !m.inputs.isEmpty())
		{
			block.add(line("inputs: " + m.inputs.stream().limit(8)
				.map(i -> i.qty + "× " + i.name).collect(Collectors.joining(", ")), OsrsSkin.FAINT));
		}
		JPanel foot = row();
		foot.add(Box.createHorizontalGlue());
		JLabel wiki = new JLabel("wiki");
		OsrsSkin.crisp(wiki);
		wiki.setFont(OsrsSkin.smallFont());
		wiki.setForeground(OsrsSkin.TITLE);
		wiki.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		wiki.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse("https://oldschool.runescape.wiki/w/" + m.wiki);
			}
		});
		foot.add(wiki);
		block.add(foot);
		cap(block);
		return block;
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private Icon icon(Method m)
	{
		if (itemManager != null && m.icon > 0)
		{
			return new ImageIcon(itemManager.getImage(m.icon).getScaledInstance(-1, 18, java.awt.Image.SCALE_SMOOTH));
		}
		return null;
	}

	private String profitText(Method m)
	{
		if (m.profit == null)
		{
			return "?";
		}
		return compactGp(m.profit) + (m.recurring ? "/run" : "/hr");
	}

	private static String compactGp(long gp)
	{
		if (Math.abs(gp) >= 1_000_000)
		{
			return Math.round(gp / 100_000.0) / 10.0 + "M";
		}
		if (Math.abs(gp) >= 1000)
		{
			return Math.round(gp / 1000.0) + "K";
		}
		return String.valueOf(gp);
	}

	private static long orZero(Integer i)
	{
		return i == null ? 0 : i;
	}

	private static int intensityRank(String intensity)
	{
		switch (intensity)
		{
			case "Low":
				return 0;
			case "Moderate":
				return 1;
			case "High":
				return 2;
			default:
				return 3;
		}
	}

	private JComponent line(String text, Color color)
	{
		JPanel r = row();
		r.add(new OsrsLabel(text, color, OsrsSkin.smallFont()).leftAligned().squeezable());
		r.add(Box.createHorizontalGlue());
		cap(r);
		return r;
	}

	private JPanel row()
	{
		JPanel r = new JPanel();
		r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
		r.setOpaque(false);
		r.setAlignmentX(LEFT_ALIGNMENT);
		return r;
	}

	private JComponent pad(JComponent inner)
	{
		JPanel holder = new JPanel(new java.awt.BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.add(inner);
		cap(holder);
		return holder;
	}

	private JComponent strut(int h)
	{
		return (JComponent) Box.createVerticalStrut(h);
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
