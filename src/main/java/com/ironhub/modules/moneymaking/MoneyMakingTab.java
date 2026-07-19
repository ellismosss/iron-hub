package com.ironhub.modules.moneymaking;

import com.ironhub.data.MoneyMakingPack;
import com.ironhub.data.MoneyMakingPack.Method;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.components.RebuildGate;
import com.ironhub.ui.osrs.OsrsIcons;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneChipRow;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneTextField;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.client.util.LinkBrowser;

/**
 * Money making tab (Bank section): the OSRS wiki Money making guide, filtered
 * to what the account can do. Six category tiles + Available/Favourites filter
 * + profit/intensity sort over a favourites-first list; each method expands to
 * its requirements, inputs and wiki. Frameless — the host provides the frame.
 */
class MoneyMakingTab extends JPanel
{
	private static final String[] CATS =
		{"Collecting", "Combat", "Processing", "Skilling", "Recurring", "Free-to-play"};
	private static final String[] CAT_LABELS =
		{"Collecting", "Combat", "Processing", "Skilling", "Recurring", "F2P"};
	private static final Color HEART_RED = new Color(0xC0392B);
	private static final int ROW_CAP = 50;

	private final MoneyMakingModule module;
	private final AccountState state;
	private final MoneyMakingPack pack;
	private final net.runelite.client.game.ItemManager itemManager; // null headless
	private final net.runelite.client.game.SkillIconManager skillIcons; // null headless
	private final OsrsTheme theme;

	private final JPanel content = new JPanel();
	private final Runnable listener = RebuildGate.install(this, this::rebuild);

	private String selectedCategory;         // null = All
	private boolean availableOnly = true;    // default: only what you can do (7)
	private boolean favouritesOnly;
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
		setBorder(new javax.swing.border.EmptyBorder(4, 4, 4, 4));

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

	/** Test seams. */
	void selectCategory(String category)
	{
		this.selectedCategory = category;
		rebuild();
	}

	void setFilters(boolean availableOnly, boolean favouritesOnly)
	{
		this.availableOnly = availableOnly;
		this.favouritesOnly = favouritesOnly;
		rebuild();
	}

	void expand(String methodId)
	{
		this.expandedId = methodId;
		rebuild();
	}

	private void rebuild()
	{
		content.removeAll();

		content.add(pad(new OsrsLabel(pack.methods.size() + " methods · profit as of "
			+ (pack.gePricesAsOf == null ? pack.generated : pack.gePricesAsOf),
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned()));
		content.add(strut(3));

		// six category tiles — click the selected one again to clear to All (1/2)
		content.add(pad(categoryTiles()));
		content.add(strut(3));

		// Available toggle + a red-heart favourites toggle (3/4/5)
		JPanel filters = row();
		StoneChipRow avail = new StoneChipRow(theme, false, "All", "Available");
		avail.setSelected(availableOnly ? 1 : 0);
		avail.onChange(i ->
		{
			availableOnly = i == 1;
			favouritesOnly = false;
			rebuild();
		});
		filters.add(avail);
		filters.add(Box.createHorizontalGlue());
		filters.add(favouriteToggle());
		cap(filters);
		content.add(pad(filters));
		content.add(strut(2));

		StoneChipRow sort = new StoneChipRow(theme, true, "Sort: profit", "Sort: intensity");
		sort.setSelected(sortByIntensity ? 1 : 0);
		sort.onChange(i ->
		{
			sortByIntensity = i == 1;
			rebuild();
		});
		content.add(pad(sort));
		content.add(strut(4));

		List<Method> visible = visible();
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

	private JComponent categoryTiles()
	{
		JPanel grid = new JPanel(new GridLayout(2, 3, 3, 3));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		for (int i = 0; i < CATS.length; i++)
		{
			String cat = CATS[i];
			boolean on = cat.equals(selectedCategory);
			StoneButton tile = new StoneButton(theme, on ? theme.selectFill : theme.boxFill,
				CAT_LABELS[i], () -> selectCategory(on ? null : cat)); // re-click clears to All (2)
			grid.add(tile);
		}
		cap(grid);
		return grid;
	}

	/** The favourites filter as a red heart (5) — filled red when active. */
	private JLabel favouriteToggle()
	{
		JLabel h = new JLabel(new PaintedIcon(
			favouritesOnly ? PaintedIcon.Shape.HEART : PaintedIcon.Shape.HEART_OUTLINE, 14));
		h.setForeground(favouritesOnly ? HEART_RED : OsrsSkin.MUTED);
		h.setToolTipText("Show favourites only");
		h.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		h.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				favouritesOnly = !favouritesOnly;
				rebuild();
			}
		});
		return h;
	}

	/** The filtered + sorted list, favourites floated to the top. */
	private List<Method> visible()
	{
		Comparator<Method> order = sortByIntensity
			? Comparator.comparingInt((Method m) -> intensityRank(m.intensity))
				.thenComparing(m -> -orZero(m.profit))
			: Comparator.comparingLong(m -> -orZero(m.profit));
		return pack.methods.stream()
			.filter(this::matchesCategory)
			.filter(this::matchesAvailability)
			.sorted(order)
			.sorted(Comparator.comparing(m -> !state.isMoneyFavourite(m.id))) // stable: favs to top
			.collect(Collectors.toList());
	}

	private boolean matchesCategory(Method m)
	{
		if (selectedCategory == null)
		{
			return true;
		}
		if ("Free-to-play".equals(selectedCategory))
		{
			return !m.members;
		}
		return selectedCategory.equals(m.category);
	}

	private boolean matchesAvailability(Method m)
	{
		if (favouritesOnly)
		{
			return state.isMoneyFavourite(m.id);
		}
		return !availableOnly || MoneyMakingModule.available(state, m);
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
		// regular text when available (8), greyed when not (6/9) — no method icon
		top.add(new OsrsLabel(m.name, can ? OsrsSkin.LABEL : OsrsSkin.FAINT, OsrsSkin.font())
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
				// missing requirements in red (10)
				sub.add(new OsrsLabel("needs " + miss.get(0)
					+ (miss.size() > 1 ? " +" + (miss.size() - 1) : ""),
					UiTokens.STATUS_WARNING, OsrsSkin.smallFont()));
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

	/** A clickable favourite heart — filled RED when favourited (14). */
	private JLabel heart(Method m, boolean fav)
	{
		JLabel h = new JLabel(new PaintedIcon(
			fav ? PaintedIcon.Shape.HEART : PaintedIcon.Shape.HEART_OUTLINE, 12));
		h.setForeground(fav ? HEART_RED : OsrsSkin.FAINT);
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
		block.setBorder(new javax.swing.border.EmptyBorder(1, UiTokens.ROW_GAP + 6, 3, UiTokens.ROW_GAP));

		// hard requirements — one per row, met green / unmet red (10/13)
		for (String req : m.reqs)
		{
			boolean met = Requirements.parse(req).isMet(state);
			block.add(reqRow(req, met ? OsrsSkin.VALUE : UiTokens.STATUS_WARNING));
		}
		// recommendations + inputs each wrap to their own icon rows (13)
		if (!m.recommends.isEmpty())
		{
			block.add(sectionLabel("Recommended"));
			for (String rec : m.recommends)
			{
				block.add(reqRow(rec, OsrsSkin.FAINT));
			}
		}
		if (m.inputs != null && !m.inputs.isEmpty())
		{
			block.add(sectionLabel("Inputs (per hour)"));
			for (MoneyMakingPack.Input in : m.inputs)
			{
				block.add(inputRow(in));
			}
		}

		// feature 7: gp target + method as a Goal
		JPanel goalRow = row();
		StoneTextField gp = new StoneTextField(theme, "gp target, e.g. 10M");
		gp.setMaximumSize(new Dimension(115, gp.getPreferredSize().height));
		goalRow.add(gp);
		goalRow.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		boolean hasGoal = state.getGoalSeeds().containsKey("custom:money:" + m.id);
		StoneButton addGoal = new StoneButton(theme, theme.boxFill, hasGoal ? "Tracked" : "+ Goal", () ->
		{
			if (state.getGoalSeeds().containsKey("custom:money:" + m.id))
			{
				state.removeGoalSeed("custom:money:" + m.id);
				return;
			}
			long amount = parseGp(gp.getText());
			if (amount > 0)
			{
				state.addGoalSeed(com.ironhub.state.GoalSeeds.money(m.id, m.name, amount));
			}
		});
		addGoal.setMaximumSize(addGoal.getPreferredSize());
		goalRow.add(addGoal);
		goalRow.add(Box.createHorizontalGlue());
		cap(goalRow);
		block.add(strut(2));
		block.add(goalRow);

		// "unlock this method" as a goal — routes the requirements (11)
		if (!MoneyMakingModule.available(state, m) && !m.reqs.isEmpty())
		{
			boolean tracking = state.getGoalSeeds().containsKey("custom:money-unlock:" + m.id);
			JPanel unlockRow = row();
			StoneButton unlock = new StoneButton(theme, theme.boxFill,
				tracking ? "Unlock tracked" : "+ Set goal to unlock method", () ->
			{
				if (tracking)
				{
					state.removeGoalSeed("custom:money-unlock:" + m.id);
				}
				else
				{
					state.addGoalSeed(com.ironhub.state.GoalSeeds.moneyUnlock(m.id, m.name, m.reqs));
				}
			});
			unlock.setMaximumSize(unlock.getPreferredSize());
			unlockRow.add(unlock);
			unlockRow.add(Box.createHorizontalGlue());
			cap(unlockRow);
			block.add(strut(2));
			block.add(unlockRow);
		}

		// a Wiki button on every method (12)
		JPanel foot = row();
		StoneButton wiki = new StoneButton(theme, theme.boxFill, "Wiki",
			() -> LinkBrowser.browse("https://oldschool.runescape.wiki/w/" + m.wiki));
		wiki.setMaximumSize(wiki.getPreferredSize());
		foot.add(wiki);
		foot.add(Box.createHorizontalGlue());
		cap(foot);
		block.add(strut(2));
		block.add(foot);
		cap(block);
		return block;
	}

	// ── detail rows (icons + wrapped, 13) ────────────────────────────────

	/** A requirement/recommendation row: skill icon + "72 Herblore", or a
	 *  quest badge + the quest, wrapped so nothing runs off screen. */
	private JComponent reqRow(String leaf, Color color)
	{
		JPanel r = row();
		r.setBorder(new javax.swing.border.EmptyBorder(1, 0, 1, 0));
		Icon icon = leafIcon(leaf);
		if (icon != null)
		{
			r.add(new JLabel(icon));
			r.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		r.add(OsrsLabel.wrapped(leafText(leaf), 190, color, OsrsSkin.smallFont()).leftAligned());
		r.add(Box.createHorizontalGlue());
		cap(r);
		return r;
	}

	private JComponent inputRow(MoneyMakingPack.Input in)
	{
		JPanel r = row();
		r.setBorder(new javax.swing.border.EmptyBorder(1, 0, 1, 0));
		if (itemManager != null && in.itemId > 0)
		{
			r.add(new JLabel(sized(itemManager.getImage(in.itemId))));
			r.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		r.add(OsrsLabel.wrapped(in.qty + "× " + in.name, 190, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		r.add(Box.createHorizontalGlue());
		cap(r);
		return r;
	}

	private JComponent sectionLabel(String text)
	{
		JPanel r = row();
		r.setBorder(new javax.swing.border.EmptyBorder(3, 0, 1, 0));
		r.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		r.add(Box.createHorizontalGlue());
		cap(r);
		return r;
	}

	private Icon leafIcon(String leaf)
	{
		if (leaf.startsWith("skill:") && skillIcons != null)
		{
			Skill s = skillOf(leaf);
			if (s != null)
			{
				return sized(skillIcons.getSkillImage(s, false));
			}
		}
		if (leaf.startsWith("quest:") || leaf.startsWith("qp:"))
		{
			return sized(OsrsIcons.image(theme, "quests"));
		}
		return null;
	}

	private String leafText(String leaf)
	{
		if (leaf.startsWith("skill:"))
		{
			String[] p = leaf.split(":");
			return p.length >= 3 ? p[2] + " " + p[1] : leaf; // "72 Herblore"
		}
		if (leaf.startsWith("combat:"))
		{
			return leaf.substring("combat:".length()) + " Combat";
		}
		return Requirements.parse(leaf).describe();
	}

	private static Skill skillOf(String leaf)
	{
		String[] p = leaf.split(":");
		if (p.length < 2)
		{
			return null;
		}
		for (Skill s : Skill.values())
		{
			if (s.getName().equalsIgnoreCase(p[1]))
			{
				return s;
			}
		}
		return null;
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private static Icon sized(java.awt.Image img)
	{
		return img == null ? null : new ImageIcon(img.getScaledInstance(-1, 16, java.awt.Image.SCALE_SMOOTH));
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

	/** Parse a gp target: "10M", "500k", "1,000,000" → gp, or -1 if unparseable. */
	private static long parseGp(String s)
	{
		if (s == null)
		{
			return -1;
		}
		s = s.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "").replace("gp", "").trim();
		double mult = 1;
		if (s.endsWith("m"))
		{
			mult = 1_000_000;
			s = s.substring(0, s.length() - 1);
		}
		else if (s.endsWith("k"))
		{
			mult = 1000;
			s = s.substring(0, s.length() - 1);
		}
		try
		{
			return (long) (Double.parseDouble(s.trim()) * mult);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
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
