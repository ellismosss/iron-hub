package com.ironhub.modules.loadout;

import com.ironhub.data.ItemNameIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed OSRS-wiki gear strategy: one entry per {@code {{Recommended
 * equipment}}} template on a Slayer-task or /Strategies page, named by
 * its {@code <tabber>} label or {@code style} parameter. Slots carry
 * item candidates in the wiki's preference order (rank 1 → 5, slashes
 * within a rank kept in sequence). Pure parser — testable offline.
 */
final class WikiStrategy
{
	/** Calculator-style slot keys used by the wiki template. */
	static final String[] SLOT_KEYS = {
		"head", "neck", "cape", "body", "legs", "weapon", "shield",
		"ammo", "hands", "feet", "ring",
	};

	private static final Pattern PLINK = Pattern.compile(
		"\\{\\{[Pp]link\\|([^|}]+)((?:\\|[^{}]*?)*)}}");
	private static final Pattern PIC = Pattern.compile("pic\\s*=\\s*([^|}]+)");
	private static final Pattern SLOT_PARAM = Pattern.compile(
		"^(head|neck|cape|body|legs|weapon|shield|ammo|hands|feet|ring)([1-9])$");

	final String name;
	/** slot key → candidates (item id + display name) in preference order. */
	final Map<String, List<Candidate>> slots;
	/** Cleaned prose immediately before the gear table (tips), may be empty. */
	final String notes;

	static final class Candidate
	{
		final int itemId;
		final String name;

		Candidate(int itemId, String name)
		{
			this.itemId = itemId;
			this.name = name;
		}
	}

	private WikiStrategy(String name, Map<String, List<Candidate>> slots, String notes)
	{
		this.name = name;
		this.slots = slots;
		this.notes = notes;
	}

	String name()
	{
		return name;
	}

	/** All Recommended-equipment templates in a page's wikitext. */
	static List<WikiStrategy> parse(String wikitext, ItemNameIndex names)
	{
		List<WikiStrategy> strategies = new ArrayList<>();
		int from = 0;
		while (true)
		{
			int start = wikitext.indexOf("{{Recommended equipment", from);
			if (start < 0)
			{
				break;
			}
			int end = balancedEnd(wikitext, start);
			if (end < 0)
			{
				break;
			}
			String template = wikitext.substring(start + 2, end - 2);
			from = end;

			Map<String, String> params = splitParams(template);
			String label = tabberLabel(wikitext, start);
			if (label == null)
			{
				label = params.get("style");
			}
			if (label == null)
			{
				label = "Setup " + (strategies.size() + 1);
			}
			strategies.add(new WikiStrategy(label.trim(), slotCandidates(params, names),
				precedingProse(wikitext, start)));
		}
		return strategies;
	}

	private static Map<String, List<Candidate>> slotCandidates(Map<String, String> params, ItemNameIndex names)
	{
		// rank-ordered: head1 before head2; slash alternatives keep order
		Map<String, java.util.TreeMap<Integer, List<Candidate>>> ranked = new LinkedHashMap<>();
		params.forEach((key, value) ->
		{
			Matcher slot = SLOT_PARAM.matcher(key);
			if (!slot.matches())
			{
				return;
			}
			List<Candidate> candidates = new ArrayList<>();
			Matcher plink = PLINK.matcher(value);
			while (plink.find())
			{
				String item = plink.group(1);
				Matcher pic = PIC.matcher(plink.group(2) == null ? "" : plink.group(2));
				if (pic.find())
				{
					item = pic.group(1); // pic names the actual item when arg 1 is a page
				}
				item = item.replaceAll("#.*", "").trim();
				Integer id = names.idOf(item);
				if (id != null)
				{
					candidates.add(new Candidate(id, item));
				}
			}
			if (!candidates.isEmpty())
			{
				ranked.computeIfAbsent(slot.group(1), k -> new java.util.TreeMap<>())
					.put(Integer.parseInt(slot.group(2)), candidates);
			}
		});
		Map<String, List<Candidate>> slots = new LinkedHashMap<>();
		ranked.forEach((slot, byRank) ->
		{
			List<Candidate> flat = new ArrayList<>();
			byRank.values().forEach(flat::addAll);
			slots.put(slot, flat);
		});
		return slots;
	}

	/** Template body → top-level params (brace-aware: plinks contain pipes). */
	private static Map<String, String> splitParams(String template)
	{
		Map<String, String> params = new LinkedHashMap<>();
		int depth = 0;
		int partStart = 0;
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < template.length(); i++)
		{
			char c = template.charAt(i);
			if (c == '{' || c == '[')
			{
				depth++;
			}
			else if (c == '}' || c == ']')
			{
				depth--;
			}
			else if (c == '|' && depth == 0)
			{
				parts.add(template.substring(partStart, i));
				partStart = i + 1;
			}
		}
		parts.add(template.substring(partStart));
		for (String part : parts)
		{
			int eq = part.indexOf('=');
			if (eq > 0)
			{
				params.put(part.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT),
					part.substring(eq + 1).trim());
			}
		}
		return params;
	}

	/** End index (exclusive) of the {{...}} starting at start, or -1. */
	private static int balancedEnd(String text, int start)
	{
		int depth = 0;
		for (int i = start; i < text.length() - 1; i++)
		{
			if (text.charAt(i) == '{' && text.charAt(i + 1) == '{')
			{
				depth++;
				i++;
			}
			else if (text.charAt(i) == '}' && text.charAt(i + 1) == '}')
			{
				depth--;
				i++;
				if (depth == 0)
				{
					return i + 1;
				}
			}
		}
		return -1;
	}

	/** Wiki tips: the prose paragraph right before the gear table, cleaned. */
	private static String precedingProse(String wikitext, int templateStart)
	{
		int windowStart = Math.max(0, templateStart - 1200);
		String window = wikitext.substring(windowStart, templateStart);
		int cut = Math.max(Math.max(window.lastIndexOf("\n=="), window.lastIndexOf("<tabber>")),
			Math.max(window.lastIndexOf("|-|"), window.lastIndexOf("}}\n")));
		if (cut >= 0)
		{
			window = window.substring(cut);
			int newline = window.indexOf('\n');
			window = newline >= 0 ? window.substring(newline + 1) : "";
		}
		String clean = window
			.replaceAll("\\{\\{[^{}]*}}", "")
			.replaceAll("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)]]", "$1")
			.replaceAll("'{2,}", "")
			.replaceAll("<[^>]+>", "")
			.replaceAll("(?m)^[=|*#: ].*$", "")
			.replaceAll("\\s+", " ")
			.trim();
		return clean.length() > 300 ? clean.substring(0, 297) + "..." : clean;
	}

	/** Nearest preceding tabber tab label ("Magic=" after <tabber> or |-|). */
	private static String tabberLabel(String wikitext, int templateStart)
	{
		int tabber = wikitext.lastIndexOf("<tabber>", templateStart);
		if (tabber < 0)
		{
			return null;
		}
		int close = wikitext.indexOf("</tabber>", tabber);
		if (close >= 0 && close < templateStart)
		{
			return null; // template sits after the tabber block
		}
		String section = wikitext.substring(tabber, templateStart);
		Matcher label = Pattern.compile("(?:<tabber>|\\|-\\|)\\s*\\n?([^=\\n|<]{1,40})=").matcher(section);
		String last = null;
		while (label.find())
		{
			last = label.group(1).trim();
		}
		return last;
	}
}
