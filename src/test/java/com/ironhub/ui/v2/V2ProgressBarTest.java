package com.ironhub.ui.v2;

import com.ironhub.ui.osrs.OsrsTheme;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The Hero's value sits centred on its bar.
 *
 * <p>Measured from the rendered pixels rather than from the arithmetic, since
 * the arithmetic was what kept saying it was fine (Luke reported it low twice,
 * 2026-07-25). This is the check that would have settled it in one round.
 */
public class V2ProgressBarTest
{
	@Test
	public void theValueIsCentredOnTheBarInEveryTheme() throws Exception
	{
		for (OsrsTheme theme : OsrsTheme.values())
		{
			V2ProgressBar bar = new V2ProgressBar(theme, V2ProgressBar.Size.FULL)
				.fraction(0.87).labels("", "1,482 / 1,706", "");
			bar.setSize(V2Tokens.CONTENT_WIDTH, bar.getPreferredSize().height);
			BufferedImage img = com.ironhub.ui.SwingRender.render(bar);

			// the bar's interior: the component less the frame's 1px lines
			int barTop = 1;
			int barBottom = bar.getPreferredSize().height - 2;

			int top = -1;
			int bottom = -1;
			for (int y = 0; y < img.getHeight(); y++)
			{
				for (int x = 0; x < img.getWidth(); x++)
				{
					int p = img.getRGB(x, y);
					if (((p >> 16) & 0xFF) > 200 && ((p >> 8) & 0xFF) > 200 && (p & 0xFF) > 200)
					{
						top = top < 0 ? y : top;
						bottom = y;
						break;
					}
				}
			}
			assertTrue(theme + ": no label ink found", top >= 0);

			int above = top - barTop;
			int below = barBottom - bottom;
			assertTrue(theme + ": value sits " + above + "px from the top and "
				+ below + "px from the bottom of the bar", Math.abs(above - below) <= 1);
		}
	}
}
