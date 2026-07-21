package com.loadoutlab.ui;

import com.ironhub.ui.UiTokens;
import com.ironhub.ui.components.PaintedIcon;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.osrs.StoneButton;
import com.ironhub.ui.osrs.StoneCheckbox;
import com.ironhub.ui.osrs.StoneComboBoxUI;
import com.ironhub.ui.osrs.StonePanel;
import com.ironhub.ui.osrs.StoneProgressBar;
import com.ironhub.ui.osrs.StoneScrollBarUI;
import com.ironhub.ui.osrs.StoneTextField;
import com.loadoutlab.UsageLog;
import com.loadoutlab.data.GearItem;
import com.loadoutlab.data.GearSlot;
import com.loadoutlab.data.LoadoutData;
import com.loadoutlab.data.MonsterNotes;
import com.loadoutlab.data.MonsterStats;
import com.loadoutlab.data.SlayerLockedMonsters;
import com.loadoutlab.data.StatBlock;
import com.loadoutlab.data.WildernessMonsters;
import com.loadoutlab.engine.BlowpipeDarts;
import com.loadoutlab.engine.CombatStyle;
import com.loadoutlab.engine.DpsResult;
import com.loadoutlab.engine.DragonfireRules;
import com.loadoutlab.engine.IncomingDpsCalculator;
import com.loadoutlab.engine.Loadout;
import com.loadoutlab.engine.MonsterMechanics;
import com.loadoutlab.engine.PvpRisk;
import com.loadoutlab.engine.QuestRewardItems;
import com.loadoutlab.engine.SpecialAttack;
import com.loadoutlab.optimizer.OptimizerService.ModeTrade;
import com.loadoutlab.optimizer.OptimizerService.StyleResult;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.ImageUtil;

/**
 * v0.1 panel: search a monster, see your best OWNED set per combat style -
 * rendered as item icons like an equipment view - with exact DPS and how it
 * compares to the best set in the game.
 *
 * <p>EDT discipline: search is debounced, optimization runs off-thread via
 * OptimizerService, item images fill in asynchronously via ItemManager;
 * this panel only renders. All content is width-constrained - the sidebar
 * must never scroll horizontally.
 */
public class LoadoutLabPanel extends PluginPanel
{
	/**
	 * Iron Hub's stone-skin theme, set by LoadoutLabModule BEFORE the plugin
	 * builds this panel (and again before a theme-flip restart). The panel
	 * styles itself at construction from this value — the module restarts
	 * the lab on an osrsTheme change so a fresh panel re-clothes.
	 */
	private static volatile com.ironhub.ui.osrs.OsrsTheme ironHubTheme =
		com.ironhub.ui.osrs.OsrsTheme.STONE;

	public static void setIronHubTheme(com.ironhub.ui.osrs.OsrsTheme theme)
	{
		ironHubTheme = theme;
	}

	static com.ironhub.ui.osrs.OsrsTheme ironHubTheme()
	{
		return ironHubTheme;
	}

	/** Captured once per construction — the module rebuilds the panel on a
	 * theme flip, so construction-time styling is always current. */
	private final OsrsTheme theme = ironHubTheme();

	/** (monster, f2pOnly, onDone) - the plugin wires this to the optimizer.
	 * maxTradeables: wilderness kept-slot cap (-1 = unconstrained);
	 * riskBudgetGp: the total gp the set may drop on a PvP death. */
	public interface ComputeHook
	{
		void compute(MonsterStats monster, boolean f2pOnly, boolean onSlayerTask,
			String spellbookLock, int maxTradeables, int riskBudgetGp,
			boolean antifirePotion, int upgradeBudgetGp,
			com.loadoutlab.optimizer.OptimizerService.OptimizeMode mode, Runnable onDone);
	}

	/** Toggle an item's excluded state; returns true when now excluded. */
	public interface ExclusionToggle
	{
		boolean toggle(int itemId);
	}

	/** The current excluded item ids. */
	public interface ExclusionView
	{
		Set<Integer> snapshot();
	}

	/** Toggle an item's dream ("green") state; true when now dreamed. */
	public interface DreamToggle
	{
		boolean toggle(int itemId);
	}

	/** The current dream item ids. */
	public interface DreamView
	{
		Set<Integer> snapshot();
	}

	/** Toggle an item's "stored elsewhere" (manual owned) state. */
	public interface StoredToggle
	{
		boolean toggle(int itemId);
	}

	/** The current stored-elsewhere item ids. */
	public interface StoredView
	{
		Set<Integer> snapshot();
	}

	/** How many items the Dude Where's My Stuff integration contributed,
	 * and whether they came over the live PluginMessage link (as opposed
	 * to the best-effort config read). */
	public interface DwmsView
	{
		int count();

		boolean live();
	}

	/** Where an owned item lives, for tooltips and the source legend. */
	public interface LocationHint
	{
		/** One tooltip clause ("" = at hand or unknown). */
		String hint(int itemId);

		/** Legend label of the primary origin ("" = unknown). */
		String primary(int itemId);
	}

	/** Per-monster user profile: pins ("always bring this HERE"), a free
	 * note, and extra items unioned into the bank Show/Filter sets. Pins
	 * and filter items are scoped: "ALL" or a CombatStyle name - a super
	 * combat for the melee card, a ranged potion for the ranged card. */
	public interface MobProfile
	{
		/** Effective pins for one style card (ALL overlaid by the style). */
		Map<com.loadoutlab.data.GearSlot, Integer> pins(int monsterId, CombatStyle style);

		/** Raw pins by scope, for the manage menu. */
		Map<String, Map<com.loadoutlab.data.GearSlot, Integer>> allPins(int monsterId);

		void pin(int monsterId, String scope, com.loadoutlab.data.GearSlot slot, int itemId);

		void unpin(int monsterId, String scope, com.loadoutlab.data.GearSlot slot);

		String note(int monsterId);

		void setNote(int monsterId, String note);

		/** Effective filter-item ids for one style card (ALL + the style). */
		Set<Integer> filterItems(int monsterId, CombatStyle style);

		/** Raw filter items by scope: scope -> id -> add-time name. */
		Map<String, Map<Integer, String>> allFilterItems(int monsterId);

		void addFilterItem(int monsterId, String scope, int itemId, String name);

		void removeFilterItem(int monsterId, String scope, int itemId);
	}

	/** Open the native chatbox item search; the pick (id, name) returns
	 * on the EDT. The strange dialog matcher is gone (field request). */
	public interface ItemSearch
	{
		void search(String prompt, java.util.function.BiConsumer<Integer, String> onPicked);
	}

	/** The every-style pin/filter scope key. */
	public static final String ALL_SETS = "ALL";

	/** Does the player actually own this item (black set)? */
	public interface OwnedCheck
	{
		boolean owns(int itemId);
	}

	/** "Show in bank": set the highlighted item ids (null clears). */
	public interface BankHighlighter
	{
		void highlight(Set<Integer> itemIds);
	}

	/** "Filter bank": show only these item ids in the bank (null clears). */
	public interface BankFilter
	{
		void filter(Set<Integer> itemIds);
	}

	private static final int SEARCH_DEBOUNCE_MS = 150;
	private static final int SEARCH_LIMIT = 25;
	private static final int ICON_SIZE = 32;
	/** Discord invite for the plugin's community; opened from the header. */
	private static final String DISCORD_URL = "https://discord.gg/6GuS6J8em3";
	/** Grid display order: weapon beside shield, body beside legs. */
	private static final GearSlot[] GRID_ORDER = {
		GearSlot.HEAD, GearSlot.CAPE, GearSlot.NECK, GearSlot.AMMO,
		GearSlot.WEAPON, GearSlot.SHIELD, GearSlot.BODY, GearSlot.LEGS,
		GearSlot.HANDS, GearSlot.FEET, GearSlot.RING,
	};

	/** Iron Hub: the text palette maps onto the stone skin's global text
	 * tokens so the embedded lab reads like every other skinned tab. */
	private static final Color MUTED = OsrsSkin.MUTED;
	private static final Color GOOD = OsrsSkin.VALUE;
	private static final Color INFO = OsrsSkin.LABEL;
	private static final Color UNOWNED = new Color(110, 190, 110);
	private static final Color BORDER_UNOWNED = new Color(100, 145, 100);

	/** Source-dot palette (bottom-right cell corner + legend), display
	 * order. Separate vocabulary from the BORDERS (gold/green/blue).
	 * At-hand sources (equipped/inventory/bank) are deliberately absent:
	 * no palette entry = no dot and no legend row - only gear needing a
	 * fetch trip gets marked, so the grid stays quiet for bank-only sets. */
	private static final Map<String, Color> SOURCE_COLORS = new java.util.LinkedHashMap<>();
	static
	{
		SOURCE_COLORS.put("looting bag", new Color(180, 130, 80));
		SOURCE_COLORS.put("POH costume room", new Color(190, 130, 230));
		SOURCE_COLORS.put("STASH", new Color(230, 120, 120));
		SOURCE_COLORS.put("cargo hold", new Color(100, 200, 190));
		SOURCE_COLORS.put("stored elsewhere", new Color(180, 210, 110));
		SOURCE_COLORS.put("DWMS", new Color(160, 160, 210));
	}

	/** Sources that appear in the CURRENT results - the legend shows
	 * exactly these, never the full palette. */
	private final Set<String> usedSources = new java.util.LinkedHashSet<>();

	/** Cell border language: gold = your item IS the game best, blue = the
	 * spec cell (matches the in-game spec orb); plain/empty cells wear the
	 * theme's own engraved-edge greys (see iconGrid). */
	private static final Color BORDER_BIS = new Color(168, 148, 88);
	private static final Color BORDER_SPEC = new Color(120, 190, 240);

	private final LoadoutData data;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final UsageLog usageLog = UsageLog.defaultLog();
	private final ComputeHook computeHook;
	private final ExclusionToggle exclusionToggle;
	private final ExclusionView exclusionView;
	private final DreamToggle dreamToggle;
	private final DreamView dreamView;
	private final StoredToggle storedToggle;
	private final StoredView storedView;
	private final DwmsView dwmsView;
	private final LocationHint locationHint;
	private final MobProfile mobProfile;
	private final ItemSearch itemSearch;
	private final OwnedCheck ownedCheck;
	// Iron Hub: wired by the wrapper module (named setup save/load)
	private Runnable saveSetupHook;
	private Runnable loadSetupHook;
	private java.util.function.Function<com.loadoutlab.data.GearSlot, Integer> wornLookup;
	private DpsCalcExport dpsCalcHook;
	private Map<com.loadoutlab.data.GearSlot, Integer> lastShownLoadout;

	/** Iron Hub: open the wiki DPS calc mirroring the shown setup. */
	public interface DpsCalcExport
	{
		void open(int monsterId, String monsterName,
			Map<com.loadoutlab.data.GearSlot, Integer> loadout, boolean onSlayerTask);
	}

	public void setWornLookup(java.util.function.Function<com.loadoutlab.data.GearSlot, Integer> lookup)
	{
		wornLookup = lookup;
	}

	public void setDpsCalcHook(DpsCalcExport hook)
	{
		dpsCalcHook = hook;
	}


	/** Per-style card collapse: user override on top of the auto default
	 * (collapsed when a standard deviation under the best set's dps). */
	private final Map<CombatStyle, Boolean> cardCollapsed = new EnumMap<>(CombatStyle.class);
	private final Map<CombatStyle, Boolean> autoCollapsed = new EnumMap<>(CombatStyle.class);
	private final BankHighlighter bankHighlighter;
	private final BankFilter bankFilter;
	/** Which style's set is filtering the bank (null = none). */
	/** Which style's set is currently glowing in the bank (null = none). */
	/** Outline + filter the bank to the selected style's best set. */
	private final ToggleRow showInBank = new ToggleRow("Show in bank");
	/** D-4: which frontier point to recommend per style — three segment
	 *  buttons, not a dropdown (Luke, 2026-07-21). Built in the ctor
	 *  (needs the resolved theme). */
	private com.ironhub.ui.osrs.StoneChipRow optimizeMode;
	/** Free-form upgrade budget: "750k", "1m", "1.5b", plain gp, or "-"
	 * for max. Empty or unparseable = off. */
	private final JTextField upgradeBudget = new StoneTextField(ironHubTheme(), null);
	private int lastBudgetGp;
	private final JLabel exclusionsLabel = new JLabel();
	private final JLabel storedLabel = new JLabel();
	private final JLabel dwmsLabel = new JLabel();
	private final JLabel weaknessLabel = new JLabel();
	private final JLabel pinnedLabel = new JLabel();
	/** The user's own note for the selected monster: a collapsible
	 * post-it, edited inline (saves on focus loss - no edit button). */
	private final JPanel notePanel = new JPanel();
	private final JLabel noteHeader = new JLabel();
	private final javax.swing.JTextArea noteArea = new javax.swing.JTextArea();
	private boolean noteCollapsed = true;
	private static final Color POSTIT_BG = new Color(222, 212, 150);
	private static final Color POSTIT_FG = new Color(55, 50, 25);

	// Iron Hub: stone search field (sunken well, faint placeholder)
	private final JTextField searchField = new StoneTextField(ironHubTheme(), "Search a monster…");
	private final DefaultListModel<MonsterStats> monsterModel = new DefaultListModel<>();
	private final JList<MonsterStats> monsterList = new JList<>(monsterModel);
	private final JScrollPane monsterScroll;
	private final JPanel selectedRow = new JPanel(new BorderLayout(4, 0));
	private final JLabel selectedLabel = new JLabel();
	private final JLabel monsterNote = new JLabel();
	private final ToggleRow f2pOnly = new ToggleRow("Non-members gear only");
	private final ToggleRow slayerTask = new ToggleRow("On slayer task");
	private final JComboBox<String> spellbook =
		new JComboBox<>(new String[]{"Any spellbook", "Standard", "Ancient", "Arceuus"});
	private final JPanel resultsPanel = new JPanel();
	private final JLabel statusLabel = new JLabel(" ");
	private final Timer searchDebounce;

	/** Guards against programmatic search-field changes re-opening the list. */
	private boolean suppressSearchEvents;

	/** Iron Hub: master switch for wilderness-specific info (death risk, kept
	 *  items, risk caps). Many "wilderness" monsters also live elsewhere —
	 *  hellhounds, green dragons — so the info is OPT-IN per player, never
	 *  auto-shown just because the corpus flags the monster (Luke, 2026-07-21). */
	private final ToggleRow wildyInfo = new ToggleRow("In wilderness");
	private final ToggleRow lowRisk = new ToggleRow("Low-risk (wilderness)");
	private final ToggleRow protectItem = new ToggleRow("Protect Item (keep 4)");
	/** Wilderness risk-cap dropdown values in gp; 75k is the default. */
	private static final int[] RISK_STEPS = {0, 25_000, 75_000, 200_000, 1_000_000};
	private final JComboBox<String> riskBudget = new JComboBox<>(
		new String[]{"Risk cap: 0", "Risk cap: 25k", "Risk cap: 75k", "Risk cap: 200k", "Risk cap: 1M"});
	/** Dragonfire: gear protection by default; right-clicking the shield
	 * cell flips to an assumed super antifire (and back). */
	private boolean superAntifireAssumed;

	private MonsterStats selectedMonster;
	/** The style card currently being rendered (EDT-only render state) -
	 * grid cells read it for per-style pin menus and tooltips. */
	private CombatStyle renderingStyle;
	/** Per-style expanded game-best (BiS) sections - hidden by default,
	 * each card's header toggles only its own section. */
	private final Set<CombatStyle> gameBestExpanded = EnumSet.noneOf(CombatStyle.class);
	private Map<CombatStyle, StyleResult> lastResults;

	public LoadoutLabPanel(LoadoutData data, ItemManager itemManager,
		SpriteManager spriteManager, ComputeHook computeHook,
		ExclusionToggle exclusionToggle, ExclusionView exclusionView,
		DreamToggle dreamToggle, DreamView dreamView,
		StoredToggle storedToggle, StoredView storedView, DwmsView dwmsView,
		LocationHint locationHint, MobProfile mobProfile, ItemSearch itemSearch,
		OwnedCheck ownedCheck,
		BankHighlighter bankHighlighter, BankFilter bankFilter)
	{
		this.bankHighlighter = bankHighlighter;
		this.bankFilter = bankFilter;
		this.data = data;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.computeHook = computeHook;
		this.exclusionToggle = exclusionToggle;
		this.exclusionView = exclusionView;
		this.dreamToggle = dreamToggle;
		this.dreamView = dreamView;
		this.storedToggle = storedToggle;
		this.storedView = storedView;
		this.dwmsView = dwmsView;
		this.locationHint = locationHint;
		this.mobProfile = mobProfile;
		this.itemSearch = itemSearch;
		this.ownedCheck = ownedCheck;

		setLayout(new BorderLayout(0, 6));
		setBackground(theme.background);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);

		JLabel title = new JLabel("Loadout Lab");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));

		// Header row: title left, an "Options" menu right (Discord, and
		// future plugin-wide actions) - mirrors the Goal Planner header.
		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		header.add(title, BorderLayout.WEST);
		JButton optionsButton = new JButton(new DotsIcon(13));
		optionsButton.setToolTipText("Options");
		optionsButton.setMargin(new Insets(2, 6, 2, 6));
		optionsButton.addActionListener(e ->
		{
			JPopupMenu menu = new JPopupMenu();
			// Entry point for the first stored-elsewhere item (before any
			// exists there is no label or right-click row to reach it from).
			JMenuItem addStored = new JMenuItem("Add a stored-elsewhere item...");
			addStored.addActionListener(ev -> showAddStoredDialog());
			menu.add(addStored);
			// Mob-specific actions live on the style cards and the
			// "This mob" line - the header menu stays plugin-wide.
			JMenuItem joinDiscord = new JMenuItem("Join our Discord");
			joinDiscord.addActionListener(ev -> LinkBrowser.browse(DISCORD_URL));
			menu.add(joinDiscord);
			menu.show(optionsButton, 0, optionsButton.getHeight());
		});
		header.add(optionsButton, BorderLayout.EAST);
		// Iron Hub: title + Options header dropped (module nav header covers it)
		top.add(Box.createVerticalStrut(4));

		searchField.setAlignmentX(LEFT_ALIGNMENT);
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		// Iron Hub (Luke, 2026-07-21): the search field + its dropdown live
		// in searchHolder, and the monster card + toggles + bank actions in
		// monsterHolder — both MOUNTED by the wrapper below the shared stat
		// tile so they show in BOTH views
		searchHolder.setLayout(new BoxLayout(searchHolder, BoxLayout.Y_AXIS));
		searchHolder.setOpaque(false);
		searchHolder.setAlignmentX(LEFT_ALIGNMENT);
		searchHolder.add(searchField);
		monsterHolder.setLayout(new BoxLayout(monsterHolder, BoxLayout.Y_AXIS));
		monsterHolder.setOpaque(false);
		monsterHolder.setAlignmentX(LEFT_ALIGNMENT);
		top.add(Box.createVerticalStrut(4));

		// Selected-monster row: replaces the dropdown once a pick is made.
		selectedRow.setOpaque(false);
		selectedRow.setAlignmentX(LEFT_ALIGNMENT);
		selectedRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		// Iron Hub: the target reads as the screen title — game title orange,
		// with compact hover-glyph affordances instead of chunky JButtons
		OsrsSkin.crisp(selectedLabel);
		selectedLabel.setForeground(OsrsSkin.TITLE);
		selectedLabel.setFont(OsrsSkin.boldFont());
		selectedLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
		selectedRow.add(selectedLabel, BorderLayout.CENTER);
		// lastShownLoadout resets each recompute via the results rebuild
		JPanel selectedButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		selectedButtons.setOpaque(false);
		selectedButtons.add(glyphButton(new ReloadIcon(11), null,
			"Re-run the search for this monster", this::recompute));
		selectedButtons.add(glyphButton(null, "×",
			"Choose a different monster", this::clearSelection));
		selectedRow.add(selectedButtons, BorderLayout.EAST);
		selectedRow.setVisible(false);
		monsterHolder.add(selectedRow);
		OsrsSkin.crisp(weaknessLabel);
		weaknessLabel.setForeground(OsrsSkin.VALUE);
		weaknessLabel.setFont(OsrsSkin.font());
		weaknessLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
		weaknessLabel.setAlignmentX(LEFT_ALIGNMENT);
		weaknessLabel.setVisible(false);
		monsterHolder.add(weaknessLabel);

		// Curated mechanics note (finishing items, immunities) for the
		// selected monster - so a correct suggestion doesn't look wrong.
		// html-wrapped prose keeps the LAF font (the pixel font and html
		// measurement disagree and clip mid-word); only the colour maps.
		monsterNote.setForeground(OsrsSkin.MUTED);
		monsterNote.setFont(monsterNote.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		monsterNote.setAlignmentX(LEFT_ALIGNMENT);
		monsterNote.setVisible(false);
		monsterHolder.add(monsterNote);

		monsterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		monsterList.setVisibleRowCount(6);
		monsterList.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value,
				int index, boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				setText(((MonsterStats) value).label());
				// crisp + top ink headroom: the pixel font floats high in its
				// em box and antialiases under the LAF's own hint otherwise
				OsrsSkin.crisp(this);
				setBorder(new EmptyBorder(2, 4, 1, 4));
				return this;
			}
		});
		// Iron Hub: sunken stone list matching the search field
		OsrsSkin.crisp(monsterList);
		monsterList.setBackground(theme.recess);
		monsterList.setForeground(OsrsSkin.MUTED);
		monsterList.setSelectionBackground(theme.selectFill);
		monsterList.setSelectionForeground(OsrsSkin.TITLE);
		monsterList.setFont(OsrsSkin.font());
		monsterScroll = new JScrollPane(monsterList);
		monsterScroll.getViewport().setBackground(theme.recess);
		monsterScroll.setBorder(new MatteBorder(1, 1, 1, 1, theme.edgeDark));
		StoneScrollBarUI.skin(monsterScroll.getVerticalScrollBar(), theme);
		monsterScroll.setPreferredSize(new Dimension(0, 130));
		monsterScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
		monsterScroll.setAlignmentX(LEFT_ALIGNMENT);
		monsterScroll.setVisible(false);
		searchHolder.add(monsterScroll);

		initToggle(f2pOnly, "Only consider free-to-play gear");
		f2pOnly.setVisible(false); // only shown on non-members worlds
		monsterHolder.add(f2pOnly);

		initToggle(slayerTask, "On task: slayer helmet bonuses apply");
		slayerTask.setSelected(true); // Iron Hub: assume on-task by default
		monsterHolder.add(slayerTask);

		// Wilderness only: everything below is OPT-IN behind this switch —
		// fighting the same monster outside the wilderness is the norm
		initToggle(wildyInfo, "Fighting this monster IN the wilderness: show"
			+ " death risk, kept items and risk caps");
		wildyInfo.setVisible(false);
		monsterHolder.add(wildyInfo);

		// Wilderness only: cap the set to the items death mechanics keep.
		initToggle(lowRisk, "Keep your 3 most valuable items (4 with Protect Item);"
			+ " everything else must total under the risk cap");
		lowRisk.setVisible(false);
		monsterHolder.add(lowRisk);

		initToggle(protectItem, "Protect Item keeps a 4th item (not while skulled)");
		protectItem.setVisible(false);
		monsterHolder.add(protectItem);

		// How much gp the set may drop on a wilderness death; 0 = nothing
		// droppable and no fees at all.
		StoneComboBoxUI.skin(riskBudget, theme);
		riskBudget.setAlignmentX(LEFT_ALIGNMENT);
		riskBudget.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		riskBudget.setToolTipText("Total gp the set may drop on a wilderness death");
		riskBudget.setSelectedIndex(2);
		riskBudget.addActionListener(e -> recompute());
		riskBudget.setVisible(false);
		monsterHolder.add(riskBudget);


		// Lock the magic card's auto-spell to one spellbook.
		spellbook.setAlignmentX(LEFT_ALIGNMENT);
		spellbook.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		spellbook.setToolTipText("Limit spells to one spellbook (powered staves always considered)");
		spellbook.addActionListener(e -> recompute());
		// Iron Hub: spellbook selector moves below the results (see bottomControls)

		// Buyable upgrades within a total gp budget join the consideration
		// pool (dream items are the manual version, via right-click).
		JPanel budgetRow = new JPanel(new BorderLayout(4, 0));
		budgetRow.setOpaque(false);
		budgetRow.setAlignmentX(LEFT_ALIGNMENT);
		budgetRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		JLabel budgetLabel = new JLabel("Upgrade budget:");
		budgetLabel.setForeground(new Color(200, 200, 200));
		budgetLabel.setFont(budgetLabel.getFont().deriveFont(UiTokens.FONT_SIZE_BODY));
		budgetRow.add(budgetLabel, BorderLayout.WEST);
		upgradeBudget.setToolTipText("Buyable-gear budget: 750k, 1m, 1.5b; - sets unlimited; empty = 0 (owned gear only, default)");
		upgradeBudget.addActionListener(e -> budgetEdited());
		upgradeBudget.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				budgetEdited();
			}
		});
		budgetRow.add(upgradeBudget, BorderLayout.CENTER);
		// Iron Hub: upgrade-budget row dropped per user direction

		// D-4: pick the offense/defense frontier point (sweep is slower).
		optimizeMode = new com.ironhub.ui.osrs.StoneChipRow(theme, true, "DPS", "Balanced", "Tank");
		optimizeMode.setAlignmentX(LEFT_ALIGNMENT);
		optimizeMode.setToolTipText("Balanced/Tank trade dps for less damage taken");
		optimizeMode.onChange(i -> recompute());
		// Iron Hub: optimize selector moves below the results (see bottomControls)

		// Excluded items ("protected" from suggestions) - click to manage.
		// Semantic warning colour survives the skin; chrome goes stone.
		OsrsSkin.crisp(exclusionsLabel);
		exclusionsLabel.setForeground(UiTokens.STATUS_WARNING);
		exclusionsLabel.setFont(OsrsSkin.font());
		exclusionsLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
		exclusionsLabel.setAlignmentX(LEFT_ALIGNMENT);
		exclusionsLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		exclusionsLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showExclusionsMenu(e);
			}
		});
		top.add(exclusionsLabel);
		refreshExclusionsLabel();

		// Stored-elsewhere items (manual owned: STASH, POH, UIM storages).
		OsrsSkin.crisp(storedLabel);
		storedLabel.setForeground(GOOD);
		storedLabel.setFont(OsrsSkin.font());
		storedLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
		storedLabel.setAlignmentX(LEFT_ALIGNMENT);
		storedLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		storedLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showStoredMenu(e);
			}
		});
		top.add(storedLabel);
		refreshStoredLabel();

		// Provenance line for the Dude Where's My Stuff import - shows the
		// import is working (and how much gear came in that way).
		dwmsLabel.setForeground(MUTED);
		dwmsLabel.setFont(dwmsLabel.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		dwmsLabel.setAlignmentX(LEFT_ALIGNMENT);
		// Iron Hub: DWMS contribution line dropped per user direction
		refreshDwmsLabel();

		// The mob's post-it note: collapsible, edited inline (saves when
		// focus leaves the text area). Lives under the DWMS line.
		notePanel.setLayout(new BoxLayout(notePanel, BoxLayout.Y_AXIS));
		notePanel.setBackground(POSTIT_BG);
		notePanel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		notePanel.setAlignmentX(LEFT_ALIGNMENT);
		noteHeader.setForeground(POSTIT_FG);
		noteHeader.setFont(noteHeader.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_BODY));
		noteHeader.setAlignmentX(LEFT_ALIGNMENT);
		noteHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		noteHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				saveNoteIfChanged();
				noteCollapsed = !noteCollapsed;
				refreshNotePanel();
			}
		});
		noteArea.setLineWrap(true);
		noteArea.setWrapStyleWord(true);
		noteArea.setRows(3);
		noteArea.setBackground(POSTIT_BG);
		noteArea.setForeground(POSTIT_FG);
		noteArea.setCaretColor(POSTIT_FG);
		noteArea.setFont(noteArea.getFont().deriveFont(UiTokens.FONT_SIZE_SECONDARY));
		noteArea.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		noteArea.setAlignmentX(LEFT_ALIGNMENT);
		noteArea.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				saveNoteIfChanged();
				refreshNotePanel();
			}
		});
		notePanel.add(noteHeader);
		notePanel.add(noteArea);
		notePanel.setVisible(false);
		// Iron Hub: the personal note editor is dropped from the embedded panel

		// Pinned items ("always bring") - click to manage.
		OsrsSkin.crisp(pinnedLabel);
		pinnedLabel.setForeground(OsrsSkin.FAINT);
		pinnedLabel.setFont(OsrsSkin.font());
		pinnedLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
		pinnedLabel.setAlignmentX(LEFT_ALIGNMENT);
		pinnedLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		pinnedLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showPinnedMenu(e);
			}
		});
		top.add(pinnedLabel);
		refreshPinnedLabel();

		add(top, BorderLayout.NORTH);

		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setOpaque(false);
		// Iron Hub: no inner scroll pane - the module shell already wraps
		// every tab in a HubScrollPane, and nesting a second scroller made
		// the wheel stick at the inner pane's limits
		// Iron Hub: grouped options block under the results - a labelled
		// ASSUMPTIONS section (boost + spellbook icon rows, centred like the
		// gear grids), then the optimize mode, then the action buttons. Every
		// child is LEFT-aligned: the old mix of alignments drifted rows right.
		JPanel bottomControls = new JPanel();
		bottomControls.setLayout(new BoxLayout(bottomControls, BoxLayout.Y_AXIS));
		bottomControls.setOpaque(false);
		bottomControls.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		OsrsLabel assumptions = new OsrsLabel("Options",
			OsrsSkin.TITLE, OsrsSkin.font()).leftAligned();
		assumptions.setToolTipText("Prayers, potions and spellbook the DPS numbers may assume");
		bottomControls.add(assumptions);
		bottomControls.add(Box.createVerticalStrut(4));
		styleButtonsHolder.setLayout(new BoxLayout(styleButtonsHolder, BoxLayout.Y_AXIS));
		styleButtonsHolder.setOpaque(false);
		styleButtonsHolder.setAlignmentX(LEFT_ALIGNMENT);
		bottomControls.add(styleButtonsHolder);
		bottomControls.add(Box.createVerticalStrut(4));
		JPanel spellRow = new JPanel(new GridLayout(1, 3, 2, 0));
		spellRow.setOpaque(false);
		spellRow.add(spellbookIcon("standard.png", "Standard spellbook", 1));
		spellRow.add(spellbookIcon("ancient.png", "Ancient spellbook", 2));
		spellRow.add(spellbookIcon("arceuus.png", "Arceuus spellbook", 3));
		JPanel boostRow = new JPanel(new GridLayout(1, 5, 2, 0));
		boostRow.setOpaque(false);
		boostRow.add(iconToggle(net.runelite.api.SpriteID.PRAYER_PIETY,
			"Melee prayer (Piety line)", false, on ->
			{
				com.loadoutlab.engine.PrayerBonuses.MELEE_PRAYER = on;
				recompute();
			}));
		boostRow.add(iconToggle(net.runelite.api.SpriteID.PRAYER_RIGOUR,
			"Ranged prayer (Rigour line)", false, on ->
			{
				com.loadoutlab.engine.PrayerBonuses.RANGED_PRAYER = on;
				recompute();
			}));
		boostRow.add(iconToggle(net.runelite.api.SpriteID.PRAYER_AUGURY,
			"Magic prayer (Augury line)", false, on ->
			{
				com.loadoutlab.engine.PrayerBonuses.MAGIC_PRAYER = on;
				recompute();
			}));
		boostRow.add(itemToggle(12695, "Combat potions", false, on ->
			{
				com.loadoutlab.optimizer.BoostSelector.POTIONS_ASSUMED = on;
				recompute();
			}));
		boostRow.add(itemToggle(20724, "Imbued/saturated heart", false, on ->
			{
				com.loadoutlab.optimizer.BoostSelector.HEART_ASSUMED = on;
				recompute();
			}));
		bottomControls.add(optimizeMode);
		bottomControls.add(Box.createVerticalStrut(6));
		bottomControls.add(centeredRow(boostRow, 5 * 36 + 8, 36));
		bottomControls.add(Box.createVerticalStrut(2));
		bottomControls.add(centeredRow(spellRow, 3 * 36 + 4, 36));
		bottomControls.add(Box.createVerticalStrut(6));
		// Show-in-bank is a CHECKBOX that both outlines AND filters the bank
		// (the separate Filter-bank button is gone — filtered is the default;
		// Luke, round 5), with the wiki-calc link beside it
		initToggle(showInBank, "While the bank is open: outline this set's items"
			+ " and filter the bank to them (needs Bank Tags enabled)");
		showInBank.onToggle(this::applyShowInBank);
		JPanel bankOpenRow = new JPanel(new GridLayout(1, 2, 4, 0));
		bankOpenRow.setOpaque(false);
		bankOpenRow.setAlignmentX(LEFT_ALIGNMENT);
		bankOpenRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		bankOpenRow.add(showInBank);
		bankOpenRow.add(openDpsCalcButton());
		bottomControls.add(bankOpenRow);
		bottomControls.add(Box.createVerticalStrut(4));
		// NORTH-anchor so spare height stays empty instead of stretching cards
		JPanel resultsAnchor = new JPanel(new BorderLayout());
		resultsAnchor.setOpaque(false);
		resultsAnchor.add(resultsPanel, BorderLayout.NORTH);
		JPanel centerWrap = new JPanel(new BorderLayout());
		centerWrap.setOpaque(false);
		centerWrap.add(resultsAnchor, BorderLayout.CENTER);
		centerWrap.add(bottomControls, BorderLayout.SOUTH);
		add(centerWrap, BorderLayout.CENTER);

		OsrsSkin.crisp(statusLabel);
		statusLabel.setForeground(OsrsSkin.FAINT);
		statusLabel.setFont(OsrsSkin.font());
		statusLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
		add(statusLabel, BorderLayout.SOUTH);

		searchDebounce = new Timer(SEARCH_DEBOUNCE_MS, e -> runSearch());
		searchDebounce.setRepeats(false);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e) { onSearchEdited(); }
			public void removeUpdate(DocumentEvent e) { onSearchEdited(); }
			public void changedUpdate(DocumentEvent e) { onSearchEdited(); }
		});
		monsterList.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting() && monsterList.getSelectedValue() != null)
			{
				select(monsterList.getSelectedValue());
			}
		});

		statusLabel.setText("Search a monster to begin.");
	}

	/** Shared toggle chrome (tooltip only — the row itself recomputes on a
	 * user press; programmatic setSelected never fires, like JCheckBox). */
	private void initToggle(ToggleRow row, String tooltip)
	{
		row.setToolTipText(tooltip);
	}

	/**
	 * Stone checkbox + label row: the whole row is the hit target and a
	 * user press recomputes (StoneChecklist.Row grammar — hand layout so
	 * the odd-sized box and the pixel font's ink both center exactly).
	 */
	private final class ToggleRow extends JPanel
	{
		private static final int PAD = 1;
		private static final int GAP = 6;

		private final StoneCheckbox box;
		private final OsrsLabel label;
		private Runnable onToggle; // null = the default recompute

		void onToggle(Runnable action)
		{
			onToggle = action;
		}

		ToggleRow(String text)
		{
			box = new StoneCheckbox(ironHubTheme(), false);
			label = new OsrsLabel(text, OsrsSkin.LABEL, OsrsSkin.font()).leftAligned();
			setLayout(null);
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			add(box);
			add(label);
			MouseAdapter clicks = new MouseAdapter()
			{
				// mousePressed, never mouseClicked — clicked drops the event
				// if the pointer drifts a pixel between press and release
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (!isEnabled())
					{
						return;
					}
					box.setChecked(!box.isChecked());
					if (onToggle != null)
					{
						onToggle.run();
					}
					else
					{
						recompute();
					}
				}
			};
			// tooltips register the children's own mouse listeners, which
			// would swallow the row's — every part carries the handler
			addMouseListener(clicks);
			label.addMouseListener(clicks);
			box.addMouseListener(clicks);
		}

		boolean isSelected()
		{
			return box.isChecked();
		}

		void setSelected(boolean selected)
		{
			box.setChecked(selected);
		}

		@Override
		public void setEnabled(boolean enabled)
		{
			super.setEnabled(enabled);
			label.setColor(enabled ? OsrsSkin.LABEL : OsrsSkin.FAINT);
			setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
				: Cursor.getDefaultCursor());
		}

		@Override
		public void setToolTipText(String text)
		{
			super.setToolTipText(text);
			label.setToolTipText(text);
			box.setToolTipText(text);
		}

		@Override
		public void doLayout()
		{
			int h = getHeight();
			Dimension bp = box.getPreferredSize();
			box.setBounds(PAD, (h - bp.height) / 2, bp.width, bp.height);
			int x = PAD + bp.width + GAP;
			label.setBounds(x, 0, Math.max(0, getWidth() - PAD - x), h);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(0, Math.max(box.getPreferredSize().height + 2 * PAD,
				label.getPreferredSize().height));
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** One info line in the game font - the shape every card row shares. */
	private static JLabel line(String text, Color fg)
	{
		JLabel line = new JLabel(text);
		OsrsSkin.crisp(line);
		line.setForeground(fg);
		line.setFont(OsrsSkin.font());
		// vertical ink headroom: the pixel font clips glyph bottoms in
		// tight JLabels without it
		line.setBorder(new EmptyBorder(2, 0, 2, 0));
		line.setAlignmentX(LEFT_ALIGNMENT);
		return line;
	}

	/** Compact hover-glyph affordance: a painted icon or text glyph that
	 * brightens under the pointer; mousePressed fires. */
	private static JLabel glyphButton(javax.swing.Icon icon, String text,
		String tooltip, Runnable onClick)
	{
		JLabel glyph = icon != null ? new JLabel(icon) : new JLabel(text);
		OsrsSkin.crisp(glyph);
		glyph.setHorizontalAlignment(SwingConstants.CENTER);
		glyph.setFont(OsrsSkin.boldFont());
		glyph.setForeground(OsrsSkin.FAINT);
		glyph.setToolTipText(tooltip);
		Dimension size = new Dimension(18, 18);
		glyph.setPreferredSize(size);
		glyph.setMinimumSize(size);
		glyph.setMaximumSize(size);
		glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		glyph.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				glyph.setForeground(OsrsSkin.LABEL);
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
			}
		});
		return glyph;
	}

	/** Iron Hub: pin a fixed-size component and centre it in a full-width
	 * row, echoing how the gear grids sit in the panel. */
	private static JPanel centeredRow(javax.swing.JComponent inner, int width, int height)
	{
		Dimension size = new Dimension(width, height);
		inner.setPreferredSize(size);
		inner.setMinimumSize(size);
		inner.setMaximumSize(size);
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		row.add(Box.createHorizontalGlue());
		row.add(inner);
		row.add(Box.createHorizontalGlue());
		return row;
	}

	/**
	 * Set by the plugin on login. On a non-members world the filter appears
	 * and defaults on; on a members world it is hidden and forced off - the
	 * toggle only earns panel space where it can matter.
	 */
	public void setF2pWorld(boolean f2pWorld)
	{
		f2pOnly.setVisible(f2pWorld);
		if (f2pOnly.isSelected() != f2pWorld)
		{
			f2pOnly.setSelected(f2pWorld);
			recompute();
		}
		revalidate();
		repaint();
	}

	public boolean isF2pOnly()
	{
		return f2pOnly.isSelected();
	}

	private void onSearchEdited()
	{
		if (!suppressSearchEvents)
		{
			searchDebounce.restart();
		}
	}

	private void runSearch()
	{
		String query = searchField.getText().trim();
		monsterModel.clear();
		if (query.length() < 2)
		{
			monsterScroll.setVisible(false);
			revalidate();
			return;
		}
		for (MonsterStats m : data.searchMonsters(query, SEARCH_LIMIT))
		{
			monsterModel.addElement(m);
		}
		// the dropdown is only as tall as its results (Luke, 2026-07-21):
		// one hit = one row, capped at 6 with the stone scrollbar beyond
		if (!monsterModel.isEmpty())
		{
			monsterList.setVisibleRowCount(Math.min(monsterModel.size(), 6));
			int height = monsterList.getPreferredScrollableViewportSize().height + 2; // matte border
			monsterScroll.setPreferredSize(new Dimension(0, height));
			monsterScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		}
		monsterScroll.setVisible(!monsterModel.isEmpty());
		statusLabel.setText(monsterModel.isEmpty() ? "No monsters match." : " ");
		revalidate();
		repaint();
	}

	/**
	 * External link-in (cross-plugin PluginMessage): select a monster by
	 * npc id or display name. Name matching reuses the search's
	 * punctuation-insensitive normalization. EDT only. Returns success.
	 */
	public void setSetupHooks(Runnable onSave, Runnable onLoad)
	{
		saveSetupHook = onSave;
		loadSetupHook = onLoad;
	}

	// ── Iron Hub shared-viewer seam (Luke, 2026-07-21): the wrapper owns the
	// gear viewer, stat tile and style buttons; the panel publishes results
	// and renders the selected style's detail card only. ──

	public interface ResultsListener
	{
		void onResults(MonsterStats monster, Map<CombatStyle, StyleResult> results);

		void onCleared();
	}

	private final JPanel searchHolder = new JPanel();

	private final JPanel monsterHolder = new JPanel();
	private final JPanel styleButtonsHolder = new JPanel();
	/** Wrapper-provided async monster icon fetch (wiki art, user-initiated). */
	private java.util.function.BiConsumer<MonsterStats,
		java.util.function.Consumer<java.awt.Image>> monsterIconLookup;

	/** The monster search (field + fitted dropdown) for the wrapper to mount. */
	public javax.swing.JComponent searchArea()
	{
		return searchHolder;
	}

	/** The monster card + toggles + bank actions, for the wrapper to mount. */
	public javax.swing.JComponent monsterArea()
	{
		return monsterHolder;
	}

	public void setMonsterIconLookup(java.util.function.BiConsumer<MonsterStats,
		java.util.function.Consumer<java.awt.Image>> lookup)
	{
		monsterIconLookup = lookup;
	}

	/** The Options section's top slot: the wrapper's style buttons mount
	 *  here, above the optimize mode and the icon rows (Luke, round 4). */
	public javax.swing.JPanel styleButtonsSlot()
	{
		return styleButtonsHolder;
	}

	private ResultsListener resultsListener;
	private CombatStyle detailStyle;

	public void setResultsListener(ResultsListener listener)
	{
		resultsListener = listener;
	}

	/** The gear corpus, for the wrapper's live-gear stat sums. */
	public LoadoutData data()
	{
		return data;
	}

	/** The wrapper's style buttons route here: the detail card follows. */
	public void setDetailStyle(CombatStyle style)
	{
		detailStyle = style;
		if (selectedMonster != null && lastResults != null)
		{
			showResults(selectedMonster, lastResults);
			revalidate();
			repaint();
		}
	}

	/** The strongest style by your best owned set's dps (MELEE when none). */
	private static CombatStyle bestStyle(Map<CombatStyle, StyleResult> results)
	{
		CombatStyle best = CombatStyle.MELEE;
		double bestDps = -1;
		for (CombatStyle style : CombatStyle.concreteValues())
		{
			StyleResult r = results.get(style);
			if (r != null && r.owned != null && !r.owned.isEmpty()
				&& r.owned.get(0).getDps() > bestDps)
			{
				bestDps = r.owned.get(0).getDps();
				best = style;
			}
		}
		return best;
	}

	public boolean selectExternal(String monsterName, Integer npcId)
	{
		if (npcId != null)
		{
			for (MonsterStats m : data.getMonsters())
			{
				if (m.getId() == npcId)
				{
					select(m);
					return true;
				}
			}
		}
		if (monsterName != null && !monsterName.isEmpty())
		{
			List<MonsterStats> hits = data.searchMonsters(monsterName, 1);
			// Sender labels often carry qualifiers the corpus doesn't -
			// "Doom of Mokhaiotl (L3)", "Duke (Awake)" - retry without them.
			int paren = monsterName.indexOf(" (");
			if (hits.isEmpty() && paren > 0)
			{
				hits = data.searchMonsters(monsterName.substring(0, paren), 1);
			}
			// Slayer assignments are PLURAL category names ("Dust devils",
			// "Wolves") while the corpus is singular - without these retries
			// a fresh task only FILLED the search box instead of running the
			// calc (Luke, 2026-07-21).
			for (String singular : singularForms(paren > 0
				? monsterName.substring(0, paren) : monsterName))
			{
				if (!hits.isEmpty())
				{
					break;
				}
				hits = data.searchMonsters(singular, 1);
			}
			if (!hits.isEmpty())
			{
				select(hits.get(0));
				return true;
			}
			// No match: surface the query in the search box so the caller's
			// click visibly did something instead of nothing.
			searchField.setVisible(true);
			searchField.setText(monsterName);
		}
		return false;
	}

	/** Singular retries for plural task names, most-specific first:
	 *  wolves -> wolf, harpies -> harpy, spectres -> spectre, devils -> devil. */
	private static List<String> singularForms(String name)
	{
		List<String> forms = new java.util.ArrayList<>();
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (lower.endsWith("ves"))
		{
			forms.add(name.substring(0, name.length() - 3) + "f");
		}
		if (lower.endsWith("ies"))
		{
			forms.add(name.substring(0, name.length() - 3) + "y");
		}
		if (lower.endsWith("es"))
		{
			forms.add(name.substring(0, name.length() - 2));
		}
		if (lower.endsWith("s"))
		{
			forms.add(name.substring(0, name.length() - 1));
		}
		return forms;
	}

	/** A pick: collapse the dropdown, show the selection, clear the query. */
	private void select(MonsterStats monster)
	{
		suppressSearchEvents = true;
		try
		{
			searchField.setText("");
		}
		finally
		{
			suppressSearchEvents = false;
		}
		monsterModel.clear();
		monsterScroll.setVisible(false);
		// the search field stays visible at all times (Luke, round 4)
		selectedMonster = monster;
		selectedLabel.setIcon(null);
		if (monsterIconLookup != null)
		{
			final MonsterStats requested = monster;
			monsterIconLookup.accept(monster, image ->
			{
				if (selectedMonster == requested && image != null)
				{
					selectedLabel.setIcon(new ImageIcon(
						image.getScaledInstance(-1, 20, java.awt.Image.SCALE_SMOOTH)));
					selectedLabel.setIconTextGap(6);
				}
			});
		}
		else
		{
			selectedLabel.setIcon(null);
		}
		boolean wilderness = WildernessMonsters.isWilderness(monster);
		wildyInfo.setVisible(wilderness);
		updateWildernessControls();
		superAntifireAssumed = false; // each monster starts on gear protection
		applyShowInBank(); // no results yet for this monster: aids clear
		// The slayer toggle has three states by monster: task-only bosses
		// (Hydra, Araxxor, Sire...) force it ON - you cannot fight them
		// off-task; unassignable monsters (raid bosses) force it OFF; and
		// everything else leaves it to the player.
		if (SlayerLockedMonsters.isTaskOnly(monster))
		{
			slayerTask.setSelected(true);
			slayerTask.setEnabled(false);
			slayerTask.setVisible(true);
			slayerTask.setToolTipText("Task-only boss - always on");
		}
		else if (!monster.isSlayerMonster())
		{
			// not assignable: the greyed row was noise — hide it (Luke)
			slayerTask.setSelected(false);
			slayerTask.setVisible(false);
		}
		else
		{
			slayerTask.setEnabled(true);
			slayerTask.setVisible(true);
			slayerTask.setToolTipText("On task: slayer helmet bonuses apply");
		}
		usageLog.record(monster.label());
		selectedLabel.setText(monster.label());
		selectedRow.setVisible(true);
		// Iron Hub: elemental weakness on its own line so it never truncates
		if (monster.getWeaknessElement().isEmpty())
		{
			weaknessLabel.setVisible(false);
		}
		else
		{
			weaknessLabel.setText("+" + monster.getWeaknessSeverity() + "% weak to "
				+ monster.getWeaknessElement() + " spells");
			weaknessLabel.setVisible(true);
		}
		String note = MonsterNotes.noteFor(monster);
		monsterNote.setText(note == null ? "" : "<html>" + note + "</html>");
		monsterNote.setVisible(note != null);
		// A new mob: fresh collapse defaults, its own note state.
		cardCollapsed.clear();
		noteCollapsed = mobProfile.note(monster.getId()).isEmpty();
		refreshNotePanel();
		refreshPinnedLabel();
		revalidate();
		repaint();
		recompute();
	}

	/** The wilderness tradeable cap, or -1 when the mode is off/hidden. */
	private int riskCap()
	{
		if (!lowRisk.isVisible() || !lowRisk.isSelected())
		{
			return -1;
		}
		return protectItem.isSelected() ? 4 : 3;
	}

	/** The selected wilderness risk budget in gp. */
	private int selectedRiskBudget()
	{
		return RISK_STEPS[riskBudget.getSelectedIndex()];
	}

	/** Recompute only when the parsed budget actually changed. */
	private void budgetEdited()
	{
		int parsed = parsedBudgetGp();
		if (parsed != lastBudgetGp)
		{
			lastBudgetGp = parsed;
			recompute();
		}
	}

	/**
	 * "750k" / "1m" / "2.5b" / "1000000" -> gp; "-" -> max (clamped to
	 * 2b; the optimizer saturates cost sums); empty/junk -> 0 (off).
	 */
	private int parsedBudgetGp()
	{
		String raw = upgradeBudget.getText() == null ? "" : upgradeBudget.getText().trim().toLowerCase();
		if (raw.isEmpty())
		{
			return 0;
		}
		if (raw.equals("-") || raw.equals("max"))
		{
			return 2_000_000_000;
		}
		double multiplier = 1;
		if (raw.endsWith("k") || raw.endsWith("m") || raw.endsWith("b"))
		{
			multiplier = raw.endsWith("k") ? 1_000 : raw.endsWith("m") ? 1_000_000 : 1_000_000_000;
			raw = raw.substring(0, raw.length() - 1);
		}
		try
		{
			double value = Double.parseDouble(raw) * multiplier;
			return (int) Math.max(0, Math.min(value, 2_000_000_000));
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private String spellbookLock()
	{
		int index = spellbook.getSelectedIndex();
		return index <= 0 ? "" : ((String) spellbook.getSelectedItem()).toLowerCase();
	}

	private void refreshExclusionsLabel()
	{
		int count = exclusionView.snapshot().size();
		exclusionsLabel.setText(count == 0 ? "" : "Excluded items: " + count + " (click to manage)");
		exclusionsLabel.setVisible(count > 0);
	}

	private void showExclusionsMenu(MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		for (Integer id : exclusionView.snapshot())
		{
			GearItem item = data.getGear(id);
			String label = item == null ? ("item " + id) : item.label();
			JMenuItem entry = new JMenuItem("Allow again: " + label);
			entry.addActionListener(a ->
			{
				exclusionToggle.toggle(id);
				refreshExclusionsLabel();
				recompute();
			});
			menu.add(entry);
		}
		menu.show(exclusionsLabel, e.getX(), e.getY());
	}

	private void refreshStoredLabel()
	{
		int count = storedView.snapshot().size();
		storedLabel.setText(count == 0 ? "" : "Stored elsewhere: " + count + " (click to manage)");
		storedLabel.setVisible(count > 0);
	}

	/** Legend for the source dots - EXACTLY the sources in the current
	 * results, in palette order; null when nothing has a known source. */
	private javax.swing.JComponent buildSourceLegend()
	{
		if (usedSources.isEmpty())
		{
			return null;
		}
		JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		legend.setOpaque(false);
		legend.setAlignmentX(LEFT_ALIGNMENT);
		JLabel title = line("Stored:", MUTED);
		legend.add(title);
		for (Map.Entry<String, Color> entry : SOURCE_COLORS.entrySet())
		{
			if (!usedSources.contains(entry.getKey()))
			{
				continue;
			}
			JLabel item = new JLabel(entry.getKey(), new SourceDotIcon(entry.getValue()),
				SwingConstants.LEADING);
			OsrsSkin.crisp(item);
			item.setForeground(MUTED);
			item.setFont(OsrsSkin.font());
			item.setBorder(new EmptyBorder(2, 0, 2, 0));
			item.setIconTextGap(4);
			legend.add(item);
		}
		return legend;
	}

	/** The small filled circle used by legend entries. */
	private static final class SourceDotIcon implements javax.swing.Icon
	{
		private final Color color;

		SourceDotIcon(Color color)
		{
			this.color = color;
		}

		@Override
		public void paintIcon(java.awt.Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(x, y + 1, 7, 7);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 8;
		}

		@Override
		public int getIconHeight()
		{
			return 9;
		}
	}

	private int currentMonsterId()
	{
		return selectedMonster == null ? -1 : selectedMonster.getId();
	}

	private static String scopeLabel(String scope)
	{
		return ALL_SETS.equals(scope) ? "all sets" : scope.toLowerCase(java.util.Locale.ROOT);
	}

	private void refreshPinnedLabel()
	{
		if (selectedMonster == null)
		{
			pinnedLabel.setVisible(false);
			return;
		}
		int monsterId = currentMonsterId();
		int pins = 0;
		for (Map<com.loadoutlab.data.GearSlot, Integer> scoped
			: mobProfile.allPins(monsterId).values())
		{
			pins += scoped.size();
		}
		int filters = 0;
		for (Map<Integer, String> scoped : mobProfile.allFilterItems(monsterId).values())
		{
			filters += scoped.size();
		}
		if (pins == 0 && filters == 0)
		{
			pinnedLabel.setVisible(false);
			return;
		}
		StringBuilder text = new StringBuilder("This mob:");
		if (pins > 0)
		{
			text.append(" ").append(pins).append(pins == 1 ? " pin" : " pins");
		}
		if (filters > 0)
		{
			text.append(pins > 0 ? "," : "").append(" ")
				.append(filters).append(" filter item").append(filters == 1 ? "" : "s");
		}
		pinnedLabel.setText(text + " (click to manage)");
		pinnedLabel.setVisible(true);
	}

	private void saveNoteIfChanged()
	{
		if (selectedMonster == null)
		{
			return;
		}
		String current = mobProfile.note(currentMonsterId());
		String edited = noteArea.getText() == null ? "" : noteArea.getText().trim();
		if (!edited.equals(current))
		{
			mobProfile.setNote(currentMonsterId(), edited);
		}
	}

	/** The post-it: hidden without a monster; collapsed shows only the
	 * header line; expanded is the inline-editable note body. */
	private void refreshNotePanel()
	{
		if (selectedMonster == null)
		{
			notePanel.setVisible(false);
			return;
		}
		String note = mobProfile.note(currentMonsterId());
		if (!noteArea.getText().equals(note) && !noteArea.isFocusOwner())
		{
			noteArea.setText(note);
		}
		noteHeader.setText(noteCollapsed
			? (note.isEmpty() ? "+ Note (click to add)" : "> Note")
			: "v Note");
		noteArea.setVisible(!noteCollapsed);
		notePanel.setVisible(true);
		notePanel.revalidate();
		notePanel.repaint();
	}

	private void showPinnedMenu(MouseEvent e)
	{
		if (selectedMonster == null)
		{
			return;
		}
		int monsterId = currentMonsterId();
		JPopupMenu menu = new JPopupMenu();
		for (Map.Entry<String, Map<com.loadoutlab.data.GearSlot, Integer>> scoped
			: mobProfile.allPins(monsterId).entrySet())
		{
			String scope = scoped.getKey();
			for (Map.Entry<com.loadoutlab.data.GearSlot, Integer> entry
				: scoped.getValue().entrySet())
			{
				GearItem item = data.getGear(entry.getValue());
				String label = item == null ? ("item " + entry.getValue()) : item.label();
				JMenuItem row = new JMenuItem(
					"Unpin " + label + " (" + scopeLabel(scope) + ")");
				com.loadoutlab.data.GearSlot slot = entry.getKey();
				row.addActionListener(a ->
				{
					mobProfile.unpin(monsterId, scope, slot);
					refreshPinnedLabel();
					recompute();
				});
				menu.add(row);
			}
		}
		for (Map.Entry<String, Map<Integer, String>> scoped
			: mobProfile.allFilterItems(monsterId).entrySet())
		{
			String scope = scoped.getKey();
			for (Map.Entry<Integer, String> entry : scoped.getValue().entrySet())
			{
				JMenuItem row = new JMenuItem("Remove filter item " + entry.getValue()
					+ " (" + scopeLabel(scope) + ")");
				int itemId = entry.getKey();
				row.addActionListener(a ->
				{
					mobProfile.removeFilterItem(monsterId, scope, itemId);
					refreshPinnedLabel();
				});
				menu.add(row);
			}
		}
		menu.addSeparator();
		JMenuItem addPin = new JMenuItem("Pin an item - all sets (search)...");
		addPin.addActionListener(a -> searchAndPin(ALL_SETS));
		menu.add(addPin);
		JMenuItem addFilter = new JMenuItem("Add a bank-filter item - all sets (search)...");
		addFilter.addActionListener(a -> searchAndAddFilter(ALL_SETS));
		menu.add(addFilter);
		menu.show(pinnedLabel, e.getX(), e.getY());
	}

	/** The per-cell pin submenu: pin/unpin the shown item for this set or
	 * all sets, or chatbox-search ANOTHER item into the pin. */
	private javax.swing.JMenu pinSubmenu(GearItem item, com.loadoutlab.data.GearSlot slot,
		CombatStyle style)
	{
		int monsterId = currentMonsterId();
		javax.swing.JMenu pinMenu = new javax.swing.JMenu("Pin " + item.label());
		Map<String, Map<com.loadoutlab.data.GearSlot, Integer>> raw = mobProfile.allPins(monsterId);
		Integer styleScoped = raw.getOrDefault(style.name(), Collections.emptyMap()).get(slot);
		Integer allScoped = raw.getOrDefault(ALL_SETS, Collections.emptyMap()).get(slot);

		if (styleScoped == null || styleScoped != item.getId())
		{
			JMenuItem thisSet = new JMenuItem("This set only");
			thisSet.addActionListener(a ->
			{
				mobProfile.pin(monsterId, style.name(), slot, item.getId());
				refreshPinnedLabel();
				recompute();
			});
			pinMenu.add(thisSet);
		}
		if (allScoped == null || allScoped != item.getId())
		{
			JMenuItem allSets = new JMenuItem("All sets");
			allSets.addActionListener(a ->
			{
				mobProfile.pin(monsterId, ALL_SETS, slot, item.getId());
				refreshPinnedLabel();
				recompute();
			});
			pinMenu.add(allSets);
		}
		if (styleScoped != null)
		{
			GearItem pinned = data.getGear(styleScoped);
			JMenuItem un = new JMenuItem("Unpin "
				+ (pinned == null ? "item" : pinned.label()) + " (this set)");
			un.addActionListener(a ->
			{
				mobProfile.unpin(monsterId, style.name(), slot);
				refreshPinnedLabel();
				recompute();
			});
			pinMenu.add(un);
		}
		if (allScoped != null)
		{
			GearItem pinned = data.getGear(allScoped);
			JMenuItem un = new JMenuItem("Unpin "
				+ (pinned == null ? "item" : pinned.label()) + " (all sets)");
			un.addActionListener(a ->
			{
				mobProfile.unpin(monsterId, ALL_SETS, slot);
				refreshPinnedLabel();
				recompute();
			});
			pinMenu.add(un);
		}
		pinMenu.addSeparator();
		JMenuItem searchThis = new JMenuItem("Another item (search) - this set only...");
		searchThis.addActionListener(a -> searchAndPin(style.name()));
		pinMenu.add(searchThis);
		JMenuItem searchAll = new JMenuItem("Another item (search) - all sets...");
		searchAll.addActionListener(a -> searchAndPin(ALL_SETS));
		pinMenu.add(searchAll);
		return pinMenu;
	}

	/** Chatbox item search -> pin into the picked item's own slot. */
	private void searchAndPin(String scope)
	{
		if (selectedMonster == null)
		{
			return;
		}
		int monsterId = currentMonsterId();
		itemSearch.search("Pin vs " + selectedMonster.getName()
			+ " (" + scopeLabel(scope) + ")", (itemId, name) ->
		{
			GearItem gear = data.getGear(itemId);
			if (gear == null)
			{
				JOptionPane.showMessageDialog(this,
					name + " is not equippable combat gear - use a bank-filter"
						+ " item for supplies.",
					"Pin an item", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			mobProfile.pin(monsterId, scope, gear.getSlot(), gear.getId());
			refreshPinnedLabel();
			recompute();
		});
	}

	/** Chatbox item search -> per-scope bank-filter supply. */
	private void searchAndAddFilter(String scope)
	{
		if (selectedMonster == null)
		{
			return;
		}
		int monsterId = currentMonsterId();
		itemSearch.search("Bank filter vs " + selectedMonster.getName()
			+ " (" + scopeLabel(scope) + ")", (itemId, name) ->
		{
			mobProfile.addFilterItem(monsterId, scope, itemId, name);
			refreshPinnedLabel();
		});
	}

	private void refreshDwmsLabel()
	{
		int count = dwmsView.count();
		dwmsLabel.setText(count == 0 ? ""
			: "From Dude Where's My Stuff: " + count + " items"
				+ (dwmsView.live() ? " (live)" : ""));
		dwmsLabel.setVisible(count > 0);
	}

	/** The DWMS live link answered (EDT): refresh the provenance line. */
	public void dwmsUpdated()
	{
		refreshDwmsLabel();
	}

	private void showStoredMenu(MouseEvent e)
	{
		JPopupMenu menu = new JPopupMenu();
		for (Integer id : storedView.snapshot())
		{
			GearItem item = data.getGear(id);
			String label = item == null ? ("item " + id) : item.label();
			JMenuItem entry = new JMenuItem("No longer stored elsewhere: " + label);
			entry.addActionListener(a ->
			{
				storedToggle.toggle(id);
				refreshStoredLabel();
				recompute();
			});
			menu.add(entry);
		}
		menu.addSeparator();
		JMenuItem add = new JMenuItem("Add a stored-elsewhere item...");
		add.addActionListener(a -> showAddStoredDialog());
		menu.add(add);
		menu.show(storedLabel, e.getX(), e.getY());
	}

	/**
	 * Add-by-name flow: gear kept in storages the ledger cannot see may
	 * never surface as a right-clickable suggestion, so typing the name is
	 * the only reliable way in (Options menu and the manage menu open this).
	 */
	private void showAddStoredDialog()
	{
		String query = JOptionPane.showInputDialog(this,
			"Item name (kept in a STASH, POH, or other storage):",
			"Stored elsewhere", JOptionPane.PLAIN_MESSAGE);
		if (query == null || query.trim().isEmpty())
		{
			return;
		}
		List<GearItem> matches = data.searchGear(query, 12);
		if (matches.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"No equipment matches '" + query.trim() + "'.",
				"Stored elsewhere", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		GearItem pick = matches.get(0);
		if (matches.size() > 1)
		{
			String[] labels = new String[matches.size()];
			for (int i = 0; i < matches.size(); i++)
			{
				labels[i] = matches.get(i).label();
			}
			Object chosen = JOptionPane.showInputDialog(this, "Which item?",
				"Stored elsewhere", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
			if (chosen == null)
			{
				return;
			}
			for (int i = 0; i < labels.length; i++)
			{
				if (labels[i].equals(chosen))
				{
					pick = matches.get(i);
					break;
				}
			}
		}
		if (!storedView.snapshot().contains(pick.getId()))
		{
			storedToggle.toggle(pick.getId());
		}
		refreshStoredLabel();
		recompute();
	}

	/** Right-click menu on a suggested item: exclude it and recompute. A
	 * container weapon (blowpipe) also offers its loaded ammo. */
	/** Iron Hub: a toggle on the game's own 36px equipment slot slab — the
	 * slot tile as the resting state, the game's slot_selected highlight
	 * when on (Luke, 2026-07-17: same slabs as the Saved setup slots). */
	private JLabel slabToggle(String tooltip, java.util.function.Supplier<Boolean> isOn)
	{
		java.awt.image.BufferedImage tile =
			com.ironhub.ui.osrs.OsrsIcons.image(theme, "equipment/slot_tile");
		java.awt.image.BufferedImage selected =
			com.ironhub.ui.osrs.OsrsIcons.image(theme, "equipment/slot_selected");
		JLabel toggle = new JLabel()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				if (tile != null)
				{
					g.drawImage(tile, 0, 0, null);
				}
				if (isOn.get() && selected != null)
				{
					g.drawImage(selected, 0, 0, null);
				}
				super.paintComponent(g); // the icon on top
			}
		};
		toggle.setOpaque(false);
		toggle.setHorizontalAlignment(SwingConstants.CENTER);
		toggle.setPreferredSize(new Dimension(36, 36));
		toggle.setToolTipText(tooltip);
		toggle.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		return toggle;
	}

	private JLabel iconToggle(int spriteId, String tooltip, boolean initial,
		java.util.function.Consumer<Boolean> onChange)
	{
		final boolean[] on = {initial};
		JLabel toggle = slabToggle(tooltip, () -> on[0]);
		if (spriteId > 0)
		{
			spriteManager.addSpriteTo(toggle, spriteId, 0);
		}
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				on[0] = !on[0];
				toggle.repaint();
				onChange.accept(on[0]);
			}
		});
		return toggle;
	}

	/** Same toggle, but the icon is an item sprite. */
	private JLabel itemToggle(int itemId, String tooltip, boolean initial,
		java.util.function.Consumer<Boolean> onChange)
	{
		JLabel toggle = iconToggle(-1, tooltip, initial, onChange);
		net.runelite.client.util.AsyncBufferedImage sprite = itemManager.getImage(itemId);
		toggle.setIcon(new javax.swing.ImageIcon(sprite));
		sprite.onLoaded(toggle::repaint);
		return toggle;
	}

	/** Spellbook icon: selects that book in the (hidden) combo; click the
	 * active one again for Any spellbook. Same slab grammar as the boosts. */
	private JLabel spellbookIcon(String bookFile, String tooltip, int comboIndex)
	{
		JLabel icon = slabToggle(tooltip + " (click again for any spellbook)",
			() -> spellbook.getSelectedIndex() == comboIndex);
		try (java.io.InputStream in = getClass().getResourceAsStream(
			"/data/icons/spellbooks/" + bookFile))
		{
			if (in != null)
			{
				icon.setIcon(new javax.swing.ImageIcon(javax.imageio.ImageIO.read(in)));
			}
		}
		catch (java.io.IOException ignored)
		{
		}
		spellbook.addActionListener(e -> icon.repaint());
		icon.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				// setSelectedIndex fires the combo's listener -> recompute
				spellbook.setSelectedIndex(spellbook.getSelectedIndex() == comboIndex ? 0 : comboIndex);
			}
		});
		return icon;
	}

	/** Iron Hub: icon dropdown of slot candidates, strongest first. */
	private void showSwapIcons(JLabel cell, com.loadoutlab.data.GearSlot slot, CombatStyle style)
	{
		List<GearItem> candidates = new java.util.ArrayList<>();
		for (GearItem item : data.getGearItems(slot))
		{
			if (item.roughScore(style) > 0 && ownedCheck.owns(item.getId()))
			{
				candidates.add(item);
			}
		}
		candidates.sort(java.util.Comparator.comparingDouble(
			(GearItem item) -> -item.roughScore(style)));
		JPopupMenu menu = new JPopupMenu();
		JPanel grid = new JPanel(new GridLayout(0, 4, 1, 1));
		grid.setBackground(theme.background);
		int shown = 0;
		for (GearItem item : candidates)
		{
			if (shown++ >= 24)
			{
				break;
			}
			JLabel pick = new JLabel();
			pick.setOpaque(true);
			pick.setBackground(theme.recess);
			pick.setBorder(new MatteBorder(1, 1, 1, 1, theme.edgeDark));
			pick.setPreferredSize(new Dimension(46, 42));
			pick.setHorizontalAlignment(SwingConstants.CENTER);
			pick.setToolTipText(item.label());
			net.runelite.client.util.AsyncBufferedImage sprite = itemManager.getImage(item.getId());
			pick.setIcon(new javax.swing.ImageIcon(sprite));
			sprite.onLoaded(pick::repaint);
			pick.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			pick.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent ev)
				{
					mobProfile.pin(selectedMonster.getId(), style.name(), slot, item.getId());
					menu.setVisible(false);
					recompute();
				}
			});
			grid.add(pick);
		}
		if (shown == 0)
		{
			JLabel none = new JLabel("Nothing owned for this slot");
			OsrsSkin.crisp(none);
			none.setFont(OsrsSkin.font());
			none.setForeground(MUTED);
			none.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
			grid.add(none);
		}
		JMenuItem clear = new JMenuItem("Reset slot to the optimizer's pick");
		clear.addActionListener(ev ->
		{
			mobProfile.unpin(selectedMonster.getId(), style.name(), slot);
			mobProfile.unpin(selectedMonster.getId(), ALL_SETS, slot);
			recompute();
		});
		menu.add(grid);
		menu.add(clear);
		menu.show(cell, 0, cell.getHeight());
	}

	private void attachExclusionMenu(JLabel cell, List<GearItem> items)
	{
		attachExclusionMenu(cell, items, Collections.emptyList(), null, null);
	}

	private void attachExclusionMenu(JLabel cell, List<GearItem> items,
		List<JMenuItem> extras)
	{
		attachExclusionMenu(cell, items, extras, null, null);
	}

	private void attachExclusionMenu(JLabel cell, List<GearItem> items,
		List<JMenuItem> extras, com.loadoutlab.data.GearSlot pinSlot, CombatStyle pinStyle)
	{
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				// Iron Hub: any click opens the swap control
				if (!e.isPopupTrigger()
					&& !(e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)))
				{
					return;
				}
				// Iron Hub: LEFT-click = instant icon dropdown of candidates;
				// RIGHT-click = quick pin menu (use worn item / pin shown item)
				if (pinSlot != null && pinStyle != null && selectedMonster != null)
				{
					if (!e.isPopupTrigger())
					{
						showSwapIcons(cell, pinSlot, pinStyle);
						return;
					}
					JPopupMenu quick = new JPopupMenu();
					Integer worn = wornLookup == null ? null : wornLookup.apply(pinSlot);
					if (worn != null && worn > 0)
					{
						JMenuItem useCurrent = new JMenuItem("Use current (equipped item)");
						useCurrent.addActionListener(ev ->
						{
							mobProfile.pin(selectedMonster.getId(), pinStyle.name(), pinSlot, worn);
							recompute();
						});
						quick.add(useCurrent);
					}
					if (!items.isEmpty())
					{
						JMenuItem pinShown = new JMenuItem("Pin " + items.get(0).label() + " for this monster");
						pinShown.addActionListener(ev ->
						{
							mobProfile.pin(selectedMonster.getId(), ALL_SETS, pinSlot, items.get(0).getId());
							recompute();
						});
						quick.add(pinShown);
					}
					JMenuItem clear = new JMenuItem("Reset slot to the optimizer's pick");
					clear.addActionListener(ev ->
					{
						mobProfile.unpin(selectedMonster.getId(), pinStyle.name(), pinSlot);
						mobProfile.unpin(selectedMonster.getId(), ALL_SETS, pinSlot);
						recompute();
					});
					quick.add(clear);
					quick.show(cell, e.getX(), e.getY());
					return;
				}
				JPopupMenu menu = new JPopupMenu();
				for (JMenuItem extra : extras)
				{
					menu.add(extra);
				}
				for (GearItem item : items)
				{
					JMenuItem exclude = new JMenuItem("Exclude " + item.label() + " from suggestions");
					exclude.addActionListener(a ->
					{
						exclusionToggle.toggle(item.getId());
						refreshExclusionsLabel();
						recompute();
					});
					menu.add(exclude);
					// Unowned items can be dreamed into the owned pool
					// (and undreamed).
					boolean stored = storedView.snapshot().contains(item.getId());
					if (!ownedCheck.owns(item.getId()))
					{
						boolean dreamed = dreamView.snapshot().contains(item.getId());
						JMenuItem dream = new JMenuItem(dreamed
							? "Stop dreaming of " + item.label()
							: "Dream: consider " + item.label() + " as owned");
						dream.addActionListener(a ->
						{
							dreamToggle.toggle(item.getId());
							recompute();
						});
						menu.add(dream);
					}
					// Stored elsewhere: STASH, POH costume room, UIM cold or
					// nest storage - genuinely owned, just invisible to the
					// ledger. Once marked, owns() is true, so the un-mark
					// entry is what keeps the state reachable.
					if (stored || !ownedCheck.owns(item.getId()))
					{
						JMenuItem storeToggle = new JMenuItem(stored
							? "No longer stored elsewhere: " + item.label()
							: "Stored elsewhere: count " + item.label() + " as owned");
						storeToggle.addActionListener(a ->
						{
							storedToggle.toggle(item.getId());
							refreshStoredLabel();
							recompute();
						});
						menu.add(storeToggle);
					}
					// Pin: user preference wins the slot outright - for
					// THIS monster, scoped to this set or all sets.
					if (pinSlot != null && pinStyle != null && selectedMonster != null)
					{
						menu.add(pinSubmenu(item, pinSlot, pinStyle));
					}
				}
				menu.show(cell, e.getX(), e.getY());
			}
		});
	}

	/** The dart loaded in a blowpipe result, resolved for exclusion menus. */
	private GearItem loadedDart(DpsResult result)
	{
		String type = result.getAttackType();
		int idx = type.indexOf(" - ");
		if (idx < 0 || !type.startsWith("ranged"))
		{
			return null;
		}
		Integer dartId = BlowpipeDarts.baseIdForTierName(type.substring(idx + 3));
		return dartId == null ? null : data.getGear(dartId);
	}

	/** "No prayer helps" mark: a prohibition sign (circle + slash), painted so
	 * it inherits the incoming line's colour (glyphs tofu on macOS Tahoe). */
	private static final class NoPrayerIcon implements javax.swing.Icon
	{
		private final int size;

		NoPrayerIcon(int size)
		{
			this.size = size;
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(c.getForeground());
			g2.setStroke(new BasicStroke(Math.max(1.3f, size / 9f),
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			double m = 1.5;
			double d = size - 2 * m - 1;
			g2.draw(new java.awt.geom.Ellipse2D.Double(x + m, y + m, d, d));
			double off = (d / 2.0) / Math.sqrt(2);
			double cx = x + size / 2.0;
			double cy = y + size / 2.0;
			g2.draw(new java.awt.geom.Line2D.Double(cx - off, cy + off, cx + off, cy - off));
			g2.dispose();
		}
	}

	/** Three-dots "more options" glyph, painted (Swing glyphs tofu on Tahoe). */
	private static final class DotsIcon implements javax.swing.Icon
	{
		private final int size;

		DotsIcon(int size)
		{
			this.size = size;
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(c.getForeground());
			double r = Math.max(1.2, size / 9.0);
			double cy = y + size / 2.0;
			for (int i = 0; i < 3; i++)
			{
				double cx = x + size * (0.22 + 0.28 * i);
				g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
			}
			g2.dispose();
		}
	}

	/**
	 * Refresh glyph (circular arrow) painted as a ShapeIcon - the Unicode
	 * reload symbols tofu in Swing on macOS Tahoe, so we draw it. Inherits
	 * the host button's foreground colour.
	 */
	private static final class ReloadIcon implements javax.swing.Icon
	{
		private final int size;

		ReloadIcon(int size)
		{
			this.size = size;
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.translate(x, y);
			g2.setColor(c.getForeground());
			double cx = size / 2.0;
			double cy = size / 2.0;
			double r = size / 2.0 - 2.0;
			// 0 deg points up, sweeps clockwise; a gap is left for the head.
			double startDeg = 50;
			double sweepDeg = 265;
			java.awt.geom.Path2D.Double arc = new java.awt.geom.Path2D.Double();
			int steps = 48;
			for (int i = 0; i <= steps; i++)
			{
				double a = Math.toRadians(startDeg + sweepDeg * i / steps);
				double px = cx + r * Math.sin(a);
				double py = cy - r * Math.cos(a);
				if (i == 0)
				{
					arc.moveTo(px, py);
				}
				else
				{
					arc.lineTo(px, py);
				}
			}
			g2.setStroke(new BasicStroke(Math.max(1.4f, size / 9f),
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.draw(arc);
			// Arrowhead at the swept end, pointing along the clockwise tangent.
			double aEnd = Math.toRadians(startDeg + sweepDeg);
			double ex = cx + r * Math.sin(aEnd);
			double ey = cy - r * Math.cos(aEnd);
			double tx = Math.cos(aEnd);
			double ty = Math.sin(aEnd);
			double nx = -ty;
			double ny = tx;
			double h = size * 0.32;
			double w = size * 0.22;
			java.awt.geom.Path2D.Double head = new java.awt.geom.Path2D.Double();
			head.moveTo(ex + tx * h, ey + ty * h);
			head.lineTo(ex - tx * h * 0.2 + nx * w, ey - ty * h * 0.2 + ny * w);
			head.lineTo(ex - tx * h * 0.2 - nx * w, ey - ty * h * 0.2 - ny * w);
			head.closePath();
			g2.fill(head);
			g2.dispose();
		}
	}

	/** Wilderness sub-controls follow the master switch: hidden until the
	 *  player says this is a wilderness trip (every ToggleRow press routes
	 *  through recompute, so the visibility stays in step). */
	private void updateWildernessControls()
	{
		boolean on = wildyInfo.isVisible() && wildyInfo.isSelected();
		lowRisk.setVisible(on);
		protectItem.setVisible(on);
		riskBudget.setVisible(on);
	}

	/** Whether wilderness-specific info (risk, kept items) should show. */
	private boolean wildernessOn()
	{
		return selectedMonster != null && WildernessMonsters.isWilderness(selectedMonster)
			&& wildyInfo.isSelected();
	}

	private void recompute()
	{
		updateWildernessControls();
		lastShownLoadout = null; // recapture from the fresh results
		if (selectedMonster == null)
		{
			return;
		}
		// Clear stale results immediately - showing the previous monster's
		// sets while the optimizer runs reads as an answer for this one.
		resultsPanel.removeAll();
		if (MascotSpinner.available())
		{
			resultsPanel.add(new MascotSpinner());
		}
		// html so long monster names wrap instead of clipping at the edge
		JLabel computing = new JLabel("<html>Optimizing vs " + selectedMonster.getName() + "...</html>");
		computing.setForeground(MUTED);
		computing.setAlignmentX(LEFT_ALIGNMENT);
		computing.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		resultsPanel.add(computing);
		resultsPanel.revalidate();
		resultsPanel.repaint();
		statusLabel.setText(" ");
		computeHook.compute(selectedMonster, f2pOnly.isSelected(), slayerTask.isSelected(),
			spellbookLock(), riskCap(), selectedRiskBudget(),
			superAntifireAssumed && DragonfireRules.breathesFire(selectedMonster),
			parsedBudgetGp(),
			com.loadoutlab.optimizer.OptimizerService.OptimizeMode.values()[optimizeMode.getSelected()],
			() -> statusLabel.setText(" "));
	}

	/** Account or profile switched: nothing on screen may survive. */
	public void resetForIdentityChange()
	{
		showInBank.setSelected(false);
		applyShowInBank();
		lastResults = null;
		clearSelection();
		refreshExclusionsLabel();
		refreshStoredLabel();
		refreshDwmsLabel();
		refreshPinnedLabel();
		refreshNotePanel();
	}

	private void clearSelection()
	{
		selectedMonster = null;
		if (resultsListener != null)
		{
			resultsListener.onCleared(); // the shared viewer reverts to live
		}
		cardCollapsed.clear();
		selectedRow.setVisible(false);
		searchField.setVisible(true);
		weaknessLabel.setVisible(false);
		selectedLabel.setText("");
		monsterNote.setText("");
		monsterNote.setVisible(false);
		refreshNotePanel();
		refreshPinnedLabel();
		resultsPanel.removeAll();
		resultsPanel.revalidate();
		resultsPanel.repaint();
		statusLabel.setText("Search a monster to begin.");
		revalidate();
		searchField.requestFocusInWindow();
	}

	/** Render results (EDT). Called by the plugin once the optimizer returns. */
	public void showResults(MonsterStats monster, Map<CombatStyle, StyleResult> results)
	{
		if (selectedMonster == null || monster.getId() != selectedMonster.getId())
		{
			return; // stale result for a monster the user moved away from
		}
		lastResults = results;
		refreshDwmsLabel();
		resultsPanel.removeAll();
		usedSources.clear();
		// Iron Hub (Luke, 2026-07-21): the three per-style cards became style
		// BUTTONS in the wrapper (which also owns the shared gear viewer and
		// stat tile) — the results area shows ONE detail card, for whichever
		// style the wrapper has selected. Default the detail to the
		// strongest style so the first render lands on the best answer.
		if (detailStyle == null || results.get(detailStyle) == null
			|| results.get(detailStyle).owned == null || results.get(detailStyle).owned.isEmpty())
		{
			detailStyle = bestStyle(results);
		}
		// the bank aids follow fresh results and style switches while the
		// Show-in-bank checkbox is on (Luke, round 5)
		applyShowInBank();
		if (resultsListener != null)
		{
			resultsListener.onResults(monster, results);
		}
		javax.swing.JComponent legend = buildSourceLegend();
		if (legend != null)
		{
			resultsPanel.add(legend);
		}
		// Iron Hub: the target row already names the monster - repeating it
		// in a footer line was noise
		statusLabel.setText(" ");
		resultsPanel.revalidate();
		resultsPanel.repaint();
	}
	// styleCard DELETED (Luke, rounds 3-5): the shared viewer/tile and the
	// Options-section Show-in-bank checkbox replaced everything it drew.


	private static final ImageIcon PRAYER_ICON = loadPrayerIcon();
	private static final ImageIcon SWORD_ICON = loadSkillIcon("attack");
	private static final ImageIcon SHIELD_ICON = loadSkillIcon("defence");
	private static final javax.swing.Icon NO_PRAYER_ICON = new NoPrayerIcon(13);

	private static ImageIcon loadPrayerIcon()
	{
		return loadSkillIcon("prayer");
	}

	private static ImageIcon loadSkillIcon(String skill)
	{
		try
		{
			BufferedImage img = ImageUtil.loadImageResource(
				SkillIconManager.class, "/skill_icons_small/" + skill + ".png");
			return new ImageIcon(img.getScaledInstance(14, 14, Image.SCALE_SMOOTH));
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** Compact frontier trade: [sword] N%-  [shield] M%+ using the Attack and
	 * Defence skill icons (Swing emoji tofu on macOS Tahoe). Hover for the
	 * full sentence. */
	private JPanel modeTradeRow(ModeTrade t)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		row.setToolTipText(String.format(
			"This mode: %d%% less DPS for %d%% less damage taken", t.dpsLossPct, t.dmgCutPct));
		row.add(tradeChip(SWORD_ICON, t.dpsLossPct + "%-"));
		row.add(tradeChip(SHIELD_ICON, t.dmgCutPct + "%+"));
		return row;
	}

	private static JLabel tradeChip(ImageIcon icon, String text)
	{
		JLabel label = new JLabel(text);
		if (icon != null)
		{
			label.setIcon(icon);
			label.setIconTextGap(3);
		}
		OsrsSkin.crisp(label);
		label.setForeground(INFO);
		label.setFont(OsrsSkin.font());
		label.setBorder(new EmptyBorder(2, 0, 2, 0));
		return label;
	}

	/**
	 * The at-a-glance stat tile (Luke, 2026-07-21): a sunken two-column
	 * grid of the numbers that used to sprawl across five separate lines,
	 * plus full-width style/spell and (opt-in wilderness) risk rows.
	 */
	// ── the shared stat tile (Luke, 2026-07-21): built HERE (the panel owns
	// the corpus + risk state) but MOUNTED by the wrapper below its Save
	// setup / View all setups buttons, fed either the calc's suggestion or
	// the player's live gear. All small text; DPS alone medium weight. ──

	/** Everything one tile render needs; nullable fields go honest "?". */
	public static final class TileStats
	{
		public DpsResult result;     // monster-dependent numbers (nullable)
		public Loadout loadout;      // bonuses source (falls back to result's)
		public MonsterStats monster; // for avg ttk (nullable)
		public String styleText;     // full-width Style row (nullable)
		public String spellbook;     // nullable
		public String riskText;      // nullable = no risk row
		public String riskTip;
		public boolean riskOk;
	}

	/** The calc's suggestion for one style as tile stats, or null when the
	 *  style has no usable owned set / no results yet. */
	public TileStats suggestionStats(CombatStyle style)
	{
		if (lastResults == null)
		{
			return null;
		}
		StyleResult result = lastResults.get(style);
		if (result == null || result.owned == null || result.owned.isEmpty())
		{
			return null;
		}
		DpsResult best = result.owned.get(0);
		TileStats stats = new TileStats();
		stats.result = best;
		stats.loadout = best.getLoadout();
		stats.monster = selectedMonster;
		String spellName = best.getSpellName();
		if (style == CombatStyle.MAGIC)
		{
			stats.styleText = spellName == null || spellName.isEmpty() ? null : spellName + " (Spell)";
			stats.spellbook = spellName == null || spellName.isEmpty() ? null : data.getSpells().stream()
				.filter(sp -> spellName.equals(sp.getName()))
				.map(sp -> capitalize(sp.getSpellbook()))
				.findFirst().orElse(null);
		}
		else if (style == CombatStyle.RANGED)
		{
			stats.styleText = best.getAttackType().contains("rapid") ? "Rapid" : "Accurate";
		}
		else
		{
			stats.styleText = capitalize(best.getAttackType());
		}
		if (wildernessOn())
		{
			int keep = protectItem.isSelected() ? 4 : 3;
			PvpRisk.Assessment risk = PvpRisk.assess(best.getLoadout(), result.specWeapon, keep);
			stats.riskText = String.format("%s gp (%d kept)", PvpRisk.formatGp(risk.riskGp), keep);
			stats.riskTip = riskTooltip(risk);
			stats.riskOk = risk.riskGp <= selectedRiskBudget();
		}
		return stats;
	}

	/** Render the MAIN stat tile on a stone slab: dps grid + style/spellbook/
	 *  risk rows. Labels medium, values bold (Luke); spellbook/risk small. */
	public javax.swing.JComponent statsTile(TileStats stats)
	{
		JPanel tile = slab();
		DpsResult best = stats.result;

		// unknown values render NOTHING, never "?" (Luke) — with no result
		// the tile is just the style row + bonus folds
		if (best != null)
		{
			JPanel grid = statGrid();
			grid.add(statCell("DPS", String.format("%.2f", best.getDps()), GOOD,
				"Damage per second vs " + (stats.monster == null ? "your target" : stats.monster.getName()),
				OsrsSkin.boldFont()));
			grid.add(statCell("Max hit", String.valueOf(best.getMaxHit()), INFO,
				"Highest possible hit", OsrsSkin.font()));
			grid.add(statCell("Accuracy", String.format("%.0f%%", best.getAccuracy() * 100), INFO,
				"Chance an attack lands", OsrsSkin.font()));
			String ttk = ttkText(stats);
			if (!"?".equals(ttk))
			{
				grid.add(statCell("Avg TTK", ttk, INFO,
					"Average time to kill: the monster's hitpoints over the dps", OsrsSkin.font()));
			}
			tile.add(grid);
		}

		if (stats.styleText != null)
		{
			tile.add(Box.createVerticalStrut(2));
			JLabel styleValue = line(stats.styleText, INFO);
			styleValue.setFont(OsrsSkin.font()); // medium, not bold (Luke)
			styleValue.setToolTipText("The style the numbers assume");
			tile.add(fullRow("Style", styleValue, OsrsSkin.font()));
		}
		if (stats.spellbook != null && !stats.spellbook.isEmpty())
		{
			tile.add(Box.createVerticalStrut(2));
			tile.add(fullRow("Spellbook", stats.spellbook, INFO, "The spellbook this spell needs"));
		}
		if (stats.riskText != null)
		{
			tile.add(Box.createVerticalStrut(2));
			JLabel risk = line(stats.riskText, stats.riskOk ? GOOD : new Color(220, 140, 120));
			risk.setFont(OsrsSkin.smallFont());
			risk.setToolTipText(stats.riskTip);
			tile.add(fullRow("Risk", risk, OsrsSkin.smallFont()));
		}
		tile.setMaximumSize(new Dimension(Integer.MAX_VALUE, tile.getPreferredSize().height));
		return tile;
	}

	/** The in-game Equipment-Stats groups on their own slab — mounted by the
	 *  wrapper as a collapsed-by-default expandable below the main tile. */
	public javax.swing.JComponent bonusesTile(TileStats stats)
	{
		Loadout loadout = stats.loadout != null ? stats.loadout
			: stats.result != null ? stats.result.getLoadout() : null;
		if (loadout == null)
		{
			return null;
		}
		JPanel tile = slab();
		com.loadoutlab.data.StatBlock off = loadout.getOffensive();
		com.loadoutlab.data.StatBlock def = loadout.getDefensive();
		com.loadoutlab.data.StatBlock bon = loadout.getBonuses();
		tile.add(groupHeader("Attack bonuses"));
		JPanel atk = statGrid();
		atk.add(statCell("Stab", plus(off.getStab()), INFO, null, OsrsSkin.smallFont()));
		atk.add(statCell("Slash", plus(off.getSlash()), INFO, null, OsrsSkin.smallFont()));
		atk.add(statCell("Crush", plus(off.getCrush()), INFO, null, OsrsSkin.smallFont()));
		atk.add(statCell("Magic", plus(off.getMagic()), INFO, null, OsrsSkin.smallFont()));
		atk.add(statCell("Range", plus(off.getRanged()), INFO, null, OsrsSkin.smallFont()));
		atk.add(statCell(" ", " ", MUTED, null, OsrsSkin.smallFont()));
		tile.add(atk);
		tile.add(Box.createVerticalStrut(3));
		tile.add(groupHeader("Defence bonuses"));
		JPanel dfn = statGrid();
		dfn.add(statCell("Stab", plus(def.getStab()), INFO, null, OsrsSkin.smallFont()));
		dfn.add(statCell("Slash", plus(def.getSlash()), INFO, null, OsrsSkin.smallFont()));
		dfn.add(statCell("Crush", plus(def.getCrush()), INFO, null, OsrsSkin.smallFont()));
		dfn.add(statCell("Magic", plus(def.getMagic()), INFO, null, OsrsSkin.smallFont()));
		dfn.add(statCell("Range", plus(def.getRanged()), INFO, null, OsrsSkin.smallFont()));
		dfn.add(statCell(" ", " ", MUTED, null, OsrsSkin.smallFont()));
		tile.add(dfn);
		tile.add(Box.createVerticalStrut(3));
		tile.add(groupHeader("Other bonuses"));
		JPanel oth = statGrid();
		oth.add(statCell("Melee STR", plus(bon.getStrength()), INFO, "Melee strength", OsrsSkin.smallFont()));
		oth.add(statCell("Ranged STR", plus(bon.getRangedStrength()), INFO, "Ranged strength", OsrsSkin.smallFont()));
		oth.add(statCell("Magic DMG", String.format("%+d%%", bon.getMagicDamage()), INFO, "Magic damage", OsrsSkin.smallFont()));
		oth.add(statCell("Prayer", plus(bon.getPrayer()), INFO, "Prayer bonus - slower drain", OsrsSkin.smallFont()));
		tile.add(oth);
		GearItem weapon = loadout.getWeapon();
		if (weapon != null)
		{
			tile.add(Box.createVerticalStrut(3));
			tile.add(fullRow("Weapon speed", weapon.getSpeed() + " ticks ("
				+ String.format("%.1fs", weapon.getSpeed() * 0.6) + ")", INFO,
				"Time between attacks"));
		}
		tile.setMaximumSize(new Dimension(Integer.MAX_VALUE, tile.getPreferredSize().height));
		return tile;
	}

	/** A stone slab, not a black well (Luke) — the tiles sit on StonePanel. */
	private JPanel slab()
	{
		StonePanel tile = new StonePanel(theme);
		tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
		tile.setAlignmentX(LEFT_ALIGNMENT);
		tile.setBorder(BorderFactory.createCompoundBorder(tile.getBorder(),
			new EmptyBorder(2, 4, 2, 4)));
		return tile;
	}

	private static String plus(int value)
	{
		return String.format("%+d", value);
	}

	private JPanel statGrid()
	{
		JPanel grid = new JPanel(new GridLayout(0, 2, 10, 1));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		return grid;
	}

	private JLabel groupHeader(String text)
	{
		JLabel header = line(text, MUTED);
		header.setFont(OsrsSkin.smallFont());
		header.setAlignmentX(LEFT_ALIGNMENT);
		return header;
	}

	/** One label-left / value-right cell with an explicit value font —
	 *  headline labels ride medium, bonus-group cells all small. */
	private JPanel statCell(String label, String value, Color valueColor, String tip,
		java.awt.Font valueFont)
	{
		boolean headline = valueFont != OsrsSkin.smallFont();
		JPanel cell = new JPanel(new BorderLayout(4, 0));
		cell.setOpaque(false);
		JLabel key = line(label, MUTED);
		key.setFont(headline ? OsrsSkin.font() : OsrsSkin.smallFont());
		JLabel val = line(value, valueColor);
		val.setFont(valueFont);
		val.setHorizontalAlignment(SwingConstants.RIGHT);
		cell.add(key, BorderLayout.WEST);
		cell.add(val, BorderLayout.EAST);
		if (tip != null)
		{
			cell.setToolTipText(tip);
			key.setToolTipText(tip);
			val.setToolTipText(tip);
		}
		return cell;
	}

	private JPanel fullRow(String label, String value, Color valueColor, String tip)
	{
		JLabel val = line(value, valueColor);
		val.setFont(OsrsSkin.smallFont());
		if (tip != null)
		{
			val.setToolTipText(tip);
		}
		return fullRow(label, val);
	}

	private JPanel fullRow(String label, JLabel value)
	{
		return fullRow(label, value, OsrsSkin.smallFont());
	}

	private JPanel fullRow(String label, JLabel value, java.awt.Font keyFont)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel key = line(label, MUTED);
		key.setFont(keyFont);
		row.add(key, BorderLayout.WEST);
		value.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(value, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** "~24s" / "~2m 05s": the monster's hp over the dps, honest "?". */
	private static String ttkText(TileStats stats)
	{
		if (stats.monster == null || stats.result == null || stats.result.getDps() <= 0)
		{
			return "?";
		}
		double seconds = stats.monster.getHitpoints() / stats.result.getDps();
		if (seconds < 90)
		{
			return String.format("~%.0fs", seconds);
		}
		return String.format("~%dm %02ds", (int) (seconds / 60), Math.round(seconds % 60));
	}

	// addPrayerLine / addStyleLine / addRiskLine folded into statsTile
	// (Luke, 2026-07-21) — the tile is the one place these numbers live.

	/** Blowpipes: name the loaded dart the numbers assume. */
	private void addDartLine(JPanel card, DpsResult result)
	{
		String type = result.getAttackType();
		int idx = type.indexOf(" - ");
		if (idx < 0 || !type.startsWith("ranged"))
		{
			return;
		}
		JLabel dart = line("Loaded with: " + type.substring(idx + 3), INFO);
		dart.setToolTipText("Dart included in the dps (right-click to exclude)");
		GearItem dartItem = loadedDart(result);
		if (dartItem != null)
		{
			attachExclusionMenu(dart, List.of(dartItem));
		}
		card.add(dart);
	}

	/** Everything the old spec line said, as the spec cell's tooltip. */
	private static String specTooltip(SpecialAttack spec, double expectedDamage,
		double drainValue, double replacedAutoExpected, String fallbackTooltip)
	{
		String headline = drainValue > 0.5
			? String.format("Spec: %s - %.0f dmg + drain ~%.0f (%d%% energy)",
				spec.getDisplayName(), expectedDamage, drainValue, spec.getEnergyCost())
			: String.format("Spec: %s - avg %.0f dmg (%d%% energy)",
				spec.getDisplayName(), expectedDamage, spec.getEnergyCost());
		String note = spec.getNote();
		// Spec throughput: weaving this spec on cooldown adds sustained dps
		// (energy regen 10% per 30s; the Lightbearer doubles it).
		String sustained = String.format("Weaving on cooldown: about +%.2f dps"
				+ " (+%.2f with a Lightbearer)",
			spec.sustainedDpsBonus(expectedDamage, replacedAutoExpected, false),
			spec.sustainedDpsBonus(expectedDamage, replacedAutoExpected, true));
		String drain = drainValue > 0.5
			? String.format("<br>Drain worth ~%.0f extra damage over the kill.", drainValue)
			: "";
		return "<html>" + headline
			+ "<br>" + (note.isEmpty() ? fallbackTooltip : note)
			+ "<br>" + sustained + drain + "</html>";
	}

	/**
	 * Magic only: name the spell and its spellbook explicitly - the weapon in
	 * the grid below is autocast-legal for that book (engine-gated).
	 */
	private void addSpellLine(JPanel card, CombatStyle style, DpsResult result)
	{
		if (style != CombatStyle.MAGIC)
		{
			return;
		}
		if (result.getSpellName() == null)
		{
			// A magic result without an autocast spell is a powered staff -
			// the weapon casts its own built-in spell.
			JLabel builtIn = line("Built-in spell (powered staff)", INFO);
			builtIn.setToolTipText("The staff casts its own spell");
			GearItem weapon = result.getLoadout().getWeapon();
			if (weapon != null)
			{
				attachItemIcon(builtIn, weapon.getId());
				builtIn.setIconTextGap(4);
			}
			card.add(builtIn);
			return;
		}
		String name = result.getSpellName();
		String book = data.getSpells().stream()
			.filter(s -> name.equals(s.getName()))
			.map(s -> s.getSpellbook())
			.findFirst().orElse("");
		JPanel row = iconRow(card);
		JLabel spell = line(name, INFO);
		spell.setToolTipText(book.isEmpty() ? "Autocast this spell"
			: "Autocast (" + capitalize(book) + " book)");
		int sprite = AssumeIcons.spellSprite(name);
		if (sprite >= 0)
		{
			attachSprite(spell, sprite);
			spell.setIconTextGap(4);
		}
		row.add(spell);
		if (name.contains("Demonbane"))
		{
			JLabel mod = new JLabel();
			mod.setToolTipText("Assumes Mark of Darkness");
			attachSprite(mod, AssumeIcons.MARK_OF_DARKNESS);
			row.add(mod);
		}
	}

	/**
	 * Wilderness: what a PvP death costs in gp for this set. Worn
	 * tradeables plus the carried spec weapon compete for the kept-on-
	 * death slots by value; everything past them is the risk. Renders as
	 * the stat tile's Risk row via {@link #riskRow}.
	 */
	private String riskTooltip(PvpRisk.Assessment risk)
	{
		StringBuilder tip = new StringBuilder("<html>Kept on death:");
		if (risk.kept.isEmpty())
		{
			tip.append(" (none - all untradeable)");
		}
		for (GearItem item : risk.kept)
		{
			tip.append("<br>+ ").append(item.label())
				.append(" (").append(PvpRisk.formatGp(risk.valueOf(item))).append(")");
		}
		if (!risk.lost.isEmpty())
		{
			tip.append("<br>Lost:");
			for (GearItem item : risk.lost)
			{
				tip.append("<br>- ").append(item.label())
					.append(" (").append(PvpRisk.formatGp(risk.valueOf(item))).append(")");
			}
		}
		if (!risk.untradeableCharges.isEmpty())
		{
			tip.append("<br>Untradeable fees on death:");
			for (PvpRisk.Charge charge : risk.untradeableCharges)
			{
				tip.append("<br>- ").append(charge.item.label())
					.append(" (").append(PvpRisk.formatGp(charge.costGp)).append(")");
			}
		}
		tip.append("<br>Skulled: keep 0-1.");
		tip.append("</html>");
		return tip.toString();
	}

	/**
	 * What buying the unowned pieces in this set would cost. Quest rewards
	 * are excluded from the gp sum - they cost effort, not coins - and are
	 * listed by source quest in the tooltip instead; a set whose only
	 * unowned pieces are quest rewards shows a compact quest-only line.
	 */
	private void addUpgradeLine(JPanel card, DpsResult best)
	{
		long cost = 0;
		boolean questRewards = false;
		StringBuilder tip = new StringBuilder("<html>Not owned yet:");
		for (GearItem item : best.getLoadout().getGear().values())
		{
			if (item == null || ownedCheck.owns(item.getId()))
			{
				continue;
			}
			String quest = QuestRewardItems.questFor(item);
			if (quest != null)
			{
				questRewards = true;
				tip.append("<br>").append(item.label())
					.append(" (quest: ").append(quest).append(")");
			}
			else
			{
				cost += item.getPriceOrZero();
				tip.append("<br>").append(item.label()).append(" (")
					.append(PvpRisk.formatGp(item.getPriceOrZero())).append(")");
			}
		}
		if (cost <= 0 && !questRewards)
		{
			return;
		}
		JLabel line = line(cost > 0
			? String.format("Upgrade cost: ~%s gp", PvpRisk.formatGp(cost))
			: "Upgrade: quest rewards", UNOWNED);
		line.setToolTipText(tip.append("</html>").toString());
		card.add(line);
	}

	/** What the boss does back to you in this set, protection prayer up. */
	private void addIncomingLine(JPanel card, IncomingDpsCalculator.Result incoming)
	{
		if (incoming == null || incoming.totalDps <= 0)
		{
			return;
		}
		boolean prayable = incoming.protectPrayer != null;
		// The protect icon IS the pray call; the text is the cost - prayed,
		// and what skipping the prayer would cost you. Unblockable bosses
		// (dodge-based / typeless) still show the intake, with a no-prayer mark.
		JLabel line = line(prayable
			? String.format("~%.2f DPS to you (~%.2f unprayed)",
				incoming.totalDps, incoming.unprayedDps)
			: String.format("~%.2f DPS to you (unavoidable)", incoming.totalDps),
			new Color(210, 140, 130));
		if (prayable)
		{
			int sprite = AssumeIcons.prayerSprite(incoming.protectPrayer);
			if (sprite >= 0)
			{
				attachSprite(line, sprite);
				line.setIconTextGap(4);
			}
		}
		else
		{
			line.setIcon(NO_PRAYER_ICON);
			line.setIconTextGap(4);
		}
		StringBuilder tip = new StringBuilder("<html>")
			.append(prayable ? "Run " + incoming.protectPrayer + "."
				: "No prayer reduces this damage.");
		for (IncomingDpsCalculator.StyleThreat threat : incoming.threats)
		{
			tip.append("<br>").append(threat.style).append(": ");
			if (!threat.modeled)
			{
				tip.append("not modeled yet");
			}
			else if (threat.blocked)
			{
				tip.append(threat.prayerFactor > 0
					? String.format("%.0f%% pierces prayer (%.2f dps, max %d)",
						threat.prayerFactor * 100, threat.dps, threat.maxHit)
					: String.format("blocked (%.2f dps, max %d)", threat.dps, threat.maxHit));
			}
			else
			{
				tip.append(String.format("%.2f dps, max %d", threat.dps, threat.maxHit));
			}
		}
		tip.append(incoming.overrideNote != null && !incoming.overrideNote.isEmpty()
			? "<br>Curated: " + incoming.overrideNote
			: "<br>Assumes a uniform rotation.");
		if (!incoming.fullyModeled)
		{
			tip.append("<br>Unmodeled attacks not counted - treat as a floor.");
		}
		line.setToolTipText(tip.append("</html>").toString());
		card.add(line);
	}

	/** The wilderness death fates a grid cell can be badged with. */
	private enum Fate
	{
		KEPT, DROPPED, FEE
	}

	/**
	 * A grid cell that can carry a small top-left glyph: the wilderness
	 * death-fate marker (halo = protected, skull = lost to the killer,
	 * coin stack = survives but bills a replacement fee). Glyphs get a
	 * dark backing so they read on bright item sprites; borders keep
	 * their own language (gold/blue/green).
	 */
	private static final class RiskDotLabel extends JLabel
	{
		private static final Color BACKING = new Color(30, 30, 30);

		private Fate fate;
		/** Item-source dot (bottom-right; fates own the top-left). */
		private Color sourceDot;

		void setFate(Fate fate)
		{
			this.fate = fate;
		}

		void setSourceDot(Color color)
		{
			this.sourceDot = color;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (fate == null && sourceDot == null)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			if (sourceDot != null)
			{
				g2.setColor(BACKING);
				g2.fillOval(getWidth() - 12, getHeight() - 12, 10, 10);
				g2.setColor(sourceDot);
				g2.fillOval(getWidth() - 10, getHeight() - 10, 6, 6);
			}
			if (fate != null)
			{
				switch (fate)
				{
					case KEPT:
						paintHalo(g2);
						break;
					case DROPPED:
						paintSkull(g2);
						break;
					default:
						paintCoins(g2);
						break;
				}
			}
			g2.dispose();
		}

		/** Angel halo: a tilted golden-white ring over a dark backing. */
		private static void paintHalo(Graphics2D g2)
		{
			g2.rotate(Math.toRadians(-15), 7.5, 4.5);
			g2.setColor(BACKING);
			g2.setStroke(new BasicStroke(4f));
			g2.drawOval(2, 2, 11, 5);
			g2.setColor(new Color(255, 236, 150));
			g2.setStroke(new BasicStroke(2f));
			g2.drawOval(2, 2, 11, 5);
		}

		/** The PK skull: cranium, jaw, eye sockets, tooth gaps. */
		private static void paintSkull(Graphics2D g2)
		{
			g2.setColor(BACKING);
			g2.fillOval(1, 1, 13, 12);
			g2.setColor(new Color(235, 235, 225));
			g2.fillOval(3, 2, 9, 8);
			g2.fillRect(5, 9, 5, 3);
			g2.setColor(BACKING);
			g2.fillOval(5, 5, 2, 2);
			g2.fillOval(8, 5, 2, 2);
			g2.drawLine(6, 10, 6, 11);
			g2.drawLine(8, 10, 8, 11);
		}

		/** The classic gp pile: stacked gold coins with darker rims. */
		private static void paintCoins(Graphics2D g2)
		{
			g2.setColor(BACKING);
			g2.fillOval(1, 1, 13, 12);
			paintCoin(g2, 3, 8);
			paintCoin(g2, 2, 5);
			paintCoin(g2, 3, 2);
		}

		private static void paintCoin(Graphics2D g2, int x, int y)
		{
			g2.setColor(new Color(140, 100, 25));
			g2.fillOval(x, y, 9, 5);
			g2.setColor(new Color(255, 200, 60));
			g2.fillOval(x + 1, y + 1, 7, 3);
			g2.setColor(new Color(255, 214, 90));
			g2.fillOval(x + 2, y + 1, 4, 2);
		}
	}

	/** Super antifire potion(4) - the icon for the assumed-potion chip. */
	private static final int SUPER_ANTIFIRE_ID = 21978;

	/** The dragonfire protection flip, shown on the shield cell. */
	private List<JMenuItem> dragonfireMenuEntries()
	{
		if (!DragonfireRules.breathesFire(selectedMonster))
		{
			return Collections.emptyList();
		}
		JMenuItem flip = new JMenuItem(superAntifireAssumed
			? "Require a dragonfire shield (drop the super antifire)"
			: "Assume a super antifire (drop the shield)");
		flip.addActionListener(a ->
		{
			superAntifireAssumed = !superAntifireAssumed;
			recompute();
		});
		return List.of(flip);
	}

	/** The active set's item ids: gear + loaded dart + spec weapon. */
	private static Set<Integer> setItemIds(DpsResult best, GearItem specWeapon, GearItem dart)
	{
		Set<Integer> ids = new java.util.HashSet<>();
		for (GearItem item : best.getLoadout().getGear().values())
		{
			if (item != null)
			{
				ids.add(item.getId());
			}
		}
		if (dart != null)
		{
			ids.add(dart.getId());
		}
		if (specWeapon != null)
		{
			ids.add(specWeapon.getId());
		}
		return ids;
	}

	/** "Filter bank": a virtual bank tag showing only this set's items. */
	private StoneButton openDpsCalcButton()
	{
		StoneButton open = new StoneButton(theme, "Open DPS calc", () ->
		{
			if (dpsCalcHook != null && selectedMonster != null && lastShownLoadout != null)
			{
				dpsCalcHook.open(selectedMonster.getId(), selectedMonster.getName(),
					lastShownLoadout, slayerTask.isSelected());
			}
		});
		open.setToolTipText("Open the wiki DPS calculator with this monster and setup mirrored");
		return open;
	}

	/**
	 * The Show-in-bank checkbox applies BOTH the outline and the filter for
	 * the selected style's best set (filtered is the default — the separate
	 * button is gone; Luke, round 5). Re-applied on new results and style
	 * switches; unchecked or cleared = both off.
	 */
	private void applyShowInBank()
	{
		StyleResult detail = lastResults == null || selectedMonster == null
			? null : lastResults.get(detailStyle);
		if (!showInBank.isSelected() || detail == null
			|| detail.owned == null || detail.owned.isEmpty())
		{
			bankHighlighter.highlight(null);
			bankFilter.filter(null);
			return;
		}
		DpsResult best = detail.owned.get(0);
		Set<Integer> ids = new java.util.HashSet<>();
		for (GearItem item : best.getLoadout().getGear().values())
		{
			if (item != null)
			{
				ids.add(item.getId());
			}
		}
		GearItem dart = loadedDart(best);
		if (dart != null)
		{
			ids.add(dart.getId());
		}
		if (detail.specWeapon != null)
		{
			ids.add(detail.specWeapon.getId());
		}
		// trip supplies (food, antidotes...) join the filtered view
		Set<Integer> filterIds = new java.util.HashSet<>(ids);
		filterIds.addAll(mobProfile.filterItems(currentMonsterId(), detailStyle));
		bankHighlighter.highlight(filterIds);
		bankFilter.filter(filterIds);
	}

	/** A left-aligned, height-capped flow row added to the card. */
	private JPanel iconRow(JPanel card)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		card.add(row);
		return row;
	}

	/**
	 * The assumed prayer + boost as icons - prayer-book sprite plus the
	 * potion/heart item icon; names live in the tooltips. Unmapped parts
	 * (e.g. "Current boosted levels") stay as text.
	 */
	private void addAssumesRow(JPanel card, String label, String tooltip)
	{
		if (label == null || label.isEmpty())
		{
			return;
		}
		JPanel row = iconRow(card);
		JLabel prefix = line("Assumes:", MUTED);
		prefix.setToolTipText(tooltip);
		row.add(prefix);
		row.add(assumesChips(label, tooltip));
	}

	/** Just the prayer/boost icon chips - the card HEADER hosts these
	 * inline with the style title to reclaim a whole row of vertical
	 * space (field request); tooltips carry the words. */
	private JPanel assumesChips(String label, String tooltip)
	{
		JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		chips.setOpaque(false);
		chips.setToolTipText(tooltip);
		if (superAntifireAssumed && DragonfireRules.breathesFire(selectedMonster))
		{
			JLabel potion = new JLabel();
			potion.setToolTipText("Super antifire (right-click the shield cell to flip back)");
			attachItemIcon(potion, SUPER_ANTIFIRE_ID);
			chips.add(potion);
		}
		for (String part : label.split(" \\+ "))
		{
			JLabel chip = new JLabel();
			chip.setToolTipText(part);
			int sprite = AssumeIcons.prayerSprite(part);
			int item = AssumeIcons.boostItem(part);
			if (sprite >= 0)
			{
				attachSprite(chip, sprite);
			}
			else if (item >= 0)
			{
				attachItemIcon(chip, item);
			}
			else
			{
				chip.setText(part);
				OsrsSkin.crisp(chip);
				chip.setForeground(MUTED);
				chip.setFont(OsrsSkin.font());
				chip.setBorder(new EmptyBorder(2, 0, 2, 0));
			}
			chips.add(chip);
		}
		return chips;
	}

	/** Game-cache sprite, scaled to line height, set async. */
	private void attachSprite(JLabel label, int spriteId)
	{
		spriteManager.getSpriteAsync(spriteId, 0, img ->
			SwingUtilities.invokeLater(() -> label.setIcon(new ImageIcon(
				img.getScaledInstance(-1, 16, Image.SCALE_SMOOTH)))));
	}

	/** Item icon scaled to line height (the native 36x32 dwarfs a text row). */
	private void attachItemIcon(JLabel label, int itemId)
	{
		AsyncBufferedImage img = itemManager.getImage(itemId);
		Runnable set = () -> label.setIcon(new ImageIcon(
			img.getScaledInstance(-1, 18, Image.SCALE_SMOOTH)));
		img.onLoaded(() -> SwingUtilities.invokeLater(set));
		set.run();
	}

	/**
	 * The set as a fixed 3x4 equipment grid - 11 explicit slots (empty =
	 * empty box) plus the spec weapon as the 12th cell, amber-bordered.
	 * Fixed rows x cols means the preferred height is always right (the
	 * old wrapping grid clipped its second row).
	 */
	private JPanel iconGrid(DpsResult result, SpecialAttack spec, GearItem specWeapon, double specExpected,
		double specDrainValue, double replacedAutoExpected, String specFallbackTooltip)
	{
		return iconGrid(result, spec, specWeapon, specExpected, specDrainValue,
			replacedAutoExpected, specFallbackTooltip, false, null);
	}

	private JPanel iconGrid(DpsResult result, SpecialAttack spec, GearItem specWeapon, double specExpected,
		double specDrainValue, double replacedAutoExpected, String specFallbackTooltip, boolean markUnowned,
		Loadout gameBest)
	{
		// Iron Hub: OSRS worn-equipment arrangement (Inventory Setups style)
		JPanel icons = new JPanel(new GridLayout(5, 3, 1, 1));
		icons.setOpaque(false);
		icons.setAlignmentX(LEFT_ALIGNMENT);
		// Inventory Setups slot geometry: fixed 46x42 boxes
		int cell = 46;
		final int cellH = 42;
		// Wilderness: badge every cell with its death fate (opt-in switch).
		PvpRisk.Assessment fates = null;
		if (markUnowned && wildernessOn())
		{
			fates = PvpRisk.assess(result.getLoadout(), specWeapon,
				protectItem.isSelected() ? 4 : 3);
		}
		if (lastShownLoadout == null)
		{
			Map<com.loadoutlab.data.GearSlot, Integer> shown = new java.util.EnumMap<>(com.loadoutlab.data.GearSlot.class);
			for (GearSlot slotType : GRID_ORDER)
			{
				GearItem shownItem = result.getLoadout().get(slotType);
				if (shownItem != null)
				{
					shown.put(slotType, shownItem.getId());
				}
			}
			lastShownLoadout = shown;
		}
		java.util.Map<GearSlot, RiskDotLabel> bySlot = new java.util.EnumMap<>(GearSlot.class);
		for (GearSlot slotType : GRID_ORDER)
		{
			GearItem item = result.getLoadout().get(slotType);
			RiskDotLabel slot = new RiskDotLabel();
			slot.setPreferredSize(new Dimension(cell, cellH));
			slot.setOpaque(true);
			slot.setBackground(theme.recess);
			slot.setHorizontalAlignment(SwingConstants.CENTER);
			List<JMenuItem> extras = slotType == GearSlot.SHIELD
				? dragonfireMenuEntries() : Collections.emptyList();
			if (item != null)
			{
				// Border language: green = you don't own it (dream/budget
				// upgrade); gold = your item IS the game's best available
				// for this slot; blue = the spec cell (matches the in-game
				// special attack bar).
				boolean unowned = markUnowned && !ownedCheck.owns(item.getId());
				GearItem bisItem = gameBest == null ? null : gameBest.get(slotType);
				// Analogs count: a stat-identical item (any god's d'hide
				// coif) is just as best-available as the exact pick.
				boolean bis = !unowned && bisItem != null
					&& (bisItem.getId() == item.getId() || statEquivalent(bisItem, item));
				Color border = unowned ? BORDER_UNOWNED
					: bis ? BORDER_BIS : theme.edgeLight;
				slot.setBorder(new MatteBorder(1, 1, 1, 1, border));
				// Quest rewards are earned, not bought: name the quest
				// instead of quoting a gp price.
				String quest = QuestRewardItems.questFor(item);
				String obtain = quest != null ? "quest: " + quest
					: PvpRisk.formatGp(item.getPriceOrZero());
				String fate = "";
				if (fates != null)
				{
					if (containsId(fates.kept, item))
					{
						slot.setFate(Fate.KEPT);
						fate = " - protected on death";
					}
					else if (containsId(fates.lost, item))
					{
						slot.setFate(Fate.DROPPED);
						fate = " - lost on death ("
							+ PvpRisk.formatGp(fates.valueOf(item))
							+ imbueRefundNote(item) + ")";
					}
					else
					{
						long fee = feeFor(fates, item);
						long friction = com.loadoutlab.engine.UntradeableDeathCosts.frictionFor(item);
						if (fee > 0 && fee <= friction)
						{
							// Gp-free but a real errand chain (salve line):
							// the charge is all rebuild friction.
							slot.setFate(Fate.FEE);
							fate = " - breaks on death (rebuild errand ~" + PvpRisk.formatGp(friction) + ")";
						}
						else if (fee > 0)
						{
							slot.setFate(Fate.FEE);
							fate = " - replaceable for " + PvpRisk.formatGp(fee) + " on death";
						}
						else if (hasDeathCharge(fates, item))
						{
							slot.setFate(Fate.FEE);
							fate = " - breaks on death (free reclaim)";
						}
					}
				}
				// Location clause only when a fetch trip is needed - "in
				// bank" would be noise on 95% of cells.
				String where = unowned ? "" : locationHint.hint(item.getId());
				Integer pinnedHere = renderingStyle == null ? null
					: mobProfile.pins(currentMonsterId(), renderingStyle).get(slotType);
				String pinNote = pinnedHere != null && pinnedHere == item.getId()
					? " - pinned" : "";
				// Source dot + legend entry: only for locations we know.
				if (!unowned)
				{
					String source = locationHint.primary(item.getId());
					Color sourceColor = SOURCE_COLORS.get(source);
					if (sourceColor != null)
					{
						slot.setSourceDot(sourceColor);
						usedSources.add(source);
					}
				}
				slot.setToolTipText(slotName(slotType) + ": " + item.label()
					+ pinNote
					+ (unowned ? " - NOT OWNED (" + obtain + ")" : "")
					+ (where.isEmpty() ? "" : " - " + where)
					+ (bis ? " - best available" : "")
					+ fate
					+ " (right-click to exclude)");
				AsyncBufferedImage img = itemManager.getImage(item.getId());
				img.addTo(slot);
				List<GearItem> menuItems = new ArrayList<>();
				menuItems.add(item);
				GearItem dart = slotType == GearSlot.WEAPON ? loadedDart(result) : null;
				if (dart != null)
				{
					menuItems.add(dart);
				}
				attachExclusionMenu(slot, menuItems, extras, slotType, renderingStyle);
			}
			else
			{
				slot.setBorder(new MatteBorder(1, 1, 1, 1, theme.edgeDark));
				slot.setToolTipText(slotName(slotType) + ": empty");
				if (!extras.isEmpty())
				{
					attachExclusionMenu(slot, Collections.emptyList(), extras);
				}
			}
			bySlot.put(slotType, slot);
		}
		// The special-attack weapon to swap in (top-right, quiver-position).
		RiskDotLabel specCell = new RiskDotLabel();
		specCell.setPreferredSize(new Dimension(cell, cellH));
		specCell.setOpaque(true);
		specCell.setBackground(theme.recess);
		specCell.setHorizontalAlignment(SwingConstants.CENTER);
		if (spec != null && specWeapon != null && specExpected > 0)
		{
			// Light sky blue, sampled from the in-game spec orb's gradient.
			specCell.setBorder(new MatteBorder(1, 1, 1, 1, BORDER_SPEC));
			String specFate = "";
			if (fates != null && specWeapon != null)
			{
				if (containsId(fates.kept, specWeapon))
				{
					specCell.setFate(Fate.KEPT);
					specFate = "<br>Protected on death.";
				}
				else if (containsId(fates.lost, specWeapon))
				{
					specCell.setFate(Fate.DROPPED);
					specFate = "<br>Lost on death ("
						+ PvpRisk.formatGp(fates.valueOf(specWeapon)) + ").";
				}
				else if (feeFor(fates, specWeapon) == 0 && hasDeathCharge(fates, specWeapon))
				{
					specCell.setFate(Fate.FEE);
					specFate = "<br>Breaks on death (free reclaim).";
				}
				else if (feeFor(fates, specWeapon) > 0)
				{
					specCell.setFate(Fate.FEE);
					specFate = "<br>Replaceable for "
						+ PvpRisk.formatGp(feeFor(fates, specWeapon)) + " on death.";
				}
			}
			String specTip = specTooltip(spec, specExpected,
				specDrainValue, replacedAutoExpected, specFallbackTooltip);
			specCell.setToolTipText(specFate.isEmpty() ? specTip
				: specTip.replace("</html>", specFate + "</html>"));
			itemManager.getImage(specWeapon.getId()).addTo(specCell);
			attachExclusionMenu(specCell, List.of(specWeapon));
		}
		else
		{
			specCell.setBorder(new MatteBorder(1, 1, 1, 1, theme.edgeDark));
			specCell.setToolTipText("Spec: none");
		}
		// blank | head | spec, cape | neck | ammo, weapon | body | shield,
		// blank | legs | blank, hands | feet | ring — the in-game layout
		GearSlot[][] arrangement = {
			{null, GearSlot.HEAD, null},
			{GearSlot.CAPE, GearSlot.NECK, GearSlot.AMMO},
			{GearSlot.WEAPON, GearSlot.BODY, GearSlot.SHIELD},
			{null, GearSlot.LEGS, null},
			{GearSlot.HANDS, GearSlot.FEET, GearSlot.RING},
		};
		boolean specPlaced = false;
		for (GearSlot[] row : arrangement)
		{
			for (GearSlot slotType : row)
			{
				if (slotType != null)
				{
					icons.add(bySlot.get(slotType));
				}
				else if (!specPlaced)
				{
					icons.add(specCell); // first gap = the quiver position
					specPlaced = true;
				}
				else
				{
					// non-slots show the card's stone fill, as the game's own
					// worn-equipment screen leaves them bare
					JLabel blank = new JLabel();
					blank.setPreferredSize(new Dimension(cell, cellH));
					blank.setOpaque(false);
					icons.add(blank);
				}
			}
		}
		// Fixed Inventory-Setups geometry: pin the grid so BoxLayout parents
		// can neither stretch nor squash it, and centre it in the panel.
		Dimension gridSize = new Dimension(3 * cell + 2, 5 * cellH + 4);
		icons.setPreferredSize(gridSize);
		icons.setMinimumSize(gridSize);
		icons.setMaximumSize(gridSize);
		JPanel centered = new JPanel();
		centered.setLayout(new BoxLayout(centered, BoxLayout.X_AXIS));
		centered.setOpaque(false);
		centered.setAlignmentX(LEFT_ALIGNMENT);
		centered.setMaximumSize(new Dimension(Integer.MAX_VALUE, gridSize.height));
		centered.add(Box.createHorizontalGlue());
		centered.add(icons);
		centered.add(Box.createHorizontalGlue());
		return centered;
	}

	/** Same combat stats in every block - interchangeable for dps. */
	private static boolean statEquivalent(GearItem a, GearItem b)
	{
		return a.getSlot() == b.getSlot()
			&& a.getSpeed() == b.getSpeed()
			&& a.isTwoHanded() == b.isTwoHanded()
			&& a.getCategory().equals(b.getCategory())
			&& sameBlock(a.getOffensive(), b.getOffensive())
			&& sameBlock(a.getDefensive(), b.getDefensive())
			&& sameBlock(a.getBonuses(), b.getBonuses());
	}

	private static boolean sameBlock(StatBlock a, StatBlock b)
	{
		return a.getStab() == b.getStab() && a.getSlash() == b.getSlash()
			&& a.getCrush() == b.getCrush() && a.getMagic() == b.getMagic()
			&& a.getRanged() == b.getRanged() && a.getStrength() == b.getStrength()
			&& a.getRangedStrength() == b.getRangedStrength()
			&& a.getMagicDamage() == b.getMagicDamage()
			&& a.getPrayer() == b.getPrayer();
	}

	private static boolean containsId(List<GearItem> items, GearItem item)
	{
		for (GearItem candidate : items)
		{
			if (candidate.getId() == item.getId())
			{
				return true;
			}
		}
		return false;
	}

	private static long feeFor(PvpRisk.Assessment fates, GearItem item)
	{
		for (PvpRisk.Charge charge : fates.untradeableCharges)
		{
			if (charge.item.getId() == item.getId())
			{
				return charge.costGp;
			}
		}
		return 0;
	}

	/**
	 * Imbued convert-class items (rings (i), ...) drop UNIMBUED to the
	 * killer and the imbue points are fully refunded (April 2024 change) -
	 * without saying so, the bare gp figure looks like it forgot the imbue
	 * (field report: warrior ring (i) at 60k read as a bad suggestion).
	 */
	private static String imbueRefundNote(GearItem item)
	{
		if (com.loadoutlab.engine.UntradeableDeathCosts.categoryFor(item) == 4
			&& (item.getNameLower().endsWith("(i)") || item.getNameLower().endsWith("(ei)")))
		{
			return com.loadoutlab.engine.UntradeableDeathCosts.frictionFor(item) > 0
				? "; incl. rebuild errand - imbue points refunded"
				: "; drops unimbued, imbue points refunded";
		}
		return "";
	}

	/** True when the item breaks on death even at zero reclaim cost. */
	private static boolean hasDeathCharge(PvpRisk.Assessment fates, GearItem item)
	{
		for (PvpRisk.Charge charge : fates.untradeableCharges)
		{
			if (charge.item.getId() == item.getId())
			{
				return true;
			}
		}
		return false;
	}

	private static String slotName(GearSlot slot)
	{
		String name = slot.name().toLowerCase();
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private static String capitalize(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
