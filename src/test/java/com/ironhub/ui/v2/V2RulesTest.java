package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The V2 rules, enforced (DESIGN-SYSTEM-V2 §10). Rules that nothing checks
 * are rules that rot, and the drift this system exists to remove is exactly
 * the kind that arrives one reasonable-looking line at a time.
 *
 * <p>Scope starts at the V2 package and its gallery, and WIDENS to each tab
 * as it migrates — add the path to {@link #SCOPE} in the same commit that
 * migrates it, so the rules can never trail the code.
 *
 * <p>A line that genuinely needs to break a rule carries a trailing
 * {@code v2-exempt: <reason>} comment. Exemptions are meant to be rare and
 * to read as deliberate.
 */
public class V2RulesTest
{
	private static final List<String> SCOPE = Arrays.asList(
		"src/main/java/com/ironhub/ui/v2",
		"src/main/java/com/ironhub/modules/designlab/DesignLabV2Tab.java");

	/** The one file allowed to name a colour, a font or a raw size. */
	private static final String TOKENS = "V2Tokens.java";
	private static final String EXEMPT = "v2-exempt";

	private static final class Rule
	{
		final String name;
		final Pattern pattern;
		final String fix;
		final List<String> exceptFiles;

		Rule(String name, String regex, String fix, String... exceptFiles)
		{
			this.name = name;
			this.pattern = Pattern.compile(regex);
			this.fix = fix;
			this.exceptFiles = Arrays.asList(exceptFiles);
		}
	}

	private static final List<Rule> RULES = Arrays.asList(
		new Rule("colour literal", "\\bnew (java\\.awt\\.)?Color\\s*\\(|\\bColor\\.[A-Z]{2,}",
			"colours come from V2Tokens", TOKENS),
		new Rule("raw font", "\\bnew (java\\.awt\\.)?Font\\s*\\(|FontManager\\.",
			"fonts come from V2Tokens", TOKENS),
		new Rule("hand-drawn border",
			"\\.drawRect\\s*\\(|\\.fillRoundRect\\s*\\(|\\.drawRoundRect\\s*\\("
				+ "|new LineBorder|new MatteBorder|new BevelBorder|BorderFactory\\.",
			"surfaces are NineSlice art — use V2Surface"),
		new Rule("raw JLabel", "\\bnew JLabel\\s*\\(|\\bnew javax\\.swing\\.JLabel\\s*\\(",
			"text goes through V2Label"),
		new Rule("art loaded outside V2Sprites",
			"ImageIO\\.read|getResource\\s*\\(",
			"sprites come from V2Sprites", "V2Sprites.java"),
		new Rule("bare Box strut in a column", "Box\\.createVerticalStrut",
			"use V2Layout.gap — a bare strut is CENTER-aligned and shifts the column",
			"V2Layout.java")
	);

	/** Spacing may only come from the scale. */
	private static final Pattern SPACED = Pattern.compile(
		"new (?:[\\w.]+\\.)?EmptyBorder\\s*\\(([^)]*)\\)"
			+ "|createVerticalStrut\\s*\\(([^)]*)\\)"
			+ "|createHorizontalStrut\\s*\\(([^)]*)\\)"
			+ "|V2Layout\\.gap\\s*\\(([^)]*)\\)|V2Layout\\.hgap\\s*\\(([^)]*)\\)");

	@Test
	public void v2CodeObeysItsOwnRules() throws IOException
	{
		List<String> violations = new ArrayList<>();
		for (Path file : sources())
		{
			String name = file.getFileName().toString();
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				String line = lines.get(i);
				if (line.contains(EXEMPT) || isComment(line))
				{
					continue;
				}
				for (Rule rule : RULES)
				{
					if (!rule.exceptFiles.contains(name) && rule.pattern.matcher(line).find())
					{
						violations.add(file + ":" + (i + 1) + "  " + rule.name
							+ " — " + rule.fix + "\n    " + line.trim());
					}
				}
				spacing(file, i + 1, line, name, violations);
			}
		}
		assertTrue("V2 rule violations (design/DESIGN-SYSTEM-V2.md §10):\n\n"
			+ String.join("\n", violations) + "\n", violations.isEmpty());
	}

	/** Every atom claims LEFT_ALIGNMENT. One centre-aligned component shifts
	 *  every left-aligned sibling in a BoxLayout column — that bug cost a
	 *  round of Design lab V2, and it is invisible until something renders. */
	@Test
	public void everyAtomClaimsTheLeftEdge()
	{
		OsrsTheme theme = OsrsTheme.STONE;
		List<Component> atoms = Arrays.asList(
			V2Surface.card(theme),
			V2Surface.well(theme),
			V2Surface.inventoryFrame(theme),
			new V2Divider(theme),
			new V2Button(theme, "Button", null),
			new V2SpriteButton(theme, V2SpriteButton.WRENCH, null),
			new V2Checkbox(theme, "Checkbox", false, null),
			new V2ChipRow(theme, true, "A", "B"),
			new V2Tile(theme, null, "Tile", 40, null),
			new V2Tab(theme, null, null),
			new V2ItemSlot(theme, V2ItemSlot.Slot.HEAD, null),
			new V2Glyph(theme, V2Glyph.TICK),
			new V2ProgressBar(theme),
			new V2Table(0),
			new V2TextField(theme, "Search", null),
			new V2Dropdown(theme, "One", "Two"),
			new V2Tooltip(theme),
			V2Hero.build(theme, "Hero", "1 / 2", 0.5, null),
			V2EmptyState.empty(theme, "Nothing here."),
			V2Layout.column(),
			V2Layout.row(),
			V2Layout.gap(V2Tokens.ROW),
			V2Label.body("Label"));
		List<String> centred = atoms.stream()
			.filter(atom -> atom.getAlignmentX() != Component.LEFT_ALIGNMENT)
			.map(atom -> atom.getClass().getSimpleName() + " = " + atom.getAlignmentX())
			.collect(Collectors.toList());
		assertTrue("atoms that do not claim LEFT_ALIGNMENT: " + centred, centred.isEmpty());
	}

	private static void spacing(Path file, int lineNumber, String line, String name,
		List<String> violations)
	{
		if (TOKENS.equals(name))
		{
			return;
		}
		Matcher matcher = SPACED.matcher(line);
		while (matcher.find())
		{
			String args = Stream.of(matcher.group(1), matcher.group(2), matcher.group(3),
					matcher.group(4), matcher.group(5))
				.filter(java.util.Objects::nonNull).findFirst().orElse("");
			for (String arg : args.split(","))
			{
				String trimmed = arg.trim();
				if (!trimmed.matches("-?\\d+"))
				{
					continue; // an expression naming a token, not a bare number
				}
				int value = Integer.parseInt(trimmed);
				if (Arrays.stream(V2Tokens.SPACING).noneMatch(step -> step == value))
				{
					violations.add(file + ":" + lineNumber
						+ "  off-scale spacing " + value
						+ " — the scale is " + Arrays.toString(V2Tokens.SPACING)
						+ "\n    " + line.trim());
				}
			}
		}
	}

	private static boolean isComment(String line)
	{
		String trimmed = line.trim();
		return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
	}

	private static List<Path> sources() throws IOException
	{
		List<Path> files = new ArrayList<>();
		for (String entry : SCOPE)
		{
			Path path = Paths.get(entry);
			if (Files.isDirectory(path))
			{
				try (Stream<Path> walk = Files.walk(path))
				{
					files.addAll(walk.filter(p -> p.toString().endsWith(".java"))
						.collect(Collectors.toList()));
				}
			}
			else
			{
				assertTrue("scoped file is missing: " + path, Files.exists(path));
				files.add(path);
			}
		}
		assertTrue("nothing in scope — did the V2 package move?", files.size() > 10);
		return files;
	}
}
