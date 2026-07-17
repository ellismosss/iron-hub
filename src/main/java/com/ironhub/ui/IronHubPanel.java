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
 * Root side panel, in the 2026-07-17 nav shape: the home view is ONE scroll
 * surface — the OSRS-skinned {@link HomePanel} with the open block's hub page
 * mounted INSIDE its stone frame, so header and content read as a single
 * connected block and scroll together; only the content beneath the stones
 * changes. Its scrollbar is hidden (wheel still scrolls): a visible bar
 * narrowed the content and misaligned it under the header (Luke's list).
 *
 * <p>The classic escape hatch keeps the old card shape: the Modules button
 * swaps to the module nav, and each module tab opens as its own card with a
 * fixed back header. Module tabs are singletons with ONE Swing parent — the
 * Dailies hub shows the same tabs the classic cards do, so every view MOUNTS
 * the tabs it needs when shown, adopting them from wherever they last lived.
 */
@Singleton
public class IronHubPanel extends PluginPanel
{
	private static final String CARD_HOME = "home";
	private static final String CARD_MODULES = "modules";

	/** Block name → the module tabs its hub page stacks, top to bottom. */
	private static final Map<String, List<String>> BLOCKS = Map.of(
		"Dailies", List.of("Dailies (New)", "Farming runs"));

	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final JPanel homeCard = new JPanel(new BorderLayout());
	private final Map<String, IronHubModule> modulesByName;
	private final Map<String, JPanel> moduleSlots = new HashMap<>();
	private final Map<String, Component> moduleWrappers = new HashMap<>();
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
		cardPanel.setBackground(UiTokens.PANEL_BG);
		cardPanel.add(homeCard, CARD_HOME);
		cardPanel.add(new ModuleNavPanel(this::showDashboard, this::openModule), CARD_MODULES);
		add(cardPanel, BorderLayout.CENTER);
		mountHome();
	}

	/** The home view: one bare scroll over the frame + whatever block is open. */
	private void mountHome()
	{
		if (home != null)
		{
			home.dispose();
		}
		home = new HomePanel(state, config.osrsTheme(), this::showModules, this::openBlock);
		homeCard.removeAll();
		homeCard.add(new HubScrollPane(home, false), BorderLayout.CENTER);
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
			cards.show(cardPanel, CARD_HOME);
		});
	}

	public void showDashboard()
	{
		home.clearSelection();
		openBlock(null);
		cards.show(cardPanel, CARD_HOME);
	}

	public void showModules()
	{
		cards.show(cardPanel, CARD_MODULES);
	}

	/**
	 * A nav block was selected (null = the open one was clicked shut): its
	 * hub page mounts inside the home's frame. Only blocks listed in BLOCKS
	 * have pages yet; the rest say so honestly.
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
		cards.show(cardPanel, CARD_HOME);
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

	/** The block's modules stacked, transparent so the frame connects them. */
	private JComponent hubPage(String name)
	{
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setOpaque(false);
		Map<String, JPanel> slots = new HashMap<>();
		for (String moduleName : BLOCKS.get(name))
		{
			JPanel slot = slot();
			slots.put(moduleName, slot);
			stack.add(slot);
			stack.add(Box.createVerticalStrut(UiTokens.PAD_SECTION));
		}
		hubSlots.put(name, slots);
		return stack;
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
