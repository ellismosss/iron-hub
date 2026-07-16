package com.ironhub.modules.designlab;

import com.ironhub.IronHubConfig;
import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;

/**
 * Development-stage test surface for the OSRS "stonework" design system
 * (design/OSRS-SKIN.md): renders the atom gallery in the real sidebar so the
 * skin can be judged in-client before any module adopts it.
 */
@Singleton
public class DesignLabModule implements IronHubModule
{
	private final IronHubConfig config;
	private DesignLabTab tab;

	@Inject
	public DesignLabModule(IronHubConfig config)
	{
		this.config = config;
	}

	@Override
	public String name()
	{
		return "Design lab";
	}

	@Override
	public boolean enabled()
	{
		return config.designLab();
	}

	@Override
	public void startUp()
	{
	}

	@Override
	public void shutDown()
	{
		tab = null;
	}

	@Override
	public JComponent buildTab()
	{
		if (tab == null)
		{
			tab = new DesignLabTab();
		}
		return tab;
	}
}
