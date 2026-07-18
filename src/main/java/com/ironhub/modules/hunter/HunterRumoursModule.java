package com.ironhub.modules.hunter;

import com.ironhub.IronHubConfig;
import com.ironhub.data.DataPack;
import com.ironhub.data.HunterRumoursPack;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import com.ironhub.state.PersistedState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Hunters' Rumours (Dailies hub, 2026-07-18): the current rumour treated
 * like a slayer task — target, trap, pity progress, hunting locations
 * with a preferred choice + Shortest Path routing, and a record history.
 *
 * <p>Detection ports the Hunter Rumours plugin (BSD-2, geel9): the rumour
 * is read from the burrows Hunter dialog and the Quetzal whistle's "your
 * current rumour target is …" chat; catches are counted from Hunter xp
 * drops that match the target creature's possible values; the rare-piece
 * message marks the pity milestone; a completion dialog clears it. State
 * lives in Iron Hub's own persistence (never the reference plugin's
 * config group).</p>
 */
@Slf4j
@Singleton
public class HunterRumoursModule implements IronHubModule
{
	private static final String WHISTLE_PREFIX = "your current rumour target is";
	private static final String PIECE_MESSAGE =
		"you find a rare piece of the creature! you should take it back to the hunter guild.";
	// burrows region (the Hunter Guild rumour area), plane 0
	private static final int BURROWS_X1 = 1549;
	private static final int BURROWS_X2 = 1565;
	private static final int BURROWS_Y1 = 9449;
	private static final int BURROWS_Y2 = 9464;
	static final java.awt.Color HIGHLIGHT = java.awt.Color.decode("#DDFF00");

	private final AccountState state;
	private final IronHubConfig config;
	private final HunterRumoursPack pack;
	private final Client client;             // null in unit tests
	private final ClientThread clientThread; // null in unit tests
	private final EventBus eventBus;         // null in unit tests
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private final com.ironhub.integrations.ShortestPathBridge pathBridge; // null in unit tests
	private final net.runelite.client.ui.overlay.OverlayManager overlayManager; // null in unit tests
	private final net.runelite.client.game.npcoverlay.NpcOverlayService npcOverlayService; // null in unit tests
	private final net.runelite.client.ui.overlay.worldmap.WorldMapPointManager worldMapPointManager; // null in unit tests
	private final com.ironhub.modules.farming.FarmBankLayout bankLayout; // null-service tolerant

	private HunterRumoursOverlay overlay;
	private final java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> mapPoints = new ArrayList<>();
	private final java.util.function.Function<NPC, net.runelite.client.game.npcoverlay.HighlightedNpc> highlighter;
	/** "Show in bank" armed from the tab; bank opens lay the setup out. */
	private volatile boolean bankShow;
	private boolean fairyRingPanelOpen;
	private boolean fairyRingScrolled;

	private HunterRumoursTab tab;
	private final List<PersistedState.RumourRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
	private boolean recordsLoaded;
	private int lastHunterXp = -1;

	@Inject
	public HunterRumoursModule(AccountState state, IronHubConfig config, DataPack dataPack,
		Client client, ClientThread clientThread, EventBus eventBus,
		net.runelite.client.game.ItemManager itemManager,
		com.ironhub.integrations.ShortestPathBridge pathBridge,
		net.runelite.client.ui.overlay.OverlayManager overlayManager,
		net.runelite.client.game.npcoverlay.NpcOverlayService npcOverlayService,
		net.runelite.client.ui.overlay.worldmap.WorldMapPointManager worldMapPointManager,
		net.runelite.client.plugins.banktags.BankTagsService bankTagsService,
		net.runelite.client.plugins.banktags.TagManager tagManager,
		net.runelite.client.plugins.banktags.tabs.LayoutManager layoutManager)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("hunter-rumours", HunterRumoursPack.class);
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.itemManager = itemManager;
		this.pathBridge = pathBridge;
		this.overlayManager = overlayManager;
		this.npcOverlayService = npcOverlayService;
		this.worldMapPointManager = worldMapPointManager;
		this.bankLayout = new com.ironhub.modules.farming.FarmBankLayout(
			bankTagsService, tagManager, layoutManager, itemManager);
		this.highlighter = npc ->
		{
			HunterRumoursPack.Rumour current = currentRumour();
			return config.hunterHighlight() && current != null && current.npcId > 0
				&& npc.getId() == current.npcId
				? net.runelite.client.game.npcoverlay.HighlightedNpc.builder()
					.npc(npc).highlightColor(HIGHLIGHT).outline(true)
					.render(n -> !n.isDead()).build()
				: null;
		};
	}

	@Override
	public String name()
	{
		return "Hunters' Rumours";
	}

	@Override
	public boolean enabled()
	{
		return config.hunterRumours();
	}

	@Override
	public void startUp()
	{
		if (eventBus != null)
		{
			eventBus.register(this);
		}
		if (npcOverlayService != null)
		{
			npcOverlayService.registerHighlighter(highlighter);
		}
		if (overlayManager != null)
		{
			overlay = new HunterRumoursOverlay(this, config, client);
			overlayManager.add(overlay);
		}
		refreshMapPoints();
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
		}
		if (npcOverlayService != null)
		{
			npcOverlayService.unregisterHighlighter(highlighter);
			npcOverlayService.rebuild();
		}
		if (overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		clearMapPoints();
		if (clientThread != null)
		{
			clientThread.invoke(bankLayout::clear);
		}
		bankShow = false;
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
			tab = new HunterRumoursTab(state, this, config.osrsTheme(), itemManager);
		}
		return tab;
	}

	@Override
	public void onThemeChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (tab != null)
			{
				tab.dispose();
				tab = null;
			}
		});
	}

	HunterRumoursPack pack()
	{
		return pack;
	}

	net.runelite.client.game.ItemManager itemManager()
	{
		return itemManager;
	}

	// ── record state ──────────────────────────────────────────────────

	private void ensureLoaded()
	{
		if (!recordsLoaded)
		{
			recordsLoaded = true;
			records.clear();
			records.addAll(state.getRumourRecords());
		}
	}

	List<PersistedState.RumourRecord> records()
	{
		ensureLoaded();
		return records;
	}

	PersistedState.RumourRecord active()
	{
		ensureLoaded();
		if (!records.isEmpty())
		{
			PersistedState.RumourRecord last = records.get(records.size() - 1);
			if (last.end == 0)
			{
				return last;
			}
		}
		return null;
	}

	HunterRumoursPack.Rumour currentRumour()
	{
		PersistedState.RumourRecord active = active();
		return active == null || pack == null ? null : pack.rumour(active.rumourId);
	}

	/** Owned Guild hunter outfit pieces (0–4) across bank + carried. */
	int outfitPieces()
	{
		if (pack == null || pack.outfit == null)
		{
			return 0;
		}
		int count = 0;
		for (Integer id : pack.outfit)
		{
			if (state.canonicalStock(id) > 0)
			{
				count++;
			}
		}
		return count;
	}

	// ── detection (Hunter Rumours parity) ─────────────────────────────

	private boolean inBurrows()
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return false;
		}
		WorldPoint at = client.getLocalPlayer().getWorldLocation();
		return at.getPlane() == 0
			&& at.getX() >= BURROWS_X1 && at.getX() <= BURROWS_X2
			&& at.getY() >= BURROWS_Y1 && at.getY() <= BURROWS_Y2;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (pack == null)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		String lower = Text.standardize(message).toLowerCase(Locale.ROOT);

		// the rare-piece message: pity milestone reached on the current rumour
		if (event.getType() == ChatMessageType.GAMEMESSAGE && lower.equals(PIECE_MESSAGE))
		{
			PersistedState.RumourRecord active = active();
			if (active != null && !active.pieceFound)
			{
				active.pieceFound = true;
				pushRecords();
			}
			return;
		}

		// the Quetzal whistle "your current rumour target is …" sync
		if (event.getType() == ChatMessageType.GAMEMESSAGE && lower.contains(WHISTLE_PREFIX))
		{
			HunterRumoursPack.Rumour rumour = pack.rumourReferenced(lower);
			HunterRumoursPack.Hunter hunter = pack.hunterReferenced(lower);
			if (rumour != null)
			{
				assignRumour(rumour, hunter);
			}
			return;
		}

		// burrows Hunter dialog (assignment / completion)
		if (event.getType() == ChatMessageType.DIALOG && inBurrows())
		{
			handleBurrowsDialog(message, lower);
		}
	}

	private void handleBurrowsDialog(String message, String lower)
	{
		int bar = message.indexOf('|');
		String speaker = bar > 0 ? message.substring(0, bar) : "";
		HunterRumoursPack.Hunter speakingHunter = pack.hunterByNpcName(speaker);
		if (speakingHunter == null)
		{
			return; // not a Guild Hunter talking
		}
		String body = bar > 0 ? lower.substring(lower.indexOf('|') + 1) : lower;

		// completion / reward dialog: clear the active rumour
		if (body.contains("would you like another rumour?")
			|| body.contains("here's your reward.")
			|| body.contains("another one done?"))
		{
			PersistedState.RumourRecord active = active();
			if (active != null)
			{
				active.end = System.currentTimeMillis();
				pushRecords();
				rumourChanged();
			}
			return;
		}
		// the At First Light intro line that mimics an assignment — ignore
		if (body.contains("stopped off for a bit of hunting first"))
		{
			return;
		}
		HunterRumoursPack.Rumour rumour = pack.rumourReferenced(body);
		HunterRumoursPack.Hunter referenced = pack.hunterReferenced(body);
		if (rumour != null)
		{
			assignRumour(rumour, referenced != null ? referenced : speakingHunter);
		}
	}

	/** Adopt a newly-detected rumour (opens a record if it changed). */
	void assignRumour(HunterRumoursPack.Rumour rumour, HunterRumoursPack.Hunter hunter)
	{
		ensureLoaded();
		PersistedState.RumourRecord active = active();
		if (active != null && active.rumourId.equals(rumour.id))
		{
			if (hunter != null && active.hunterId.isEmpty())
			{
				active.hunterId = hunter.id;
				pushRecords();
			}
			return;
		}
		if (active != null)
		{
			active.end = System.currentTimeMillis();
		}
		PersistedState.RumourRecord record = new PersistedState.RumourRecord();
		record.rumourId = rumour.id;
		record.hunterId = hunter == null ? "" : hunter.id;
		record.start = System.currentTimeMillis();
		records.add(record);
		lastHunterXp = client == null ? -1 : client.getSkillExperience(Skill.HUNTER);
		pushRecords();
		rumourChanged();
		if (tab != null)
		{
			SwingUtilities.invokeLater(tab::rebuild);
		}
	}

	/** Rebuild the in-game surfaces that follow the current rumour. */
	private void rumourChanged()
	{
		if (npcOverlayService != null)
		{
			npcOverlayService.rebuild();
		}
		refreshMapPoints();
		fairyRingScrolled = false;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.HUNTER)
		{
			return;
		}
		int xp = event.getXp();
		if (lastHunterXp < 0)
		{
			lastHunterXp = xp;
			return;
		}
		int gained = xp - lastHunterXp;
		lastHunterXp = xp;
		HunterRumoursPack.Rumour rumour = currentRumour();
		PersistedState.RumourRecord active = active();
		if (rumour != null && active != null && !active.pieceFound && rumour.matchesCatchXp(gained))
		{
			active.caught++;
			pushRecords();
			if (tab != null)
			{
				SwingUtilities.invokeLater(tab::rebuild);
			}
		}
	}

	private void pushRecords()
	{
		state.setRumourRecords(records);
	}

	// ── locations + routing ───────────────────────────────────────────

	/** Preferred location for the current target, or the first pack area. */
	HunterRumoursPack.Location preferredLocation(HunterRumoursPack.Rumour rumour)
	{
		List<HunterRumoursPack.Location> areas = pack.locationsFor(rumour.creature);
		if (areas.isEmpty())
		{
			return null;
		}
		String pref = state.getRumourPrefLocation(rumour.creature);
		if (pref != null)
		{
			for (HunterRumoursPack.Location area : areas)
			{
				if (area.name.equals(pref))
				{
					return area;
				}
			}
		}
		return areas.get(0);
	}

	void setPreferredLocation(String creature, String location)
	{
		state.setRumourPrefLocation(creature, location);
	}

	void route(WorldPoint point)
	{
		if (pathBridge != null && point != null)
		{
			pathBridge.pathTo(point);
		}
	}

	// ── world map points ──────────────────────────────────────────────

	/** One pin per hunting area of the current target (client thread). */
	void refreshMapPoints()
	{
		if (worldMapPointManager == null || clientThread == null)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			clearMapPoints();
			HunterRumoursPack.Rumour rumour = currentRumour();
			if (rumour == null || !config.hunterNavAids())
			{
				return;
			}
			for (HunterRumoursPack.Location area : pack.locationsFor(rumour.creature))
			{
				net.runelite.client.ui.overlay.worldmap.WorldMapPoint point =
					new net.runelite.client.ui.overlay.worldmap.WorldMapPoint(
						area.worldPoint(), mapMarker());
				point.setName(rumour.name + " — " + area.name);
				point.setTooltip(rumour.name + " — " + area.name
					+ (area.fairyRing != null ? " (" + area.fairyRing + ")" : ""));
				point.setSnapToEdge(true);
				point.setJumpOnClick(true);
				worldMapPointManager.add(point);
				mapPoints.add(point);
			}
		});
	}

	private void clearMapPoints()
	{
		if (worldMapPointManager != null)
		{
			for (net.runelite.client.ui.overlay.worldmap.WorldMapPoint point : mapPoints)
			{
				worldMapPointManager.remove(point);
			}
		}
		mapPoints.clear();
	}

	private static java.awt.image.BufferedImage mapMarker()
	{
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
			16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setColor(HIGHLIGHT);
		g.fillOval(2, 2, 12, 12);
		g.setColor(HIGHLIGHT.darker().darker());
		g.drawOval(2, 2, 12, 12);
		g.dispose();
		return image;
	}

	// ── fairy ring travel log highlight (Hunter Rumours port) ─────────

	/** The preferred area's fairy code for the current rumour, or null. */
	String fairyRingCode()
	{
		HunterRumoursPack.Rumour rumour = currentRumour();
		if (rumour == null)
		{
			return null;
		}
		HunterRumoursPack.Location preferred = preferredLocation(rumour);
		return preferred == null ? null : preferred.fairyRing;
	}

	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.FAIRYRINGS)
		{
			fairyRingPanelOpen = true;
			fairyRingScrolled = false;
		}
	}

	@Subscribe
	public void onWidgetClosed(net.runelite.api.events.WidgetClosed event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.FAIRYRINGS)
		{
			fairyRingPanelOpen = false;
		}
	}

	/** The game re-renders the travel log continuously, so the highlight
	 *  re-asserts each client tick while the panel is open (reference
	 *  plugin behaviour); the scroll fires once per panel lifetime. */
	@Subscribe
	public void onPostClientTick(net.runelite.api.events.PostClientTick event)
	{
		if (!fairyRingPanelOpen || client == null || !config.hunterNavAids())
		{
			return;
		}
		String code = fairyRingCode();
		if (code == null || code.length() != 3)
		{
			return;
		}
		Widget contents = client.getWidget(
			net.runelite.api.gameval.InterfaceID.FairyringsLog.CONTENTS);
		if (contents == null || contents.isHidden())
		{
			return;
		}
		java.util.List<Widget> candidates = new ArrayList<>();
		if (contents.getDynamicChildren() != null)
		{
			candidates.addAll(java.util.Arrays.asList(contents.getDynamicChildren()));
		}
		Widget favorites = client.getWidget(
			net.runelite.api.widgets.ComponentID.FAIRY_RING_PANEL_FAVORITES);
		if (favorites != null && favorites.getStaticChildren() != null)
		{
			candidates.addAll(java.util.Arrays.asList(favorites.getStaticChildren()));
		}
		for (Widget widget : candidates)
		{
			String text = widget.getText();
			if (text == null || !text.replace(" ", "").contains(code)
				|| text.contains("(Rumour)"))
			{
				continue;
			}
			widget.setTextColor(0x00FF00);
			widget.setText("(Rumour) " + text);
			if (!fairyRingScrolled)
			{
				fairyRingScrolled = true;
				client.runScript(net.runelite.api.ScriptID.UPDATE_SCROLLBAR,
					net.runelite.api.gameval.InterfaceID.FairyringsLog.SCROLLBAR,
					net.runelite.api.gameval.InterfaceID.FairyringsLog.CONTENTS,
					widget.getRelativeY());
			}
			return;
		}
	}

	// ── gear setups (slayer parity: the Loadout key space) ────────────

	/** The setup key for a trap type — rumours sharing a trap share gear. */
	static String setupKey(HunterRumoursPack.Rumour rumour)
	{
		return "Hunter: " + rumour.trap;
	}

	PersistedState.SavedSetup rumourSetup()
	{
		HunterRumoursPack.Rumour rumour = currentRumour();
		return rumour == null ? null : state.savedSetup(setupKey(rumour));
	}

	void saveRumourSetup()
	{
		HunterRumoursPack.Rumour rumour = currentRumour();
		if (rumour != null)
		{
			state.saveSetup(setupKey(rumour), state.captureSetup());
		}
	}

	boolean bankShowArmed()
	{
		return bankShow;
	}

	void setBankShow(boolean show)
	{
		bankShow = show;
		if (!show && clientThread != null)
		{
			clientThread.invoke(bankLayout::clear);
		}
	}

	@Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_INIT || !bankShow)
		{
			return;
		}
		PersistedState.SavedSetup setup = rumourSetup();
		HunterRumoursPack.Rumour rumour = currentRumour();
		if (setup == null || rumour == null || clientThread == null)
		{
			return;
		}
		// never open a bank tag during the bank's own build script — defer
		clientThread.invokeLater(() -> bankLayout.apply(setupKey(rumour), setup));
	}

	@Subscribe
	public void onScriptPostFired(net.runelite.api.events.ScriptPostFired event)
	{
		if (event.getScriptId() != net.runelite.api.ScriptID.BANKMAIN_FINISHBUILDING
			|| client == null || !bankLayout.isApplied())
		{
			return;
		}
		Widget title = client.getWidget(net.runelite.api.gameval.InterfaceID.Bankmain.TITLE);
		HunterRumoursPack.Rumour rumour = currentRumour();
		if (title != null && rumour != null)
		{
			title.setText("<col=ff981f>" + rumour.trap + "</col> — rumour setup");
		}
	}

	// ── overlay reads ─────────────────────────────────────────────────

	/** True while the player stands within ~30 tiles of the preferred area. */
	boolean nearPreferredLocation()
	{
		HunterRumoursPack.Rumour rumour = currentRumour();
		if (rumour == null || client == null || client.getLocalPlayer() == null)
		{
			return false;
		}
		HunterRumoursPack.Location preferred = preferredLocation(rumour);
		if (preferred == null)
		{
			return false;
		}
		WorldPoint at = client.getLocalPlayer().getWorldLocation();
		return at.getPlane() == preferred.plane
			&& at.distanceTo2D(preferred.worldPoint()) <= 30;
	}

	/** The trap item when it is not carried, or null (verified shortfall). */
	String missingTrapItem()
	{
		HunterRumoursPack.Rumour rumour = currentRumour();
		if (rumour == null || state.carriedCount(rumour.trapItemId) > 0)
		{
			return null;
		}
		return rumour.trapItemName;
	}

	/** Test seam: force a Hunter xp delta through the catch counter. */
	void feedHunterXp(int totalXp)
	{
		StatChanged event = new StatChanged(Skill.HUNTER, totalXp, 99, 99);
		onStatChanged(event);
	}
}
