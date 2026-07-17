package com.ironhub.ui;

import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.ui.components.HubScrollPane;
import com.ironhub.ui.components.NavHeader;
import java.awt.BorderLayout;
import java.awt.CardLayout;
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
 * Root side panel, in the 2026-07-16 nav-rework shape: the OSRS-skinned
 * {@link HomePanel} is PERSISTENT at the top — it never leaves, so the player
 * can hop anywhere from anywhere (Luke's spec) — and everything else swaps in
 * the card area beneath it: a nav block's hub page, the classic module nav,
 * or a single module tab.
 *
 * <p>Module tabs are singletons owned by their modules, and a Swing component
 * has ONE parent — the Dailies hub shows the same tabs the classic module
 * cards do, so every view MOUNTS the tabs it needs when shown, adopting them
 * from wherever they last lived. Whoever is visible owns the tab.
 */
@Singleton
public class IronHubPanel extends PluginPanel
{
	private static final String CARD_DEFAULT = "default";
	private static final String CARD_MODULES = "modules";

	/** Block name → the module tabs its hub page stacks, top to bottom. */
	private static final Map<String, List<String>> BLOCKS = Map.of(
		"Dailies", List.of("Dailies (New)", "Farming runs"));

	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final Map<String, IronHubModule> modulesByName;
	private final Map<String, JPanel> moduleSlots = new HashMap<>();
	private final Map<String, Component> moduleWrappers = new HashMap<>();
	private final Map<String, Map<String, JPanel>> hubSlots = new HashMap<>();
	private final Map<String, Component> hubCards = new HashMap<>();
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

		cardPanel.setBackground(UiTokens.PANEL_BG);
		JPanel blank = new JPanel();
		blank.setBackground(UiTokens.PANEL_BG);
		cardPanel.add(blank, CARD_DEFAULT);
		cardPanel.add(new ModuleNavPanel(this::showDashboard, this::openModule), CARD_MODULES);
		add(cardPanel, BorderLayout.CENTER);
		mountHome();
	}

	/** The persistent OSRS-skinned home. Rebuilt on theme flips. */
	private void mountHome()
	{
		if (home != null)
		{
			remove(home);
			home.dispose();
		}
		home = new HomePanel(state, config.osrsTheme(), this::showModules, this::openBlock);
		add(home, BorderLayout.NORTH);
		revalidate();
		repaint();
	}

	/** The osrsTheme setting changed — re-clothe the home (EDT). */
	public void themeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			mountHome();
			cards.show(cardPanel, CARD_DEFAULT);
		});
	}

	public void showDashboard()
	{
		home.clearSelection();
		cards.show(cardPanel, CARD_DEFAULT);
	}

	public void showModules()
	{
		cards.show(cardPanel, CARD_MODULES);
	}

	/**
	 * A nav block was selected (null = the open one was clicked shut). Only
	 * blocks listed in BLOCKS have hub pages yet; the rest say so honestly.
	 */
	public void openBlock(String name)
	{
		if (name == null)
		{
			cards.show(cardPanel, CARD_DEFAULT);
			return;
		}
		String card = "block:" + name;
		if (!hubCards.containsKey(card))
		{
			Component hub = BLOCKS.containsKey(name) ? hubPage(name) : placeholder(name);
			hubCards.put(card, hub);
			cardPanel.add(hub, card);
		}
		mountHub(name);
		cards.show(cardPanel, card);
	}

	/** Open a module tab by nav name; rows without a live tab are inert. */
	public void openModule(String name)
	{
		IronHubModule module = modulesByName.get(name);
		if (module == null || !module.enabled())
		{
			return;
		}
		Component wrapper = moduleWrappers.get(name);
		if (wrapper == null)
		{
			if (module.buildTab() == null)
			{
				return;
			}
			JPanel slot = slot();
			moduleSlots.put(name, slot);
			wrapper = wrap(name, slot);
			moduleWrappers.put(name, wrapper);
			cardPanel.add(wrapper, "module:" + name);
		}
		mount(name, moduleSlots.get(name));
		cards.show(cardPanel, "module:" + name);
	}

	/** Drop a module's cached card (module was shut down / re-enabled). */
	public void invalidateModule(String name)
	{
		Component wrapper = moduleWrappers.remove(name);
		moduleSlots.remove(name);
		if (wrapper != null)
		{
			boolean showing = wrapper.isVisible();
			cardPanel.remove(wrapper);
			if (showing)
			{
				showDashboard();
			}
		}
	}

	// ── hub pages ─────────────────────────────────────────────────────

	/** The block's modules stacked in one scroll surface. */
	private Component hubPage(String name)
	{
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(UiTokens.PANEL_BG);
		Map<String, JPanel> slots = new HashMap<>();
		for (String moduleName : BLOCKS.get(name))
		{
			JPanel slot = slot();
			slots.put(moduleName, slot);
			stack.add(slot);
			stack.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}
		hubSlots.put(name, slots);
		return new HubScrollPane(stack);
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
		slot.setBackground(UiTokens.PANEL_BG);
		slot.setAlignmentX(Component.LEFT_ALIGNMENT);
		return slot;
	}

	/** An unwired block: say so, never fake a page. */
	private Component placeholder(String name)
	{
		JPanel page = new JPanel(new BorderLayout());
		page.setBackground(UiTokens.PANEL_BG);
		JLabel note = new JLabel(name + " is not built yet", javax.swing.SwingConstants.CENTER);
		note.setForeground(UiTokens.TEXT_MUTED);
		note.setFont(note.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		page.add(note, BorderLayout.NORTH);
		return page;
	}

	private JPanel wrap(String name, JComponent slot)
	{
		// back header fixed at the top; only the tab content scrolls
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(UiTokens.PANEL_BG);
		wrapper.add(new NavHeader(name, this::showModules), BorderLayout.NORTH);
		wrapper.add(new HubScrollPane(slot), BorderLayout.CENTER);
		return wrapper;
	}
}
