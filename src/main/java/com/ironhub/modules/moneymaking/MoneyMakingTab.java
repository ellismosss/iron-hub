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
	private static final String[] CAT_KEYS =
		{"collecting", "combat", "processing", "skilling", "recurring", "f2p"};
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
	private final com.ironhub.ui.components.SpriteCache sprites;

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
		this.sprites = new com.ironhub.ui.components.SpriteCache(itemManager, listener);
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
		canDoCache.clear(); // fresh state per pass; see canDo
		content.removeAll();

		content.add(pad(new OsrsLabel(pack.methods.size() + " methods · profit as of "
			+ (pack.gePricesAsOf == null ? pack.generated : pack.gePricesAsOf),
			OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned()));
		content.add(strut(3));

		// six category tiles — click the selected one again to clear to All (1/2)
		content.add(pad(categoryTiles()));
		content.add(strut(3));

		// three equal tiles: All · Available · Favourites (heart) — Available
		// sits centred between the same-width All and Favourites tiles (2/3/4/5)
		JPanel filters = new JPanel(new GridLayout(1, 3, 3, 0));
		filters.setOpaque(false);
		filters.setAlignmentX(LEFT_ALIGNMENT);
		filters.add(selectTile(null, "All", !availableOnly && !favouritesOnly, () ->
		{
			availableOnly = false;
			favouritesOnly = false;
			rebuild();
		}));
		filters.add(selectTile(null, "Available", availableOnly && !favouritesOnly, () ->
		{
			availableOnly = true;
			favouritesOnly = false;
			rebuild();
		}));
		filters.add(favouriteTile());
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
			grid.add(selectTile(sized(OsrsIcons.image(theme, "moneymaking/" + CAT_KEYS[i])),
				CAT_LABELS[i], on, () -> selectCategory(on ? null : cat))); // re-click → All (2)
		}
		cap(grid);
		return grid;
	}

	/** A filter tile: highlighted (selectFill) with orange text when selected,
	 *  just like All/Available (4). Icon optional. */
	private JComponent selectTile(Icon icon, String label, boolean selected, Runnable onClick)
	{
		StonePanel tile = new StonePanel(theme);
		tile.setBackground(selected ? theme.selectFill : theme.boxFill);
		tile.setLayout(new BoxLayout(tile, BoxLayout.X_AXIS));
		tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		tile.add(Box.createHorizontalGlue());
		if (icon != null)
		{
			tile.add(new JLabel(icon));
			if (label != null)
			{
				tile.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
		}
		if (label != null)
		{
			tile.add(new OsrsLabel(label, selected ? OsrsSkin.TITLE : OsrsSkin.MUTED, OsrsSkin.smallFont()));
		}
		tile.add(Box.createHorizontalGlue());
		tile.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}
		});
		return tile;
	}

	/** The favourites tile: a red heart that highlights when active (3/5). */
	private JComponent favouriteTile()
	{
		StonePanel tile = new StonePanel(theme);
		tile.setBackground(favouritesOnly ? theme.selectFill : theme.boxFill);
		tile.setLayout(new BoxLayout(tile, BoxLayout.X_AXIS));
		tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		tile.setToolTipText("Show favourites only");
		JLabel heart = new JLabel(new PaintedIcon(PaintedIcon.Shape.HEART, 13));
		heart.setForeground(HEART_RED);
		tile.add(Box.createHorizontalGlue());
		tile.add(heart);
		tile.add(Box.createHorizontalGlue());
		tile.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				favouritesOnly = !favouritesOnly;
				rebuild();
			}
		});
		return tile;
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
		return !availableOnly || canDo(m);
	}

	/** Availability per rebuild pass — the filter, each row and the detail
	 *  card all ask, and one evaluation walks the method's requirement
	 *  graph; 526 methods per keystroke added up (2026-07-20 audit). */
	private final java.util.Map<String, Boolean> canDoCache = new java.util.HashMap<>();

	private boolean canDo(Method m)
	{
		return canDoCache.computeIfAbsent(m.id,
			id -> MoneyMakingModule.available(state, m));
	}

	// ── rows ─────────────────────────────────────────────────────────────

	private JComponent methodRow(Method m)
	{
		boolean can = canDo(m);
		boolean fav = state.isMoneyFavourite(m.id);
		boolean expanded = m.id.equals(expandedId);
		StonePanel card = new StonePanel(theme);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel top = row();
		top.add(heart(m, fav));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		// light-grey text when available (1/8), greyed when not (9) — the profit
		// stays orange; no method icon (6)
		top.add(new OsrsLabel(m.name, can ? OsrsSkin.MUTED : OsrsSkin.FAINT, OsrsSkin.font())
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

		// a Wiki button in the upper-right corner (7/12)
		JPanel wikiTop = row();
		wikiTop.add(Box.createHorizontalGlue());
		StoneButton wiki = new StoneButton(theme, theme.boxFill, "Wiki",
			() -> LinkBrowser.browse("https://oldschool.runescape.wiki/w/" + m.wiki));
		wiki.setMaximumSize(wiki.getPreferredSize());
		wikiTop.add(wiki);
		cap(wikiTop);
		block.add(wikiTop);

		// hard requirements — one per row, met green / unmet red (10/13)
		for (String req : m.reqs)
		{
			boolean met = Requirements.parse(req).isMet(state);
			block.add(reqRow(req, met ? OsrsSkin.VALUE : UiTokens.STATUS_WARNING));
		}
		// recommendations + inputs + outputs each wrap to their own icon rows (13)
		if (!m.recommends.isEmpty())
		{
			block.add(sectionLabel("Recommended"));
			for (String rec : m.recommends)
			{
				block.add(reqRow(rec, OsrsSkin.FAINT));
			}
		}
		itemSection(block, "Inputs (per hour)", m.inputs);
		itemSection(block, "Outputs (per hour)", m.outputs); // (8)

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
		if (!canDo(m) && !m.reqs.isEmpty())
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
		cap(block);
		return block;
	}

	// ── detail rows (icons + wrapped, 13) ────────────────────────────────

	/** An Inputs/Outputs section — a header then one item row each (capped). */
	private void itemSection(JPanel block, String title, List<MoneyMakingPack.Input> items)
	{
		if (items == null || items.isEmpty())
		{
			return;
		}
		block.add(sectionLabel(title));
		int shown = 0;
		for (MoneyMakingPack.Input in : items)
		{
			block.add(inputRow(in));
			if (++shown >= 10 && items.size() > shown)
			{
				block.add(sectionLabel("+ " + (items.size() - shown) + " more"));
				break;
			}
		}
	}

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
			java.awt.Image inSprite = sprites.getBox(in.itemId, 16);
			r.add(new JLabel(inSprite == null ? null : new ImageIcon(inSprite)));
			r.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		// a plain quantity reads as "3× Anglerfish"; a drop-rate expression
		// (outputs like "15*(5/128)") is noise, so show just the item then
		boolean messy = in.qty.contains("*") || in.qty.contains("(");
		String text = messy ? in.name : in.qty + "× " + in.name;
		r.add(OsrsLabel.wrapped(text, 190, OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		r.add(Box.createHorizontalGlue());
		// where this input comes from (the KB projection) — the row names
		// WHAT the method consumes, the hover names where an iron gets it
		if (in.itemId > 0 && module.itemSources() != null)
		{
			String sources = module.itemSources().sourceLine(in.itemId);
			if (sources != null)
			{
				r.setToolTipText(sources);
			}
		}
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

	/** Scale to fit a 16×16 box (max dimension = 16) — source sprites vary in
	 *  size, so height-only scaling left some icons (Thieving) oversized (6). */
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
