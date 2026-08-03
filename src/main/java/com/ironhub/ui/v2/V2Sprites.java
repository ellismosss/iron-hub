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
	/** One indexed sprite: its natural size and which theme variants exist. */
	public static final class Meta
	{
		int w;
		int h;
		java.util.List<String> variants;

		public int width()
		{
			return w;
		}

		public int height()
		{
			return h;
		}

		/** Which of vanilla / mystic / dark the curated set actually ships. */
		public java.util.List<String> variants()
		{
			return java.util.Collections.unmodifiableList(variants);
		}

		public boolean has(String variant)
		{
			return variants.contains(variant);
		}

		/** More than one variant, so the theme setting changes this sprite. */
		public boolean themed()
		{
			return variants.size() > 1;
		}

		/** No vanilla original — the art came from a pack and is used in
		 *  every theme. Flagged so the gallery can say so rather than
		 *  implying it is the game's own. */
		public boolean packOnly()
		{
			return !variants.contains("vanilla");
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

	/**
	 * The curated icon for a skill, by its NAME — the one place that mapping is
	 * written, so no module builds a sprite path out of a {@code Skill}. Takes
	 * a String rather than the enum to keep this package free of the client's
	 * API. Check {@link #has} first: the set covers the 23 skills that existed
	 * when it was curated, and a skill added later has no art here.
	 */
	public static String skill(String name)
	{
		return "icons/skills/" + name.toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * The Card's grain as a repeating paint — what every Tile surface is filled
	 * with. Here rather than rebuilt at each call site: the hub's nav row, the
	 * lab's copy of it and {@code V2Surface.paintTile} were three constructions
	 * of one texture, which is three chances to drift.
	 */
	public static java.awt.TexturePaint grain(OsrsTheme theme)
	{
		BufferedImage art = cardInterior(theme);
		return new java.awt.TexturePaint(art,
			new java.awt.Rectangle(0, 0, art.getWidth(), art.getHeight()));
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
		String path = key + suffix(variantFor(theme, meta));
		BufferedImage image = load(path);
		if (image == null)
		{
			throw new IllegalStateException("V2 sprite '" + path
				+ "' is indexed but missing from " + ART);
		}
		return image;
	}

	/**
	 * Which variant a theme actually gets for this sprite: its own if the
	 * pack re-skinned it, otherwise vanilla, otherwise whatever exists.
	 *
	 * <p>The vanilla fallback is the whole model — a resource pack overrides
	 * the sprites it ships and leaves the rest alone, so Dark Vanilla's 160
	 * sprites sit on top of the game's own 370 rather than needing all of
	 * them. The last branch covers pack-only art (a family the game has no
	 * equivalent for), which every theme then shares.
	 */
	public static String variantFor(OsrsTheme theme, Meta meta)
	{
		String wanted = theme.spriteVariant();
		if (meta.has(wanted))
		{
			return wanted;
		}
		return meta.has("vanilla") ? "vanilla" : meta.variants.get(0);
	}

	private static String suffix(String variant)
	{
		return "vanilla".equals(variant) ? "" : "_" + variant;
	}

	/**
	 * The sprite with the pointer wash baked in, clipped to its OWN pixels.
	 * Filling the component rectangle washed the transparent air around a
	 * plus sign and outside a rounded square (Luke, 2026-07-25); SrcAtop over
	 * a copy of the sprite lights only what the sprite actually draws.
	 */
	public static BufferedImage highlighted(OsrsTheme theme, String key)
	{
		// resolved BEFORE computeIfAbsent: get() caches into this same map, and
		// a nested computeIfAbsent on a ConcurrentHashMap throws "Recursive
		// update" whenever the source sprite is not already warm
		BufferedImage art = trimmed(theme, key);
		String cacheKey = "hl/" + theme.spriteVariant() + "/" + key;
		return CACHE.computeIfAbsent(cacheKey, k -> Optional.of(wash(art))).orElse(null);
	}

	/** The same wash over an emblem scaled to a box — {@link #highlighted} and
	 *  {@link #fitted} composed, so a fitted button lights like every other. */
	public static BufferedImage highlighted(OsrsTheme theme, String key, int box)
	{
		if (box <= 0)
		{
			return highlighted(theme, key);
		}
		BufferedImage art = fitted(theme, key, box);
		String cacheKey = "hl" + box + "/" + theme.spriteVariant() + "/" + key;
		return CACHE.computeIfAbsent(cacheKey, k -> Optional.of(wash(art))).orElse(null);
	}

	/** A copy of the art with {@code HIGHLIGHT} composited SrcAtop — lit where
	 *  the sprite draws, untouched where it is transparent. */
	private static BufferedImage wash(BufferedImage art)
	{
		BufferedImage lit = new BufferedImage(art.getWidth(), art.getHeight(),
			BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = lit.createGraphics();
		g.drawImage(art, 0, 0, null);
		g.setComposite(java.awt.AlphaComposite.SrcAtop);
		g.setColor(V2Tokens.HIGHLIGHT);
		g.fillRect(0, 0, art.getWidth(), art.getHeight());
		g.dispose();
		return lit;
	}

	/**
	 * The sprite cropped to its own ink — the alpha bounding box.
	 *
	 * <p>A nine-slice cuts its edges from the sprite's CANVAS, so a sprite with
	 * transparent padding hands the slicer nothing. {@code ui/buttons/button}
	 * is exactly that: a 35x35 canvas whose ink runs rows 5..29 only, so
	 * slicing it at 5 took two rows of pure transparency as the top and bottom
	 * edges and tiled the entire real button — both rounded ends included —
	 * through the middle. That is Luke's chip with "no lower half" (2026-07-25)
	 * and the same button family that clipped under {@code V2Button}.
	 *
	 * <p>Measured 2026-07-25: {@code button} and {@code button_hovered} are the
	 * ONLY slice sources with padding. Every other one is already flush, so
	 * trimming is a no-op for them and this cannot shift art that was right.
	 */
	public static BufferedImage trimmed(OsrsTheme theme, String key)
	{
		BufferedImage art = get(theme, key);
		String cacheKey = "trim/" + theme.spriteVariant() + "/" + key;
		return CACHE.computeIfAbsent(cacheKey, k ->
		{
			int minX = art.getWidth(), minY = art.getHeight(), maxX = -1, maxY = -1;
			for (int y = 0; y < art.getHeight(); y++)
			{
				for (int x = 0; x < art.getWidth(); x++)
				{
					if ((art.getRGB(x, y) >>> 24) != 0)
					{
						minX = Math.min(minX, x);
						minY = Math.min(minY, y);
						maxX = Math.max(maxX, x);
						maxY = Math.max(maxY, y);
					}
				}
			}
			return Optional.of(maxX < 0 ? art
				: art.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1));
		}).orElse(null);
	}

	/**
	 * A sprite scaled to read at a given weight, aspect preserved, NEAREST
	 * NEIGHBOUR. The one place in V2 that resizes art, and it exists because a
	 * row of emblems pulled from different families arrives at different sizes
	 * — the nav row's six span 15px to 36px, which reads as six unrelated
	 * icons rather than one row (Luke, 2026-07-25).
	 *
	 * <p>NEAREST is not a preference: smooth interpolation is what made the
	 * first nav pass read soft, and Luke's fix then was native size only
	 * ("much nicer and crisper"). Nearest keeps every pixel hard-edged; the
	 * cost at a non-integer ratio is uneven pixel runs, which is a visible,
	 * honest artefact rather than a blur.
	 *
	 * <p>This is NOT a licence to scale surfaces — §2 stands, and
	 * {@link NineSlice} is still the only way to draw a surface at a size it
	 * did not come in. An emblem is a picture, not a tiled texture.
	 */
	public static BufferedImage fitted(OsrsTheme theme, String key, int box)
	{
		// resolved BEFORE computeIfAbsent — see highlighted()
		BufferedImage art = get(theme, key);
		if (box <= 0)
		{
			return art; // no box asked for: the sprite's own size
		}
		String cacheKey = "fit" + box + "/" + theme.spriteVariant() + "/" + key;
		return CACHE.computeIfAbsent(cacheKey, k ->
		{
			double factor = Math.min(box / (double) art.getWidth(),
				box / (double) art.getHeight());
			int w = Math.max(1, (int) Math.round(art.getWidth() * factor));
			int h = Math.max(1, (int) Math.round(art.getHeight() * factor));
			if (w == art.getWidth() && h == art.getHeight())
			{
				return Optional.of(art);
			}
			BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = out.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g.drawImage(art, 0, 0, w, h, null);
			g.dispose();
			return Optional.of(out);
		}).orElse(null);
	}

	/**
	 * The Card's interior — {@code enter_wilderness_teleport} inside its 9px
	 * bevel. The Tile fills its chamfer with this, so the two surfaces share
	 * one colour, one lightness and one grain (Luke, 2026-07-25); they are
	 * told apart by their outline, not their tone.
	 *
	 * <p>Used AS IT COMES, at 1:1 — a TexturePaint repeats it, which is the
	 * §2 tiling rule holding for a fill exactly as it does for a slice.
	 */
	public static BufferedImage cardInterior(OsrsTheme theme)
	{
		BufferedImage card = get(theme, "ui/buttons/enter_wilderness_teleport");
		int inset = V2Tokens.SLICE_INSET;
		return card.getSubimage(inset, inset,
			card.getWidth() - 2 * inset, card.getHeight() - 2 * inset);
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
