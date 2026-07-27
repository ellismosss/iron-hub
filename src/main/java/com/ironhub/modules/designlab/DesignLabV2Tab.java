package com.ironhub.modules.designlab;

import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneNavButton;
import com.ironhub.ui.v2.V2Button;
import com.ironhub.ui.v2.V2Checkbox;
import com.ironhub.ui.v2.V2ChipRow;
import com.ironhub.ui.v2.V2Divider;
import com.ironhub.ui.v2.V2Dropdown;
import com.ironhub.ui.v2.V2EmptyState;
import com.ironhub.ui.v2.V2Glyph;
import com.ironhub.ui.v2.V2Hero;
import com.ironhub.ui.v2.V2ItemSlot;
import com.ironhub.ui.v2.V2Label;
import com.ironhub.ui.v2.V2Layout;
import com.ironhub.ui.v2.V2ProgressBar;
import com.ironhub.ui.v2.V2SpriteButton;
import com.ironhub.ui.v2.V2Sprites;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2Tab;
import com.ironhub.ui.v2.V2Table;
import com.ironhub.ui.v2.V2TextField;
import com.ironhub.ui.v2.V2Tile;
import com.ironhub.ui.v2.V2Tokens;
import com.ironhub.ui.v2.V2Tooltip;
import java.awt.image.BufferedImage;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Design lab V2 — every atom of the V2 system, in every state its art
 * offers, in whichever theme the config selects.
 *
 * <p>This is the surface Luke signs off before any module migrates (his
 * gate, 2026-07-24). It is deliberately exhaustive rather than pretty: an
 * atom that only looks right in the one arrangement its author chose is the
 * problem this system exists to solve, so each one appears here beside its
 * neighbours at panel width, with the states it actually has.
 *
 * <p>Sample data throughout, and said so at the foot.
 */
public class DesignLabV2Tab extends JPanel
{
	/**
	 * The nav emblems, in stone order against {@code HomePanel.navBlocks()} —
	 * Luke's pick from the curated set (2026-07-25), replacing the wiki PNGs
	 * the live row still wears.
	 */
	/**
	 * The 28 items the inventory shows — real game items, drawn by
	 * {@code ItemManager} rather than by any sprite in this repo (Luke,
	 * 2026-07-25). IDs come from {@code data/supplies.json}, which is generated
	 * from knowledge.db, so they are verified rather than remembered.
	 */
	private static final int[] INVENTORY = {
		12695, 2444, 22461, 3024, 2434,   // potions
		13441, 391, 11936, 7060, 385,     // food
		561, 563, 560, 565, 566,          // runes
		892, 21326, 11212, 890,           // ammo
		536, 22124, 22780, 6729,          // prayer
		7936, 314, 1777, 207, 3051,       // materials
	};

	private final OsrsTheme theme;
	/** null in the render tests — there is no client, so the slots stay empty
	 *  and everything else on the page still renders. */
	private final com.ironhub.ui.components.SpriteCache sprites;
	/** Held so the cache's arrival callback can re-fill it — see {@link #fillInventory}. */
	private com.ironhub.ui.v2.V2Inventory inventory;

	public DesignLabV2Tab(OsrsTheme theme)
	{
		this(theme, null);
	}

	/** The curated skill set, in the game's own stat-panel order. */
	private static final String[] SKILLS = {
		"Attack", "Hitpoints", "Mining",
		"Strength", "Agility", "Smithing",
		"Defence", "Herblore", "Fishing",
		"Ranged", "Thieving", "Cooking",
		"Prayer", "Crafting", "Firemaking",
		"Magic", "Fletching", "Woodcutting",
		"Runecraft", "Slayer", "Farming",
		"Construction", "Hunter"};

	public DesignLabV2Tab(OsrsTheme theme, net.runelite.client.game.ItemManager itemManager)
	{
		this.theme = theme;
		this.sprites = new com.ironhub.ui.components.SpriteCache(itemManager, this::fillInventory);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		// NO horizontal inset: the frame takes all 225px of the panel (Luke,
		// 2026-07-25: "it could still be wider"). DesignLabTab's own border is
		// EmptyBorder(4, 0, 4, 0) and its 4px belongs to the CHIP ROW, so
		// nothing insets this tab — an earlier version of this comment said it
		// did, and cost a round trip on the Goals frame.
		setBorder(new EmptyBorder(V2Tokens.PAD, 0, V2Tokens.PAD, 0));
		setAlignmentX(LEFT_ALIGNMENT);

		nav();
		surfaces();
		text();
		actions();
		selection();
		status();
		skills();
		layoutAtoms();
		composites();

		add(V2Layout.gap(V2Tokens.SECTION));
		add(V2Label.faint("Design lab V2 · sample data"));
		// how much of the panel this theme actually re-skins. A pack covers
		// what it covers, and the rest falls back to vanilla — with three
		// themes that is worth stating rather than leaving to be noticed
		// (Dark Vanilla ships no tab art, so the tabs above are brown)
		long reskinned = V2Sprites.all().values().stream()
			.filter(meta -> meta.has(theme.spriteVariant())).count();
		add(V2Label.faint(theme + " · " + reskinned + " of "
			+ V2Sprites.all().size() + " sprites"));

		wrapInFrame();
	}

	/**
	 * Move everything the sections just built inside the panel's own frame, at
	 * the panel's full 225px — measured 2026-07-25. Every migrated view wears
	 * the same frame at the same width (§7).
	 *
	 * <p>Done by reparenting after the fact rather than by threading a
	 * container through all seven section methods: {@code Container.add}
	 * removes a child from its previous parent, so the loop drains this panel
	 * into the frame in order and leaves every section's code untouched.
	 */
	private void wrapInFrame()
	{
		V2Surface frame = V2Surface.frame(theme);
		while (getComponentCount() > 0)
		{
			frame.add(getComponent(0));
		}
		add(frame);
	}

	// ── sections ──────────────────────────────────────────────────────

	/**
	 * The live nav row, imported as-is (Luke, 2026-07-25). This is the one
	 * thing on the page that is NOT V2 art, and it sits at the top because it
	 * is the first thing the panel shows:
	 *
	 * <ul>
	 * <li>the stone is {@link StoneNavButton}, which paints its own bevel with
	 *     {@code fillRect} from {@code OsrsTheme} colours — §2's hand-drawn
	 *     border, exactly what the system removes. The curated equivalent
	 *     ({@code ui/tabs/tab_stone_middle}, already sliced by
	 *     {@code V2Tokens.navStone()}) is unused by the real nav bar.</li>
	 * <li>the emblems are now curated art (Luke's pick, 2026-07-25), so the
	 *     wiki PNGs under {@code data/icons/osrs/nav/} are out of the picture
	 *     here — the live HomePanel row still wears them.</li>
	 * </ul>
	 *
	 * <p>It is here to be judged and then rebuilt on V2 art, not to be copied.
	 */
	private void nav()
	{
		heading("Nav tiles");
		JPanel stones = row();
		// glue BOTH sides — the nav row is the one thing on this page that is
		// centred rather than on the section's left edge (§7), because that is
		// how HomePanel builds it and how the game's own tab row sits
		stones.add(V2Layout.glue());
		String[][] blocks = com.ironhub.ui.HomePanel.navBlocks();
		for (int i = 0; i < blocks.length; i++)
		{
			// flush, no gap between stones — the game's own tab row, and how
			// HomePanel builds it. Selected on the first so both states show.
			// fitted, not native: the six sources span 15px to 36px, which
			// reads as six unrelated icons in one row (Luke, 2026-07-25)
			StoneNavButton stone = new StoneNavButton(theme,
				new ImageIcon(V2Sprites.fitted(theme, blocks[i][2],
					Integer.parseInt(blocks[i][3]))),
				i == 0, null)
				.textured(V2Sprites.grain(theme));
			stone.setToolTipText(blocks[i][1]);
			stones.add(stone);
		}
		stones.add(V2Layout.glue());
		add(stones);
		gap(V2Tokens.TIGHT);
		add(V2Label.faint("hand-painted stone, curated emblems"));
		add(V2Label.faint("stone not V2 art yet · Goals selected"));
		gap(V2Tokens.SECTION);
	}

	private void surfaces()
	{
		heading("Surfaces");
		V2Surface frame = V2Surface.frame(theme);
		frame.stack(V2Label.heading("Frame"), V2Tokens.ROW);
		frame.add(V2Label.body("The panel's own edge. Wraps a block."));
		add(frame);
		gap(V2Tokens.ROW);

		V2Surface tile = V2Surface.tile(theme);
		tile.stack(V2Label.heading("Tile"), V2Tokens.ROW);
		tile.add(V2Label.body("Static. Never clickable."));
		add(tile);
		gap(V2Tokens.ROW);

		V2Surface card = V2Surface.card(theme);
		card.stack(V2Label.heading("Card"), V2Tokens.ROW);
		card.add(V2Label.body("Filled. Sections, tiles, tooltips."));
		add(card);
		gap(V2Tokens.ROW);

		V2Surface well = V2Surface.well(theme);
		well.stack(V2Label.heading("Well"), V2Tokens.ROW);
		well.add(V2Label.body("Border only. Lists and fields."));
		add(well);
		gap(V2Tokens.ROW);

		V2Surface slab = V2Surface.slab(theme);
		slab.stack(V2Label.heading("Slab"), V2Tokens.ROW);
		slab.add(V2Label.body("Engraved box. Stat boxes."));
		add(slab);
		gap(V2Tokens.TIGHT);
		add(V2Label.faint("Tile's grain, notched corners"));
		gap(V2Tokens.ROW);

		// the real inventory, ported from Gear & Combat's SavedSetupView so
		// V2 shares its measured geometry instead of re-deriving it: 4 x 7 on
		// the game's backing, each frame strip cropped to its own opaque band.
		// All 28 slots full, with the GAME's own item images — a half-empty
		// grid of skill icons proved nothing about how a real one reads.
		inventory = new com.ironhub.ui.v2.V2Inventory(theme);
		fillInventory();
		JPanel centred = row();
		centred.add(V2Layout.glue());
		centred.add(inventory);
		centred.add(V2Layout.glue());
		add(centred);
		gap(V2Tokens.TIGHT);
		add(V2Label.faint("Inventory — Gear & Combat only"));
		gap(V2Tokens.ROW);

		add(new V2Divider(theme));
		gap(V2Tokens.TIGHT);
		add(V2Label.faint("Divider"));
		gap(V2Tokens.SECTION);
	}

	private void text()
	{
		heading("Text — five roles");
		add(V2Label.heading("Heading — sections and heroes"));
		add(V2Label.body("Body — the default"));
		add(V2Label.value("Value — 99 Slayer"));
		add(V2Label.detail("Detail — a second line"));
		add(V2Label.faint("Faint — provenance"));
		gap(V2Tokens.ROW);
		add(V2Label.status("Done", V2Tokens.DONE));
		add(V2Label.status("Actionable now", V2Tokens.ACTION));
		add(V2Label.status("Blocked", V2Tokens.BLOCKED));
		gap(V2Tokens.ROW);
		add(V2Label.wrapped("Wrapped body text, measured with the pixel font rather "
			+ "than html, so it breaks between words instead of mid-glyph.", contentWidth()));
		gap(V2Tokens.SECTION);
	}

	private void actions()
	{
		heading("Buttons");
		add(new V2Button(theme, "Save setup", null));
		gap(V2Tokens.ROW);
		add(new V2Button(theme, "Saved", null).labelColor(V2Tokens.DONE));
		gap(V2Tokens.ROW);
		V2Button held = new V2Button(theme, "Held down", null);
		held.setPressed(true);
		add(held);
		gap(V2Tokens.ROW);
		add(V2Label.faint("rest = metal, hover = card"));
		add(V2Label.faint("pressed = metal's own _hovered art"));
		gap(V2Tokens.ROW);

		add(V2Label.detail("Utility"));
		add(spriteRow(V2SpriteButton.WRENCH, V2SpriteButton.HELP, V2SpriteButton.MENU,
			V2SpriteButton.CLOSE, V2SpriteButton.CANCEL));
		add(V2Label.detail("Steppers"));
		add(spriteRow(V2SpriteButton.INCREMENT, V2SpriteButton.DECREMENT,
			V2SpriteButton.PLUS, V2SpriteButton.MINUS,
			V2SpriteButton.PLUS_LARGE, V2SpriteButton.MINUS_LARGE));
		add(V2Label.detail("Arrows"));
		add(spriteRow(V2SpriteButton.ARROW_UP, V2SpriteButton.ARROW_DOWN,
			V2SpriteButton.ARROW_LEFT, V2SpriteButton.ARROW_RIGHT, V2SpriteButton.BACK));
		add(V2Label.detail("Squares and wiki"));
		JPanel squares = row();
		for (String key : new String[]{V2SpriteButton.SQUARE_SMALL,
			V2SpriteButton.SQUARE_LARGE, V2SpriteButton.WIKI,
			V2SpriteButton.WIKI_SMALL})
		{
			squares.add(new V2SpriteButton(theme, key, false, null));
			squares.add(V2Layout.hgap(V2Tokens.PAD));
		}
		// a character over the art, for a mark the curated set has no sprite for
		squares.add(new V2SpriteButton(theme, V2SpriteButton.EMPTY_BOX, false, null)
			.letter("W"));
		squares.add(V2Layout.glue());
		add(squares);
		add(V2Label.faint("wiki_small — one state, opens a page"));
		add(V2Label.faint("W on the empty box — a letter, not a sprite"));
		add(V2Label.faint("selected: the two squares and the wiki toggle"));
		JPanel selected = row();
		for (String key : new String[]{V2SpriteButton.SQUARE_SMALL,
			V2SpriteButton.SQUARE_LARGE, V2SpriteButton.WIKI})
		{
			V2SpriteButton button = new V2SpriteButton(theme, key, false, null);
			button.setSelected(true);
			selected.add(button);
			selected.add(V2Layout.hgap(V2Tokens.PAD));
		}
		selected.add(V2Layout.glue());
		add(selected);
		gap(V2Tokens.SECTION);
	}

	private void selection()
	{
		heading("Checkbox");
		add(new V2Checkbox(theme, "Herb run", true, null));
		add(new V2Checkbox(theme, "Tree run", false, null));
		add(new V2Checkbox(theme, "Hardwood run", false, null)
			.state(V2Checkbox.State.LOCKED));
		add(new V2Checkbox(theme, "Fruit tree run", true, null)
			.state(V2Checkbox.State.DISABLED_ON));
		gap(V2Tokens.SECTION);

		heading("Chips");
		add(new V2ChipRow(theme, true, "Today", "Route", "Goals"));
		gap(V2Tokens.ROW);
		V2ChipRow narrow = new V2ChipRow(theme, false, "All", "Owned", "Missing");
		narrow.setSelected(1);
		add(narrow);
		gap(V2Tokens.SECTION);

		heading("Tiles");
		JPanel tiles = row();
		String[] skills = {"farming", "slayer", "construction", "hunter"};
		for (int i = 0; i < skills.length; i++)
		{
			V2Tile tile = new V2Tile(theme, sprite("icons/skills/" + skills[i]), null, 50, null);
			tile.selected(i == 1).owned(i == 0);
			V2Tooltip.install(tile, "Tile — " + skills[i]);
			tiles.add(tile);
			tiles.add(V2Layout.hgap(V2Tokens.ROW));
		}
		tiles.add(V2Layout.glue());
		add(tiles);
		gap(V2Tokens.ROW);
		JPanel captioned = row();
		String[] names = {"Herbs", "Trees", "Seeds"};
		for (int i = 0; i < names.length; i++)
		{
			V2Tile tile = new V2Tile(theme, sprite("icons/skills/farming"), names[i], 60, null);
			tile.selected(i == 0);
			captioned.add(tile);
			captioned.add(V2Layout.hgap(V2Tokens.ROW));
		}
		captioned.add(V2Layout.glue());
		add(captioned);
		gap(V2Tokens.ROW);
		add(V2Label.detail("Caption inside, corner count"));
		JPanel inside = row();
		String[] pages = {"Abyssal Sire", "Barrows Chests", "Wintertodt"};
		String[] counts = {"7/33", "24/24", "0/12"};
		for (int i = 0; i < pages.length; i++)
		{
			V2Tile tile = new V2Tile(theme, sprite("icons/skills/slayer"), pages[i], 56, null)
				.width(68).captionLines(2).captionInside().corner(counts[i]);
			if (i == 1)
			{
				tile.status(V2Tile.Status.DONE);
			}
			else if (i == 0)
			{
				tile.status(V2Tile.Status.READY).progress(7 / 33.0);
			}
			inside.add(tile);
			inside.add(V2Layout.hgap(V2Tokens.ROW));
		}
		inside.add(V2Layout.glue());
		add(inside);
		gap(V2Tokens.ROW);
		add(V2Label.detail("Status tiles"));
		JPanel statuses = row();
		V2Tile.Status[] states = V2Tile.Status.values();
		for (int i = 0; i < states.length; i++)
		{
			V2Tile tile = new V2Tile(theme, sprite("icons/skills/mining"), null, 50, null);
			tile.status(states[i]);
			statuses.add(tile);
			statuses.add(V2Layout.hgap(V2Tokens.ROW));
		}
		statuses.add(V2Layout.glue());
		add(statuses);
		gap(V2Tokens.ROW);
		add(V2Label.detail("Wrapping progress"));
		JPanel wraps = row();
		double[] fractions = {0.25, 0.5, 0.75, 1};
		for (double fraction : fractions)
		{
			wraps.add(new V2Tile(theme, sprite("icons/skills/farming"), null, 50, null)
				.status(V2Tile.Status.READY).progress(fraction));
			wraps.add(V2Layout.hgap(V2Tokens.ROW));
		}
		wraps.add(V2Layout.glue());
		add(wraps);
		gap(V2Tokens.SECTION);

		heading("Tabs — two styles to pick from");
		for (V2Tab.Style style : V2Tab.Style.values())
		{
			add(V2Label.detail(style.name().toLowerCase()));
			JPanel tabs = row();
			for (int i = 0; i < 3; i++)
			{
				tabs.add(new V2Tab(theme, style, sprite("icons/skills/mining"), null)
					.active(i == 1));
			}
			if (style == V2Tab.Style.TAB)
			{
				tabs.add(new V2Tab(theme, style, null, null).empty(true));
			}
			tabs.add(V2Layout.glue());
			add(tabs);
			gap(V2Tokens.ROW);
		}
		gap(V2Tokens.ROW);
		add(V2Label.detail("Slots"));
		JPanel slots = row();
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.HEAD, null));
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.CAPE, null));
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.WEAPON, null)
			.item(sprite("icons/skills/attack")));
		slots.add(new V2ItemSlot(theme, V2ItemSlot.Slot.SHIELD, null).selected(true));
		slots.add(new V2ItemSlot(theme, null));
		slots.add(V2Layout.glue());
		add(slots);
		gap(V2Tokens.SECTION);
	}

	private void status()
	{
		heading("Glyphs");
		JPanel glyphs = row();
		for (String key : new String[]{V2Glyph.TICK, V2Glyph.TICK_LARGE, V2Glyph.CROSS,
			V2Glyph.LOCK, V2Glyph.STAR, V2Glyph.CHEVRON_CLOSED, V2Glyph.CHEVRON_OPEN,
			V2Glyph.SORT_ASCENDING, V2Glyph.SORT_DESCENDING})
		{
			glyphs.add(new V2Glyph(theme, key));
			glyphs.add(V2Layout.hgap(V2Tokens.PAD));
		}
		glyphs.add(V2Layout.glue());
		add(glyphs);
		gap(V2Tokens.SECTION);

		heading("Progress — three weights");
		add(new V2ProgressBar(theme, V2ProgressBar.Size.FULL).fraction(0.35));
		gap(V2Tokens.ROW);
		add(new V2ProgressBar(theme, V2ProgressBar.Size.ROW).fraction(0.35)
			.labels("Farming", "72 / 99", "8.4m"));
		gap(V2Tokens.ROW);
		add(new V2ProgressBar(theme, V2ProgressBar.Size.METER).fraction(0.35).segments(4));
		gap(V2Tokens.ROW);
		add(new V2ProgressBar(theme, V2ProgressBar.Size.ROW));
		gap(V2Tokens.TIGHT);
		add(V2Label.faint("unknown — an empty trough, never a zero"));
		gap(V2Tokens.SECTION);
	}

	/**
	 * Every skill icon in the curated set, at its own 25px size. Four rows of
	 * six rather than one long line — the panel is 225px and a wrapping row is
	 * how you see at a glance that a skill is MISSING, which is the only
	 * question a sprite sheet has to answer.
	 */
	private void skills()
	{
		heading("Skill icons");
		JPanel line = row();
		int inRow = 0;
		for (String name : SKILLS)
		{
			if (inRow == 6)
			{
				line.add(V2Layout.glue());
				add(line);
				add(V2Layout.gap(V2Tokens.ROW));
				line = row();
				inRow = 0;
			}
			line.add(new V2SpriteButton(theme, V2Sprites.skill(name), false, null));
			line.add(V2Layout.hgap(V2Tokens.PAD));
			inRow++;
		}
		line.add(V2Layout.glue());
		add(line);
		gap(V2Tokens.TIGHT);
		add(V2Label.faint(SKILLS.length + " skills, 25px native"));
		gap(V2Tokens.SECTION);
	}

	private void layoutAtoms()
	{
		heading("Hero");
		add(V2Hero.build(theme, "Collections Logged", "1,482 / 1,706", 0.868,
			"as of last log open"));
		gap(V2Tokens.SECTION);

		heading("Table");
		V2Table table = new V2Table(theme, 1);
		table.row(new V2Glyph(theme, V2Glyph.TICK), V2Label.body("Ranarr weed"),
			V2Table.right(V2Label.value("142")));
		table.row(new V2Glyph(theme, V2Glyph.CROSS), V2Label.body("Snapdragon"),
			V2Table.right(V2Label.value("0")));
		table.row(new V2Glyph(theme, V2Glyph.LOCK), V2Label.body("Torstol seed"),
			V2Table.right(V2Label.value("8")));
		table.row(V2Table.blank(), V2Label.body("Grimy toadflax, a longer name"),
			V2Table.right(V2Label.value("1,204")));
		add(table);
		gap(V2Tokens.SECTION);

		heading("Checklist");
		com.ironhub.ui.v2.V2Checklist list = new com.ironhub.ui.v2.V2Checklist(theme);
		list.row(new V2Checkbox(theme, "Herb run", true, null));
		list.row(new V2Checkbox(theme, "Tree run", false, null));
		list.row(new V2Checkbox(theme, "Hardwood run", false, null)
			.state(V2Checkbox.State.LOCKED));
		list.setHighlighted(1);
		add(list);
		gap(V2Tokens.SECTION);

		heading("Empty and unknown");
		add(V2EmptyState.empty(theme, "No runs configured yet."));
		gap(V2Tokens.ROW);
		add(V2EmptyState.unknown(theme, "We haven't seen your house yet.",
			"Enter building mode in your own house to sync what's built."));
		gap(V2Tokens.SECTION);
	}

	private void composites()
	{
		heading("Field and dropdown");
		add(new V2TextField(theme, "Search items...", null));
		gap(V2Tokens.ROW);
		V2TextField typed = new V2TextField(theme, "Search items...", null);
		typed.setText("ranarr");
		add(typed);
		gap(V2Tokens.ROW);
		add(new V2Dropdown(theme, "Value", "Attack bonus", "Defence bonus"));
		gap(V2Tokens.SECTION);

		heading("Tooltip");
		V2Tooltip tip = new V2Tooltip(theme);
		tip.setTipText("Needs Farming 65 and a Magic secateurs you don't own yet.");
		add(tip);
	}

	// ── helpers ───────────────────────────────────────────────────────

	private void heading(String text)
	{
		add(V2Label.heading(text));
		add(V2Layout.gap(V2Tokens.ROW));
	}

	private void gap(int px)
	{
		add(V2Layout.gap(px));
	}

	private static JPanel row()
	{
		return V2Layout.row();
	}

	private JComponent spriteRow(String... keys)
	{
		JPanel row = row();
		for (String key : keys)
		{
			row.add(new V2SpriteButton(theme, key, null));
			row.add(V2Layout.hgap(V2Tokens.PAD));
		}
		row.add(V2Layout.glue());
		return row;
	}

	/**
	 * Ask the cache for all 28 item images and hand them to the inventory.
	 *
	 * <p>Called once at build and AGAIN from the cache's arrival callback,
	 * which is the whole point: {@code SpriteCache.get} returns null on a miss
	 * and fetches in the background, so a one-shot population at build time
	 * stores 28 nulls and a bare {@code repaint()} redraws those same nulls
	 * forever. The callback has to re-ASK, not just redraw.
	 */
	private void fillInventory()
	{
		if (inventory == null)
		{
			return; // the cache can call back before the field is assigned
		}
		for (int slot = 0; slot < INVENTORY.length; slot++)
		{
			// width -1 keeps the sprite's own 36x32 aspect — the convention
			// every other tab uses; a square box squashes wide items
			inventory.item(slot, sprites.get(INVENTORY[slot], -1, V2Tokens.ICON * 2));
		}
	}

	/** The Card's grain, repeating — what Tile and Card already wear. */
	private BufferedImage sprite(String key)
	{
		return V2Sprites.get(theme, key);
	}

	private static int contentWidth()
	{
		return V2Tokens.CONTENT_WIDTH - 2 * V2Tokens.ROW;
	}
}
