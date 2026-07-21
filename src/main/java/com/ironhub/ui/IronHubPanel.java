package com.ironhub.ui;

import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.HubScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;

/**
 * Root side panel, in the 2026-07-17 nav shape: the home view is ONE scroll
 * surface — the OSRS-skinned {@link HomePanel} with the open block's hub page
 * mounted INSIDE its stone frame, so header and content read as a single
 * connected block and scroll together; only the content beneath the stones
 * changes. Its scrollbar is hidden (wheel still scrolls): a visible bar
 * narrowed the content and misaligned it under the header (Luke's list).
 *
 * <p>All six blocks are wired (Luke, 2026-07-17), so the nav stones are the
 * whole navigation — the Modules button and the classic per-module cards are
 * gone. Module tabs are singletons with ONE Swing parent, so every hub page
 * MOUNTS the tabs it needs when shown, adopting them from wherever they last
 * lived (a theme swap rebuilds the pages and re-adopts). Hub sections are
 * collapsible and EXCLUSIVE (Luke, same day): only one module's tab is built
 * and mounted at a time — a seven-module hub built in one click was a
 * resource spike the dev client felt.
 */
@Singleton
public class IronHubPanel extends PluginPanel
{
	/**
	 * Block name → the module tabs its hub page stacks, top to bottom.
	 * Luke's groupings, plus the homes the classic nav's removal forced:
	 * Loot & supplies rides with Combat, the Design lab under Settings.
	 * The classic Dailies tab left the nav — its module keeps the brain
	 * (detection, runs, overlays) that Dailies (New) renders.
	 */
	/** Modules pinned ALWAYS-OPEN in their hub (Luke, 2026-07-21: the gear &
	 *  combat view IS the combat hub's face — never collapsible). Exclusive
	 *  expansion still governs the hub's OTHER modules. */
	private static final java.util.Set<String> PINNED_MODULES = java.util.Set.of("Gear & Combat");

	private static final Map<String, List<String>> BLOCKS = Map.of(
		"Goals", List.of("Goals"),
		"Gear & Combat", List.of("Gear & Combat", "Slayer", "Loot & supplies"),
		"Dailies", List.of("Dailies", "Farm runs", "Hunters' Rumours", "Port tasks"),
		"Progression", List.of("Collection log", "Combat achievements", "Gear progression",
			"PoH", "Sailing upgrades", "Achievement diaries", "Quests", "Clues & STASH",
			"QoL checklist"),
		"Bank", List.of("Bank & banked XP", "Bank space saver", "Money making", "Supplies runway",
			"Death recovery"),
		"Settings", List.of("Design lab"));

	private final JPanel homeCard = new JPanel(new BorderLayout());
	private final Map<String, IronHubModule> modulesByName;
	private final Map<String, Map<String, JPanel>> hubSlots = new HashMap<>();
	private final Map<String, Map<String, JLabel>> hubTriangles = new HashMap<>();
	private final Map<String, JComponent> hubPages = new HashMap<>();
	/**
	 * Hub name → the ONE module whose tab is built and shown (Luke,
	 * 2026-07-17: sections are collapsible and EXCLUSIVE — building a
	 * seven-tab hub page in one click was a resource spike, and only one
	 * module is read at a time anyway). Null = all collapsed. Survives
	 * theme swaps so the re-adopted page reopens where the player was.
	 */
	private final Map<String, String> expandedModules = new HashMap<>();
	private final AccountState state;
	private final com.ironhub.IronHubConfig config;
	private HomePanel home;

	@Inject
	public IronHubPanel(Set<IronHubModule> modules, AccountState state,
		com.ironhub.IronHubConfig config)
	{
		super(false);
		this.state = state;
		this.config = config;
		modulesByName = modules.stream()
			.collect(Collectors.toMap(IronHubModule::name, Function.identity()));

		setLayout(new BorderLayout());
		setBackground(UiTokens.PANEL_BG);
		homeCard.setBackground(UiTokens.PANEL_BG);
		add(homeCard, BorderLayout.CENTER);
		mountHome();
	}

	/** Every block's contents — the lifecycle test proves each name resolves. */
	public static Map<String, List<String>> blockContents()
	{
		return BLOCKS;
	}

	/** The home view: one bare scroll over the frame + whatever block is open. */
	private void mountHome()
	{
		if (home != null)
		{
			home.dispose();
		}
		IronHubModule goalModule = modulesByName.get("Goals");
		home = new HomePanel(state,
			goalModule instanceof com.ironhub.modules.goals.GoalPlannerModule
				? (com.ironhub.modules.goals.GoalPlannerModule) goalModule : null,
			config.osrsTheme(), this::openBlock);
		homeCard.removeAll();
		HubScrollPane pane = new HubScrollPane(home, false);
		// the home scrolls on the THEME backing, not the classic grey — the
		// hub content below the stone frame sits directly on it
		java.awt.Color backing = config.osrsTheme().background;
		homeCard.setBackground(backing);
		pane.setBackground(backing);
		pane.getViewport().setBackground(backing);
		((JComponent) pane.getViewport().getView()).setBackground(backing);
		homeCard.add(pane, BorderLayout.CENTER);
		homeCard.revalidate();
		homeCard.repaint();
	}

	/** The osrsTheme setting changed — re-clothe the home (EDT). */
	public void themeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			hubPages.clear();
			hubSlots.clear();
			hubTriangles.clear();
			// expandedModules survives: the rebuilt page reopens where the
			// player was
			mountHome();
		});
	}

	/**
	 * A nav block was selected (null = the open one was clicked shut): its
	 * hub page mounts inside the home's frame. Only blocks listed in BLOCKS
	 * have pages; anything else says so honestly.
	 */
	public void openBlock(String name)
	{
		JPanel slot = home.contentSlot();
		slot.removeAll();
		if (name != null)
		{
			JComponent page = hubPages.computeIfAbsent(name,
				key -> BLOCKS.containsKey(key) ? hubPage(key) : placeholder(key));
			refreshHub(name);
			slot.add(page, BorderLayout.CENTER);
		}
		slot.revalidate();
		slot.repaint();
	}

	/** A module toggled on or off: any hub slot SHOWING its tab re-mounts
	 *  (collapsed slots are empty and stay that way until expanded). */
	public void invalidateModule(String name)
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			for (Map.Entry<String, Map<String, JPanel>> hub : hubSlots.entrySet())
			{
				JPanel slot = hub.getValue().get(name);
				if (slot != null && name.equals(expandedModules.get(hub.getKey())))
				{
					mount(name, slot);
				}
			}
		});
	}

	/**
	 * Expand one module in a hub (collapsing whichever was open — the
	 * sections are exclusive), or collapse it if it was the open one.
	 * The header plates route here; also the test seam.
	 */
	public void toggleModule(String hub, String module)
	{
		String open = expandedModules.get(hub);
		expandedModules.put(hub, module.equals(open) ? null : module);
		refreshHub(hub);
	}

	// ── hub pages ─────────────────────────────────────────────────────

	/** The block's modules stacked, transparent so the frame connects them.
	 *  Only the expanded module's slot holds a tab; the first module opens
	 *  by default so a hub never lands empty. */
	private JComponent hubPage(String name)
	{
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setOpaque(false);
		Map<String, JPanel> slots = new HashMap<>();
		Map<String, JLabel> triangles = new HashMap<>();
		for (String moduleName : BLOCKS.get(name))
		{
			stack.add(moduleHeader(name, moduleName, triangles));
			JPanel slot = slot();
			slots.put(moduleName, slot);
			stack.add(slot);
			stack.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}
		hubSlots.put(name, slots);
		hubTriangles.put(name, triangles);
		expandedModules.putIfAbsent(name, BLOCKS.get(name).get(0));
		return stack;
	}

	/**
	 * A module's name plate in a hub page — the Design lab's notched-box
	 * button look, now a COLLAPSE TOGGLE (Luke, 2026-07-17: exclusive
	 * sections, one module shown at a time): triangle affordance on the
	 * left, press anywhere to expand/collapse. Title in the bolder game
	 * font (the "Assumptions" grammar) — with the stone frame ending above
	 * the plates, they carry the section hierarchy alone.
	 */
	private JComponent moduleHeader(String hubName, String name, Map<String, JLabel> triangles)
	{
		com.ironhub.ui.osrs.OsrsTheme theme = config.osrsTheme();
		// a hub with one module never collapses — it's the whole section
		// (Luke: Goals is the hub's only module) — and pinned modules stay
		// open regardless of what else the hub shows
		boolean collapsible = BLOCKS.get(hubName).size() > 1
			&& !PINNED_MODULES.contains(name);
		com.ironhub.ui.osrs.StonePanel plate = new com.ironhub.ui.osrs.StonePanel(theme);
		plate.setLayout(new BoxLayout(plate, BoxLayout.X_AXIS));
		if (collapsible)
		{
			JLabel triangle = new JLabel(new com.ironhub.ui.components.PaintedIcon(
				com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
			triangle.setForeground(com.ironhub.ui.osrs.OsrsSkin.MUTED);
			triangles.put(name, triangle);
			plate.add(triangle);
		}
		plate.add(Box.createHorizontalGlue());
		plate.add(new com.ironhub.ui.osrs.OsrsLabel(name,
			com.ironhub.ui.osrs.OsrsSkin.TITLE, com.ironhub.ui.osrs.OsrsSkin.boldFont()));
		plate.add(Box.createHorizontalGlue());
		if (collapsible)
		{
			// mirror the triangle's width so the title stays optically centred
			plate.add(Box.createHorizontalStrut(10));
			plate.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			plate.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					toggleModule(hubName, name);
				}
			});
		}

		JPanel pad = new JPanel(new BorderLayout());
		pad.setOpaque(false);
		pad.setAlignmentX(Component.LEFT_ALIGNMENT);
		pad.setBorder(new javax.swing.border.EmptyBorder(0, 4, 3, 4));
		pad.add(plate, BorderLayout.CENTER);
		pad.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
			pad.getPreferredSize().height));
		return pad;
	}

	/** Show the hub's expanded module (adopting its singleton tab), keep
	 *  the rest collapsed-empty, and point every triangle the right way. */
	private void refreshHub(String name)
	{
		Map<String, JPanel> slots = hubSlots.get(name);
		if (slots == null)
		{
			return;
		}
		String open = expandedModules.get(name);
		Map<String, JLabel> triangles = hubTriangles.get(name);
		for (Map.Entry<String, JPanel> entry : slots.entrySet())
		{
			boolean expanded = entry.getKey().equals(open)
				|| PINNED_MODULES.contains(entry.getKey());
			JLabel triangle = triangles == null ? null : triangles.get(entry.getKey());
			if (triangle != null)
			{
				triangle.setIcon(new com.ironhub.ui.components.PaintedIcon(
					expanded ? com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_DOWN
						: com.ironhub.ui.components.PaintedIcon.Shape.TRIANGLE_RIGHT, 10));
			}
			if (expanded)
			{
				mount(entry.getKey(), entry.getValue());
			}
			else if (entry.getValue().getComponentCount() > 0)
			{
				entry.getValue().removeAll();
				entry.getValue().revalidate();
				entry.getValue().repaint();
			}
		}
	}

	/** Put a module's singleton tab into the given slot (Swing re-parents). */
	private void mount(String moduleName, JPanel slot)
	{
		IronHubModule module = modulesByName.get(moduleName);
		JComponent tab = module != null && module.enabled() ? module.buildTab() : null;
		if (tab == null)
		{
			slot.removeAll();
			JLabel off = new JLabel("Enable the " + moduleName + " module", javax.swing.SwingConstants.CENTER);
			off.setForeground(UiTokens.TEXT_MUTED);
			off.setFont(off.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
			slot.add(off);
		}
		else if (tab.getParent() != slot)
		{
			slot.removeAll();
			slot.add(tab);
		}
		slot.revalidate();
		slot.repaint();
	}

	private JPanel slot()
	{
		JPanel slot = new JPanel(new BorderLayout());
		slot.setOpaque(false);
		slot.setAlignmentX(Component.LEFT_ALIGNMENT);
		return slot;
	}

	/** An unwired block: say so, never fake a page. */
	private JComponent placeholder(String name)
	{
		JPanel page = new JPanel(new BorderLayout());
		page.setOpaque(false);
		page.setBorder(new javax.swing.border.EmptyBorder(4, 4, 8, 4));
		com.ironhub.ui.osrs.OsrsLabel note = new com.ironhub.ui.osrs.OsrsLabel(
			name + " is not built yet", com.ironhub.ui.osrs.OsrsSkin.MUTED,
			com.ironhub.ui.osrs.OsrsSkin.font());
		page.add(note, BorderLayout.NORTH);
		return page;
	}
}
