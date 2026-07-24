package com.ironhub.modules.poh;

import com.ironhub.data.PohPack;
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
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * House tab (the uniform Build design system, {@link TileTree}): a grid of
 * ROOM tiles; click one to expand its HOTSPOT sub-tiles; click a hotspot to
 * open its tier ladder — built green, the next tier with its requirements
 * and materials, later tiers faint. A room/hotspot tile carries a green
 * corner tick when its ladder is complete and an orange bevel when the next
 * tier is buildable right now. Clicking a tier row toggles the manual built
 * mark (the escape hatch for houses built before Iron Hub). Frameless — the
 * host names the module.
 */
class PohTab extends JPanel
{
	private final AccountState state;
	private final PohModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless — icons skipped
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final SpriteCache sprites;
	private final JPanel header = new JPanel();
	private final TileTree tree;

	/** Usable temporary-boost headroom per skill, refreshed each rebuild. */
	private Map<net.runelite.api.Skill, Integer> boosts = Map.of();

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

	/** Test seam: open a hotspot's detail (expands its room, selects it). */
	void expand(String spaceId)
	{
		PohPack pack = module.pack();
		if (pack == null)
		{
			return;
		}
		for (PohPack.Space space : pack.spaces)
		{
			if (space.id.equals(spaceId))
			{
				tree.selectForTest(roomId(space), spaceId);
				return;
			}
		}
	}

	void rebuild()
	{
		PohPack pack = module.pack();
		header.removeAll();
		if (pack == null)
		{
			header.add(new OsrsLabel("House pack unavailable.", OsrsSkin.FAINT, OsrsSkin.font()).leftAligned());
			tree.setModel(List.of());
			revalidate();
			repaint();
			return;
		}
		boosts = module.boostsPack() == null ? Map.of()
			: com.ironhub.requirements.Boosts.available(module.boostsPack(), state);

		int complete = 0;
		for (PohPack.Space space : pack.spaces)
		{
			if (module.nextTier(space) == null)
			{
				complete++;
			}
		}
		JPanel title = new JPanel();
		title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
		title.setOpaque(false);
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.add(new OsrsLabel("Rooms", OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		title.add(Box.createHorizontalGlue());
		title.add(new OsrsLabel(complete + "/" + pack.spaces.size() + " builds complete",
			OsrsSkin.MUTED, OsrsSkin.smallFont()));
		cap(title);
		header.add(title);

		// Detection only runs in building mode (it is the only way to know the
		// house is yours), so say so rather than leaving an empty grid looking
		// broken — that silence is exactly what read as a bug before.
		if (!anyBuilt(pack))
		{
			header.add(Box.createVerticalStrut(2));
			header.add(OsrsLabel.wrapped("Enter building mode in your house to sync what "
					+ "you have built, or click any tier to mark it yourself.",
				205, OsrsSkin.FAINT, OsrsSkin.smallFont()).leftAligned());
		}

		tree.setModel(buildModel(pack));
		revalidate();
		repaint();
	}

	// ── model: rooms -> hotspots -> tier-ladder detail ────────────────────

	/** Whether anything at all is marked built — drives the sync hint. */
	private boolean anyBuilt(PohPack pack)
	{
		for (PohPack.Space space : pack.spaces)
		{
			for (PohPack.Tier tier : space.tiers)
			{
				if (state.isPohBuilt(tier.id))
				{
					return true;
				}
			}
		}
		return false;
	}

	private List<TileTree.Top> buildModel(PohPack pack)
	{
		// group the spaces (hotspots) by their room, in first-appearance order
		Map<String, TileTree.Top> byRoom = new LinkedHashMap<>();
		for (PohPack.Space space : pack.spaces)
		{
			String key = roomId(space);
			TileTree.Top room = byRoom.computeIfAbsent(key, k ->
			{
				TileTree.Top t = new TileTree.Top();
				t.id = k;
				t.label = canonicalRoom(space.room);
				t.icon = space.icon;   // the room's first hotspot is its emblem
				return t;
			});
			room.leaves.add(hotspotLeaf(space));
		}
		// roll room-level state up from its hotspots
		List<TileTree.Top> rooms = new ArrayList<>(byRoom.values());
		for (TileTree.Top room : rooms)
		{
			room.badge = room.leaves.size();
			room.owned = room.leaves.stream().allMatch(l -> l.owned);
			room.tracked = room.leaves.stream().anyMatch(l -> l.tracked);
			int done = (int) room.leaves.stream().filter(l -> l.owned).count();
			room.tooltip = room.label + " — " + done + "/" + room.leaves.size() + " hotspots complete";
		}
		return rooms;
	}

	private TileTree.Leaf hotspotLeaf(PohPack.Space space)
	{
		PohPack.Tier next = module.nextTier(space);
		PohPack.Tier built = module.builtTier(space);
		TileTree.Leaf leaf = new TileTree.Leaf();
		leaf.id = space.id;
		leaf.label = space.name;
		leaf.icon = space.icon;
		leaf.owned = next == null;                                   // ladder complete
		leaf.tracked = next != null && (met(next.reqs) || boostMet(next.reqs)); // buildable now
		leaf.badge = space.tiers.size();
		leaf.tooltip = "<html><div style='width:200px'>" + space.name + " — "
			+ hotspotStatus(space, next, built) + "</div></html>";
		leaf.detail = () -> hotspotDetail(space);
		return leaf;
	}

	private String hotspotStatus(PohPack.Space space, PohPack.Tier next, PohPack.Tier built)
	{
		if (next == null)
		{
			return "complete — " + built.name + " built";
		}
		if (met(next.reqs))
		{
			return "buildable now: " + next.name + " (Construction " + next.level + ")";
		}
		if (boostMet(next.reqs))
		{
			return "buildable with a boost: " + next.name;
		}
		if (built != null)
		{
			return built.name + " built · next " + nextLine(next);
		}
		return "not built · needs " + nextLine(next);
	}

	/** The level-3 detail: the hotspot's tier ladder + a mark/wiki hint. */
	private JComponent hotspotDetail(PohPack.Space space)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setOpaque(false);
		card.setAlignmentX(LEFT_ALIGNMENT);
		if (space.benefit != null && !space.benefit.isEmpty())
		{
			card.add(line(space.benefit, OsrsSkin.MUTED));
		}
		PohPack.Tier next = module.nextTier(space);
		for (PohPack.Tier tier : space.tiers)
		{
			card.add(tierRow(space, tier, tier == next));
		}
		card.add(line("Click a tier to mark it built (for houses built before Iron Hub) · W = wiki",
			OsrsSkin.FAINT));
		cap(card);
		return card;
	}

	private String roomId(PohPack.Space space)
	{
		return "room:" + (space.room == null ? "?" : space.room.toLowerCase(Locale.ROOT));
	}

	/** Title-case a wiki room name so mixed casing groups and displays cleanly
	 *  ("Superior garden"/"Achievement gallery" -> "Superior Garden"). */
	private static String canonicalRoom(String room)
	{
		if (room == null || room.isEmpty())
		{
			return "Other";
		}
		StringBuilder out = new StringBuilder();
		for (String word : room.trim().split("\\s+"))
		{
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(Character.toUpperCase(word.charAt(0)))
				.append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	// ── tier ladder (the build/upgrade tracking, unchanged grammar) ───────

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
			: isNext ? (met(tier.reqs) || boostMet(tier.reqs)
				? ColorScheme.PROGRESS_INPROGRESS_COLOR : OsrsSkin.MUTED)
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
			: "Track building " + tier.name + " in Goals",
			() -> module.toggleGoal(tier)));
		top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		top.add(wikiGlyph(tier.page));
		cap(top);
		row.add(top);

		if (!built && isNext)
		{
			String missing = missingText(tier.reqs);
			boolean boostable = missing != null && boostMet(tier.reqs);
			OsrsLabel needs = new OsrsLabel(missing == null ? "Buildable now"
					: boostable ? "Buildable with a boost"
					: "Needs: " + missing,
				missing == null || boostable ? OsrsSkin.VALUE : OsrsSkin.FAINT,
				OsrsSkin.smallFont()).leftAligned().squeezable();
			if (boostable)
			{
				needs.setToolTipText("<html><div style='width:200px'>"
					+ boostDetail(tier.reqs) + "</div></html>");
			}
			row.add(needs);
			for (PohPack.Material m : tier.materials)
			{
				row.add(materialRow(m));
			}
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

	/** One build-material line: sprite, "qty x name", owned count green when
	 *  covered, red shortfall — with a where-from hover when short. */
	private JComponent materialRow(PohPack.Material m)
	{
		JPanel r = new JPanel();
		r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
		r.setOpaque(false);
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setBorder(new EmptyBorder(1, UiTokens.PAD, 1, 0));
		if (itemManager != null)
		{
			java.awt.Image sprite = sprites.getBox(m.itemId, 16);
			if (sprite != null)
			{
				r.add(new JLabel(new javax.swing.ImageIcon(sprite)));
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
		if (missing != null && boostMet(next.reqs))
		{
			return next.name + " (buildable with a boost — " + boostDetail(next.reqs) + ")";
		}
		return next.name + (missing == null ? " (buildable now)" : " — needs " + missing);
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

	// ── shared bits ───────────────────────────────────────────────────────

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

	/** The +/× goal affordance — a dedicated control (JLabel so its own
	 *  listener wins over the row's build-toggle click). */
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
