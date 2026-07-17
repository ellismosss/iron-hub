package com.ironhub.ui;

import com.ironhub.data.DataPack;
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
 * lived (a theme swap rebuilds the pages and re-adopts).
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
	private static final Map<String, List<String>> BLOCKS = Map.of(
		"Goals", List.of("Goal planner"),
		"Combat", List.of("Loadout Lab", "Slayer", "Loot & supplies"),
		"Dailies", List.of("Dailies (New)", "Farming runs"),
		"Progression", List.of("Collection log", "Combat achievements", "Gear progression",
			"Achievement diaries", "Quests", "Clues & STASH", "QoL checklist"),
		"Bank", List.of("Bank & banked XP", "Supplies runway", "Death recovery"),
		"Settings", List.of("Design lab"));

	private final JPanel homeCard = new JPanel(new BorderLayout());
	private final Map<String, IronHubModule> modulesByName;
	private final Map<String, Map<String, JPanel>> hubSlots = new HashMap<>();
	private final Map<String, JComponent> hubPages = new HashMap<>();
	private final AccountState state;
	private final com.ironhub.IronHubConfig config;
	private HomePanel home;

	@Inject
	public IronHubPanel(Set<IronHubModule> modules, AccountState state, DataPack dataPack,
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
		home = new HomePanel(state, config.osrsTheme(), this::openBlock);
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
			mountHub(name);
			slot.add(page, BorderLayout.CENTER);
		}
		slot.revalidate();
		slot.repaint();
	}

	/** A module toggled on or off: any hub slot carrying its tab re-mounts. */
	public void invalidateModule(String name)
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			for (Map<String, JPanel> slots : hubSlots.values())
			{
				JPanel slot = slots.get(name);
				if (slot != null)
				{
					mount(name, slot);
				}
			}
		});
	}

	// ── hub pages ─────────────────────────────────────────────────────

	/** The block's modules stacked, transparent so the frame connects them. */
	private JComponent hubPage(String name)
	{
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setOpaque(false);
		Map<String, JPanel> slots = new HashMap<>();
		for (String moduleName : BLOCKS.get(name))
		{
			stack.add(moduleHeader(moduleName));
			JPanel slot = slot();
			slots.put(moduleName, slot);
			stack.add(slot);
			stack.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}
		hubSlots.put(name, slots);
		return stack;
	}

	/**
	 * A module's name plate in a hub page — the Design lab's notched-box
	 * button look (Luke, 2026-07-17), display-only: no hover fill, no hand
	 * cursor, because the tab beneath IS the content. Title in the bolder
	 * game font (the "Assumptions" grammar) — with the stone frame ending
	 * above the plates, they carry the section hierarchy alone.
	 */
	private JComponent moduleHeader(String name)
	{
		com.ironhub.ui.osrs.OsrsTheme theme = config.osrsTheme();
		com.ironhub.ui.osrs.StonePanel plate = new com.ironhub.ui.osrs.StonePanel(theme);
		plate.setLayout(new BoxLayout(plate, BoxLayout.X_AXIS));
		plate.add(Box.createHorizontalGlue());
		plate.add(new com.ironhub.ui.osrs.OsrsLabel(name,
			com.ironhub.ui.osrs.OsrsSkin.TITLE, com.ironhub.ui.osrs.OsrsSkin.boldFont()));
		plate.add(Box.createHorizontalGlue());

		JPanel pad = new JPanel(new BorderLayout());
		pad.setOpaque(false);
		pad.setAlignmentX(Component.LEFT_ALIGNMENT);
		pad.setBorder(new javax.swing.border.EmptyBorder(0, 4, 3, 4));
		pad.add(plate, BorderLayout.CENTER);
		pad.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
			pad.getPreferredSize().height));
		return pad;
	}

	/** Adopt every tab this hub shows — from wherever each last lived. */
	private void mountHub(String name)
	{
		Map<String, JPanel> slots = hubSlots.get(name);
		if (slots == null)
		{
			return;
		}
		for (Map.Entry<String, JPanel> entry : slots.entrySet())
		{
			mount(entry.getKey(), entry.getValue());
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
