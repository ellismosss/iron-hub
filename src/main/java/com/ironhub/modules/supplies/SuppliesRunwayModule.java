package com.ironhub.modules.supplies;

import com.ironhub.modules.IronHubModule;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Supplies Runway: rolling consumption rates x bank stock = hours of runway
 * per consumable, with shortfall alerts, restock guidance, and a planning mode
 * ("100 Zulrah kills" -> projected supply bill). See DESIGN.md §3.17.
 */
@Slf4j
@Singleton
public class SuppliesRunwayModule implements IronHubModule
{
	@Inject
	public SuppliesRunwayModule()
	{
	}

	@Override
	public String name()
	{
		return "Supplies runway";
	}

	@Override
	public void startUp()
	{
		// TODO: rolling-rate model over Loot/Supplies data; threshold alerts;
		// restock method links; planning-mode calculator
	}

	@Override
	public void shutDown()
	{
	}
}
