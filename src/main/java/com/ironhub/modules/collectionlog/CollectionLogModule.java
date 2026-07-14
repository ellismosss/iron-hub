package com.ironhub.modules.collectionlog;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import com.ironhub.state.AccountState;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Collection log (DESIGN.md §3.18): overall progress parsed from the log
 * window title when it opens ("open your log to sync" — the graceful
 * degrade the risk register prescribes) plus per-source kill counts from
 * loot tracking. Per-slot detail and dry-streak stats come later.
 */
@Slf4j
@Singleton
public class CollectionLogModule implements IronHubModule
{
	static final Pattern TITLE = Pattern.compile("Collection Log - ([\\d,]+)/([\\d,]+)");

	private final AccountState state;
	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final IronHubConfig config;
	private CollectionLogTab tab;

	@Inject
	public CollectionLogModule(AccountState state, Client client, ClientThread clientThread,
		EventBus eventBus, IronHubConfig config)
	{
		this.state = state;
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Collection log";
	}

	@Override
	public boolean enabled()
	{
		return config.collectionLog();
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
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
			tab = new CollectionLogTab(state);
		}
		return tab;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.COLLECTION && clientThread != null)
		{
			// the title text populates after the interface's scripts run
			clientThread.invokeLater(this::readLogTitle);
		}
	}

	private void readLogTitle()
	{
		Widget frame = client.getWidget(InterfaceID.Collection.FRAME);
		if (frame == null)
		{
			return;
		}
		if (!scanForTitle(frame))
		{
			log.debug("collection log title not found on open");
		}
	}

	private boolean scanForTitle(Widget widget)
	{
		int[] parsed = parseTitle(widget.getText());
		if (parsed != null)
		{
			state.recordCollectionLog(parsed[0], parsed[1]);
			return true;
		}
		for (Widget[] children : new Widget[][]{
			widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null && scanForTitle(child))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/** "[slots, total]" from a log title, or null. Static for tests. */
	static int[] parseTitle(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher matcher = TITLE.matcher(text);
		if (!matcher.find())
		{
			return null;
		}
		return new int[]{
			Integer.parseInt(matcher.group(1).replace(",", "")),
			Integer.parseInt(matcher.group(2).replace(",", ""))};
	}
}
