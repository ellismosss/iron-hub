package com.ironhub.modules.poh;

import com.ironhub.data.PohPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.WrapLayout;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneTile;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * POH tab: one tile per useful house space (green bevel = ladder complete,
 * orange = the next tier is buildable right now, plain = progressing,
 * dim = nothing built and the next tier is locked). A single expanded
 * space (farm-overview grammar) lists its tier ladder — built green, the
 * next tier with its requirements and what's missing, later tiers faint.
 * Clicking a tier row toggles the manual built mark (the escape hatch for
 * houses built before Iron Hub). Frameless — the host names the module.
 */
class PohTab extends JPanel
{
	private final AccountState state;
	private final PohModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless — icons skipped
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final com.ironhub.ui.components.SpriteCache sprites;

	private final JPanel content = new JPanel();
	private String expanded;

	PohTab(AccountState state, PohModule module, OsrsTheme theme)
	{
		this(state, module, theme, null);
	}

	PohTab(AccountState state, PohModule module, OsrsTheme theme, ItemManager itemManager)
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

		state.addListener(listener);
		rebuild();
	}

	void dispose()
	{
		state.removeListener(listener);
	}

	/** Test seam: expand one space (null collapses). */
	void expand(String spaceId)
	{
		expanded = spaceId;
		rebuild();
	}

	void rebuild()
	{
		content.removeAll();
		PohPack pack = module.pack();
		if (pack == null)
		{
			content.add(line("POH pack unavailable.", OsrsSkin.FAINT));
			content.revalidate();
			content.repaint();
			return;
		}

		int maxed = 0;
		for (PohPack.Space space : pack.spaces)
		{
			if (module.nextTier(space) == null)
			{
				maxed++;
			}
		}
		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setBorder(new EmptyBorder(2, 4, 2, 4));
		head.add(new OsrsLabel("Useful builds", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		head.add(Box.createHorizontalGlue());
		head.add(new OsrsLabel(maxed + "/" + pack.spaces.size() + " complete",
			OsrsSkin.VALUE, OsrsSkin.font()));
		cap(head);
		content.add(head);

		// the tile grid (farm-overview grammar: click toggles the expansion)
		JPanel grid = new JPanel(new WrapLayout(FlowLayout.LEFT, UiTokens.CHIP_GAP, UiTokens.CHIP_GAP));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		for (PohPack.Space space : pack.spaces)
		{
			grid.add(tile(space));
		}
		grid.setMaximumSize(new Dimension(UiTokens.PANEL_WIDTH, Integer.MAX_VALUE));
		content.add(grid);

		if (expanded != null)
		{
			for (PohPack.Space space : pack.spaces)
			{
				if (space.id.equals(expanded))
				{
					addLadder(space);
				}
			}
		}
		else
		{
			content.add(line("Click a build to see its tiers", OsrsSkin.FAINT));
		}
		content.revalidate();
		content.repaint();
	}

	private StoneTile tile(PohPack.Space space)
	{
		PohPack.Tier next = module.nextTier(space);
		PohPack.Tier built = module.builtTier(space);
		Color bevel;
		boolean dim = false;
		String status;
		if (next == null)
		{
			bevel = ColorScheme.PROGRESS_COMPLETE_COLOR.darker();
			status = "Complete — " + built.name + " built";
		}
		else if (met(next.reqs))
		{
			bevel = ColorScheme.PROGRESS_INPROGRESS_COLOR;
			status = "Buildable now: " + next.name + " (Construction " + next.level + ")";
		}
		else if (built != null)
		{
			bevel = null;
			status = built.name + " built · next " + nextLine(next);
		}
		else
		{
			bevel = null;
			dim = true;
			status = "Not built · needs " + nextLine(next);
		}
		StoneTile tile = new StoneTile(theme, bevel, dim,
			"<html><div style='width:200px'>" + space.name + " — " + status + "</div></html>");
		Integer icon = space.icon;
		if (icon != null)
		{
			java.awt.Image sprite = sprites.get(icon, -1, StoneTile.ICON);
			if (sprite != null)
			{
				tile.setIconImage(sprite);
			}
		}
		tile.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		tile.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				expanded = space.id.equals(expanded) ? null : space.id;
				SwingUtilities.invokeLater(PohTab.this::rebuild);
			}
		});
		return tile;
	}

	private void addLadder(PohPack.Space space)
	{
		JPanel title = new JPanel();
		title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
		title.setOpaque(false);
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(new EmptyBorder(8, 4, 3, 4));
		title.add(new OsrsLabel(space.name, OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		title.add(Box.createHorizontalGlue());
		if (space.room != null)
		{
			title.add(new OsrsLabel(space.room, OsrsSkin.FAINT, OsrsSkin.smallFont()));
		}
		cap(title);
		content.add(title);

		PohPack.Tier next = module.nextTier(space);
		for (PohPack.Tier tier : space.tiers)
		{
			content.add(tierRow(space, tier, tier == next));
		}
		content.add(line("Click a tier to mark it built (for houses built before Iron Hub) · W = wiki",
			OsrsSkin.FAINT));
	}

	private JComponent tierRow(PohPack.Space space, PohPack.Tier tier, boolean isNext)
	{
		boolean built = state.isPohBuilt(tier.id);
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.setOpaque(false);
		top.setAlignmentX(LEFT_ALIGNMENT);
		Color color = built ? OsrsSkin.VALUE
			: isNext ? (met(tier.reqs) ? ColorScheme.PROGRESS_INPROGRESS_COLOR : OsrsSkin.MUTED)
			: OsrsSkin.FAINT;
		OsrsLabel name = new OsrsLabel(tier.name, color, OsrsSkin.font()).leftAligned().squeezable();
		name.setToolTipText(built ? tier.name + " — built (click to unmark)"
			: tier.name + " — click to mark as built");
		top.add(name);
		top.add(Box.createHorizontalGlue());
		top.add(new OsrsLabel("Lv " + tier.level,
			built ? OsrsSkin.FAINT : OsrsSkin.LABEL, OsrsSkin.smallFont()));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		boolean isGoal = module.isGoal(tier);
		top.add(goalGlyph(isGoal, isGoal ? tier.name + " — tracked; click to untrack"
			: "Track building " + tier.name + " in the Goal planner",
			() -> module.toggleGoal(tier)));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		top.add(wikiGlyph(tier.page));
		cap(top);
		row.add(top);

		if (!built && isNext)
		{
			String missing = missingText(tier.reqs);
			row.add(new OsrsLabel(missing == null
					? "Buildable now" : "Needs: " + missing,
				missing == null ? OsrsSkin.VALUE : OsrsSkin.FAINT, OsrsSkin.smallFont())
				.leftAligned().squeezable());
		}
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		row.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				module.toggleBuilt(tier); // listener rebuilds
			}
		});
		cap(row);
		return row;
	}

	// ── requirement helpers ───────────────────────────────────────────

	private boolean met(List<String> reqs)
	{
		for (String req : reqs)
		{
			if (!Requirements.parse(req).isMet(state))
			{
				return false;
			}
		}
		return true;
	}

	/** Unmet leaves as a comma line, or null when all met. */
	private String missingText(List<String> reqs)
	{
		List<String> missing = new ArrayList<>();
		for (String req : reqs)
		{
			Requirement parsed = Requirements.parse(req);
			if (!parsed.isMet(state))
			{
				for (Requirement leaf : parsed.missing(state))
				{
					missing.add(leaf.describe());
				}
			}
		}
		return missing.isEmpty() ? null : String.join(", ", missing);
	}

	private String nextLine(PohPack.Tier next)
	{
		String missing = missingText(next.reqs);
		return next.name + (missing == null ? " (buildable now)" : " — needs " + missing);
	}

	// ── shared bits ───────────────────────────────────────────────────

	private static OsrsLabel wikiGlyph(String page)
	{
		OsrsLabel glyph = new OsrsLabel("W", OsrsSkin.FAINT, OsrsSkin.font());
		glyph.setToolTipText("Open the wiki page");
		glyph.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		glyph.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				glyph.setColor(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				glyph.setColor(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
					+ page.replace(' ', '_'));
				e.consume();
			}
		});
		return glyph;
	}

	/** The +/× goal affordance in skin colours — a dedicated control with
	 *  its own action, never the row's build-toggle click (the diaries
	 *  glyph grammar; JLabel so its own listener wins over the row's). */
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

	private JComponent line(String text, Color color)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
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
