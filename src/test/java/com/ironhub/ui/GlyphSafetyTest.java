package com.ironhub.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * RuneLite's bundled RuneScape fonts only cover ASCII plus a handful of
 * punctuation (· … — ×). Any other codepoint in label text renders as the
 * boxed missing-glyph character in the client — decorative glyphs must be
 * PaintedIcon shapes instead. This scans main-source string literals so a
 * stray ‹ or ▾ fails the build rather than shipping.
 */
public class GlyphSafetyTest
{
	private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final String SAFE_EXTRAS = "·…—×"; // · … — ×

	@Test
	public void mainSourceStringLiteralsUseFontSafeGlyphs() throws IOException
	{
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n");
				for (int i = 0; i < lines.length; i++)
				{
					String line = lines[i];
					int comment = line.indexOf("//");
					if (comment >= 0)
					{
						line = line.substring(0, comment);
					}
					if (line.trim().startsWith("*"))
					{
						continue; // javadoc
					}
					Matcher m = STRING_LITERAL.matcher(line);
					while (m.find())
					{
						for (char c : m.group(1).toCharArray())
						{
							if (c > 0x7E && SAFE_EXTRAS.indexOf(c) < 0)
							{
								offenders.add(file + ":" + (i + 1) + " '" + c + "' (U+"
									+ Integer.toHexString(c).toUpperCase() + ") in " + m.group());
							}
						}
					}
				}
			}
		}
		assertTrue("glyphs unsupported by the RuneScape fonts — paint them instead:\n"
			+ String.join("\n", offenders), offenders.isEmpty());
	}
}
