package com.ironhub.ui.components;

import com.ironhub.ui.UiTokens;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JLabel;

/**
 * 10 px bold, letterspaced, all-caps SECTION LABEL in muted text.
 */
public class SectionLabel extends JLabel
{
	public SectionLabel(String text)
	{
		this(text, UiTokens.FONT_SIZE_LABEL);
	}

	/** Same label at a non-default size (e.g. the Loadout Lab scale). */
	public SectionLabel(String text, float size)
	{
		super(text.toUpperCase());
		setForeground(UiTokens.TEXT_MUTED);
		setFont(letterSpaced(getFont().deriveFont(Font.BOLD, size),
			UiTokens.LETTER_SPACING_LABEL));
		setAlignmentX(LEFT_ALIGNMENT);
	}

	public static Font letterSpaced(Font font, float tracking)
	{
		Map<TextAttribute, Object> attributes = new HashMap<>();
		attributes.put(TextAttribute.TRACKING, tracking);
		return font.deriveFont(attributes);
	}
}
