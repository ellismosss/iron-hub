package com.ironhub.modules.porttasks;

import com.ironhub.data.PortTasksPack;
import com.ironhub.state.AccountState;
import com.ironhub.ui.UiTokens;
import com.ironhub.ui.osrs.OsrsLabel;
import com.ironhub.ui.osrs.OsrsSkin;
import com.ironhub.ui.osrs.OsrsTheme;
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
	private final AccountState state;
	private final PortTasksModule module;
	private final OsrsTheme theme;
	private final ItemManager itemManager; // null headless
	private final Runnable listener = com.ironhub.ui.components.RebuildGate.install(this, this::rebuild);

	private final JPanel content = new JPanel();

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
		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setBorder(new EmptyBorder(2, 4, 2, 4));
		head.add(new OsrsLabel("Completed today: " + module.completedToday(),
			OsrsSkin.MUTED, OsrsSkin.font()).leftAligned());
		head.add(Box.createHorizontalGlue());
		head.add(new OsrsLabel(module.freeSlots() + " slots free",
			OsrsSkin.FAINT, OsrsSkin.smallFont()));
		cap(head);
		content.add(head);
	}

	// ── accepted tasks ────────────────────────────────────────────────

	private void addActiveTasks()
	{
		content.add(header("Active tasks"));
		List<PortTasksModule.ActiveTask> tasks = module.activeTasks();
		if (tasks.isEmpty())
		{
			content.add(line("No port tasks accepted.", OsrsSkin.FAINT));
			return;
		}
		for (PortTasksModule.ActiveTask task : tasks)
		{
			content.add(taskRow(task));
		}
		if (!module.catalogLoaded())
		{
			content.add(line("Task names sync from the game cache on login.",
				OsrsSkin.FAINT));
		}
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
		content.add(header("Noticeboard"));
		List<PortTasksModule.Advice> ranked = module.rankOffers();
		if (ranked.isEmpty())
		{
			content.add(line("Open a port task board and its offers rank here "
				+ "by xp per tile added to your route.", OsrsSkin.FAINT));
			return;
		}
		if (!module.boardOpen())
		{
			content.add(line("As of the last board you opened:", OsrsSkin.FAINT));
		}
		int rank = 1;
		for (PortTasksModule.Advice advice : ranked)
		{
			content.add(adviceRow(rank++, advice));
		}
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

	private void addPorts()
	{
		content.add(header("Noticeboard ports"));
		List<PortTasksModule.PortSuggestion> ports = module.portSuggestions();
		if (!module.catalogLoaded())
		{
			content.add(line("Best-task scores appear once the task catalog "
				+ "has synced from the game (log in).", OsrsSkin.FAINT));
		}
		for (PortTasksModule.PortSuggestion s : ports)
		{
			content.add(portRow(s));
		}
		content.add(line("Click a port to mark it preferred - preferred ports "
			+ "stay on top.", OsrsSkin.FAINT));
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
		head.add(new OsrsLabel(text, OsrsSkin.TITLE, OsrsSkin.boldFont()).leftAligned());
		head.add(Box.createHorizontalGlue());
		cap(head);
		return head;
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
