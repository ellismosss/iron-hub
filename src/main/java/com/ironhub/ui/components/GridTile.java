package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.LineBorder;

/**
 * 30×30 icon cell for grid-first views (mockup frame 2a): status is conveyed
 * by border color; content is a game sprite at runtime — until sprites are
 * wired, a two-letter placeholder code marks the sprite site.
 */
public class GridTile extends JLabel
{
	public enum State
	{
		OWNED("owned"),
		NEXT("obtainable now"),
		READY("ready"),
		LOCKED("locked"),
		WARNING("warning");

		private final String label;

		State(String label)
		{
			this.label = label;
		}
	}

	public GridTile(String code, String name, State state, boolean highlighted)
	{
		super(code, SwingConstants.CENTER);
		setOpaque(true);
		setBackground(state == State.LOCKED ? UiTokens.TILE_BG_LOCKED : UiTokens.CARD_BG);
		setBorder(new LineBorder(borderColor(state)));
		setForeground(highlighted ? UiTokens.TEXT_PRIMARY : codeColor(state));
		setFont(getFont().deriveFont(Font.BOLD, UiTokens.FONT_SIZE_TILE_CODE));
		setToolTipText(name + " — " + state.label + (highlighted ? ", equipped" : ""));
		Dimension size = new Dimension(UiTokens.ICON_CELL_SIZE, UiTokens.ICON_CELL_SIZE);
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);
	}

	private static Color borderColor(State state)
	{
		switch (state)
		{
			case OWNED:
				return UiTokens.STATUS_OWNED;
			case NEXT:
				return UiTokens.ACCENT; // interaction: the next upgrade to chase
			case READY:
				return UiTokens.STATUS_AVAILABLE;
			case WARNING:
				return UiTokens.STATUS_WARNING;
			default:
				return UiTokens.BORDER_DIM;
		}
	}

	private static Color codeColor(State state)
	{
		switch (state)
		{
			case NEXT:
			case READY:
				return UiTokens.STATUS_AVAILABLE;
			case WARNING:
				return UiTokens.STATUS_WARNING;
			case LOCKED:
				return UiTokens.TEXT_FAINT;
			default:
				return UiTokens.GLYPH_MUTED;
		}
	}
}
