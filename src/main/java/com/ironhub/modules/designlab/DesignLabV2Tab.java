package com.ironhub.modules.designlab;

import com.ironhub.ui.osrs.OsrsTheme;
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
	private final OsrsTheme theme;

	public DesignLabV2Tab(OsrsTheme theme)
	{
		this.theme = theme;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(V2Tokens.PAD, V2Tokens.ROW, V2Tokens.PAD, V2Tokens.ROW));
		setAlignmentX(LEFT_ALIGNMENT);

		surfaces();
		text();
		actions();
		selection();
		status();
		layoutAtoms();
		composites();

		add(V2Layout.gap(V2Tokens.SECTION));
		add(V2Label.faint("Design lab V2 · sample data · "
			+ V2Sprites.all().size() + " curated sprites"));
	}

	// ── sections ──────────────────────────────────────────────────────

	private void surfaces()
	{
		heading("Surfaces");
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

		V2Surface frame = V2Surface.frame(theme);
		frame.add(V2Label.body("Frame — the panel border"));
		add(frame);
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
		add(V2Label.faint("the curated art has no hover for this button"));
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
		add(spriteRow(V2SpriteButton.SQUARE, V2SpriteButton.SQUARE_SMALL,
			V2SpriteButton.SQUARE_LARGE, V2SpriteButton.WIKI));
		add(V2Label.faint("selected states the art has"));
		JPanel selected = row();
		for (String key : new String[]{V2SpriteButton.SQUARE_SMALL,
			V2SpriteButton.SQUARE_LARGE, V2SpriteButton.WIKI, V2SpriteButton.MENU})
		{
			V2SpriteButton button = new V2SpriteButton(theme, key, null);
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
		gap(V2Tokens.SECTION);

		heading("Tabs and slots");
		JPanel tabs = row();
		for (int i = 0; i < 3; i++)
		{
			tabs.add(new V2Tab(theme, sprite("icons/skills/mining"), null).active(i == 1));
		}
		tabs.add(V2Layout.glue());
		add(tabs);
		gap(V2Tokens.ROW);
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

		heading("Progress");
		add(new V2ProgressBar(theme).fraction(0.35));
		gap(V2Tokens.ROW);
		add(new V2ProgressBar(theme).fraction(1));
		gap(V2Tokens.ROW);
		add(new V2ProgressBar(theme));
		gap(V2Tokens.TIGHT);
		add(V2Label.faint("unknown — an empty trough, never a zero"));
		gap(V2Tokens.SECTION);
	}

	private void layoutAtoms()
	{
		heading("Hero");
		add(V2Hero.build(theme, "Collections Logged", "1,482 / 1,706", 0.868,
			"as of last log open"));
		gap(V2Tokens.SECTION);

		heading("Table");
		V2Table table = new V2Table(1);
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

	private BufferedImage sprite(String key)
	{
		return V2Sprites.get(theme, key);
	}

	private static int contentWidth()
	{
		return V2Tokens.CONTENT_WIDTH - 2 * V2Tokens.ROW;
	}
}
