package com.ironhub.ui;

import com.ironhub.ui.components.AlertChip;
import com.ironhub.ui.components.GridTile;
import com.ironhub.ui.components.HubProgressBar;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.LabeledTile;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.NavHeader;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SegmentedControl;
import com.ironhub.ui.components.Status;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Renders every shared UI atom at exact panel width for a side-by-side
 * against mockup frame 1a. Content mirrors the 1a §5 row sampler plus the
 * 2a ladder tiles and 2f labeled tiles. Not shipped — test sources only.
 */
public class AtomGallery
{
	public static JPanel build()
	{
		JPanel root = new JPanel();
		root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
		root.setBackground(UiTokens.PANEL_BG);

		root.add(new NavHeader("Gear progression", null));

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(UiTokens.PANEL_BG);
		body.setBorder(new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD));

		body.add(new SearchField("Search modules…"));
		body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		// frame 1a §5 — the four list-row states
		body.add(ListRow.owned("Dragon defender", IconButton.path(null), IconButton.wiki(null)));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		body.add(ListRow.available("Barrows gloves", IconButton.path(null), IconButton.wiki(null)));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		body.add(ListRow.locked("Ava's assembler", "Dragon Slayer II", IconButton.path(null), IconButton.wiki(null)));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		body.add(ListRow.warning("Prayer potions", "6 h left", IconButton.wiki(null)));
		body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		// frame 1a §6 — controls
		JPanel controls = row();
		controls.add(new SegmentedControl(false, "Tree", "Checklist"));
		controls.add(Box.createHorizontalStrut(UiTokens.PAD));
		controls.add(SegmentedControl.viewToggle());
		controls.add(Box.createHorizontalStrut(UiTokens.PAD));
		controls.add(new AlertChip("4 dailies", Status.AVAILABLE));
		controls.add(Box.createHorizontalGlue());
		body.add(controls);
		body.add(Box.createVerticalStrut(UiTokens.PAD));

		SegmentedControl styleTabs = new SegmentedControl(true, "Melee", "Range", "Mage");
		styleTabs.setSelected(1);
		body.add(styleTabs);
		body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		body.add(HubProgressBar.bar(0.34));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		body.add(HubProgressBar.bar(1.0));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		JPanel miniRow = row();
		miniRow.add(HubProgressBar.mini(0.57, 48));
		miniRow.add(Box.createHorizontalGlue());
		body.add(miniRow);
		body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		// frame 2a HEAD ladder — grid tile states
		JPanel tiles = row();
		GridTile[] ladder = {
			new GridTile("CO", "Coif", GridTile.State.OWNED, false),
			new GridTile("AH", "Archer helm", GridTile.State.OWNED, true),
			new GridTile("KC", "Karil's coif", GridTile.State.NEXT, false),
			new GridTile("CH", "Crystal helm", GridTile.State.LOCKED, false),
			new GridTile("MM", "Masori mask", GridTile.State.LOCKED, false),
		};
		for (GridTile tile : ladder)
		{
			tiles.add(tile);
			tiles.add(Box.createHorizontalStrut(UiTokens.GRID_GAP));
		}
		tiles.add(Box.createHorizontalGlue());
		body.add(tiles);
		body.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));

		// frame 2f — labeled 3-col tiles
		JPanel labeled = new JPanel(new GridLayout(1, 3, UiTokens.GRID_GAP, 0));
		labeled.setBackground(UiTokens.PANEL_BG);
		labeled.setAlignmentX(Component.LEFT_ALIGNMENT);
		labeled.add(new LabeledTile("HB", "Herblore", "2.4M", UiTokens.STATUS_AVAILABLE,
			null, "Herblore — 2.4M banked, method: unf potions"));
		labeled.add(new LabeledTile("CK", "Cooking", "3.0M", UiTokens.STATUS_AVAILABLE,
			null, "Cooking — 3.0M banked"));
		labeled.add(new LabeledTile("FL", "Fletching", "1.1M", UiTokens.STATUS_AVAILABLE,
			HubProgressBar.mini(0.57, 48), "Fletching — 1.1M banked"));
		body.add(labeled);
		body.add(Box.createVerticalGlue());

		root.add(body);
		return root;
	}

	private static JPanel row()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBackground(UiTokens.PANEL_BG);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	public static BufferedImage render()
	{
		JPanel root = build();
		root.setSize(new Dimension(UiTokens.PANEL_WIDTH, root.getPreferredSize().height));
		layoutTree(root);
		// height settles once children know their width
		root.setSize(new Dimension(UiTokens.PANEL_WIDTH, root.getPreferredSize().height));
		layoutTree(root);

		BufferedImage image = new BufferedImage(
			root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
		root.paint(image.getGraphics());
		return image;
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	public static void main(String[] args) throws IOException
	{
		File out = new File(args.length > 0 ? args[0] : "build/atom-gallery.png");
		out.getParentFile().mkdirs();
		ImageIO.write(render(), "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}
}
