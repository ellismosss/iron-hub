package com.ironhub.modules.bankspace;

import com.ironhub.IronHubConfig;
import com.ironhub.data.BankStoragePack;
import com.ironhub.data.DataPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Bank space saver (Bank hub): everything in the bank that could live in
 * a dedicated storage instead — tackle box, seed vault, POH cases and
 * wardrobes, treasure chest and the rest — ported from the Wasted Bank
 * Space hub plugin (Riley McGee, BSD-2, pinned in gen_bank_storage.py).
 *
 * <p>Iron Hub already persists the bank snapshot, so the flag set derives
 * offline from the last bank visit — no open bank needed to read the
 * tab. Best-in-slot gear is not flagged by default (the reference's
 * default too: you keep bis gear banked on purpose); per-location
 * switches and a per-item ignore list persist profile-scoped. In the
 * open bank, flagged items glow amber and shift-right-click toggles
 * Flag/Unflag (the reference's menu, re-worded).</p>
 */
@Slf4j
@Singleton
public class BankSpaceModule implements IronHubModule
{
	private final AccountState state;
	private final IronHubConfig config;
	private final BankStoragePack pack;
	private final EventBus eventBus;             // null in unit tests
	private final Client client;                 // null in unit tests
	private final OverlayManager overlayManager; // null in unit tests
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private BankSpaceTab tab;
	private BankSpaceOverlay overlay;

	private Map<Integer, BankStoragePack.Entry> entryById;
	private Map<Integer, String> locationNameByItem;

	@Inject
	public BankSpaceModule(AccountState state, IronHubConfig config, DataPack dataPack,
		EventBus eventBus, Client client, OverlayManager overlayManager,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("bank-storage", BankStoragePack.class);
		this.eventBus = eventBus;
		this.client = client;
		this.overlayManager = overlayManager;
		this.itemManager = itemManager;
	}

	@Override
	public String name()
	{
		return "Bank space saver";
	}

	@Override
	public boolean enabled()
	{
		return config.bankSpaceSaver();
	}

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		if (overlayManager != null)
		{
			overlay = new BankSpaceOverlay(this::flaggedItems);
			overlayManager.add(overlay);
		}
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		if (overlayManager != null && overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		if (tab != null)
		{
			tab.dispose();
			tab = null;
		}
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new BankSpaceTab(state, this, config.osrsTheme(), itemManager);
		}
		return tab;
	}

	@Override
	public void onThemeChanged()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}

	BankStoragePack pack()
	{
		return pack;
	}

	// ── the derivation (reference algorithm over the persisted bank) ──

	/** A storage location's storable-in-bank slice. */
	public static final class LocationReport
	{
		public BankStoragePack.Location location;
		public boolean enabled;
		/** Flagged entries actually in the bank (ignored + hidden-bis excluded). */
		public List<BankStoragePack.Entry> storable = new ArrayList<>();
	}

	/**
	 * Every location with at least one bank item it could hold, largest
	 * first. Wasted Space = bank ∩ location items − ignored − (bis unless
	 * flagged) — the reference's algorithm over the persisted snapshot.
	 */
	List<LocationReport> reports()
	{
		if (pack == null)
		{
			return List.of();
		}
		Map<Integer, Integer> bank = state.getBankSnapshot();
		Set<String> off = state.getBankStorageOff();
		Set<Integer> ignored = state.getBankStorageIgnored();
		boolean flagBis = state.isBankStorageFlagBis();
		List<LocationReport> out = new ArrayList<>();
		for (BankStoragePack.Location location : pack.locations)
		{
			LocationReport report = new LocationReport();
			report.location = location;
			report.enabled = !off.contains(location.id);
			for (BankStoragePack.Entry entry : location.items)
			{
				if (bank.getOrDefault(entry.id, 0) > 0
					&& !ignored.contains(entry.id)
					&& (flagBis || !entry.bis))
				{
					report.storable.add(entry);
				}
			}
			if (!report.storable.isEmpty())
			{
				out.add(report);
			}
		}
		out.sort((a, b) -> Integer.compare(b.storable.size(), a.storable.size()));
		return out;
	}

	/** Distinct flagged item ids across ENABLED locations — the wasted
	 *  slot count, and the amber-glow set for the open bank. */
	Set<Integer> flaggedItems()
	{
		Set<Integer> out = new HashSet<>();
		for (LocationReport report : reports())
		{
			if (report.enabled)
			{
				for (BankStoragePack.Entry entry : report.storable)
				{
					out.add(entry.id);
				}
			}
		}
		return out;
	}

	/** Ignored pack items still sitting in the bank, name-sorted. */
	List<BankStoragePack.Entry> ignoredInBank()
	{
		Map<Integer, Integer> bank = state.getBankSnapshot();
		Set<Integer> ignored = state.getBankStorageIgnored();
		Map<Integer, BankStoragePack.Entry> out = new LinkedHashMap<>();
		for (BankStoragePack.Entry entry : entriesById().values())
		{
			if (ignored.contains(entry.id) && bank.getOrDefault(entry.id, 0) > 0)
			{
				out.putIfAbsent(entry.id, entry);
			}
		}
		List<BankStoragePack.Entry> list = new ArrayList<>(out.values());
		list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return list;
	}

	/** The (first) storage location a pack item belongs to, for labels. */
	String locationNameOf(int itemId)
	{
		if (locationNameByItem == null)
		{
			Map<Integer, String> m = new LinkedHashMap<>();
			if (pack != null)
			{
				for (BankStoragePack.Location location : pack.locations)
				{
					for (BankStoragePack.Entry entry : location.items)
					{
						m.putIfAbsent(entry.id, location.name);
					}
				}
			}
			locationNameByItem = m;
		}
		return locationNameByItem.get(itemId);
	}

	private Map<Integer, BankStoragePack.Entry> entriesById()
	{
		if (entryById == null)
		{
			Map<Integer, BankStoragePack.Entry> m = new LinkedHashMap<>();
			if (pack != null)
			{
				for (BankStoragePack.Location location : pack.locations)
				{
					for (BankStoragePack.Entry entry : location.items)
					{
						m.putIfAbsent(entry.id, entry);
					}
				}
			}
			entryById = m;
		}
		return entryById;
	}

	boolean isStorable(int itemId)
	{
		return entriesById().containsKey(itemId);
	}

	// ── the bank shift-right-click menu (reference port) ──────────────

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (client == null || !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}
		MenuEntry[] entries = event.getMenuEntries();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			Widget widget = entries[i].getWidget();
			if (widget == null
				|| WidgetUtil.componentToInterface(widget.getId()) != InterfaceID.BANKMAIN)
			{
				continue;
			}
			int itemId = widget.getItemId();
			if (isStorable(itemId))
			{
				boolean ignored = state.getBankStorageIgnored().contains(itemId);
				// the reference calls the deprecated Client.createMenuEntry;
				// the Menu interface is its modern home
				client.getMenu().createMenuEntry(i)
					.setOption(ignored ? "Flag as storable" : "Unflag storable")
					.setTarget(entries[i].getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> state.toggleBankStorageIgnored(itemId));
			}
			return; // first bank-widget entry decides, as in the reference
		}
	}
}
