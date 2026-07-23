package com.ironhub.modules.sailing;

import com.ironhub.data.BoatUpgradesPack;
import com.ironhub.requirements.Requirement;
import com.ironhub.requirements.Requirements;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
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
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * Sailing upgrades tab: one section per boat the player has captained
 * (raft / skiff / sloop), listing available upgrades first (orange —
 * levels met), then the next locked tier per part (faint, with what it
 * needs), then completed ladders (green). A single expanded row (farm
 * grammar) shows the part's benefit, the materials with owned/missing
 * counts from the bank, and the schematic gate when one applies.
 * Frameless — the host names the module.
 */
class SailingUpgradesTab extends JPanel
{
	private final AccountState state;
	private final SailingUpgradesModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless — sprites skipped
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);
	private final com.ironhub.ui.components.SpriteCache sprites;

	private final JPanel content = new JPanel();
	private String expanded; // "<boatType>:<part key>"
	/** Usable temporary-boost headroom per skill, refreshed each rebuild. */
	private java.util.Map<net.runelite.api.Skill, Integer> boosts = java.util.Map.of();

	SailingUpgradesTab(AccountState state, SailingUpgradesModule module,
		OsrsTheme theme, ItemManager itemManager)
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

	/** Test seam: expand one row (null collapses). */
	void expand(String key)
	{
		expanded = key;
		rebuild();
	}

	void rebuild()
	{
		content.removeAll();
		BoatUpgradesPack pack = module.pack();
		if (pack == null)
		{
			content.add(line("Boat-upgrades pack unavailable.", OsrsSkin.FAINT));
			finish();
			return;
		}
		boosts = module.boostsPack() == null ? java.util.Map.of()
			: com.ironhub.requirements.Boosts.available(module.boostsPack(), state);
		List<Integer> boats = module.knownBoats();
		if (boats.isEmpty())
		{
			content.add(line("Board your boat (or enter the shipyard) and "
				+ "Iron Hub will sync its parts and upgrades here.", OsrsSkin.FAINT));
			finish();
			return;
		}
		for (int boatType : boats)
		{
			addBoat(boatType);
		}
		content.add(line("Synced while you captain the boat · "
			+ "click a row for materials · + tracks it as a Goal", OsrsSkin.FAINT));
		finish();
	}

	private void finish()
	{
		content.revalidate();
		content.repaint();
	}

	private void addBoat(int boatType)
	{
		List<BoatUpgradesPack.Part> parts = module.partsFor(boatType);
		List<BoatUpgradesPack.Part> available = new ArrayList<>();
		List<BoatUpgradesPack.Part> locked = new ArrayList<>();
		List<BoatUpgradesPack.Part> complete = new ArrayList<>();
		for (BoatUpgradesPack.Part part : parts)
		{
			BoatUpgradesPack.Upgrade next = module.nextRow(boatType, part.key);
			if (next == null)
			{
				complete.add(part);
			}
			else if (met(next.reqs) || boostMet(next.reqs))
			{
				available.add(part);
			}
			else
			{
				locked.add(part);
			}
		}

		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setBorder(new EmptyBorder(8, 4, 3, 4));
		OsrsLabel title = new OsrsLabel(SailingUpgradesModule.boatLabel(boatType),
			OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned();
		title.setToolTipText("As of your last boarding - board again to re-sync");
		head.add(title);
		head.add(Box.createHorizontalGlue());
		head.add(new OsrsLabel(available.size() + " available",
			available.isEmpty() ? OsrsSkin.FAINT : OsrsSkin.VALUE, OsrsSkin.smallFont()));
		cap(head);
		content.add(head);

		for (BoatUpgradesPack.Part part : available)
		{
			addPartRow(boatType, part, Status.AVAILABLE);
		}
		for (BoatUpgradesPack.Part part : locked)
		{
			addPartRow(boatType, part, Status.LOCKED);
		}
		for (BoatUpgradesPack.Part part : complete)
		{
			addPartRow(boatType, part, Status.COMPLETE);
		}
	}

	private enum Status
	{
		AVAILABLE, LOCKED, COMPLETE
	}

	private void addPartRow(int boatType, BoatUpgradesPack.Part part, Status status)
	{
		BoatUpgradesPack.Upgrade next = module.nextRow(boatType, part.key);
		BoatUpgradesPack.Upgrade current = module.currentRow(boatType, part.key);
		String key = boatType + ":" + part.key;

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.setOpaque(false);
		top.setAlignmentX(LEFT_ALIGNMENT);

		Color color = status == Status.COMPLETE ? OsrsSkin.VALUE
			: status == Status.AVAILABLE ? ColorScheme.PROGRESS_INPROGRESS_COLOR
			: OsrsSkin.FAINT;
		// tier names are self-describing ("Teak hull", "Rope trawling net") —
		// a part prefix doubles one-tier facilities ("Range - Range") and
		// truncates the rest past 225px
		String text = status == Status.COMPLETE
			? (current == null ? part.name + " complete" : current.name)
			: next.name;
		OsrsLabel name = new OsrsLabel(text, color, OsrsSkin.font()).leftAligned().squeezable();
		name.setToolTipText(text);
		top.add(name);
		top.add(Box.createHorizontalGlue());
		if (status == Status.LOCKED)
		{
			top.add(new OsrsLabel("Sailing " + next.sailing,
				OsrsSkin.FAINT, OsrsSkin.smallFont()));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
		}
		if (next != null)
		{
			boolean isGoal = module.isGoal(next);
			top.add(goalGlyph(isGoal, isGoal
					? next.name + " - tracked; click to untrack"
					: "Track building " + next.name + " in Goals",
				() -> module.toggleGoal(next)));
			top.add(Box.createHorizontalStrut(UiTokens.PAD_TIGHT));
			top.add(wikiGlyph(next.page));
		}
		cap(top);
		row.add(top);

		if (key.equals(expanded))
		{
			addExpansion(row, boatType, part, next, current);
		}
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expanded = key.equals(expanded) ? null : key;
				SwingUtilities.invokeLater(SailingUpgradesTab.this::rebuild);
			}
		});
		cap(row);
		content.add(row);
	}

	private void addExpansion(JPanel row, int boatType, BoatUpgradesPack.Part part,
		BoatUpgradesPack.Upgrade next, BoatUpgradesPack.Upgrade current)
	{
		row.add(detail(part.benefit, OsrsSkin.MUTED));
		if (current != null)
		{
			row.add(detail("Built: " + current.name, OsrsSkin.VALUE));
		}
		if (next == null)
		{
			return;
		}
		row.add(detail("Sailing " + next.sailing + " · Construction "
			+ next.construction, OsrsSkin.MUTED));
		String missing = missingText(next.reqs);
		if (missing != null && boostMet(next.reqs))
		{
			row.add(detail("Needs a boost: " + boostDetail(next.reqs), OsrsSkin.VALUE));
		}
		else if (missing != null)
		{
			row.add(detail("Needs: " + missing, OsrsSkin.FAINT));
		}
		for (BoatUpgradesPack.Material m : next.materials)
		{
			row.add(materialRow(m));
		}
		// beyond-next tiers still level-gated: name the one after, faintly
		BoatUpgradesPack.Upgrade after = rowAfter(boatType, part.key, next.tier);
		if (after != null)
		{
			row.add(detail("Then: " + after.name + " (Sailing " + after.sailing + ")",
				OsrsSkin.FAINT));
		}
	}

	/** One material line: sprite, "qty x name", and the owned count — green
	 *  when the bank+carried stock covers it, red with the shortfall not. */
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
		// a short material's hover answers the next question: where from
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

	// ── requirement helpers (PohTab grammar) ──────────────────────────

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

	/** Every requirement met once usable temporary boosts are counted. */
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

	/** "Construction 78 boostable with Spicy stew, Crystal saw" per closed gap. */
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

	// ── shared bits ───────────────────────────────────────────────────

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

	/** The +/× goal affordance — its own control, never the row click
	 *  (JLabel so its listener wins over the row's; diaries grammar). */
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
