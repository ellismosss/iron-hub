package com.ironhub.modules.qol;

import com.ironhub.data.QolPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.components.Status;
import com.ironhub.ui.components.TileTree;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2Surface;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;

/**
 * QoL tab in the uniform Build design system ({@link TileTree}, the House
 * grammar — Luke, 2026-07-28): a hero Card ("Unlocks obtained" between two
 * inventory emblems over the sprite bar, with the available-now tally on a
 * counter line), then the unlocks as CATEGORY cards — 2-wide squares with
 * corner obtained/total counts and meter strips — expanding into 2-wide
 * unlock tiles (green tick = obtained, orange edge = obtainable right now),
 * each opening a detail card with the unlock's benefit prose, met-coloured
 * requirement lines, and the goal/wiki affordances. Same brain as before —
 * status/blocking stay on {@link QolModule}. Frameless — the host names
 * the module.
 */
class QolTab extends JPanel
{
	/**
	 * The flat 98-unlock pack grouped for the card grid — curated here
	 * because the pack has no category column. Members render in this
	 * order (progression order where one exists). QolModuleTest pins that
	 * the map covers the pack exactly; anything future lands on an
	 * "Other" card rather than vanishing.
	 */
	static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();
	/** Each category's emblem — a representative member's item sprite. */
	private static final Map<String, String> EMBLEMS = new LinkedHashMap<>();

	private static void category(String name, String emblem, String... ids)
	{
		CATEGORIES.put(name, List.of(ids));
		EMBLEMS.put(name, emblem);
	}

	static
	{
		category("Diary rewards", "achievement_diary_cape",
			"achievement_diary_cape", "ardougne_cloak_1", "ardougne_cloak_2",
			"ardougne_cloak_3", "ardougne_cloak_4", "desert_amulet_4",
			"explorer_s_ring_2", "explorer_s_ring_3", "explorer_s_ring_4",
			"falador_shield_3", "fremennik_sea_boots_4", "kandarin_headgear_4",
			"karamja_gloves_3", "karamja_gloves_4", "morytania_legs_3",
			"rada_s_blessing_4", "varrock_armour_3", "western_banner_4",
			"wilderness_sword_4");
		category("Tools", "crystal_pickaxe",
			"crystal_axe", "infernal_axe", "dragon_harpoon", "crystal_harpoon",
			"infernal_harpoon", "crystal_pickaxe", "infernal_pickaxe",
			"crystal_saw", "amy_s_saw", "imcando_hammer", "magic_secateurs",
			"farming_cape", "bruma_torch");
		category("Storage", "herb_sack",
			"herb_sack", "silklined_herb_sack", "seed_box", "coal_bag",
			"log_basket", "forestry_kit", "forestry_basket", "plank_sack",
			"fish_barrel", "fish_sack_barrel", "tackle_box", "looting_bag",
			"bottomless_compost_bucket", "basket", "empty_sack",
			"reagent_pouch", "gnomish_firelighter", "steel_key_ring");
		category("Transport", "royal_seed_pod",
			"dramen_staff", "ectophial", "camulet", "royal_seed_pod",
			"master_scroll_book", "book_of_the_dead", "basic_quetzal_whistle",
			"enhanced_quetzal_whistle", "perfected_quetzal_whistle");
		category("Combat", "dragon_defender",
			"dragon_defender", "ava_accumulator", "ava_assembler",
			"rune_pouch", "divine_rune_pouch", "bolt_pouch");
		category("Essence pouches", "colossal_pouch",
			"small_pouch", "medium_pouch", "large_pouch", "giant_pouch",
			"colossal_pouch");
		category("Gem containers", "gem_sack",
			"gem_pouch", "gem_satchel", "gem_tote", "gem_sack", "gem_bag");
		category("Hunter pouches", "huntsman_s_kit",
			"small_fur_pouch", "medium_fur_pouch", "large_fur_pouch",
			"small_meat_pouch", "large_meat_pouch", "huntsman_s_kit");
		category("Shades of Mort'ton", "gold_coffin",
			"flamtaer_bag", "bronze_coffin", "steel_coffin", "black_coffin",
			"silver_coffin", "gold_coffin");
		category("Graceful outfit", "graceful_top",
			"graceful_hood", "graceful_top", "graceful_legs",
			"graceful_gloves", "graceful_boots", "graceful_cape");
		category("Rogue outfit", "rogue_top",
			"rogue_mask", "rogue_top", "rogue_trousers", "rogue_gloves",
			"rogue_boots");
	}

	private final AccountState state;
	private final QolPack pack;
	private final OsrsTheme theme;
	private final java.util.function.IntPredicate planWantsItem;
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final SpriteCache sprites;
	private final JPanel header = new JPanel();
	private final TileTree tree;

	QolTab(AccountState state, QolPack pack, OsrsTheme theme,
		java.util.function.IntPredicate planWantsItem, ItemManager itemManager)
	{
		this.state = state;
		this.pack = pack;
		this.theme = theme;
		this.planWantsItem = planWantsItem;
		this.sprites = new SpriteCache(itemManager, listener);
		this.tree = new TileTree(theme, sprites);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setBorder(new EmptyBorder(2, 4, 4, 4));
		add(header);
		add(tree);
		add(Box.createVerticalGlue());

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seam: open an unlock's detail (expands its category, selects it). */
	void expand(String unlockId)
	{
		for (Map.Entry<String, List<String>> e : CATEGORIES.entrySet())
		{
			if (e.getValue().contains(unlockId))
			{
				tree.selectForTest("cat:" + e.getKey(), unlockId);
				return;
			}
		}
	}

	private void rebuild()
	{
		int owned = 0;
		int available = 0;
		for (QolPack.Unlock unlock : pack.getUnlocks())
		{
			Status status = QolModule.status(state, unlock);
			if (status == Status.OWNED)
			{
				owned++;
			}
			else if (status == Status.AVAILABLE)
			{
				available++;
			}
		}
		int total = pack.getUnlocks().size();

		header.removeAll();
		V2Surface hero = V2Surface.card(theme);
		JPanel top = row();
		top.add(inventoryEmblem());
		top.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		middle.add(new OsrsLabel("Unlocks obtained", OsrsSkin.TITLE, OsrsSkin.font()));
		middle.add(new OsrsLabel(owned + " / " + total, OsrsSkin.TITLE, OsrsSkin.boldFont()));
		top.add(middle);
		top.add(Box.createHorizontalGlue());
		top.add(inventoryEmblem());
		cap(top);
		hero.add(top);
		hero.add(Box.createVerticalStrut(3));
		// the fill answers the SAME numbers as the label riding it
		com.ironhub.ui.v2.V2ProgressBar bar = new com.ironhub.ui.v2.V2ProgressBar(theme);
		bar.fraction(total == 0 ? 0 : (double) owned / total);
		bar.labels("", owned + " / " + total, "");
		hero.add(bar);
		hero.add(Box.createVerticalStrut(3));
		JPanel counters = row();
		counters.add(new OsrsLabel("Obtainable now: ",
			OsrsSkin.LABEL, OsrsSkin.smallFont()).leftAligned());
		counters.add(new OsrsLabel(String.valueOf(available),
			available > 0 ? OsrsSkin.COUNT_YELLOW : OsrsSkin.MUTED,
			OsrsSkin.smallFont()).leftAligned());
		counters.add(Box.createHorizontalGlue());
		cap(counters);
		hero.add(counters);
		cap(hero);
		header.add(hero);
		header.add(Box.createVerticalStrut(4));

		tree.setModel(buildModel());
		revalidate();
		repaint();
	}

	// ── model: categories -> unlocks -> detail card ───────────────────────

	private List<TileTree.Top> buildModel()
	{
		Map<String, QolPack.Unlock> byId = new LinkedHashMap<>();
		for (QolPack.Unlock unlock : pack.getUnlocks())
		{
			byId.put(unlock.getId(), unlock);
		}
		List<TileTree.Top> tops = new ArrayList<>();
		java.util.Set<String> placed = new java.util.HashSet<>();
		for (Map.Entry<String, List<String>> e : CATEGORIES.entrySet())
		{
			TileTree.Top top = new TileTree.Top();
			top.id = "cat:" + e.getKey();
			top.label = e.getKey();
			for (String id : e.getValue())
			{
				QolPack.Unlock unlock = byId.get(id);
				if (unlock != null)
				{
					top.leaves.add(leaf(unlock));
					placed.add(id);
				}
			}
			QolPack.Unlock emblem = byId.get(EMBLEMS.get(e.getKey()));
			if (emblem != null)
			{
				top.icon = emblem.getItemIds().get(0);
			}
			if (!top.leaves.isEmpty())
			{
				tops.add(top);
			}
		}
		// a pack entry the curated map doesn't know yet still shows up
		TileTree.Top other = new TileTree.Top();
		other.id = "cat:Other";
		other.label = "Other";
		for (QolPack.Unlock unlock : pack.getUnlocks())
		{
			if (!placed.contains(unlock.getId()))
			{
				other.leaves.add(leaf(unlock));
			}
		}
		if (!other.leaves.isEmpty())
		{
			other.icon = other.leaves.get(0).icon;
			tops.add(other);
		}
		for (TileTree.Top top : tops)
		{
			top.tracked = top.leaves.stream().anyMatch(l -> l.tracked);
		}
		return tops;
	}

	private TileTree.Leaf leaf(QolPack.Unlock unlock)
	{
		Status status = QolModule.status(state, unlock);
		TileTree.Leaf leaf = new TileTree.Leaf();
		leaf.id = unlock.getId();
		leaf.label = unlock.getName();
		leaf.icon = unlock.getItemIds().isEmpty() ? null : unlock.getItemIds().get(0);
		leaf.owned = status == Status.OWNED;
		leaf.tracked = status == Status.AVAILABLE;
		leaf.tooltip = unlock.getName() + " — " + standing(status, unlock);
		leaf.detail = () -> detailCard(unlock, status);
		return leaf;
	}

	private String standing(Status status, QolPack.Unlock unlock)
	{
		if (status == Status.OWNED)
		{
			return "obtained";
		}
		if (status == Status.AVAILABLE)
		{
			return "obtainable now";
		}
		String blocking = QolModule.blockingLine(state, unlock);
		return blocking == null ? "locked" : "needs " + blocking;
	}

	/** The level-3 detail: name + goal/wiki affordances, the benefit prose,
	 *  and the requirement lines met-coloured like a Task's steps. */
	private JComponent detailCard(QolPack.Unlock unlock, Status status)
	{
		V2Surface card = V2Surface.card(theme);
		Color nameColor = status == Status.OWNED ? OsrsSkin.VALUE
			: status == Status.AVAILABLE ? OsrsSkin.TITLE : OsrsSkin.MUTED;
		JPanel top = row();
		top.add(new OsrsLabel(unlock.getName(), nameColor, OsrsSkin.font())
			.leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		boolean isGoal = state.getGoalSeeds().containsKey("qol:" + unlock.getId());
		boolean planned = !isGoal && !unlock.getItemIds().isEmpty()
			&& planWantsItem.test(unlock.getItemIds().get(0));
		if (planned)
		{
			// already routed by ANOTHER goal (gear chart etc.) — say so
			// instead of offering a duplicate goal
			JLabel mark = new JLabel("·");
			OsrsSkin.crisp(mark);
			mark.setFont(OsrsSkin.font());
			mark.setForeground(OsrsSkin.VALUE);
			mark.setToolTipText(unlock.getName() + " — already in your current plan");
			top.add(mark);
		}
		else
		{
			top.add(goalGlyph(isGoal, isGoal ? unlock.getName() + " — tracked; click to untrack"
				: "Track unlocking " + unlock.getName() + " in Goals",
				() -> toggleGoal(unlock)));
		}
		top.add(Box.createHorizontalStrut(4));
		top.add(wikiGlyph(unlock.getName()));
		cap(top);
		card.add(top);
		if (unlock.getBenefit() != null)
		{
			card.add(OsrsLabel.wrapped(unlock.getBenefit(), 185,
				OsrsSkin.MUTED, OsrsSkin.smallFont()).leftAligned());
		}
		for (String req : unlock.getRequirements())
		{
			com.ironhub.requirements.Requirement parsed =
				com.ironhub.requirements.Requirements.parse(req);
			boolean manual = com.ironhub.requirements.Requirements.isManual(parsed);
			boolean met = !manual && parsed.isMet(state);
			OsrsLabel line = new OsrsLabel("· " + parsed.describe(),
				manual ? OsrsSkin.FAINT : met ? OsrsSkin.VALUE : OsrsSkin.MUTED,
				OsrsSkin.smallFont()).leftAligned().squeezable();
			line.setToolTipText(manual ? parsed.describe()
				: parsed.describe() + (met ? " — met" : " — not met"));
			card.add(line);
		}
		if (unlock.getBenefit() == null && unlock.getRequirements().isEmpty())
		{
			card.add(new OsrsLabel("No further detail known", OsrsSkin.FAINT,
				OsrsSkin.smallFont()).leftAligned());
		}
		cap(card);
		return card;
	}

	/** The '+' action: unlock joins the Goal planner as a "qol:" goal;
	 *  achieved by owning it (fully graph-detectable, no proof flag). */
	private void toggleGoal(QolPack.Unlock unlock)
	{
		String goalId = "qol:" + unlock.getId();
		if (state.getGoalSeeds().containsKey(goalId))
		{
			state.removeGoalSeed(goalId);
		}
		else
		{
			state.addGoalSeed(com.ironhub.state.GoalSeeds.qol(unlock.getId(),
				unlock.getName(), unlock.getItemIds(), unlock.getRequirements()));
		}
	}

	/** The inventory emblem at native size, flanking the hero. */
	private JComponent inventoryEmblem()
	{
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		java.awt.Image art = com.ironhub.ui.v2.V2Sprites.get(theme, "icons/inventory/inventory");
		if (art != null)
		{
			icon.setIcon(new javax.swing.ImageIcon(art));
		}
		else
		{
			icon.setPreferredSize(new Dimension(30, 30));
		}
		return icon;
	}

	/** The +/× goal affordance — a dedicated control (JLabel so its own
	 *  listener wins over the detail card's surface). */
	private static JLabel goalGlyph(boolean isGoal, String tooltip, Runnable onClick)
	{
		JLabel glyph = new JLabel(isGoal ? "×" : "+");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tooltip);
		glyph.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		glyph.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.TITLE);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				onClick.run();
				e.consume();
			}
		});
		return glyph;
	}

	/** A small "W" wiki affordance — faint until hovered (GoalsTab grammar). */
	private static JLabel wikiGlyph(String pageName)
	{
		JLabel glyph = new JLabel("W");
		OsrsSkin.crisp(glyph);
		glyph.setFont(OsrsSkin.font());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText("Open the wiki page");
		glyph.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		glyph.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
					+ pageName.replace(' ', '_'));
				e.consume();
			}
		});
		return glyph;
	}

	// ── layout helpers ────────────────────────────────────────────────────

	private JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
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
