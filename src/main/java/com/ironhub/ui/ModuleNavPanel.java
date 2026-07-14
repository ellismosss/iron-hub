package com.ironhub.ui;

import com.ironhub.ui.components.HubScrollPane;
import com.ironhub.ui.components.NavHeader;
import com.ironhub.ui.components.SearchField;
import com.ironhub.ui.components.SectionLabel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Module navigation (mockup frame 1c): back header, search field, category
 * accordion of module rows. Typing in the search flattens the accordion to a
 * filtered flat list. Two-letter icon squares mark module sprite sites.
 *
 * M1: placeholder badges; rows navigate nowhere until module tabs exist.
 */
public class ModuleNavPanel extends JPanel
{
	private static final Category[] CATEGORIES = {
		new Category("Progression",
			new Module("GE", "Gear progression", null, null),
			new Module("QU", "Quests", null, null),
			new Module("SK", "Skill milestones", null, null),
			new Module("DI", "Achievement diaries", null, null),
			new Module("CA", "Combat achievements", null, null),
			new Module("CL", "Collection log", null, null)),
		new Category("Planning",
			new Module("GO", "Goal planner", "3 goals", UiTokens.TEXT_MUTED),
			new Module("WN", "What now?", null, null),
			new Module("RW", "Supplies runway", "!", UiTokens.STATUS_WARNING)),
		new Category("Daily loop",
			new Module("FA", "Farming runs", "7", UiTokens.STATUS_AVAILABLE),
			new Module("DA", "Dailies", "4", UiTokens.STATUS_AVAILABLE),
			new Module("SL", "Slayer", "87 left", UiTokens.TEXT_MUTED),
			new Module("CS", "Clues & STASH", null, null)),
		new Category("Account",
			new Module("BK", "Bank & banked XP", null, null),
			new Module("LS", "Loot & supplies", null, null),
			new Module("QL", "QoL checklist", null, null),
			new Module("BT", "Boat upgrades", null, null),
			new Module("DR", "Death recovery", null, null)),
	};

	private final Set<String> collapsed = new HashSet<>();
	private final JPanel list = new JPanel();
	private final SearchField search = new SearchField("Search modules…");
	private final java.util.function.Consumer<String> onOpenModule;

	public ModuleNavPanel(Runnable onBack, java.util.function.Consumer<String> onOpenModule)
	{
		this.onOpenModule = onOpenModule;
		setLayout(new BorderLayout());
		setBackground(UiTokens.PANEL_BG);
		collapsed.add("Account"); // shown collapsed in the mockup

		// header + search stay fixed; the category list scrolls below them
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(UiTokens.PANEL_BG);
		top.add(new NavHeader("Modules", onBack));

		JPanel searchStrip = new JPanel();
		searchStrip.setLayout(new BoxLayout(searchStrip, BoxLayout.Y_AXIS));
		searchStrip.setBackground(UiTokens.PANEL_BG);
		searchStrip.setAlignmentX(LEFT_ALIGNMENT);
		searchStrip.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, UiTokens.BORDER_ROW),
			new EmptyBorder(UiTokens.PAD, UiTokens.PAD, UiTokens.PAD, UiTokens.PAD)));
		searchStrip.add(search);
		top.add(searchStrip);
		add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(UiTokens.PANEL_BG);
		add(new HubScrollPane(list), BorderLayout.CENTER);

		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});
		rebuild();
	}

	private void rebuild()
	{
		list.removeAll();
		String filter = search.getText().trim().toLowerCase(Locale.ROOT);

		if (filter.isEmpty())
		{
			for (Category category : CATEGORIES)
			{
				list.add(categoryHeader(category));
				if (!collapsed.contains(category.name))
				{
					for (Module module : category.modules)
					{
						list.add(new NavRow(module, onOpenModule));
					}
				}
			}
		}
		else
		{
			// typing flattens the accordion to a filtered list
			for (Category category : CATEGORIES)
			{
				for (Module module : category.modules)
				{
					if (module.name.toLowerCase(Locale.ROOT).contains(filter))
					{
						list.add(new NavRow(module, onOpenModule));
					}
				}
			}
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel categoryHeader(Category category)
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setBackground(UiTokens.CARD_BG);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, UiTokens.BORDER_ROW),
			new EmptyBorder(0, UiTokens.PAD, 0, UiTokens.PAD)));
		header.setPreferredSize(new Dimension(0, UiTokens.CATEGORY_HEADER_HEIGHT));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.CATEGORY_HEADER_HEIGHT));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		boolean isCollapsed = collapsed.contains(category.name);
		JLabel chevron = new JLabel(isCollapsed ? "▸" : "▾");
		chevron.setForeground(UiTokens.TEXT_MUTED);
		chevron.setFont(chevron.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_TILE_LABEL));
		chevron.setPreferredSize(new Dimension(10, 0));
		chevron.setMaximumSize(new Dimension(10, Integer.MAX_VALUE));
		header.add(chevron);
		header.add(Box.createHorizontalStrut(UiTokens.ROW_GAP));

		header.add(new SectionLabel(category.name));
		header.add(Box.createHorizontalGlue());

		JLabel count = new JLabel(String.valueOf(category.modules.size()));
		count.setForeground(UiTokens.TEXT_FAINT);
		count.setFont(count.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_LABEL));
		header.add(count);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!collapsed.remove(category.name))
				{
					collapsed.add(category.name);
				}
				rebuild();
			}
		});
		return header;
	}

	private static class Category
	{
		final String name;
		final List<Module> modules;

		Category(String name, Module... modules)
		{
			this.name = name;
			this.modules = new ArrayList<>(List.of(modules));
		}
	}

	private static class Module
	{
		final String code;
		final String name;
		final String badge;
		final Color badgeColor;

		Module(String code, String name, String badge, Color badgeColor)
		{
			this.code = code;
			this.name = name;
			this.badge = badge;
			this.badgeColor = badgeColor;
		}
	}

	/** All nav row names, in display order — for wiring tests. */
	static List<String> moduleNames()
	{
		List<String> names = new ArrayList<>();
		for (Category category : CATEGORIES)
		{
			for (Module module : category.modules)
			{
				names.add(module.name);
			}
		}
		return names;
	}

	/** 26 px borderless nav row: icon square · name · badge · › (hover fill). */
	private static class NavRow extends JPanel
	{
		NavRow(Module module, java.util.function.Consumer<String> onOpen)
		{
			setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
			setBackground(UiTokens.PANEL_BG);
			setAlignmentX(LEFT_ALIGNMENT);
			setBorder(new EmptyBorder(0, UiTokens.PAD, 0, UiTokens.PAD));
			setPreferredSize(new Dimension(0, UiTokens.ROW_HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.ROW_HEIGHT));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			JLabel icon = new JLabel(module.code, SwingConstants.CENTER);
			icon.setOpaque(true);
			icon.setBackground(UiTokens.BORDER); // sprite placeholder square
			icon.setForeground(UiTokens.GLYPH_MUTED);
			icon.setFont(icon.getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_TILE_CODE));
			Dimension iconSize = new Dimension(UiTokens.NAV_ICON_SIZE, UiTokens.NAV_ICON_SIZE);
			icon.setPreferredSize(iconSize);
			icon.setMinimumSize(iconSize);
			icon.setMaximumSize(iconSize);
			add(icon);
			add(Box.createHorizontalStrut(UiTokens.ROW_GAP + 1)); // mockup gap 7

			JLabel name = new JLabel(module.name);
			name.setForeground(UiTokens.TEXT_BODY);
			name.setFont(name.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
			name.setToolTipText(module.name);
			name.setMinimumSize(new Dimension(0, 0));
			add(name);
			add(Box.createHorizontalGlue());

			if (module.badge != null)
			{
				JLabel badge = new JLabel(module.badge);
				badge.setForeground(module.badgeColor);
				boolean muted = module.badgeColor == UiTokens.TEXT_MUTED;
				badge.setFont(badge.getFont().deriveFont(
					muted ? Font.PLAIN : Font.BOLD, UiTokens.FONT_SIZE_LABEL));
				add(badge);
				add(Box.createHorizontalStrut(UiTokens.ROW_GAP + 1));
			}

			JLabel chevron = new JLabel("›");
			chevron.setForeground(UiTokens.TEXT_FAINT);
			chevron.setFont(chevron.getFont().deriveFont(Font.PLAIN, UiTokens.FONT_SIZE_BODY));
			add(chevron);

			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					setBackground(UiTokens.NAV_ROW_HOVER_BG);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setBackground(UiTokens.PANEL_BG);
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					// rows whose module has no tab yet are inert
					if (onOpen != null)
					{
						onOpen.accept(module.name);
					}
				}
			});
		}
	}
}
