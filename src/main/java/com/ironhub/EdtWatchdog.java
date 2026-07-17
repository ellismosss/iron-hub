package com.ironhub;

import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * Freeze detector (2026-07-17): a daemon that heartbeats the EDT once a
 * second and, when a beat goes unanswered past the stall threshold, logs
 * one WARN with the stacks of the EDT and the game's Client thread — so
 * the next in-client freeze leaves evidence in client.log instead of an
 * anecdote. Silent when healthy; one log line per stall episode.
 */
@Slf4j
final class EdtWatchdog
{
	private static final long PERIOD_MS = 1_000;
	private static final long STALL_MS = 500;

	private volatile Thread edt;
	private volatile long lastBeatMs;
	private volatile boolean running;
	private Thread thread;

	synchronized void start()
	{
		if (running)
		{
			return;
		}
		running = true;
		lastBeatMs = System.currentTimeMillis();
		thread = new Thread(this::loop, "iron-hub-freeze-watchdog");
		thread.setDaemon(true);
		thread.start();
	}

	synchronized void stop()
	{
		running = false;
		if (thread != null)
		{
			thread.interrupt();
			thread = null;
		}
	}

	private void loop()
	{
		boolean reported = false;
		while (running)
		{
			SwingUtilities.invokeLater(() ->
			{
				edt = Thread.currentThread();
				lastBeatMs = System.currentTimeMillis();
			});
			try
			{
				Thread.sleep(PERIOD_MS);
			}
			catch (InterruptedException e)
			{
				return;
			}
			long silentMs = System.currentTimeMillis() - lastBeatMs;
			if (silentMs > PERIOD_MS + STALL_MS)
			{
				if (!reported)
				{
					reported = true;
					log.warn("EDT unresponsive for ~{} ms\nEDT stack:\n{}\nClient thread stack:\n{}",
						silentMs, stackOf(edt), stackOf(findThread("Client")));
				}
			}
			else
			{
				reported = false;
			}
		}
	}

	private static Thread findThread(String name)
	{
		for (Thread t : Thread.getAllStackTraces().keySet())
		{
			if (name.equals(t.getName()))
			{
				return t;
			}
		}
		return null;
	}

	private static String stackOf(Thread t)
	{
		if (t == null)
		{
			return "  (thread not found)";
		}
		StringBuilder sb = new StringBuilder();
		for (StackTraceElement e : t.getStackTrace())
		{
			sb.append("  at ").append(e).append('\n');
		}
		return sb.length() == 0 ? "  (no frames)" : sb.toString();
	}
}
