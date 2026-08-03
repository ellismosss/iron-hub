package com.ironhub.modules.porttasks;

import com.ironhub.data.PortTasksPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
import com.ironhub.ui.v2.V2EmptyState;
import com.ironhub.ui.v2.V2Layout;
import com.ironhub.ui.v2.V2Surface;
import com.ironhub.ui.v2.V2Tokens;
import com.ironhub.ui.v2.V2Well;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;

/**
 * Port tasks tab: the five accepted task slots with live progress, the
 * last-seen noticeboard ranked by the advisor (Sailing XP per tile added
 * to your route), and every noticeboard port with its level gate — click
 * a port to mark it preferred (preferred float to the top). Frameless —
 * the host names the module.
 */
class PortTasksTab extends JPanel
{
	/** The system's list cap (§7). */
	private static final int MAX_PORT_ROWS = 20;

	private final AccountState state;
	private final PortTasksModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final JPanel content = new JPanel();
	/** The ports list re-fills alone on a search keystroke (PS1 2026-08-03)
	 *  — a full rebuild would tear the field out from under the caret. */
	private final JPanel portsHolder = new JPanel();
	private final com.ironhub.ui.v2.V2TextField portSearch;
	private String portFilter = "";

	PortTasksTab(AccountState state, PortTasksModule module, OsrsTheme theme,
		ItemManager itemManager)
	{
		this.state = state;
		this.module = module;
		this.theme = theme;
		this.itemManager = itemManager;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);
		setBackground(theme.background);
		setBorder(new EmptyBorder(4, 4, 4, 4));

		portsHolder.setLayout(new BoxLayout(portsHolder, BoxLayout.Y_AXIS));
		portsHolder.setOpaque(false);
		portsHolder.setAlignmentX(LEFT_ALIGNMENT);
		portSearch = new com.ironhub.ui.v2.V2TextField(theme, "Search ports...",
			this::portSearchChanged);

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

	void rebuild()
	{
		content.removeAll();
		if (module.pack() == null)
		{
			content.add(line("Port-tasks pack unavailable.", OsrsSkin.FAINT));
			finish();
			return;
		}
		addSummary();
		addActiveTasks();
		addBoard();
		addPorts();
		finish();
	}

	private void finish()
	{
		content.revalidate();
		content.repaint();
	}

	private void addSummary()
	{
		// the day's standing is the one live readout on the page — the
		// reference HERO shape (PS1 2026-08-03): flanking emblems, a centred
		// headline, the standing beneath. No bar: the daily counter has no
		// completion target (the board merely restocks every 8) and a bar
		// with no target would invent one.
		V2Surface hero = V2Surface.card(theme);
		JPanel top = rows();
		JPanel line = rowLine();
		line.add(wheelEmblem());
		line.add(Box.createHorizontalGlue());
		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		OsrsLabel title = new OsrsLabel("Completed today", OsrsSkin.TITLE, OsrsSkin.font());
		title.setAlignmentX(CENTER_ALIGNMENT);
		middle.add(title);
		OsrsLabel count = new OsrsLabel(String.valueOf(module.completedToday()),
			OsrsSkin.TITLE, OsrsSkin.boldFont());
		count.setAlignmentX(CENTER_ALIGNMENT);
		middle.add(count);
		line.add(middle);
		line.add(Box.createHorizontalGlue());
		line.add(wheelEmblem());
		cap(line);
		top.add(line);
		JPanel standing = rowLine();
		standing.add(Box.createHorizontalGlue());
		standing.add(new OsrsLabel(module.freeSlots() + " slots free",
			OsrsSkin.FAINT, OsrsSkin.smallFont()));
		standing.add(Box.createHorizontalGlue());
		cap(standing);
		top.add(standing);
		hero.add(top);
		cap(hero);
		content.add(hero);
	}

	/** The ship's-wheel emblem flanking the hero (the Boats hero's own). */
	private JComponent wheelEmblem()
	{
		javax.swing.JLabel icon = new javax.swing.JLabel();
		icon.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		String key = "icons/sailing/steering_large";
		if (com.ironhub.ui.v2.V2Sprites.has(key))
		{
			icon.setIcon(new javax.swing.ImageIcon(
				com.ironhub.ui.v2.V2Sprites.get(theme, key)));
		}
		else
		{
			icon.setPreferredSize(new Dimension(24, 24));
		}
		return icon;
	}

	// ── accepted tasks ────────────────────────────────────────────────

	private void addActiveTasks()
	{
		List<PortTasksModule.ActiveTask> tasks = module.activeTasks();
		if (tasks.isEmpty())
		{
			content.add(header("Active tasks"));
			content.add(V2EmptyState.empty(theme, "No port tasks accepted."));
			return;
		}
		JPanel well = section("Active tasks");
		for (PortTasksModule.ActiveTask task : tasks)
		{
			well.add(taskRow(task));
		}
		if (!module.catalogLoaded())
		{
			well.add(line("Task names sync from the game cache on login.",
				OsrsSkin.FAINT));
		}
		addSection(well);
	}

	private JComponent taskRow(PortTasksModule.ActiveTask task)
	{
		JPanel row = rows();
		JPanel top = rowLine();
		String name = task.courier != null ? task.courier.name
			: task.bounty != null ? task.bounty.name
			: "Task #" + task.taskId;
		top.add(new OsrsLabel(name == null ? "Task #" + task.taskId : name,
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		int xp = task.courier != null ? task.courier.xp
			: task.bounty != null ? task.bounty.xp : 0;
		if (xp > 0)
		{
			top.add(new OsrsLabel(xp + " xp", OsrsSkin.LABEL, OsrsSkin.smallFont()));
		}
		cap(top);
		row.add(top);

		if (task.courier != null)
		{
			PortTasksModule.CourierInfo c = task.courier;
			row.add(sub(portName(c.cargoPort) + " to " + portName(c.deliverPort),
				OsrsSkin.FAINT));
			String progress;
			Color color;
			if (task.delivered >= c.cargoAmount)
			{
				progress = "Claim rewards!";
				color = OsrsSkin.VALUE;
			}
			else if (task.taken >= c.cargoAmount)
			{
				progress = "Delivered " + task.delivered + "/" + c.cargoAmount;
				color = ColorScheme.PROGRESS_INPROGRESS_COLOR;
			}
			else
			{
				progress = "Cargo " + task.taken + "/" + c.cargoAmount;
				color = UiTokens.STATUS_WARNING;
			}
			row.add(sub(progress, color));
		}
		else if (task.bounty != null)
		{
			PortTasksModule.BountyInfo b = task.bounty;
			row.add(sub(portName(b.port) + " · items " + task.collected + "/" + b.qty
				+ (b.rarity > 1 ? " (1 in " + b.rarity + ")" : ""),
				task.collected >= b.qty ? OsrsSkin.VALUE : OsrsSkin.FAINT));
		}
		cap(row);
		return row;
	}

	// ── the noticeboard advisor ───────────────────────────────────────

	private void addBoard()
	{
		List<PortTasksModule.Advice> ranked = module.rankOffersCached();
		if (ranked.isEmpty())
		{
			content.add(header("Noticeboard"));
			content.add(V2EmptyState.unknown(theme, "No offers ranked yet",
				"Open a port task board and its offers rank here by xp per tile "
					+ "added to your route."));
			return;
		}
		JPanel well = section("Noticeboard");
		if (!module.boardOpen())
		{
			well.add(line("As of the last board you opened:", OsrsSkin.FAINT));
		}
		int rank = 1;
		for (PortTasksModule.Advice advice : ranked)
		{
			well.add(adviceRow(rank++, advice));
		}
		addSection(well);
	}

	private JComponent adviceRow(int rank, PortTasksModule.Advice advice)
	{
		JPanel row = rows();
		JPanel top = rowLine();
		Color color = advice.levelGated || advice.alreadyTaken ? OsrsSkin.FAINT
			: advice.courier != null && rank == 1 ? OsrsSkin.VALUE : OsrsSkin.MUTED;
		top.add(new OsrsLabel(rank + ". " + advice.label, color,
			OsrsSkin.font()).leftAligned().squeezable());
		top.add(Box.createHorizontalGlue());
		if (advice.xp > 0)
		{
			top.add(new OsrsLabel(advice.xp + " xp", OsrsSkin.LABEL, OsrsSkin.smallFont()));
		}
		cap(top);
		row.add(top);
		if (advice.alreadyTaken)
		{
			row.add(sub("Already accepted", OsrsSkin.FAINT));
		}
		else if (advice.levelGated)
		{
			int level = advice.courier != null ? advice.courier.level : advice.bounty.level;
			row.add(sub("Needs Sailing " + level, OsrsSkin.FAINT));
		}
		else if (advice.courier != null)
		{
			row.add(sub(Double.isNaN(advice.marginalTiles) ? "Route unknown"
				: "+" + Math.round(advice.marginalTiles) + " tiles to your route",
				OsrsSkin.FAINT));
		}
		else
		{
			row.add(sub("Bounty · kill time unknown", OsrsSkin.FAINT));
		}
		cap(row);
		return row;
	}

	// ── ports ─────────────────────────────────────────────────────────

	private void portSearchChanged()
	{
		portFilter = portSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
		fillPorts();
	}

	private void addPorts()
	{
		JPanel well = section("Noticeboard ports");
		if (!module.catalogLoaded())
		{
			well.add(line("Best-task scores appear once the task catalog "
				+ "has synced from the game (log in).", OsrsSkin.FAINT));
		}
		// search where the list is long (PS1 2026-08-03, the clog grammar) —
		// the "+ N more" tail was honest but unreachable without it
		well.add(portSearch);
		well.add(portsHolder);
		fillPorts();
		addSection(well);
		content.add(line("Click a port to mark it preferred - preferred ports "
			+ "stay on top.", OsrsSkin.FAINT));
	}

	private void fillPorts()
	{
		portsHolder.removeAll();
		List<PortTasksModule.PortSuggestion> ports = module.portSuggestions();
		if (!portFilter.isEmpty())
		{
			ports = new java.util.ArrayList<>(ports);
			ports.removeIf(s -> !s.port.name.toLowerCase(java.util.Locale.ROOT)
				.contains(portFilter));
		}
		// §7: a list caps at 20 rows with an honest "+ N more" — preferred
		// ports sort to the top, and the search reaches the tail
		int shown = 0;
		for (PortTasksModule.PortSuggestion s : ports)
		{
			if (shown++ >= MAX_PORT_ROWS)
			{
				portsHolder.add(line("+ " + (ports.size() - MAX_PORT_ROWS)
					+ " more ports - search to narrow", OsrsSkin.FAINT));
				break;
			}
			portsHolder.add(portRow(s));
		}
		if (ports.isEmpty() && !portFilter.isEmpty())
		{
			portsHolder.add(line("No port matches.", OsrsSkin.FAINT));
		}
		portsHolder.revalidate();
		portsHolder.repaint();
	}

	private JComponent portRow(PortTasksModule.PortSuggestion s)
	{
		JPanel row = rows();
		JPanel top = rowLine();
		Color color = s.preferred ? OsrsSkin.TITLE
			: s.unlocked ? OsrsSkin.MUTED : OsrsSkin.FAINT;
		OsrsLabel name = new OsrsLabel(s.port.name, color, OsrsSkin.font())
			.leftAligned().squeezable();
		name.setToolTipText(s.preferred ? s.port.name + " - preferred; click to unmark"
			: "Mark " + s.port.name + " preferred");
		top.add(name);
		top.add(Box.createHorizontalGlue());
		if (!s.unlocked && s.port.level != null)
		{
			top.add(new OsrsLabel("Sailing " + s.port.level, OsrsSkin.FAINT,
				OsrsSkin.smallFont()));
		}
		else if (s.bestScore > 0)
		{
			top.add(new OsrsLabel(oneDp(s.bestScore) + " xp/tile", OsrsSkin.LABEL,
				OsrsSkin.smallFont()));
		}
		cap(top);
		row.add(top);
		if (s.bestScore > 0 && s.bestLabel != null)
		{
			row.add(sub("Best: " + s.bestLabel, OsrsSkin.FAINT));
		}
		// the tooltipped name label eats the press (deepest-component
		// dispatch) — relay so the toggle works over the text too
		com.ironhub.ui.v2.MouseRelay.install(row);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				module.togglePreferred(s.port.dbrow); // state listener rebuilds
			}
		});
		cap(row);
		return row;
	}

	// ── shared bits ───────────────────────────────────────────────────

	private String portName(int dbrow)
	{
		PortTasksPack.Port port = module.pack().port(dbrow);
		return port == null ? "Port #" + dbrow : port.name;
	}

	private static String oneDp(double value)
	{
		return String.valueOf(Math.round(value * 10) / 10.0);
	}

	private JComponent header(String text)
	{
		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setBorder(new EmptyBorder(8, 4, 3, 4));
		head.add(new OsrsLabel(text, OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		head.add(Box.createHorizontalGlue());
		cap(head);
		return head;
	}

	/**
	 * A titled block in the SlayerTab grammar: a bare header on the backing,
	 * then its rows in a Well at the list inset (§4). Returns the WELL, so
	 * {@code well.add(row)} lands rows inside it; {@link #addSection} adds the
	 * column holding both.
	 */
	private JPanel section(String title)
	{
		JPanel block = V2Layout.column();
		block.add(header(title));
		V2Surface well = V2Surface.well(theme);
		int inset = V2Well.CAP + V2Tokens.TIGHT;
		well.setBorder(new EmptyBorder(inset, inset, inset, inset));
		block.add(well);
		return well;
	}

	/** Takes the WELL {@link #section} handed back and adds the column. */
	private void addSection(JPanel well)
	{
		java.awt.Container column = well.getParent();
		JComponent block = column instanceof JComponent ? (JComponent) column : well;
		cap(block);
		content.add(block);
	}

	private static JPanel rows()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(new EmptyBorder(1, UiTokens.ROW_GAP, 1, UiTokens.ROW_GAP));
		return row;
	}

	private static JPanel rowLine()
	{
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.setOpaque(false);
		top.setAlignmentX(LEFT_ALIGNMENT);
		return top;
	}

	private JComponent sub(String text, Color color)
	{
		JPanel holder = new JPanel();
		holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, UiTokens.PAD, 1, 0));
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
