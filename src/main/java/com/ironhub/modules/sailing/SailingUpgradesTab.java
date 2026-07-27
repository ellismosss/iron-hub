package com.ironhub.modules.sailing;

import com.ironhub.data.BoatUpgradesPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.SpriteCache;
import com.ironhub.ui.components.TileTree;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * Boats tab (the uniform Build design system, {@link TileTree}): a grid of
 * your OWNED BOAT tiles (raft / skiff / sloop); click one to expand its
 * FACILITY sub-tiles (Hull, Helm, Cannon, Cargo hold, ...); click a facility
 * to open its tier ladder — built green, the next tier with its Sailing and
 * Construction levels, materials and schematic gate, later tiers faint. A
 * boat/facility tile carries a green corner tick when its ladder is complete
 * and an orange bevel when the next tier is buildable right now. Detection is
 * automatic (synced while you captain the boat), so the ladder is
 * display-only. Frameless — the host names the module.
 */
class SailingUpgradesTab extends JPanel
{
	private final AccountState state;
	private final SailingUpgradesModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless — sprites skipped
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final SpriteCache sprites;
	private final JPanel header = new JPanel();
	private final TileTree tree;

	/** Usable temporary-boost headroom per skill, refreshed each rebuild. */
	private java.util.Map<net.runelite.api.Skill, Integer> boosts = java.util.Map.of();

	SailingUpgradesTab(AccountState state, SailingUpgradesModule module,
		OsrsTheme theme, ItemManager itemManager)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.itemManager = itemManager;
		this.sprites = new SpriteCache(itemManager, listener);
		this.tree = new TileTree(theme, sprites);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
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

	/** Test seam: open a facility's detail. Key is "&lt;boatType&gt;:&lt;part&gt;". */
	void expand(String key)
	{
		int colon = key.indexOf(':');
		if (colon < 0)
		{
			return;
		}
		tree.selectForTest("boat:" + key.substring(0, colon), key);
	}

	void rebuild()
	{
		BoatUpgradesPack pack = module.pack();
		header.removeAll();
		if (pack == null)
		{
			header.add(new OsrsLabel("Boats pack unavailable.", OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
			tree.setModel(List.of());
			finish();
			return;
		}
		boosts = module.boostsPack() == null ? java.util.Map.of()
			: com.ironhub.requirements.Boosts.available(module.boostsPack(), state);
		List<Integer> boats = module.knownBoats();
		if (boats.isEmpty())
		{
			header.add(OsrsLabel.wrapped("Board your boat (or enter the shipyard) and Iron "
				+ "Hub will sync its parts here.", 210, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
			tree.setModel(List.of());
			finish();
			return;
		}
		int available = 0;
		for (int boatType : boats)
		{
			for (BoatUpgradesPack.Part part : module.partsFor(boatType))
			{
				BoatUpgradesPack.Upgrade next = module.nextRow(boatType, part.key);
				if (next != null && (met(next.reqs) || boostMet(next.reqs)))
				{
					available++;
				}
			}
		}
		header.add(new OsrsLabel("Boats", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		header.add(Box.createHorizontalGlue());
		header.add(new OsrsLabel(available + " upgrade" + (available == 1 ? "" : "s") + " available",
			available == 0 ? OsrsSkin.FAINT : OsrsSkin.VALUE, OsrsSkin.smallFont()));

		tree.setModel(buildModel(boats));
		finish();
	}

	private void finish()
	{
		revalidate();
		repaint();
	}

	// ── model: boats -> facilities -> tier-ladder detail ──────────────────

	private List<TileTree.Top> buildModel(List<Integer> boats)
	{
		List<TileTree.Top> tops = new ArrayList<>();
		for (int boatType : boats)
		{
			TileTree.Top boat = new TileTree.Top();
			boat.id = "boat:" + boatType;
			boat.label = SailingUpgradesModule.boatLabel(boatType);
			List<BoatUpgradesPack.Part> parts = module.partsFor(boatType);
			for (BoatUpgradesPack.Part part : parts)
			{
				boat.leaves.add(facilityLeaf(boatType, part));
			}
			boat.badge = boat.leaves.size();
			boat.owned = boat.leaves.stream().allMatch(l -> l.owned);
			boat.tracked = boat.leaves.stream().anyMatch(l -> l.tracked);
			int done = (int) boat.leaves.stream().filter(l -> l.owned).count();
			boat.tooltip = boat.label + " — " + done + "/" + boat.leaves.size()
				+ " facilities complete (as of your last boarding)";
			tops.add(boat);
		}
		return tops;
	}

	private TileTree.Leaf facilityLeaf(int boatType, BoatUpgradesPack.Part part)
	{
		BoatUpgradesPack.Upgrade next = module.nextRow(boatType, part.key);
		BoatUpgradesPack.Upgrade current = module.currentRow(boatType, part.key);
		TileTree.Leaf leaf = new TileTree.Leaf();
		leaf.id = boatType + ":" + part.key;
		leaf.label = part.name;
		leaf.icon = repIcon(current != null ? current : next);   // a representative build material
		leaf.owned = next == null;                                // ladder complete
		leaf.tracked = next != null && (met(next.reqs) || boostMet(next.reqs));  // buildable now
		leaf.badge = module.pack().rowsFor(part.key, boatType).size();
		leaf.tooltip = "<html><div style='width:200px'>" + part.name + " — "
			+ facilityStatus(next, current) + "</div></html>";
		leaf.detail = () -> facilityDetail(boatType, part);
		return leaf;
	}

	private String facilityStatus(BoatUpgradesPack.Upgrade next, BoatUpgradesPack.Upgrade current)
	{
		if (next == null)
		{
			return "complete" + (current == null ? "" : " — " + current.name + " built");
		}
		if (met(next.reqs))
		{
			return "buildable now: " + next.name + " (Sailing " + next.sailing + ")";
		}
		if (boostMet(next.reqs))
		{
			return "buildable with a boost: " + next.name;
		}
		return (current == null ? "not built" : current.name + " built")
			+ " · next " + next.name + " (Sailing " + next.sailing + ")";
	}

	/** A representative item icon for a facility tile: its build material (no
	 *  inventory item exists for a built boat part). Null when unknown. */
	private Integer repIcon(BoatUpgradesPack.Upgrade upgrade)
	{
		return upgrade != null && upgrade.materials != null && !upgrade.materials.isEmpty()
			? upgrade.materials.get(0).itemId : null;
	}

	/** The level-3 detail: the facility's benefit, built tier, the next tier's
	 *  levels/materials/gates, and later tiers — plus goal/wiki controls. */
	private JComponent facilityDetail(int boatType, BoatUpgradesPack.Part part)
	{
		BoatUpgradesPack.Upgrade next = module.nextRow(boatType, part.key);
		BoatUpgradesPack.Upgrade current = module.currentRow(boatType, part.key);
		// the opened facility is the one live readout on the page — the Card
		com.ironhub.ui.v2.V2Surface card = com.ironhub.ui.v2.V2Surface.card(theme);

		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setBorder(new EmptyBorder(1, UiTokens.PAD, 2, 0));
		head.add(new OsrsLabel(part.name, OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned().squeezable());
		head.add(Box.createHorizontalGlue());
		if (next != null)
		{
			boolean isGoal = module.isGoal(next);
			head.add(goalGlyph(isGoal, isGoal ? next.name + " — tracked; click to untrack"
				: "Track building " + next.name + " in Goals", () -> module.toggleGoal(next)));
			head.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			head.add(wikiGlyph(next.page));
		}
		cap(head);
		card.add(head);

		card.add(detail(part.benefit, OsrsSkin.MUTED));
		if (current != null)
		{
			card.add(detail("Built: " + current.name, OsrsSkin.VALUE));
		}
		if (next == null)
		{
			card.add(detail("Fully upgraded.", OsrsSkin.VALUE));
			cap(card);
			return card;
		}
		card.add(detail("Sailing " + next.sailing + " · Construction " + next.construction, OsrsSkin.MUTED));
		String missing = missingText(next.reqs);
		if (missing != null && boostMet(next.reqs))
		{
			card.add(detail("Needs a boost: " + boostDetail(next.reqs), OsrsSkin.VALUE));
		}
		else if (missing != null)
		{
			card.add(detail("Needs: " + missing, OsrsSkin.FAINT));
		}
		else
		{
			card.add(detail("Buildable now", OsrsSkin.VALUE));
		}
		for (BoatUpgradesPack.Material m : next.materials)
		{
			card.add(materialRow(m));
		}
		BoatUpgradesPack.Upgrade after = rowAfter(boatType, part.key, next.tier);
		if (after != null)
		{
			card.add(detail("Then: " + after.name + " (Sailing " + after.sailing + ")", OsrsSkin.FAINT));
		}
		cap(card);
		return card;
	}

	/** One material line: sprite, "qty x name", owned count green when covered,
	 *  red shortfall — with a where-from hover when short. */
	private JComponent materialRow(BoatUpgradesPack.Material m)
	{
		JPanel r = new JPanel();
		r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
		r.setOpaque(false);
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setBorder(new EmptyBorder(1, UiTokens.PAD, 1, 0));
		if (itemManager != null)
		{
			java.awt.Image sprite = sprites.getBox(m.itemId, 16);
			Icon icon = sprite == null ? null : new ImageIcon(sprite);
			if (icon != null)
			{
				r.add(new JLabel(icon));
				r.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			}
		}
		int owned = state.canonicalStock(m.itemId);
		boolean enough = owned >= m.qty;
		OsrsLabel matName = new OsrsLabel(m.qty + " x " + m.name, OsrsSkin.MUTED,
			OsrsSkin.smallFont()).leftAligned().squeezable();
		String sources = !enough && module.itemSources() != null
			? module.itemSources().sourceLine(m.itemId, state,
				state.getItemSourcePref(m.itemId)) : null;
		if (sources != null)
		{
			matName.setToolTipText("<html>" + m.name + "<br>" + sources + "</html>");
			r.setToolTipText(matName.getToolTipText());
		}
		r.add(matName);
		r.add(Box.createHorizontalGlue());
		r.add(new OsrsLabel(enough ? "have " + m.qty : owned + "/" + m.qty,
			enough ? OsrsSkin.VALUE : UiTokens.STATUS_WARNING, OsrsSkin.smallFont()));
		cap(r);
		return r;
	}

	private BoatUpgradesPack.Upgrade rowAfter(int boatType, String partKey, int tier)
	{
		for (BoatUpgradesPack.Upgrade row : module.pack().rowsFor(partKey, boatType))
		{
			if (row.tier > tier)
			{
				return row;
			}
		}
		return null;
	}

	// ── requirement helpers ───────────────────────────────────────────────

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

	private boolean boostMet(List<String> reqs)
	{
		for (String req : reqs)
		{
			if (!Requirements.parse(req).isMetWithBoosts(state, boosts))
			{
				return false;
			}
		}
		return true;
	}

	private String boostDetail(List<String> reqs)
	{
		List<String> parts = new ArrayList<>();
		for (String req : reqs)
		{
			Requirement parsed = Requirements.parse(req);
			if (parsed.isMet(state))
			{
				continue;
			}
			for (Requirement leaf : parsed.missing(state))
			{
				net.runelite.api.Skill skill = leaf.boostableSkill();
				if (skill == null || !leaf.isMetWithBoosts(state, boosts))
				{
					continue;
				}
				List<String> sources = module.boostsPack() == null ? List.of()
					: com.ironhub.requirements.Boosts.describe(module.boostsPack(), state, skill);
				parts.add(leaf.describe() + (sources.isEmpty() ? " boostable"
					: " boostable with " + String.join(", ", sources)));
			}
		}
		return parts.isEmpty() ? null : String.join("; ", parts);
	}

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

	// ── shared bits ───────────────────────────────────────────────────────

	private JComponent detail(String text, Color color)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(1, UiTokens.PAD, 1, 0));
		holder.add(OsrsLabel.wrapped(text, 190, color, OsrsSkin.smallFont()).leftAligned());
		holder.add(Box.createHorizontalGlue());
		cap(holder);
		return holder;
	}

	private static OsrsLabel wikiGlyph(String page)
	{
		OsrsLabel glyph = new OsrsLabel("W", OsrsSkin.FAINT, OsrsSkin.font());
		glyph.setToolTipText("Open the wiki page");
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setColor(OsrsSkin.LABEL);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				glyph.setColor(OsrsSkin.FAINT);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
					+ page.replace(' ', '_'));
				e.consume();
			}
		});
		return glyph;
	}

	/** The +/× goal affordance — its own control (JLabel, diaries grammar). */
	private static JLabel goalGlyph(boolean isGoal, String tooltip, Runnable onClick)
	{
		JLabel glyph = new JLabel(isGoal ? "×" : "+");
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
