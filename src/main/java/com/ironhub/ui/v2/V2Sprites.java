package com.ironhub.ui.v2;

import com.google.gson.Gson;
import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The V2 design system's art: Luke's curated set, shipped under data/v2/ and
 * indexed by data/v2-sprites.json (tools/gen_v2_sprites.py). Every pixel in
 * V2 comes from here — nothing is drawn by hand.
 *
 * <p><b>The theme rule</b> (Luke, 2026-07-24): a sprite changes with the theme
 * ONLY if a {@code _mystic} twin sits beside it in the source folder.
 * Everything else is the same art in both themes. That is why the rule lives
 * in the generated index rather than in a runtime filename probe: the old
 * skin's mystic→vanilla fallback made "is this themed?" a question you could
 * only answer by trying, and the answer changed silently whenever art moved.
 *
 * <p>An unknown key THROWS. The keys are compile-time constants in the atoms,
 * so a miss is a typo, and a design system that silently renders nothing is
 * how surfaces drift apart in the first place.
 */
public final class V2Sprites
{
	/** One indexed sprite: its natural size and how it answers the theme. */
	public static final class Meta
	{
		int w;
		int h;
		boolean themed;
		boolean mysticOnly;

		public int width()
		{
			return w;
		}

		public int height()
		{
			return h;
		}

		/** A {@code _mystic} twin exists, so the Mystic theme swaps art. */
		public boolean themed()
		{
			return themed;
		}

		/** The only art is the Mystic file — used in both themes, flagged so
		 *  the gallery can say so rather than implying it is vanilla. */
		public boolean mysticOnly()
		{
			return mysticOnly;
		}
	}

	private static final class Index
	{
		Map<String, Meta> sprites;
	}

	private static final String ART = "/data/v2/";
	private static final Map<String, Optional<BufferedImage>> CACHE = new ConcurrentHashMap<>();
	private static volatile Map<String, Meta> index;

	private V2Sprites()
	{
	}

	/** Every indexed key, for the gallery and the coverage test. */
	public static Map<String, Meta> all()
	{
		return Collections.unmodifiableMap(index());
	}

	public static boolean has(String key)
	{
		return index().containsKey(key);
	}

	/** Natural size and theme behaviour; null when the key is unknown. */
	public static Meta meta(String key)
	{
		return index().get(key);
	}

	/**
	 * The sprite as the given theme wears it. Never null — an unknown key is
	 * a typo in an atom, and it throws rather than painting nothing.
	 */
	public static BufferedImage get(OsrsTheme theme, String key)
	{
		Meta meta = index().get(key);
		if (meta == null)
		{
			throw new IllegalArgumentException("no V2 sprite '" + key
				+ "' — check data/v2-sprites.json (rerun tools/gen_v2_sprites.py)");
		}
		// mysticOnly art has no vanilla original, so the FILE is the _mystic
		// one in both themes — the index says which sprite, the file name
		// says which pixels
		String path = meta.mysticOnly || (meta.themed && theme == OsrsTheme.MYSTIC)
			? key + "_mystic" : key;
		BufferedImage image = load(path);
		if (image == null)
		{
			throw new IllegalStateException("V2 sprite '" + path
				+ "' is indexed but missing from " + ART);
		}
		return image;
	}

	private static BufferedImage load(String path)
	{
		return CACHE.computeIfAbsent(path, key ->
		{
			java.net.URL url = V2Sprites.class.getResource(ART + key + ".png");
			if (url == null)
			{
				return Optional.empty();
			}
			try
			{
				return Optional.ofNullable(javax.imageio.ImageIO.read(url));
			}
			catch (java.io.IOException e)
			{
				return Optional.empty();
			}
		}).orElse(null);
	}

	private static Map<String, Meta> index()
	{
		Map<String, Meta> local = index;
		if (local == null)
		{
			try (Reader reader = new InputStreamReader(
				V2Sprites.class.getResourceAsStream("/data/v2-sprites.json"),
				StandardCharsets.UTF_8))
			{
				index = local = new Gson().fromJson(reader, Index.class).sprites;
			}
			catch (Exception e)
			{
				throw new IllegalStateException("data/v2-sprites.json is missing or corrupt", e);
			}
		}
		return local;
	}
}
