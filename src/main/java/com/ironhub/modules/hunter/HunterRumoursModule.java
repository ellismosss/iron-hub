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
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
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

	private final AccountState state;
	private final IronHubConfig config;
	private final HunterRumoursPack pack;
	private final Client client;             // null in unit tests
	private final ClientThread clientThread; // null in unit tests
	private final EventBus eventBus;         // null in unit tests
	private final net.runelite.client.game.ItemManager itemManager; // null in unit tests
	private final com.ironhub.integrations.ShortestPathBridge pathBridge; // null in unit tests

	private HunterRumoursTab tab;
	private final List<PersistedState.RumourRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
	private boolean recordsLoaded;
	private int lastHunterXp = -1;

	@Inject
	public HunterRumoursModule(AccountState state, IronHubConfig config, DataPack dataPack,
		Client client, ClientThread clientThread, EventBus eventBus,
		net.runelite.client.game.ItemManager itemManager,
		com.ironhub.integrations.ShortestPathBridge pathBridge)
	{
		this.state = state;
		this.config = config;
		this.pack = dataPack == null ? null : dataPack.load("hunter-rumours", HunterRumoursPack.class);
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.itemManager = itemManager;
		this.pathBridge = pathBridge;
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
	}

	@Override
	public void shutDown()
	{
		if (eventBus != null)
		{
			eventBus.unregister(this);
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
		if (tab != null)
		{
			SwingUtilities.invokeLater(tab::rebuild);
		}
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

	/** Test seam: force a Hunter xp delta through the catch counter. */
	void feedHunterXp(int totalXp)
	{
		StatChanged event = new StatChanged(Skill.HUNTER, totalXp, 99, 99);
		onStatChanged(event);
	}
}
