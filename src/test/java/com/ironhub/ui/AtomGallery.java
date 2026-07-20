package com.ironhub.ui;

import com.ironhub.ui.components.GridTile;
import com.ironhub.ui.components.IconButton;
import com.ironhub.ui.components.ListRow;
import com.ironhub.ui.components.NavHeader;
import java.awt.Component;
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

		// frame 1a §5 — the four list-row states
		body.add(ListRow.owned("Dragon defender", IconButton.path(null), IconButton.wiki(null)));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		body.add(ListRow.available("Barrows gloves", IconButton.path(null), IconButton.wiki(null)));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		body.add(ListRow.locked("Ava's assembler", "Dragon Slayer II", IconButton.path(null), IconButton.wiki(null)));
		body.add(Box.createVerticalStrut(UiTokens.PAD_TIGHT));
		body.add(ListRow.warning("Prayer potions", "6 h left", IconButton.wiki(null)));
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
		return SwingRender.render(build());
	}

	public static void main(String[] args) throws IOException
	{
		File out = new File(args.length > 0 ? args[0] : "build/atom-gallery.png");
		out.getParentFile().mkdirs();
		ImageIO.write(render(), "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}
}
