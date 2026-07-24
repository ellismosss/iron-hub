/*
 * The widget / chat / inventory-diff storage detection here is ported
 * byte-faithfully from the "Dude, Where's My Stuff?" RuneLite plugin
 * (github.com/Thource/dude-wheres-my-stuff @ d032272, BSD 2-Clause,
 * (c) 2022 Thource — licenses/dude-wheres-my-stuff-LICENSE). Widget ids,
 * chat patterns and item tables are copied from the reference; because these
 * read raw interface widgets they want an in-client pass to confirm.
 */
package com.ironhub.modules.wheresmystuff;

import com.ironhub.data.StorageLocationsPack;
import com.ironhub.state.AccountState;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.math.NumberUtils;

/**
 * Widget / chat / inventory-diff STORAGE DETECTION scrapers, ported byte-faithfully from the
 * "Dude, Where's My Stuff?" hub plugin (github.com/Thource/dude-wheres-my-stuff @ d032272,
 * BSD-2, (c) 2022 Thource — licenses/dude-wheres-my-stuff-LICENSE).
 *
 * <p>Each scraper mirrors one of the reference's Storage subclasses: same widget ids, chat
 * patterns and item-id lists, transcribed verbatim. Where the reference maintains an in-place
 * {@code List<ItemStack>} and returns a boolean "updated", this class keeps the equivalent
 * running quantities as small maps and hands a full snapshot to
 * {@link WheresMyStuffModule.Sink#commit} whenever the snapshot changes (the module applies the
 * never-seen-empty-stays-silent rule).</p>
 *
 * <p>The reference reads container diffs through its {@code ItemContainerWatcher} (a per-game-tick
 * poll of {@code client.getItemContainer}). That is reproduced here by the {@link Watcher} inner
 * class: on every {@link GameTick} it re-reads the INV (and BANK) container and exposes the items
 * added / removed since the previous tick.</p>
 *
 * <p>Death storages are intentionally NOT ported (too coupled to the reference's DyingState
 * machine — the module handles death separately), nor are carryable inventory/equipment or coins
 * bank/inventory (AccountState owns those).</p>
 */
public class WheresMyStuffScrapers
{
	private final Client client;
	private final ItemManager itemManager;         // may be null in tests — guard every use
	@SuppressWarnings("unused")
	private final AccountState state;              // stored per the integration contract
	@SuppressWarnings("unused")
	private final StorageLocationsPack pack;       // stored per the integration contract
	private final WheresMyStuffModule.Sink sink;

	/** POH region ids, byte-faithful to the reference's Region.REGION_POH. */
	private static final Set<Integer> POH_REGIONS =
		Set.of(7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);

	/** Last snapshot committed per storage id, for change detection. */
	private final Map<String, Map<Integer, Integer>> lastItems = new HashMap<>();

	private final Watcher invWatcher = new Watcher(InventoryID.INV, true);
	private final Watcher bankWatcher = new Watcher(InventoryID.BANK, false);

	public WheresMyStuffScrapers(Client client, ItemManager itemManager, AccountState state,
		StorageLocationsPack pack, WheresMyStuffModule.Sink sink)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.state = state;
		this.pack = pack;
		this.sink = sink;

		initPotions();
	}

	// ── shared commit + name resolution ───────────────────────────────

	/**
	 * Commit a storage snapshot if it changed. {@code items} is filtered to non-zero quantities;
	 * {@code names} (nullable) supplies display-name overrides for point-icon items — the module
	 * resolves any id not present in it (so pass null for real-item storages).
	 */
	private void commit(String storageId, Map<Integer, Integer> items, Map<Integer, String> names)
	{
		Map<Integer, Integer> snap = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			if (e.getKey() != null && e.getKey() > 0 && e.getValue() != null && e.getValue() > 0)
			{
				snap.put(e.getKey(), e.getValue());
			}
		}

		Map<Integer, Integer> prior = lastItems.get(storageId);
		if (Objects.equals(prior, snap))
		{
			return;
		}
		if (prior == null && snap.isEmpty())
		{
			lastItems.put(storageId, snap);
			return; // never-seen + empty stays silent
		}
		lastItems.put(storageId, snap);

		Map<Integer, String> resolved = null;
		if (names != null)
		{
			resolved = new HashMap<>();
			for (Integer id : snap.keySet())
			{
				String n = names.get(id);
				if (n != null)
				{
					resolved.put(id, n);
				}
			}
		}
		sink.commit(storageId, snap, resolved, System.currentTimeMillis());
	}

	private String itemName(int id)
	{
		if (itemManager == null)
		{
			return "";
		}
		return itemManager.getItemComposition(id).getName();
	}

	private WorldPoint localPoint()
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return null;
		}
		return WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation());
	}

	// ── inventory / bank watcher (per-tick container diff) ─────────────

	/** Mirrors the reference's ItemContainerWatcher: added/removed since the previous tick. */
	private final class Watcher
	{
		private final int containerId;
		private final boolean requiresInitial;
		private Map<Integer, Integer> prev = new HashMap<>();
		private final Map<Integer, Integer> added = new HashMap<>();
		private final Map<Integer, Integer> removed = new HashMap<>();
		private boolean initialized;

		Watcher(int containerId, boolean requiresInitial)
		{
			this.containerId = containerId;
			this.requiresInitial = requiresInitial;
		}

		void tick()
		{
			added.clear();
			removed.clear();
			ItemContainer c = client == null ? null : client.getItemContainer(containerId);
			if (c == null)
			{
				return; // reference: justUpdated=false, no diffs, prev untouched
			}

			Map<Integer, Integer> cur = new HashMap<>();
			for (Item it : c.getItems())
			{
				if (it.getId() > 0)
				{
					cur.merge(it.getId(), it.getQuantity(), Integer::sum);
				}
			}

			if (requiresInitial && !initialized)
			{
				initialized = true;
				prev = cur; // seed without emitting diffs
				return;
			}

			for (Map.Entry<Integer, Integer> e : cur.entrySet())
			{
				int d = e.getValue() - prev.getOrDefault(e.getKey(), 0);
				if (d > 0)
				{
					added.put(e.getKey(), d);
				}
			}
			for (Map.Entry<Integer, Integer> e : prev.entrySet())
			{
				int d = e.getValue() - cur.getOrDefault(e.getKey(), 0);
				if (d > 0)
				{
					removed.put(e.getKey(), d);
				}
			}
			prev = cur;
		}
	}

	// ── event dispatch ────────────────────────────────────────────────

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client == null)
		{
			return;
		}
		// The reference updates its container watchers first, then runs every storage's onGameTick.
		invWatcher.tick();
		bankWatcher.tick();

		tickForestryShop();
		tickSandstorm();
		tickPotionStorage();
		tickCompostBins();
		tickNest();
		tickLogStorage();
		tickBottomlessBucket();
		tickGnomishFirelighter();
		tickHerbSack();
		tickServantsMoneybag();
		tickGrandExchange();
		tickShiloFurnace();
		tickBountyHunter();
		tickMageTrainingArena();
		tickLastManStanding();
		tickPestControl();
		tickMahoganyHomes();
		tickGiantsFoundry();
		tickVolcanicMine();
		tickGuardiansOfTheRift();
		tickMasteringMixology();
		tickMenagerie();
		tickSpiceRack();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (client == null)
		{
			return;
		}
		if (!isGameOrSpam(event))
		{
			return;
		}
		chatLogStorage(event);
		chatBottomlessBucket(event);
		chatGnomishFirelighter(event);
		chatHerbSack(event);
		chatGuardiansOfTheRift(event);
		chatMahoganyHomes(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (client == null)
		{
			return;
		}
		varScarEssenceMine(event);
		varNightmareZone(event);
		varBarbarianAssault(event);
		varMenagerie(event);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (client == null)
		{
			return;
		}
		mtaOnWidgetLoaded(event);
		pestControlOnWidgetLoaded(event);
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (client == null)
		{
			return;
		}
		mtaOnWidgetClosed(event);
		pestControlOnWidgetClosed(event);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (client == null)
		{
			return;
		}
		menagerieOnContainerChanged(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (client == null)
		{
			return;
		}
		herbSackOnMenuOptionClicked(event);
	}

	private static boolean isGameOrSpam(ChatMessage event)
	{
		return event.getType() == net.runelite.api.ChatMessageType.GAMEMESSAGE
			|| event.getType() == net.runelite.api.ChatMessageType.SPAM;
	}

	private Widget widget(int group, int child)
	{
		return client.getWidget(group, child);
	}

	// ══════════════════════════════════════════════════════════════════
	// world:forestryshop — 9 log types parsed from the check messagebox
	// ══════════════════════════════════════════════════════════════════

	private static final int[] FORESTRY_IDS = {
		ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.YEW_LOGS, ItemID.MAPLE_LOGS,
		ItemID.MAGIC_LOGS, ItemID.TEAK_LOGS, ItemID.MAHOGANY_LOGS, ItemID.REDWOOD_LOGS,
		ItemID.ARCTIC_PINE_LOG
	};
	private static final Pattern[] FORESTRY_PATTERNS = {
		Pattern.compile("Oak\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Willow\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Yew\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Maple\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Magic\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Teak\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Mahogany\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Redwood\\s+logs:\\s+(\\d+)"),
		Pattern.compile("Arctic\\s+pine\\s+logs:\\s+(\\d+)")
	};

	private void tickForestryShop()
	{
		Widget w = widget(InterfaceID.Messagebox.TEXT);
		if (w == null || !w.getText().startsWith("Your log storage contains:"))
		{
			return;
		}
		String text = w.getText().replace("<br>", " ").replace(",", "");
		Map<Integer, Integer> items = new LinkedHashMap<>();
		for (int i = 0; i < FORESTRY_PATTERNS.length; i++)
		{
			Matcher m = FORESTRY_PATTERNS[i].matcher(text);
			int qty = m.find() ? NumberUtils.toInt(m.group(1), 0) : 0;
			items.put(FORESTRY_IDS[i], qty);
		}
		commit("world:forestryshop", items, null);
	}

	private Widget widget(int packed)
	{
		return client.getWidget(packed);
	}

	// ══════════════════════════════════════════════════════════════════
	// world:sandstorm — buckets + sand (widget 231,6 + inventory diff)
	// ══════════════════════════════════════════════════════════════════

	private static final Pattern SANDSTORM_CHECK = Pattern.compile("I have (\\d+) of your buckets and "
		+ "you've ground enough sandstone for (\\d+) buckets of sand.");
	private static final Pattern SANDSTORM_SAND_DEPOSIT =
		Pattern.compile("sandstone (?:equivalent to|for) (\\d+|one) buckets? of sand");
	private static final Pattern SANDSTORM_BUCKET_DEPOSIT =
		Pattern.compile("holding onto (\\d+|one) buckets? for ya");

	private int sandBuckets;
	private int sandSand;
	private boolean sandJustWithdrawn;

	private void tickSandstorm()
	{
		Widget w = widget(231, 6);

		boolean updated = false;
		if (!sandJustWithdrawn)
		{
			if (w != null && w.getText().startsWith("If ya need any more sand"))
			{
				sandJustWithdrawn = true;
				Integer added = invWatcher.added.get(1784); // BUCKET_EMPTY, raw id from source
				if (added != null)
				{
					sandBuckets -= added;
					sandSand -= added;
					updated = true;
				}
			}
		}
		else if (w == null || !w.getText().startsWith("If ya need any more sand"))
		{
			sandJustWithdrawn = false;
		}

		Matcher check = sandstormWidget(SANDSTORM_CHECK);
		if (check != null)
		{
			sandBuckets = NumberUtils.toInt(check.group(1), 0);
			sandSand = NumberUtils.toInt(check.group(2), 0);
			updated = true;
		}
		Matcher sand = sandstormWidget(SANDSTORM_SAND_DEPOSIT);
		if (sand != null)
		{
			sandSand = "one".equals(sand.group(1)) ? 1 : NumberUtils.toInt(sand.group(1), 0);
			updated = true;
		}
		Matcher bucket = sandstormWidget(SANDSTORM_BUCKET_DEPOSIT);
		if (bucket != null)
		{
			sandBuckets = "one".equals(bucket.group(1)) ? 1 : NumberUtils.toInt(bucket.group(1), 0);
			updated = true;
		}

		if (updated)
		{
			Map<Integer, Integer> items = new LinkedHashMap<>();
			items.put(ItemID.BUCKET_EMPTY, Math.max(0, sandBuckets));
			items.put(ItemID.HANDSAND_SAND, Math.max(0, sandSand));
			commit("world:sandstorm", items, null);
		}
	}

	private Matcher sandstormWidget(Pattern pattern)
	{
		Widget w = widget(231, 6);
		if (w == null)
		{
			return null;
		}
		Matcher m = pattern.matcher(w.getText().replace("<br>", " ").replace(",", ""));
		return m.find() ? m : null;
	}

	// ══════════════════════════════════════════════════════════════════
	// world:potionStorage — the bank potion store + deposit-box/bank diff
	// ══════════════════════════════════════════════════════════════════

	private final Map<String, Integer> potionByText = new HashMap<>();     // widget label -> rep (1-dose) id
	private final Map<Integer, int[]> potionDoseInfo = new HashMap<>();    // any dose id -> {repId, doses}
	private final Map<Integer, Integer> potionQty = new LinkedHashMap<>(); // rep id -> total doses
	private int potionVials;

	private void addPotion(String text, int oneDoseId, int... otherDoseIds)
	{
		potionByText.put(text, oneDoseId);
		potionQty.put(oneDoseId, 0);
		potionDoseInfo.put(oneDoseId, new int[]{oneDoseId, 1});
		int doses = 1;
		for (int id : otherDoseIds)
		{
			potionDoseInfo.put(id, new int[]{oneDoseId, ++doses});
		}
	}

	private void initPotions()
	{
		potionDoseInfo.put(ItemID.VIAL_EMPTY, new int[]{ItemID.VIAL_EMPTY, 0});

		// regular potions
		addPotion("Agility potion", ItemID._1DOSE1AGILITY, ItemID._2DOSE1AGILITY, ItemID._3DOSE1AGILITY, ItemID._4DOSE1AGILITY);
		addPotion("Ancient brew", ItemID._1DOSEANCIENTBREW, ItemID._2DOSEANCIENTBREW, ItemID._3DOSEANCIENTBREW, ItemID._4DOSEANCIENTBREW);
		addPotion("Anti-venom", ItemID.ANTIVENOM1, ItemID.ANTIVENOM2, ItemID.ANTIVENOM3, ItemID.ANTIVENOM4);
		addPotion("Anti-venom+", ItemID.ANTIVENOM_1, ItemID.ANTIVENOM_2, ItemID.ANTIVENOM_3, ItemID.ANTIVENOM_4);
		addPotion("Antidote+", ItemID.ANTIDOTE_1, ItemID.ANTIDOTE_2, ItemID.ANTIDOTE_3, ItemID.ANTIDOTE_4);
		addPotion("Antidote++", ItemID.ANTIDOTE__1, ItemID.ANTIDOTE__2, ItemID.ANTIDOTE__3, ItemID.ANTIDOTE__4);
		addPotion("Antifire potion", ItemID._1DOSE1ANTIDRAGON, ItemID._2DOSE1ANTIDRAGON, ItemID._3DOSE1ANTIDRAGON, ItemID._4DOSE1ANTIDRAGON);
		addPotion("Antipoison", ItemID._1DOSEANTIPOISON, ItemID._2DOSEANTIPOISON, ItemID._3DOSEANTIPOISON, ItemID._4DOSEANTIPOISON);
		addPotion("Attack potion", ItemID._1DOSE1ATTACK, ItemID._2DOSE1ATTACK, ItemID._3DOSE1ATTACK, ItemID._4DOSE1ATTACK);
		addPotion("Battlemage potion", ItemID._1DOSEBATTLEMAGE, ItemID._2DOSEBATTLEMAGE, ItemID._3DOSEBATTLEMAGE, ItemID._4DOSEBATTLEMAGE);
		addPotion("Bastion potion", ItemID._1DOSEBASTION, ItemID._2DOSEBASTION, ItemID._3DOSEBASTION, ItemID._4DOSEBASTION);
		addPotion("Blighted super restore", ItemID.BLIGHTED_1DOSE2RESTORE, ItemID.BLIGHTED_2DOSE2RESTORE, ItemID.BLIGHTED_3DOSE2RESTORE, ItemID.BLIGHTED_4DOSE2RESTORE);
		addPotion("Combat potion", ItemID._1DOSECOMBAT, ItemID._2DOSECOMBAT, ItemID._3DOSECOMBAT, ItemID._4DOSECOMBAT);
		addPotion("Compost potion", ItemID.SUPERCOMPOST_POTION_1, ItemID.SUPERCOMPOST_POTION_2, ItemID.SUPERCOMPOST_POTION_3, ItemID.SUPERCOMPOST_POTION_4);
		addPotion("Defence potion", ItemID._1DOSE1DEFENSE, ItemID._2DOSE1DEFENSE, ItemID._3DOSE1DEFENSE, ItemID._4DOSE1DEFENSE);
		addPotion("Divine bastion", ItemID._1DOSEDIVINEBASTION, ItemID._2DOSEDIVINEBASTION, ItemID._3DOSEDIVINEBASTION, ItemID._4DOSEDIVINEBASTION);
		addPotion("Divine battlemage", ItemID._1DOSEDIVINEBATTLEMAGE, ItemID._2DOSEDIVINEBATTLEMAGE, ItemID._3DOSEDIVINEBATTLEMAGE, ItemID._4DOSEDIVINEBATTLEMAGE);
		addPotion("Divine magic", ItemID._1DOSEDIVINEMAGIC, ItemID._2DOSEDIVINEMAGIC, ItemID._3DOSEDIVINEMAGIC, ItemID._4DOSEDIVINEMAGIC);
		addPotion("Divine ranging", ItemID._1DOSEDIVINERANGE, ItemID._2DOSEDIVINERANGE, ItemID._3DOSEDIVINERANGE, ItemID._4DOSEDIVINERANGE);
		addPotion("Divine super attack", ItemID._1DOSEDIVINEATTACK, ItemID._2DOSEDIVINEATTACK, ItemID._3DOSEDIVINEATTACK, ItemID._4DOSEDIVINEATTACK);
		addPotion("Divine super combat", ItemID._1DOSEDIVINECOMBAT, ItemID._2DOSEDIVINECOMBAT, ItemID._3DOSEDIVINECOMBAT, ItemID._4DOSEDIVINECOMBAT);
		addPotion("Divine super defence", ItemID._1DOSEDIVINEDEFENCE, ItemID._2DOSEDIVINEDEFENCE, ItemID._3DOSEDIVINEDEFENCE, ItemID._4DOSEDIVINEDEFENCE);
		addPotion("Divine super strength", ItemID._1DOSEDIVINESTRENGTH, ItemID._2DOSEDIVINESTRENGTH, ItemID._3DOSEDIVINESTRENGTH, ItemID._4DOSEDIVINESTRENGTH);
		addPotion("Energy potion", ItemID._1DOSE1ENERGY, ItemID._2DOSE1ENERGY, ItemID._3DOSE1ENERGY, ItemID._4DOSE1ENERGY);
		addPotion("Extended anti-venom+", ItemID.EXTENDED_ANTIVENOM_1, ItemID.EXTENDED_ANTIVENOM_2, ItemID.EXTENDED_ANTIVENOM_3, ItemID.EXTENDED_ANTIVENOM_4);
		addPotion("Extended antifire", ItemID._1DOSE2ANTIDRAGON, ItemID._2DOSE2ANTIDRAGON, ItemID._3DOSE2ANTIDRAGON, ItemID._4DOSE2ANTIDRAGON);
		addPotion("Extended super antifire", ItemID._1DOSE4ANTIDRAGON, ItemID._2DOSE4ANTIDRAGON, ItemID._3DOSE4ANTIDRAGON, ItemID._4DOSE4ANTIDRAGON);
		addPotion("Fishing potion", ItemID._1DOSEFISHERSPOTION, ItemID._2DOSEFISHERSPOTION, ItemID._3DOSEFISHERSPOTION, ItemID._4DOSEFISHERSPOTION);
		addPotion("Forgotten brew", ItemID._1DOSEFORGOTTENBREW, ItemID._2DOSEFORGOTTENBREW, ItemID._3DOSEFORGOTTENBREW, ItemID._4DOSEFORGOTTENBREW);
		addPotion("Goading potion", ItemID._1DOSEGOADING, ItemID._2DOSEGOADING, ItemID._3DOSEGOADING, ItemID._4DOSEGOADING);
		addPotion("Guthix balance", ItemID.BURGH_GUTHIX_BALANCE_1, ItemID.BURGH_GUTHIX_BALANCE_2, ItemID.BURGH_GUTHIX_BALANCE_3, ItemID.BURGH_GUTHIX_BALANCE_4);
		addPotion("Hunter potion", ItemID._1DOSEHUNTING, ItemID._2DOSEHUNTING, ItemID._3DOSEHUNTING, ItemID._4DOSEHUNTING);
		addPotion("Magic essence", ItemID._1DOSEMAGICESS, ItemID._2DOSEMAGICESS, ItemID._3DOSEMAGICESS, ItemID._4DOSEMAGICESS);
		addPotion("Magic potion", ItemID._1DOSE1MAGIC, ItemID._2DOSE1MAGIC, ItemID._3DOSE1MAGIC, ItemID._4DOSE1MAGIC);
		addPotion("Menaphite remedy", ItemID._1DOSESTATRENEWAL, ItemID._2DOSESTATRENEWAL, ItemID._3DOSESTATRENEWAL, ItemID._4DOSESTATRENEWAL);
		addPotion("Prayer potion", ItemID._1DOSEPRAYERRESTORE, ItemID._2DOSEPRAYERRESTORE, ItemID._3DOSEPRAYERRESTORE, ItemID._4DOSEPRAYERRESTORE);
		addPotion("Prayer regeneration", ItemID._1DOSE1PRAYER_REGENERATION, ItemID._2DOSE1PRAYER_REGENERATION, ItemID._3DOSE1PRAYER_REGENERATION, ItemID._4DOSE1PRAYER_REGENERATION);
		addPotion("Ranging potion", ItemID._1DOSERANGERSPOTION, ItemID._2DOSERANGERSPOTION, ItemID._3DOSERANGERSPOTION, ItemID._4DOSERANGERSPOTION);
		addPotion("Relicym's balm", ItemID.RELICYMS_BALM1, ItemID.RELICYMS_BALM2, ItemID.RELICYMS_BALM3, ItemID.RELICYMS_BALM4);
		addPotion("Restore potion", ItemID._1DOSESTATRESTORE, ItemID._2DOSESTATRESTORE, ItemID._3DOSESTATRESTORE, ItemID._4DOSESTATRESTORE);
		addPotion("Sanfew serum", ItemID.SANFEW_SALVE_1_DOSE, ItemID.SANFEW_SALVE_2_DOSE, ItemID.SANFEW_SALVE_3_DOSE, ItemID.SANFEW_SALVE_4_DOSE);
		addPotion("Saradomin brew", ItemID._1DOSEPOTIONOFSARADOMIN, ItemID._2DOSEPOTIONOFSARADOMIN, ItemID._3DOSEPOTIONOFSARADOMIN, ItemID._4DOSEPOTIONOFSARADOMIN);
		addPotion("Serum 207", ItemID.MORT_SERUM1, ItemID.MORT_SERUM2, ItemID.MORT_SERUM3, ItemID.MORT_SERUM4);
		addPotion("Stamina potion", ItemID._1DOSESTAMINA, ItemID._2DOSESTAMINA, ItemID._3DOSESTAMINA, ItemID._4DOSESTAMINA);
		addPotion("Strength potion", ItemID._1DOSE1STRENGTH, ItemID._2DOSE1STRENGTH, ItemID._3DOSE1STRENGTH, ItemID.STRENGTH4);
		addPotion("Super antifire potion", ItemID._1DOSE3ANTIDRAGON, ItemID._2DOSE3ANTIDRAGON, ItemID._3DOSE3ANTIDRAGON, ItemID._4DOSE3ANTIDRAGON);
		addPotion("Super attack", ItemID._1DOSE2ATTACK, ItemID._2DOSE2ATTACK, ItemID._3DOSE2ATTACK, ItemID._4DOSE2ATTACK);
		addPotion("Super combat potion", ItemID._1DOSE2COMBAT, ItemID._2DOSE2COMBAT, ItemID._3DOSE2COMBAT, ItemID._4DOSE2COMBAT);
		addPotion("Super defence", ItemID._1DOSE2DEFENSE, ItemID._2DOSE2DEFENSE, ItemID._3DOSE2DEFENSE, ItemID._4DOSE2DEFENSE);
		addPotion("Super energy", ItemID._1DOSE2ENERGY, ItemID._2DOSE2ENERGY, ItemID._3DOSE2ENERGY, ItemID._4DOSE2ENERGY);
		addPotion("Super restore", ItemID._1DOSE2RESTORE, ItemID._2DOSE2RESTORE, ItemID._3DOSE2RESTORE, ItemID._4DOSE2RESTORE);
		addPotion("Super strength", ItemID._1DOSE2STRENGTH, ItemID._2DOSE2STRENGTH, ItemID._3DOSE2STRENGTH, ItemID._4DOSE2STRENGTH);
		addPotion("Superantipoison", ItemID._1DOSE2ANTIPOISON, ItemID._2DOSE2ANTIPOISON, ItemID._3DOSE2ANTIPOISON, ItemID._4DOSE2ANTIPOISON);
		addPotion("Weapon poison", ItemID.WEAPON_POISON);
		addPotion("Weapon poison(+)", ItemID.WEAPON_POISON_);
		addPotion("Weapon poison(++)", ItemID.WEAPON_POISON__);
		addPotion("Zamorak brew", ItemID._1DOSEPOTIONOFZAMORAK, ItemID._2DOSEPOTIONOFZAMORAK, ItemID._3DOSEPOTIONOFZAMORAK, ItemID._4DOSEPOTIONOFZAMORAK);

		// brutal (mix) potions
		addPotion("Agility mix", ItemID.BRUTAL_1DOSE1AGILITY, ItemID.BRUTAL_2DOSE1AGILITY);
		addPotion("Ancient mix", ItemID.BRUTAL_1DOSEANCIENTBREW, ItemID.BRUTAL_2DOSEANCIENTBREW);
		addPotion("Antifire mix", ItemID.BRUTAL_1DOSE1ANTIDRAGON, ItemID.BRUTAL_2DOSE1ANTIDRAGON);
		addPotion("Antipoison mix", ItemID.BRUTAL_1DOSEANTIPOISON, ItemID.BRUTAL_2DOSEANTIPOISON);
		addPotion("Antidote+ mix", ItemID.BRUTAL_ANTIDOTE_1, ItemID.BRUTAL_ANTIDOTE_2);
		addPotion("Attack mix", ItemID.BRUTAL_1DOSE1ATTACK, ItemID.BRUTAL_2DOSE1ATTACK);
		addPotion("Combat mix", ItemID.BRUTAL_1DOSECOMBAT, ItemID.BRUTAL_2DOSECOMBAT);
		addPotion("Defence mix", ItemID.BRUTAL_1DOSE1DEFENSE, ItemID.BRUTAL_2DOSE1DEFENSE);
		addPotion("Energy mix", ItemID.BRUTAL_1DOSE1ENERGY, ItemID.BRUTAL_2DOSE1ENERGY);
		addPotion("Extended antifire mix", ItemID.BRUTAL_1DOSE2ANTIDRAGON, ItemID.BRUTAL_2DOSE2ANTIDRAGON);
		addPotion("Ext. super antifire mix", ItemID.BRUTAL_1DOSE4ANTIDRAGON, ItemID.BRUTAL_2DOSE4ANTIDRAGON);
		addPotion("Fishing mix", ItemID.BRUTAL_1DOSEFISHERSPOTION, ItemID.BRUTAL_2DOSEFISHERSPOTION);
		addPotion("Hunting mix", ItemID.BRUTAL_1DOSE1HUNTING, ItemID.BRUTAL_2DOSE1HUNTING);
		addPotion("Magic essence mix", ItemID.BRUTAL_1DOSEMAGICESS, ItemID.BRUTAL_2DOSEMAGICESS);
		addPotion("Magic mix", ItemID.BRUTAL_1DOSE1MAGIC, ItemID.BRUTAL_2DOSE1MAGIC);
		addPotion("Prayer mix", ItemID.BRUTAL_1DOSEPRAYERRESTORE, ItemID.BRUTAL_2DOSEPRAYERRESTORE);
		addPotion("Ranging mix", ItemID.BRUTAL_1DOSERANGERSPOTION, ItemID.BRUTAL_2DOSERANGERSPOTION);
		addPotion("Restore mix", ItemID.BRUTAL_1DOSESTATRESTORE, ItemID.BRUTAL_2DOSESTATRESTORE);
		addPotion("Stamina mix", ItemID.BRUTAL_1DOSESTAMINA, ItemID.BRUTAL_2DOSESTAMINA);
		addPotion("Strength mix", ItemID.BRUTAL_1DOSE1STRENGTH, ItemID.BRUTAL_2DOSE1STRENGTH);
		addPotion("Super antifire mix", ItemID.BRUTAL_1DOSE3ANTIDRAGON, ItemID.BRUTAL_2DOSE3ANTIDRAGON);
		addPotion("Superattack mix", ItemID.BRUTAL_1DOSE2ATTACK, ItemID.BRUTAL_2DOSE2ATTACK);
		addPotion("Super def. mix", ItemID.BRUTAL_1DOSE2DEFENSE, ItemID.BRUTAL_2DOSE2DEFENSE);
		addPotion("Super energy mix", ItemID.BRUTAL_1DOSE2ENERGY, ItemID.BRUTAL_2DOSE2ENERGY);
		addPotion("Super restore mix", ItemID.BRUTAL_1DOSE2RESTORE, ItemID.BRUTAL_2DOSE2RESTORE);
		addPotion("Super str. mix", ItemID.BRUTAL_1DOSE2STRENGTH, ItemID.BRUTAL_2DOSE2STRENGTH);
		addPotion("Anti-poison supermix", ItemID.BRUTAL_1DOSE2ANTIPOISON, ItemID.BRUTAL_2DOSE2ANTIPOISON);
		addPotion("Zamorak mix", ItemID.BRUTAL_1DOSEPOTIONOFZAMORAK, ItemID.BRUTAL_2DOSEPOTIONOFZAMORAK);
		addPotion("Relicym's mix", ItemID.BRUTAL_RELICYMS_BALM1, ItemID.BRUTAL_RELICYMS_BALM2);

		// unfinished potions
		addPotion("Avantoe potion (unf)", ItemID.AVANTOEVIAL);
		addPotion("Cadantine blood potion (unf)", ItemID.CADANTINE_BLOODVIAL);
		addPotion("Cadantine potion (unf)", ItemID.CADANTINEVIAL);
		addPotion("Dwarf weed potion (unf)", ItemID.DWARFWEEDVIAL);
		addPotion("Guam potion (unf)", ItemID.GUAMVIAL);
		addPotion("Harralander potion (unf)", ItemID.HARRALANDERVIAL);
		addPotion("Huasca potion (unf)", ItemID.HUASCAVIAL);
		addPotion("Irit potion (unf)", ItemID.IRITVIAL);
		addPotion("Kwuarm potion (unf)", ItemID.KWUARMVIAL);
		addPotion("Lantadyme potion (unf)", ItemID.LANTADYMEVIAL);
		addPotion("Marrentill potion (unf)", ItemID.MARRENTILLVIAL);
		addPotion("Ranarr potion (unf)", ItemID.RANARRVIAL);
		addPotion("Snapdragon potion (unf)", ItemID.SNAPDRAGONVIAL);
		addPotion("Tarromin potion (unf)", ItemID.TARROMINVIAL);
		addPotion("Toadflax potion (unf)", ItemID.TOADFLAXVIAL);
		addPotion("Torstol potion (unf)", ItemID.TORSTOLVIAL);
	}

	private void tickPotionStorage()
	{
		boolean updated = updatePotionStorageWidget();
		updated |= updatePotionDepositBoxOrBank();
		if (updated)
		{
			Map<Integer, Integer> items = new LinkedHashMap<>();
			items.put(ItemID.VIAL_EMPTY, potionVials);
			for (Map.Entry<Integer, Integer> e : potionQty.entrySet())
			{
				items.put(e.getKey(), e.getValue());
			}
			commit("world:potionStorage", items, null);
		}
	}

	private boolean updatePotionStorageWidget()
	{
		Widget store = widget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
		if (store == null || store.isHidden() || store.getChildren() == null)
		{
			return false;
		}

		Map<String, Integer> parsed = new HashMap<>();
		boolean updated = false;
		String current = null;
		for (Widget w : store.getChildren())
		{
			String text = w.getText();
			if (text.startsWith("Vials:"))
			{
				int newVials = Integer.parseInt(text.replace("Vials: ", "").replaceAll("\\D+", ""));
				if (newVials != potionVials)
				{
					updated = true;
					potionVials = newVials;
				}
				continue;
			}
			if (text.startsWith("Doses: ") || text.startsWith("Quantity: "))
			{
				if (current != null)
				{
					String qtyText = text.replace("Doses: ", "").replace("Quantity: ", "").replaceAll("\\D+", "");
					parsed.put(current, Integer.parseInt(qtyText));
					current = null;
				}
				continue;
			}
			if (text.contains("("))
			{
				current = text;
				if (!potionByText.containsKey(current))
				{
					current = text.split("\\s*\\(")[0];
				}
			}
		}

		for (Map.Entry<String, Integer> e : potionByText.entrySet())
		{
			int repId = e.getValue();
			int newQty = parsed.getOrDefault(e.getKey(), 0);
			if (newQty != potionQty.getOrDefault(repId, 0))
			{
				updated = true;
				potionQty.put(repId, newQty);
			}
		}
		return updated;
	}

	private boolean updatePotionDepositBoxOrBank()
	{
		if (client.getVarbitValue(VarbitID.BANK_DEPOSITPOTION) != 1)
		{
			return false;
		}
		Widget depositBox = widget(InterfaceID.BankDepositbox.FRAME);
		boolean depositBoxOpen = depositBox != null && !depositBox.isHidden();
		Widget bank = widget(InterfaceID.Bankmain.ITEMS);
		boolean bankOpen = bank != null && !bank.isHidden();
		if (bankOpen)
		{
			Widget store = widget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
			boolean storeOpen = store != null && !store.isHidden() && store.getChildren() != null;
			if (storeOpen)
			{
				return false;
			}
		}
		else if (!depositBoxOpen)
		{
			return false;
		}

		boolean updated = false;
		for (Map.Entry<Integer, Integer> removed : invWatcher.removed.entrySet())
		{
			int canonical = itemManager == null ? removed.getKey() : itemManager.canonicalize(removed.getKey());
			int[] info = potionDoseInfo.get(canonical);
			if (info != null)
			{
				int repId = info[0];
				int doses = info[1];
				potionQty.merge(repId, removed.getValue() * doses, Integer::sum);
				potionVials += removed.getValue();
				updated = true;
			}
		}
		return updated;
	}

	// ══════════════════════════════════════════════════════════════════
	// world:compostBins — per-region compost varbit decode
	// ══════════════════════════════════════════════════════════════════

	private static final class CompostBin
	{
		final boolean big;
		final int varbitId;
		final Set<Integer> regions;
		final int[] qty = new int[4]; // compost, super, ultra, rottenTomato

		CompostBin(boolean big, int varbitId, Integer... regions)
		{
			this.big = big;
			this.varbitId = varbitId;
			this.regions = Set.of(regions);
		}

		boolean decode(int binValue)
		{
			int[] old = qty.clone();
			qty[0] = qty[1] = qty[2] = qty[3] = 0;
			if (big)
			{
				if (binValue >= 16 && binValue <= 30) { qty[0] = binValue - 15; }
				else if (binValue >= 48 && binValue <= 62) { qty[1] = binValue - 47; }
				else if (binValue >= 78 && binValue <= 92) { qty[0] = 15 + binValue - 77; }
				else if (binValue == 93) { qty[0] = 30; }
				else if (binValue == 99) { qty[1] = 30; }
				else if (binValue >= 100 && binValue <= 114) { qty[1] = 15 + binValue - 99; }
				else if (binValue >= 144 && binValue <= 158) { qty[3] = binValue - 143; }
				else if (binValue >= 176 && binValue <= 205) { qty[2] = binValue - 175; }
				else if (binValue >= 207 && binValue <= 221) { qty[3] = 15 + binValue - 206; }
				else if (binValue == 222) { qty[3] = 30; }
			}
			else
			{
				if (binValue >= 16 && binValue <= 30) { qty[0] = binValue - 15; }
				else if (binValue >= 48 && binValue <= 62) { qty[1] = binValue - 47; }
				else if (binValue == 94) { qty[0] = 15; }
				else if (binValue == 126) { qty[1] = 15; }
				else if (binValue >= 144 && binValue <= 158) { qty[3] = binValue - 143; }
				else if (binValue == 160) { qty[3] = 15; }
				else if (binValue >= 176 && binValue <= 190) { qty[2] = binValue - 175; }
			}
			return old[0] != qty[0] || old[1] != qty[1] || old[2] != qty[2] || old[3] != qty[3];
		}
	}

	private final List<CompostBin> compostBins = List.of(
		new CompostBin(false, VarbitID.FARMING_TRANSMIT_E, 10548),
		new CompostBin(false, VarbitID.FARMING_TRANSMIT_E, 11062),
		new CompostBin(false, VarbitID.FARMING_TRANSMIT_E, 12083),
		new CompostBin(false, VarbitID.FARMING_TRANSMIT_E, 6967, 6711),
		new CompostBin(false, VarbitID.FARMING_TRANSMIT_E, 14391, 14390),
		new CompostBin(true, VarbitID.FARMING_TRANSMIT_N, 4922, 5177, 5178, 5179, 4921, 4923, 4665, 4666, 4667),
		new CompostBin(false, VarbitID.FARMING_TRANSMIT_D, 13151, 12895, 12894, 13150, 12994, 12993, 12737, 12738, 12126, 12127, 13250)
	);
	private int compostLastRegion = -1;

	private void tickCompostBins()
	{
		WorldPoint wp = localPoint();
		if (wp == null)
		{
			return;
		}
		int region = wp.getRegionID();
		if (region == compostLastRegion)
		{
			return;
		}
		compostLastRegion = region;

		boolean changed = false;
		for (CompostBin bin : compostBins)
		{
			if (bin.regions.contains(region))
			{
				changed = bin.decode(client.getVarbitValue(bin.varbitId));
				break;
			}
		}
		if (!changed)
		{
			return;
		}

		Map<Integer, Integer> items = new LinkedHashMap<>();
		for (CompostBin bin : compostBins)
		{
			items.merge(ItemID.BUCKET_COMPOST, bin.qty[0], Integer::sum);
			items.merge(ItemID.BUCKET_SUPERCOMPOST, bin.qty[1], Integer::sum);
			items.merge(ItemID.BUCKET_ULTRACOMPOST, bin.qty[2], Integer::sum);
			items.merge(ItemID.ROTTEN_TOMATO, bin.qty[3], Integer::sum);
		}
		commit("world:compostBins", items, null);
	}

	// ══════════════════════════════════════════════════════════════════
	// world:nest — the single item held in the Nest (Objectbox widget)
	// ══════════════════════════════════════════════════════════════════

	private int nestItemId = -1;

	private int nestConvertedId(int itemId)
	{
		switch (itemId)
		{
			case ItemID.TOA_LOOT_POO: return ItemID.VARLAMORE_NASTY_TOKEN_1;
			case ItemID.RAW_CHICKEN: return ItemID.COOKED_CHICKEN;
			case ItemID.GNOME_SPICE: return ItemID.IRON_PICKAXE;
			case ItemID.ROPE: return ItemID.LEATHER_GLOVES;
			case ItemID.DRAGON_CLAWS: return ItemID.EGG;
			case ItemID.RUNE_PLATELEGS: return ItemID.RUNITE_BAR;
			case ItemID.ADAMANT_SCIMITAR: return ItemID.CAKE;
			case ItemID.XBOWS_CROSSBOW_LIMBS_MITHRIL: return ItemID.XBOWS_CROSSBOW_MITHRIL;
			case ItemID.STEEL_DAGGER: return ItemID.SNELM_ROUND_YELLOW;
			case ItemID.KWUARMVIAL: return ItemID.WEAPON_POISON;
			default: return itemId;
		}
	}

	private void tickNest()
	{
		Widget text = widget(InterfaceID.Objectbox.TEXT);
		if (text == null)
		{
			return;
		}
		boolean deposit = text.getText().equals("You place your item in the nest.");
		boolean retrieval = text.getText().equals("You retrieve your item from the nest.");
		if (!deposit && !retrieval)
		{
			return;
		}
		Widget itemWidget = widget(InterfaceID.Objectbox.ITEM);
		if (itemWidget == null)
		{
			return;
		}

		int id = nestConvertedId(itemWidget.getItemId());
		if (deposit)
		{
			if (nestItemId != id)
			{
				nestItemId = id;
				commit("world:nest", Map.of(id, 1), null);
			}
		}
		else if (nestItemId != -1)
		{
			nestItemId = -1;
			commit("world:nest", Map.of(), null);
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// world:logstorage — hot-air-balloon log crate (chat + messagebox)
	// ══════════════════════════════════════════════════════════════════

	private static final int[] LOG_STORAGE_IDS = {
		ItemID.LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.YEW_LOGS, ItemID.MAGIC_LOGS
	};
	private static final Pattern LOG_CHECK = Pattern.compile("This crate currently contains (\\d+) logs,"
		+ " (\\d+) oak logs, (\\d+) willow logs, (\\d+) yew logs and (\\d+) magic logs.");
	private static final Pattern LOG_DEPOSIT =
		Pattern.compile("You put the (.*) in the crate. You now have (\\d+) stored.");
	private static final Pattern LOG_CHAT =
		Pattern.compile("You have (\\d+) sets of (.*) left in storage.");
	private final int[] logStorageQty = new int[5];

	private void chatLogStorage(ChatMessage event)
	{
		Matcher m = LOG_CHAT.matcher(event.getMessage());
		if (!m.find())
		{
			return;
		}
		int idx = logStorageIndexByName(m.group(2));
		if (idx < 0)
		{
			return;
		}
		int qty = NumberUtils.toInt(m.group(1), 0);
		if (logStorageQty[idx] != qty)
		{
			logStorageQty[idx] = qty;
			commitLogStorage();
		}
	}

	private void tickLogStorage()
	{
		Widget check = widget(InterfaceID.Messagebox.TEXT);
		if (check != null)
		{
			Matcher m = LOG_CHECK.matcher(check.getText().replace("<br>", " "));
			if (m.find())
			{
				boolean updated = false;
				for (int i = 0; i < LOG_STORAGE_IDS.length; i++)
				{
					int qty = NumberUtils.toInt(m.group(i + 1), 0);
					if (logStorageQty[i] != qty)
					{
						logStorageQty[i] = qty;
						updated = true;
					}
				}
				if (updated)
				{
					commitLogStorage();
				}
			}
		}

		Widget deposit = widget(193, 2);
		if (deposit != null)
		{
			Matcher m = LOG_DEPOSIT.matcher(deposit.getText().replace("<br>", " "));
			if (m.find())
			{
				int idx = logStorageIndexByName(m.group(1));
				if (idx >= 0)
				{
					int qty = NumberUtils.toInt(m.group(2), 0);
					if (logStorageQty[idx] != qty)
					{
						logStorageQty[idx] = qty;
						commitLogStorage();
					}
				}
			}
		}
	}

	private int logStorageIndexByName(String name)
	{
		for (int i = 0; i < LOG_STORAGE_IDS.length; i++)
		{
			if (Objects.equals(itemName(LOG_STORAGE_IDS[i]), name))
			{
				return i;
			}
		}
		return -1;
	}

	private void commitLogStorage()
	{
		Map<Integer, Integer> items = new LinkedHashMap<>();
		for (int i = 0; i < LOG_STORAGE_IDS.length; i++)
		{
			items.put(LOG_STORAGE_IDS[i], logStorageQty[i]);
		}
		commit("world:logstorage", items, null);
	}

	// ══════════════════════════════════════════════════════════════════
	// carryable:bottomlessbucket — compost charges (widget 193,2 + chat)
	// ══════════════════════════════════════════════════════════════════

	private static final Pattern BUCKET_CHARGES = Pattern.compile("(\\d+) uses");
	private int bucketCompost;
	private int bucketSuper;
	private int bucketUltra;

	private void tickBottomlessBucket()
	{
		Widget w = widget(193, 2);
		if (w == null)
		{
			return;
		}
		String text = w.getText().replace("<br>", " ").replace(",", "");
		if (!text.contains("compost bucket"))
		{
			return;
		}
		if (text.contains("currently empty") || text.startsWith("You discard"))
		{
			setBucket(0, 0, 0);
			return;
		}
		Matcher m = BUCKET_CHARGES.matcher(text);
		int charges = 1;
		if (m.find())
		{
			charges = NumberUtils.toInt(m.group(1));
		}
		else if (!text.contains("one use"))
		{
			return;
		}
		if (text.contains("ultracompost")) { setBucket(0, 0, charges); }
		else if (text.contains("supercompost")) { setBucket(0, charges, 0); }
		else { setBucket(charges, 0, 0); }
	}

	private void chatBottomlessBucket(ChatMessage event)
	{
		String msg = event.getMessage();
		if (!msg.startsWith("Your bottomless compost bucket has"))
		{
			return;
		}
		if (msg.contains("run out"))
		{
			setBucket(0, 0, 0);
			return;
		}
		Matcher m = BUCKET_CHARGES.matcher(msg.replace(",", ""));
		int charges = 1;
		if (m.find())
		{
			charges = NumberUtils.toInt(m.group(1));
		}
		else if (!msg.contains("single use"))
		{
			return;
		}
		if (msg.contains("ultracompost")) { setBucket(0, 0, charges); }
		else if (msg.contains("supercompost")) { setBucket(0, charges, 0); }
		else { setBucket(charges, 0, 0); }
	}

	private void setBucket(int compost, int superC, int ultra)
	{
		bucketCompost = compost;
		bucketSuper = superC;
		bucketUltra = ultra;
		Map<Integer, Integer> items = new LinkedHashMap<>();
		items.put(ItemID.BUCKET_COMPOST, compost);
		items.put(ItemID.BUCKET_SUPERCOMPOST, superC);
		items.put(ItemID.BUCKET_ULTRACOMPOST, ultra);
		commit("carryable:bottomlessbucket", items, null);
	}

	// ══════════════════════════════════════════════════════════════════
	// carryable:gnomishfirelighter — firelighter charges by colour
	// ══════════════════════════════════════════════════════════════════

	private static final Pattern FIRELIGHTER_CHARGES = Pattern.compile("(\\d+) (\\w+) firelighter charges");
	private static final int[] FIRELIGHTER_IDS = {
		ItemID.GNOMISH_FIRELIGHTER_RED, ItemID.GNOMISH_FIRELIGHTER_GREEN, ItemID.GNOMISH_FIRELIGHTER_BLUE,
		ItemID.TRAIL_GNOMISH_FIRELIGHTER_PURPLE, ItemID.TRAIL_GNOMISH_FIRELIGHTER_WHITE
	};
	private final int[] firelighterQty = new int[5];

	private void tickGnomishFirelighter()
	{
		Widget w = widget(193, 2);
		if (w == null)
		{
			return;
		}
		String text = w.getText().replace("<br>", " ");
		if (!text.contains("gnomish firelighter"))
		{
			return;
		}
		if (text.contains("is empty"))
		{
			java.util.Arrays.fill(firelighterQty, 0);
			commitFirelighter();
			return;
		}
		Matcher m = FIRELIGHTER_CHARGES.matcher(text);
		if (!m.find())
		{
			return;
		}
		int charges = NumberUtils.toInt(m.group(1));
		int idx = firelighterIndexByColour(m.group(2));
		if (idx < 0)
		{
			return;
		}
		firelighterQty[idx] = charges;
		commitFirelighter();
	}

	private void chatGnomishFirelighter(ChatMessage event)
	{
		if (!event.getMessage().startsWith("You uncharge the gnomish firelighter"))
		{
			return;
		}
		java.util.Arrays.fill(firelighterQty, 0);
		commitFirelighter();
	}

	private int firelighterIndexByColour(String colour)
	{
		for (int i = 0; i < FIRELIGHTER_IDS.length; i++)
		{
			// reference matches the item name CONTAINING the colour word (Red/Green/…)
			if (itemName(FIRELIGHTER_IDS[i]).contains(colour))
			{
				return i;
			}
		}
		return -1;
	}

	private void commitFirelighter()
	{
		Map<Integer, Integer> items = new LinkedHashMap<>();
		for (int i = 0; i < FIRELIGHTER_IDS.length; i++)
		{
			items.put(FIRELIGHTER_IDS[i], firelighterQty[i]);
		}
		commit("carryable:gnomishfirelighter", items, null);
	}

	// ══════════════════════════════════════════════════════════════════
	// carryable:herbSack — 15 unidentified herbs (chat + inventory diff)
	// ══════════════════════════════════════════════════════════════════

	private static final int[] HERB_SACK_IDS = {
		ItemID.UNIDENTIFIED_GUAM, ItemID.UNIDENTIFIED_MARENTILL, ItemID.UNIDENTIFIED_TARROMIN,
		ItemID.UNIDENTIFIED_HARRALANDER, ItemID.UNIDENTIFIED_RANARR, ItemID.UNIDENTIFIED_TOADFLAX,
		ItemID.UNIDENTIFIED_IRIT, ItemID.UNIDENTIFIED_AVANTOE, ItemID.UNIDENTIFIED_KWUARM,
		ItemID.UNIDENTIFIED_HUASCA, ItemID.UNIDENTIFIED_SNAPDRAGON, ItemID.UNIDENTIFIED_CADANTINE,
		ItemID.UNIDENTIFIED_LANTADYME, ItemID.UNIDENTIFIED_DWARF_WEED, ItemID.UNIDENTIFIED_TORSTOL
	};
	private static final int[] HERB_SACK_CONTAINER_IDS = {
		ItemID.SLAYER_HERB_SACK, ItemID.SLAYER_HERB_SACK_OPEN
	};
	private static final Pattern HERB_SACK_CHECK = Pattern.compile("(\\d+) x (.*)");
	private static final Pattern HERB_SACK_PICK_UP = Pattern.compile("You put the (.*) herb into your herb sack");
	private static final Pattern HERB_SACK_USE = Pattern.compile("You add the (.*) to your sack");

	private final Map<Integer, Integer> herbSackQty = new LinkedHashMap<>();
	private boolean herbCheckingSack;
	private boolean herbAddingToSack;
	private boolean herbRemovingToInv;
	private boolean herbRemovingToBank;

	private void herbAddByName(String name, int quantity)
	{
		int id = herbIdByName(name);
		if (id > 0)
		{
			int now = Math.min(30, Math.max(0, herbSackQty.getOrDefault(id, 0) + quantity));
			herbSackQty.put(id, now);
		}
	}

	private void herbSetByName(String name, int quantity)
	{
		int id = herbIdByName(name);
		if (id > 0)
		{
			herbSackQty.put(id, quantity);
		}
	}

	private int herbIdByName(String name)
	{
		for (int id : HERB_SACK_IDS)
		{
			if (Objects.equals(itemName(id), name))
			{
				return id;
			}
		}
		return -1;
	}

	private void tickHerbSack()
	{
		boolean updated = false;
		if (herbCheckingSack)
		{
			herbCheckingSack = false;
			updated = true;
		}
		if (herbAddingToSack)
		{
			for (Map.Entry<Integer, Integer> e : invWatcher.removed.entrySet())
			{
				herbAddByName(itemName(e.getKey()), 1);
			}
			herbAddingToSack = false;
			updated = true;
		}
		if (herbRemovingToInv)
		{
			for (Map.Entry<Integer, Integer> e : invWatcher.added.entrySet())
			{
				herbAddByName(itemName(e.getKey()), -1);
			}
			herbRemovingToInv = false;
			updated = true;
		}
		if (herbRemovingToBank)
		{
			for (Map.Entry<Integer, Integer> e : bankWatcher.added.entrySet())
			{
				herbAddByName(itemName(e.getKey()), -e.getValue());
			}
			herbRemovingToBank = false;
			updated = true;
		}
		if (updated)
		{
			commit("carryable:herbSack", herbSackQty, null);
		}
	}

	private void chatHerbSack(ChatMessage event)
	{
		String msg = event.getMessage();
		if (msg.startsWith("The herb sack is empty"))
		{
			herbSackQty.clear();
			commit("carryable:herbSack", herbSackQty, null);
			return;
		}
		if (herbCheckingSack)
		{
			Matcher m = HERB_SACK_CHECK.matcher(msg);
			if (m.find())
			{
				herbSetByName(m.group(2), Integer.parseInt(m.group(1)));
			}
			return;
		}
		Matcher pickUp = HERB_SACK_PICK_UP.matcher(msg);
		if (pickUp.find())
		{
			herbAddByName(pickUp.group(1), 1);
			commit("carryable:herbSack", herbSackQty, null);
			return;
		}
		if (HERB_SACK_USE.matcher(msg).find() || msg.startsWith("You add the herbs to your sack"))
		{
			herbAddingToSack = true;
		}
		else if (msg.startsWith("You look in your herb sack"))
		{
			herbCheckingSack = true;
		}
		else if (msg.startsWith("You rummage around to see if you can extract any herbs from your herb sack"))
		{
			herbRemovingToInv = true;
		}
	}

	private void herbSackOnMenuOptionClicked(MenuOptionClicked event)
	{
		Widget w = event.getWidget();
		if (w == null || !containsId(HERB_SACK_CONTAINER_IDS, w.getItemId())
			|| !event.getMenuOption().equals("Empty"))
		{
			return;
		}
		if (w.getParentId() == InterfaceID.Bankside.ITEMS)
		{
			herbRemovingToBank = true;
			return;
		}
		if (w.getParentId() == InterfaceID.BankDepositbox.INVENTORY)
		{
			// deposit-box "Empty": the herbs go to the bank (AccountState tracks that) — the sack empties
			herbSackQty.clear();
			commit("carryable:herbSack", herbSackQty, null);
		}
	}

	private static boolean containsId(int[] ids, int id)
	{
		for (int i : ids)
		{
			if (i == id)
			{
				return true;
			}
		}
		return false;
	}

	// ══════════════════════════════════════════════════════════════════
	// coins:* — single 995-coin storages
	// ══════════════════════════════════════════════════════════════════

	private void commitCoins(String storageId, int coins)
	{
		commit(storageId, Map.of(995, Math.max(0, coins)), null);
	}

	private void tickServantsMoneybag()
	{
		Widget w = widget(193, 2);
		if (w == null || !w.getText().startsWith("The money bag "))
		{
			return;
		}
		commitCoins("coins:servantsmoneybag", NumberUtils.toInt(w.getText().replaceAll("\\D+", ""), 0));
	}

	private void tickGrandExchange()
	{
		if (widget(465, 1) != null)
		{
			long sum = 0;
			for (int slot = 0; slot < 8; slot++)
			{
				sum += grandExchangeSlotCoins(slot);
			}
			commitCoins("coins:grandexchange", (int) sum);
			return;
		}
		if (widget(402, 1) != null)
		{
			long sum = 0;
			for (int slot = 0; slot < 8; slot++)
			{
				sum += collectSlotCoins(slot);
			}
			commitCoins("coins:grandexchange", (int) sum);
		}
	}

	private int grandExchangeSlotCoins(int slot)
	{
		Widget slotWidget = widget(465, 7 + slot);
		if (slotWidget == null)
		{
			return 0;
		}
		Widget offerType = slotWidget.getChild(16);
		if (offerType == null || !Objects.equals(offerType.getText(), "Buy"))
		{
			return 0;
		}
		Widget offerBar = slotWidget.getChild(22);
		if (offerBar == null || !Objects.equals(offerBar.getTextColor(), 0x8f0000))
		{
			return 0;
		}
		Widget offerCoins = slotWidget.getChild(25);
		if (offerCoins == null)
		{
			return 0;
		}
		return NumberUtils.toInt(offerCoins.getText().replaceAll("\\D+", ""), 0);
	}

	private int collectSlotCoins(int slot)
	{
		Widget slotWidget = widget(402, 5 + slot);
		if (slotWidget == null)
		{
			return 0;
		}
		Widget itemWidget = slotWidget.getChild(3);
		if (itemWidget == null || itemWidget.getItemId() != 995 || itemWidget.isHidden())
		{
			return 0;
		}
		return itemWidget.getItemQuantity();
	}

	private void tickShiloFurnace()
	{
		Widget w = widget(219, 1);
		if (w == null)
		{
			return;
		}
		Widget textWidget = w.getChild(0);
		if (textWidget == null || !textWidget.getText().startsWith("Furnace coffer: "))
		{
			return;
		}
		commitCoins("coins:shilofurnace", NumberUtils.toInt(textWidget.getText().replaceAll("\\D+", ""), 0));
	}

	private static final Pattern BOUNTY_HUNTER_DEPOSIT = Pattern.compile(
		"You (?:withdrew|added) \\d+ coins (?:from|to) your coffer. There are now (\\d+) coins in it. "
			+ "You need to have at least \\d+ coins in your coffer to participate.");

	private void tickBountyHunter()
	{
		WorldPoint wp = localPoint();
		if (wp == null)
		{
			return;
		}
		int region = wp.getRegionID();
		if (region != 12600 && region != 12344 && region != 13631)
		{
			return;
		}

		Widget main = widget(219, 1);
		if (main != null)
		{
			Widget textWidget = main.getChild(0);
			if (textWidget != null && textWidget.getText().startsWith("Current coffer: "))
			{
				commitCoins("coins:bountyhunter", NumberUtils.toInt(textWidget.getText().replaceAll("\\D+", ""), 0));
				return;
			}
		}
		Widget dw = widget(193, 2);
		if (dw != null)
		{
			Matcher m = BOUNTY_HUNTER_DEPOSIT.matcher(dw.getText().replace(",", "").replace("<br>", " "));
			if (m.matches())
			{
				commitCoins("coins:bountyhunter", NumberUtils.toInt(m.group(1), 0));
			}
		}
	}

	private void varScarEssenceMine(VarbitChanged event)
	{
		if (event.getVarpId() != VarPlayerID.SCAR_ESSENCEMINE_COFFER)
		{
			return;
		}
		commitCoins("coins:scarEssenceMine", client.getVarpValue(VarPlayerID.SCAR_ESSENCEMINE_COFFER));
	}

	// ══════════════════════════════════════════════════════════════════
	// minigames:* — point-icon storages (name override = "Points" etc.)
	// ══════════════════════════════════════════════════════════════════

	// ---- Mage Training Arena ----
	private static final class MtaPoint
	{
		final int iconId;
		final String name;
		final int widgetGroup;
		final int varpId;
		final int lobbyChild;
		int qty;
		Widget widget;

		MtaPoint(int iconId, String name, int widgetGroup, int varpId, int lobbyChild)
		{
			this.iconId = iconId;
			this.name = name;
			this.widgetGroup = widgetGroup;
			this.varpId = varpId;
			this.lobbyChild = lobbyChild;
		}
	}

	private final List<MtaPoint> mtaPoints = List.of(
		new MtaPoint(ItemID.LAWRUNE, "Telekinetic Points", 198, 261, 10),
		new MtaPoint(ItemID.FAKE_COINS, "Alchemist Points", 194, 262, 11),
		new MtaPoint(ItemID.MAGICTRAINING_ENCHAN_CYLINDER, "Enchantment Points", 195, 263, 12),
		new MtaPoint(ItemID.PEACH, "Graveyard Points", 196, 264, 13)
	);
	private Widget mtaShopWidget;
	private boolean mtaLobbyOpen;

	private void mtaOnWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == 197)
		{
			mtaShopWidget = widget(197, 0);
		}
		else if (event.getGroupId() == 553)
		{
			mtaLobbyOpen = true;
		}
		else
		{
			for (MtaPoint p : mtaPoints)
			{
				if (event.getGroupId() == p.widgetGroup)
				{
					p.widget = widget(p.widgetGroup, 6);
				}
			}
		}
		tickMageTrainingArena();
	}

	private void mtaOnWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == 197)
		{
			mtaShopWidget = null;
		}
		else if (event.getGroupId() == 553)
		{
			mtaLobbyOpen = false;
		}
		else
		{
			for (MtaPoint p : mtaPoints)
			{
				if (event.getGroupId() == p.widgetGroup)
				{
					p.widget = null;
				}
			}
		}
	}

	private void tickMageTrainingArena()
	{
		boolean updated = false;
		if (mtaShopWidget != null)
		{
			for (MtaPoint p : mtaPoints)
			{
				p.qty = client.getVarpValue(p.varpId);
			}
			updated = true;
		}
		else if (mtaLobbyOpen)
		{
			for (MtaPoint p : mtaPoints)
			{
				Widget w = widget(553, p.lobbyChild);
				if (w != null)
				{
					p.qty = NumberUtils.toInt(w.getText().replace(",", ""), 0);
				}
			}
			updated = true;
		}
		else
		{
			for (MtaPoint p : mtaPoints)
			{
				if (p.widget != null)
				{
					p.qty = NumberUtils.toInt(p.widget.getText().replace(",", ""), 0);
					updated = true;
					break;
				}
			}
		}
		if (updated)
		{
			Map<Integer, Integer> items = new LinkedHashMap<>();
			Map<Integer, String> names = new HashMap<>();
			for (MtaPoint p : mtaPoints)
			{
				items.put(p.iconId, p.qty);
				names.put(p.iconId, p.name);
			}
			commit("minigames:magetrainingarena", items, names);
		}
	}

	// ---- Last Man Standing ----
	private static final Pattern LMS_SHOP = Pattern.compile("Points: (\\d+)");
	private int lmsPoints;

	private void tickLastManStanding()
	{
		Widget w = widget(645, 8);
		if (w == null)
		{
			return;
		}
		Matcher m = LMS_SHOP.matcher(Text.removeTags(w.getText()).replace(",", ""));
		if (!m.find())
		{
			return;
		}
		lmsPoints = Integer.parseInt(m.group(1));
		commit("minigames:lastmanstanding", Map.of(ItemID.SKULL, lmsPoints),
			Map.of(ItemID.SKULL, "Points"));
	}

	// ---- Nightmare Zone (varbit points + reward potions) ----
	private static final int[] NZONE_POTION_VARBITS = {
		VarbitID.NZONE_POTION_1, VarbitID.NZONE_POTION_2, VarbitID.NZONE_POTION_3, VarbitID.NZONE_POTION_4
	};
	private static final int[] NZONE_POTION_IDS = {
		ItemID.NZONE1DOSE2RANGERSPOTION, ItemID.NZONE1DOSE2MAGICPOTION,
		ItemID.NZONE1DOSEOVERLOADPOTION, ItemID.NZONE1DOSEABSORPTIONPOTION
	};
	private int nzonePoints;
	private final int[] nzonePotionQty = new int[4];

	private void varNightmareZone(VarbitChanged event)
	{
		boolean updated = false;
		for (int i = 0; i < NZONE_POTION_VARBITS.length; i++)
		{
			if (event.getVarbitId() == NZONE_POTION_VARBITS[i])
			{
				int v = client.getVarbitValue(NZONE_POTION_VARBITS[i]);
				if (v != nzonePotionQty[i])
				{
					nzonePotionQty[i] = v;
					updated = true;
				}
			}
		}
		if (event.getVarbitId() == VarbitID.NZONE_CURRENTPOINTS
			|| event.getVarpId() == VarPlayerID.NZONE_REWARDPOINTS)
		{
			int v = client.getVarbitValue(VarbitID.NZONE_CURRENTPOINTS)
				+ client.getVarpValue(VarPlayerID.NZONE_REWARDPOINTS);
			if (v != nzonePoints)
			{
				nzonePoints = v;
				updated = true;
			}
		}
		if (updated)
		{
			Map<Integer, Integer> items = new LinkedHashMap<>();
			items.put(ItemID.DREAM_VIAL_FULL, nzonePoints);
			for (int i = 0; i < NZONE_POTION_IDS.length; i++)
			{
				items.put(NZONE_POTION_IDS[i], nzonePotionQty[i]);
			}
			commit("minigames:nightmarezone", items, Map.of(ItemID.DREAM_VIAL_FULL, "Points"));
		}
	}

	// ---- Pest Control ----
	private static final Pattern PC_AFTER_GAME_1 = Pattern.compile("awarded you (\\d+) Void Knight");
	private static final Pattern PC_AFTER_GAME_2 = Pattern.compile("now have <col=800000>(\\d+)<col=000080> Void Knight");
	private static final Pattern PC_AFTER_PURCHASE = Pattern.compile("Remaining Void Knight Commendation Points: (\\d+)");
	private int pcPoints;
	private Widget pcShopWidget;

	private void pestControlOnWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == 243)
		{
			pcShopWidget = widget(243, 0);
			pestControlUpdateFromWidget();
		}
		else if (event.getGroupId() == 231)
		{
			Widget w = widget(231, 6);
			if (w != null)
			{
				Matcher m = PC_AFTER_GAME_1.matcher(w.getText().replace("<br>", " "));
				if (m.find())
				{
					pcPoints += NumberUtils.toInt(m.group(1));
					commitPestControl();
				}
			}
		}
	}

	private void pestControlOnWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == 243)
		{
			pcShopWidget = null;
		}
	}

	private void tickPestControl()
	{
		Widget w = widget(InterfaceID.Messagebox.TEXT);
		if (w != null)
		{
			String text = w.getText().replace("<br>", " ").replace(",", "");
			Matcher m = PC_AFTER_GAME_2.matcher(text);
			if (m.find())
			{
				pcPoints = NumberUtils.toInt(m.group(1));
				commitPestControl();
				return;
			}
			m = PC_AFTER_PURCHASE.matcher(text);
			if (m.find())
			{
				pcPoints = NumberUtils.toInt(m.group(1));
				commitPestControl();
				return;
			}
		}
		pestControlUpdateFromWidget();
	}

	private void pestControlUpdateFromWidget()
	{
		if (pcShopWidget == null)
		{
			return;
		}
		pcPoints = client.getVarpValue(VarPlayerID.IF1);
		commitPestControl();
	}

	private void commitPestControl()
	{
		commit("minigames:pestcontrol", Map.of(ItemID.PEST_SEAL_1, pcPoints),
			Map.of(ItemID.PEST_SEAL_1, "Points"));
	}

	// ---- Barbarian Assault (role points + queen kills, varbits) ----
	private static final int[] BA_POINT_VARBITS = {
		VarbitID.BARBASSAULT_POINTS_ATTACKER_BASE, VarbitID.BARBASSAULT_POINTS_COLLECTOR_BASE,
		VarbitID.BARBASSAULT_POINTS_DEFENDER_BASE, VarbitID.BARBASSAULT_POINTS_HEALER_BASE
	};
	private static final int[] BA_POINT_ICONS = {
		ItemID.BARBASSAULT_PLAYERICON_ATTACKER, ItemID.BARBASSAULT_PLAYERICON_COLLECTOR,
		ItemID.BARBASSAULT_PLAYERICON_DEFENDER, ItemID.BARBASSAULT_PLAYERICON_HEALER
	};
	private static final String[] BA_POINT_NAMES = {
		"Attacker Points", "Collector Points", "Defender Points", "Healer Points"
	};
	private final int[] baPoints = new int[4];
	private int baQueenKills;

	private void varBarbarianAssault(VarbitChanged event)
	{
		boolean updated = false;
		for (int i = 0; i < BA_POINT_VARBITS.length; i++)
		{
			if (event.getVarbitId() == BA_POINT_VARBITS[i] || event.getVarbitId() == BA_POINT_VARBITS[i] + 4)
			{
				int v = client.getVarbitValue(BA_POINT_VARBITS[i]) + client.getVarbitValue(BA_POINT_VARBITS[i] + 4) * 512;
				if (v != baPoints[i])
				{
					baPoints[i] = v;
					updated = true;
				}
			}
		}
		if (event.getVarbitId() == VarbitID.BARBASSAULT_QUEENKILLS_EXTRA
			|| event.getVarbitId() == VarbitID.BARBASSAULT_QUEENKILLS_EXTRA_2)
		{
			int v = client.getVarbitValue(VarbitID.BARBASSAULT_QUEENKILLS_EXTRA) * 2
				+ client.getVarbitValue(VarbitID.BARBASSAULT_QUEENKILLS_EXTRA_2) * 16;
			if (v != baQueenKills)
			{
				baQueenKills = v;
				updated = true;
			}
		}
		if (updated)
		{
			Map<Integer, Integer> items = new LinkedHashMap<>();
			Map<Integer, String> names = new HashMap<>();
			for (int i = 0; i < BA_POINT_ICONS.length; i++)
			{
				items.put(BA_POINT_ICONS[i], baPoints[i]);
				names.put(BA_POINT_ICONS[i], BA_POINT_NAMES[i]);
			}
			items.put(ItemID.PENANCEPET, baQueenKills);
			names.put(ItemID.PENANCEPET, "Queen Kills");
			commit("minigames:barbarianassault", items, names);
		}
	}

	// ---- Guardians of the Rift (elemental + catalytic energy) ----
	private static final Pattern GOTR_CHAT_POINTS = Pattern.compile(
		"Total elemental energy: <col=ef1020>(\\d+)</col>\\. Total catalytic energy: <col=ef1020>(\\d+)</col>\\.");
	private static final Pattern GOTR_WIDGET = Pattern.compile(
		"You have (\\d+) catalytic energy and (\\d+) elemental energy\\.");
	private int gotrElemental;
	private int gotrCatalytic;

	/** Per-tick read of the "You have N catalytic energy and N elemental energy." check messagebox. */
	private void tickGuardiansOfTheRift()
	{
		Widget w = widget(InterfaceID.Messagebox.TEXT);
		if (w == null)
		{
			return;
		}
		Matcher m = GOTR_WIDGET.matcher(w.getText().replace(",", ""));
		if (!m.find())
		{
			return;
		}
		gotrElemental = NumberUtils.toInt(m.group(2), 0);
		gotrCatalytic = NumberUtils.toInt(m.group(1), 0);
		commitGotr();
	}

	private void chatGuardiansOfTheRift(ChatMessage event)
	{
		String msg = event.getMessage();
		if (msg.startsWith("You found some loot:"))
		{
			WorldPoint wp = localPoint();
			if (wp == null || wp.getRegionID() != 14484) // MG_GUARDIANS_OF_THE_RIFT
			{
				return;
			}
			gotrElemental -= 1;
			gotrCatalytic -= 1;
			commitGotr();
			return;
		}
		Matcher m = GOTR_CHAT_POINTS.matcher(msg.replace(",", ""));
		if (!m.matches())
		{
			return;
		}
		gotrElemental = NumberUtils.toInt(m.group(1), 0);
		gotrCatalytic = NumberUtils.toInt(m.group(2), 0);
		commitGotr();
	}

	private void commitGotr()
	{
		Map<Integer, Integer> items = new LinkedHashMap<>();
		items.put(ItemID.AIRRUNE, Math.max(0, gotrElemental));
		items.put(ItemID.COSMICRUNE, Math.max(0, gotrCatalytic));
		commit("minigames:guardiansoftherift", items,
			Map.of(ItemID.AIRRUNE, "Elemental Energy", ItemID.COSMICRUNE, "Catalytic Energy"));
	}

	// ---- Mahogany Homes ----
	private static final Pattern MAHOGANY_CHAT = Pattern.compile(
		"You have completed <col=ef1020>\\d+</col> contracts with a total of <col=ef1020>(\\d+)</col> points\\.");
	private int mahoganyPoints;

	private void tickMahoganyHomes()
	{
		Widget w = widget(673, 8);
		if (w == null)
		{
			return;
		}
		mahoganyPoints = NumberUtils.toInt(Text.removeTags(w.getText()).replaceAll("\\D+", ""), 0);
		commitMahogany();
	}

	private void chatMahoganyHomes(ChatMessage event)
	{
		Matcher m = MAHOGANY_CHAT.matcher(event.getMessage().replace(",", ""));
		if (!m.matches())
		{
			return;
		}
		mahoganyPoints = NumberUtils.toInt(m.group(1), 0);
		commitMahogany();
	}

	private void commitMahogany()
	{
		commit("minigames:mahoganyhomes", Map.of(ItemID.POH_SAW, mahoganyPoints),
			Map.of(ItemID.POH_SAW, "Points"));
	}

	// ---- Giants' Foundry ----
	private static final Pattern GF_HAND_IN = Pattern.compile("at quality: (\\d+)");
	private int gfPoints;
	private boolean gfDidJustHandIn;

	private void tickGiantsFoundry()
	{
		Widget shop = widget(753, 13);
		if (shop != null)
		{
			gfPoints = Integer.parseInt(shop.getText());
			commitGiantsFoundry();
			return;
		}
		Widget chat = widget(InterfaceID.Messagebox.TEXT);
		if (chat != null)
		{
			if (!gfDidJustHandIn)
			{
				Matcher m = GF_HAND_IN.matcher(chat.getText());
				if (m.find())
				{
					gfPoints += NumberUtils.toInt(m.group(1));
					gfDidJustHandIn = true;
					commitGiantsFoundry();
				}
			}
		}
		else
		{
			gfDidJustHandIn = false;
		}
	}

	private void commitGiantsFoundry()
	{
		commit("minigames:giantsfoundry", Map.of(ItemID.GIANTS_FOUNDRY_COLOSSAL_BLADE, gfPoints),
			Map.of(ItemID.GIANTS_FOUNDRY_COLOSSAL_BLADE, "Points"));
	}

	// ---- Volcanic Mine ----
	private static final Pattern VM_SHOP = Pattern.compile("Points: (\\d+)");
	private int vmPoints;

	private void tickVolcanicMine()
	{
		Widget w = widget(612, 5);
		if (w == null)
		{
			return;
		}
		Matcher m = VM_SHOP.matcher(Text.removeTags(w.getText()).replace(",", ""));
		if (!m.find())
		{
			return;
		}
		vmPoints = Integer.parseInt(m.group(1));
		commit("minigames:volcanicmine", Map.of(ItemID.FOSSIL_VOLCANIC_ASH, vmPoints),
			Map.of(ItemID.FOSSIL_VOLCANIC_ASH, "Points"));
	}

	// ---- Mastering Mixology (paste only — resin is a sprite currency, no item id) ----
	private final int[] mixologyPaste = new int[3];

	private void tickMasteringMixology()
	{
		Widget w = widget(InterfaceID.MmOverlay.CONTENT);
		if (w == null)
		{
			return;
		}
		Widget[] children = w.getChildren();
		if (children == null || children.length < 16)
		{
			return;
		}
		mixologyPaste[0] = Integer.parseInt(children[8].getText());
		mixologyPaste[1] = Integer.parseInt(children[11].getText());
		mixologyPaste[2] = Integer.parseInt(children[14].getText());
		Map<Integer, Integer> items = new LinkedHashMap<>();
		items.put(ItemID.MM_MOX_PASTE, mixologyPaste[0]);
		items.put(ItemID.MM_AGA_PASTE, mixologyPaste[1]);
		items.put(ItemID.MM_LYE_PASTE, mixologyPaste[2]);
		// ponytail: the resin reward-points path is intentionally skipped — resin has no item id
		// (the reference renders it from sprite ids 5666-5668), so it can't be keyed into the
		// id-based sink. Only paste (real item ids) is tracked here.
		commit("minigames:masteringMixology", items, null);
	}

	// ══════════════════════════════════════════════════════════════════
	// playerownedhouse:menagerie — pets (container + varplayer bits + inv diff)
	// ══════════════════════════════════════════════════════════════════

	private static final List<Integer> MENAGERIE_ITEM_IDS = List.of(
		ItemID.VT_USELESS_ROCK, ItemID.KITTENOBJECT, ItemID.KITTENOBJECT_LIGHT, ItemID.KITTENOBJECT_BROWN,
		ItemID.KITTENOBJECT_BLACK, ItemID.KITTENOBJECT_BROWNGREY, ItemID.KITTENOBJECT_BLUEGREY,
		ItemID.GROWNCATOBJECT, ItemID.GROWNCATOBJECT_LIGHT, ItemID.GROWNCATOBJECT_BROWN,
		ItemID.GROWNCATOBJECT_BLACK, ItemID.GROWNCATOBJECT_BROWNGREY, ItemID.GROWNCATOBJECT_BLUEGREY,
		ItemID.OVERGROWNCATOBJECT, ItemID.OVERGROWNCATOBJECT_LIGHT, ItemID.OVERGROWNCATOBJECT_BROWN,
		ItemID.OVERGROWNCATOBJECT_BLACK, ItemID.OVERGROWNCATOBJECT_BROWNGREY, ItemID.OVERGROWNCATOBJECT_BLUEGREY,
		ItemID.WILEYCATOBJECT_LIGHT, ItemID.WILEYCATOBJECT, ItemID.WILEYCATOBJECT_BROWN,
		ItemID.WILEYCATOBJECT_BLACK, ItemID.WILEYCATOBJECT_BROWNGREY, ItemID.WILEYCATOBJECT_BLUEGREY,
		ItemID.LAZYCATOBJECT_LIGHT, ItemID.LAZYCATOBJECT, ItemID.LAZYCATOBJECT_BROWN,
		ItemID.LAZYCATOBJECT_BLACK, ItemID.LAZYCATOBJECT_BROWNGREY, ItemID.LAZYCATOBJECT_BLUEGREY,
		ItemID.KITTENOBJECT_HELL, ItemID.GROWNCATOBJECT_HELL, ItemID.OVERGROWNCATOBJECT_HELL,
		ItemID.WILEYCATOBJECT_HELL, ItemID.LAZYCATOBJECT_HELL, ItemID.FISHBOWL_BLUEFISH,
		ItemID.FISHBOWL_GREENFISH, ItemID.FISHBOWL_SPINEFISH, ItemID.POH_TOY_CAT, ItemID.WGS_BROAV,
		ItemID.SCRAMBLED_EGG, ItemID.HW25_CHAIR_OBJ_REWARD, ItemID.CURRENT_AFFAIRS_MAYOR_OF_CATHERBY);

	private final Map<Integer, Integer> menagerieItems = new LinkedHashMap<>(); // container + inv-diff
	private final Map<Integer, Integer> menagerieVarItems = new LinkedHashMap<>(); // decoded from bits
	private int menageriePetBits1;
	private int menageriePetBits2;
	private int menageriePetBits3;
	private boolean menagerieFollowedLastTick;

	private void menagerieOnContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		if (containerId > 0x8000)
		{
			containerId -= 0x8000;
		}
		if (containerId != InventoryID.POH_MENAGERIE_PETS)
		{
			return;
		}
		menagerieItems.clear();
		if (event.getItemContainer() != null)
		{
			for (Item item : event.getItemContainer().getItems())
			{
				if (item.getId() != -1)
				{
					menagerieItems.put(item.getId(), 1);
				}
			}
		}
		commitMenagerie();
	}

	private void tickMenagerie()
	{
		WorldPoint wp = localPoint();
		if (wp == null || !POH_REGIONS.contains(wp.getRegionID()))
		{
			return;
		}
		boolean followed = client.getVarpValue(VarPlayerID.FOLLOWER_NPC) != -1;
		boolean updated = false;

		// a pet leaving the inventory (dropped into the menagerie) — unless it's the pet being
		// summoned to follow this tick
		if (!followed || menagerieFollowedLastTick)
		{
			for (Integer id : invWatcher.removed.keySet())
			{
				if (MENAGERIE_ITEM_IDS.contains(id))
				{
					menagerieItems.put(id, 1);
					updated = true;
				}
			}
		}
		// a pet appearing in the inventory (taken back out of the menagerie)
		for (Integer id : invWatcher.added.keySet())
		{
			if (MENAGERIE_ITEM_IDS.contains(id) && menagerieItems.remove(id) != null)
			{
				updated = true;
			}
		}

		menagerieFollowedLastTick = followed;
		if (updated)
		{
			commitMenagerie();
		}
	}

	private void varMenagerie(VarbitChanged event)
	{
		if (event.getVarpId() != VarPlayerID.PRAYER20
			&& event.getVarpId() != VarPlayerID.MENAGERIE_CONTENTS2
			&& event.getVarpId() != VarPlayerID.MENAGERIE_CONTENTS3)
		{
			return;
		}
		int b1 = client.getVarpValue(VarPlayerID.PRAYER20);
		int b2 = client.getVarpValue(VarPlayerID.MENAGERIE_CONTENTS2);
		int b3 = client.getVarpValue(VarPlayerID.MENAGERIE_CONTENTS3);
		if (b1 == menageriePetBits1 && b2 == menageriePetBits2 && b3 == menageriePetBits3)
		{
			return;
		}
		menageriePetBits1 = b1;
		menageriePetBits2 = b2;
		menageriePetBits3 = b3;
		rebuildMenageriePetsFromBits();
		commitMenagerie();
	}

	private void rebuildMenageriePetsFromBits()
	{
		menagerieVarItems.clear();
		EnumComposition petEnum = client.getEnum(985);
		if (petEnum == null)
		{
			return;
		}
		int n = petEnum.getIntVals().length;
		for (int i = 0; i < n; i++)
		{
			boolean set;
			if (i < 32)
			{
				set = (menageriePetBits1 & (1 << i)) != 0;
			}
			else if (i < 63)
			{
				set = (menageriePetBits2 & (1 << (i - 32))) != 0;
			}
			else if (i < 94)
			{
				set = (menageriePetBits3 & (1 << (i - 63))) != 0;
			}
			else
			{
				set = false;
			}
			if (set)
			{
				menagerieVarItems.put(petEnum.getIntValue(i), 1);
			}
		}
	}

	private void commitMenagerie()
	{
		Map<Integer, Integer> items = new LinkedHashMap<>(menagerieItems);
		for (Integer id : menagerieVarItems.keySet())
		{
			items.put(id, 1);
		}
		commit("playerownedhouse:menagerie", items, null);
	}

	// ══════════════════════════════════════════════════════════════════
	// playerownedhouse:spiceRack — 4 spices (state machine + inv diff)
	// ══════════════════════════════════════════════════════════════════

	private static final Pattern SPICE_CHECK = Pattern.compile(
		"(\\d+) x Red Spice.<br>(\\d+) x Brown Spice.<br>(\\d+) x Yellow Spice.<br>(\\d+) x Orange Spice.");

	// dose id -> {repId, doses}
	private final Map<Integer, int[]> spiceDoseInfo = new HashMap<>();
	private int spiceRed;
	private int spiceBrown;
	private int spiceYellow;
	private int spiceOrange;

	private enum SpiceState { NONE, WITHDRAWING, CHECK_WITHDRAW, CHECK_WITHDRAW_TWICE, CHECKED_DEPOSIT }

	private SpiceState spiceState = SpiceState.NONE;

	{
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_RED_1, new int[]{0, 1});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_RED_2, new int[]{0, 2});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_RED_3, new int[]{0, 3});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_RED_4, new int[]{0, 4});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_BROWN_1, new int[]{1, 1});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_BROWN_2, new int[]{1, 2});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_BROWN_3, new int[]{1, 3});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_BROWN_4, new int[]{1, 4});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_YELLOW_1, new int[]{2, 1});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_YELLOW_2, new int[]{2, 2});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_YELLOW_3, new int[]{2, 3});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_YELLOW_4, new int[]{2, 4});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_ORANGE_1, new int[]{3, 1});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_ORANGE_2, new int[]{3, 2});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_ORANGE_3, new int[]{3, 3});
		spiceDoseInfo.put(ItemID.HUNDRED_DAVE_SPICE_ORANGE_4, new int[]{3, 4});
	}

	private void addSpiceDoses(int colourIdx, int doses)
	{
		switch (colourIdx)
		{
			case 0: spiceRed += doses; break;
			case 1: spiceBrown += doses; break;
			case 2: spiceYellow += doses; break;
			case 3: spiceOrange += doses; break;
			default:
		}
	}

	private void tickSpiceRack()
	{
		boolean updated = false;

		Widget withdrawWidget = widget(InterfaceID.Chatbox.MES_TEXT);
		if (withdrawWidget != null && !withdrawWidget.isHidden()
			&& Objects.equals(withdrawWidget.getText(), "How much spice would you like to take?:"))
		{
			spiceState = SpiceState.WITHDRAWING;
		}
		else if (spiceState == SpiceState.WITHDRAWING)
		{
			spiceState = SpiceState.CHECK_WITHDRAW;
		}

		if (spiceState == SpiceState.CHECK_WITHDRAW || spiceState == SpiceState.CHECK_WITHDRAW_TWICE)
		{
			for (Map.Entry<Integer, Integer> e : invWatcher.added.entrySet())
			{
				int[] info = spiceDoseInfo.get(e.getKey());
				if (info != null)
				{
					addSpiceDoses(info[0], -info[1] * e.getValue());
				}
			}
			updated = true;
			spiceState = spiceState == SpiceState.CHECK_WITHDRAW
				? SpiceState.CHECK_WITHDRAW_TWICE : SpiceState.NONE;
		}

		Widget messageBox = widget(InterfaceID.Messagebox.TEXT);
		if (messageBox != null && Objects.equals(messageBox.getText(), "Your spices have been stored."))
		{
			if (spiceState != SpiceState.CHECKED_DEPOSIT)
			{
				for (Map.Entry<Integer, Integer> e : invWatcher.removed.entrySet())
				{
					int[] info = spiceDoseInfo.get(e.getKey());
					if (info != null)
					{
						addSpiceDoses(info[0], info[1] * e.getValue());
					}
				}
				updated = true;
				spiceState = SpiceState.CHECKED_DEPOSIT;
			}
		}
		else if (spiceState == SpiceState.CHECKED_DEPOSIT)
		{
			spiceState = SpiceState.NONE;
		}

		if (messageBox != null)
		{
			Matcher m = SPICE_CHECK.matcher(messageBox.getText());
			if (m.matches())
			{
				int red = Integer.parseInt(m.group(1));
				int brown = Integer.parseInt(m.group(2));
				int yellow = Integer.parseInt(m.group(3));
				int orange = Integer.parseInt(m.group(4));
				if (red != spiceRed || brown != spiceBrown || yellow != spiceYellow || orange != spiceOrange)
				{
					spiceRed = red;
					spiceBrown = brown;
					spiceYellow = yellow;
					spiceOrange = orange;
					updated = true;
				}
			}
		}

		if (updated)
		{
			Map<Integer, Integer> items = new LinkedHashMap<>();
			items.put(ItemID.HUNDRED_DAVE_SPICE_RED_1, Math.max(0, spiceRed));
			items.put(ItemID.HUNDRED_DAVE_SPICE_BROWN_1, Math.max(0, spiceBrown));
			items.put(ItemID.HUNDRED_DAVE_SPICE_YELLOW_1, Math.max(0, spiceYellow));
			items.put(ItemID.HUNDRED_DAVE_SPICE_ORANGE_1, Math.max(0, spiceOrange));
			commit("playerownedhouse:spiceRack", items, null);
		}
	}
}
