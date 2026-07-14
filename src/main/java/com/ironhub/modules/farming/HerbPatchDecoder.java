package com.ironhub.modules.farming;

/**
 * Herb patch varbit decoder — mechanically generated from RuneLite core's
 * PatchImplementation.HERB at the pinned client version (tag
 * runelite-parent-1.12.32) and held to it by HerbDecoderParityTest, which
 * reflectively sweeps core's decoder across all values and asserts
 * equivalence. Do not hand-edit the range list; regenerate from the tag
 * matching the compile dependency if the parity test fails.
 */
final class HerbPatchDecoder
{
	/** Growing stages before harvestable. */
	static final int GROWING_STAGES = 4;
	/** Minutes per herb growth stage (all herbs; asserted in tests). */
	static final int STAGE_MINUTES = 20;

	enum State
	{
		EMPTY,
		GROWING,
		HARVESTABLE,
		DISEASED,
		DEAD,
		UNKNOWN
	}

	static final class Seen
	{
		final State state;
		final String herb;
		final int stage;

		Seen(State state, String herb, int stage)
		{
			this.state = state;
			this.herb = herb;
			this.stage = stage;
		}
	}

	private HerbPatchDecoder()
	{
	}

	static Seen decode(int value)
	{
		if (value >= 0 && value <= 3)
		{
			return new Seen(State.EMPTY, "Weeds", 3 - value);
		}
		if (value >= 4 && value <= 7)
		{
			return new Seen(State.GROWING, "Guam", value - 4);
		}
		if (value >= 8 && value <= 10)
		{
			return new Seen(State.HARVESTABLE, "Guam", 10 - value);
		}
		if (value >= 11 && value <= 14)
		{
			return new Seen(State.GROWING, "Marrentill", value - 11);
		}
		if (value >= 15 && value <= 17)
		{
			return new Seen(State.HARVESTABLE, "Marrentill", 17 - value);
		}
		if (value >= 18 && value <= 21)
		{
			return new Seen(State.GROWING, "Tarromin", value - 18);
		}
		if (value >= 22 && value <= 24)
		{
			return new Seen(State.HARVESTABLE, "Tarromin", 24 - value);
		}
		if (value >= 25 && value <= 28)
		{
			return new Seen(State.GROWING, "Harralander", value - 25);
		}
		if (value >= 29 && value <= 31)
		{
			return new Seen(State.HARVESTABLE, "Harralander", 31 - value);
		}
		if (value >= 32 && value <= 35)
		{
			return new Seen(State.GROWING, "Ranarr", value - 32);
		}
		if (value >= 36 && value <= 38)
		{
			return new Seen(State.HARVESTABLE, "Ranarr", 38 - value);
		}
		if (value >= 39 && value <= 42)
		{
			return new Seen(State.GROWING, "Toadflax", value - 39);
		}
		if (value >= 43 && value <= 45)
		{
			return new Seen(State.HARVESTABLE, "Toadflax", 45 - value);
		}
		if (value >= 46 && value <= 49)
		{
			return new Seen(State.GROWING, "Irit", value - 46);
		}
		if (value >= 50 && value <= 52)
		{
			return new Seen(State.HARVESTABLE, "Irit", 52 - value);
		}
		if (value >= 53 && value <= 56)
		{
			return new Seen(State.GROWING, "Avantoe", value - 53);
		}
		if (value >= 57 && value <= 59)
		{
			return new Seen(State.HARVESTABLE, "Avantoe", 59 - value);
		}
		if (value >= 60 && value <= 63)
		{
			return new Seen(State.GROWING, "Huasca", value - 60);
		}
		if (value >= 64 && value <= 66)
		{
			return new Seen(State.HARVESTABLE, "Huasca", 66 - value);
		}
		if (value == 67)
		{
			return new Seen(State.EMPTY, "Weeds", 3);
		}
		if (value >= 68 && value <= 71)
		{
			return new Seen(State.GROWING, "Kwuarm", value - 68);
		}
		if (value >= 72 && value <= 74)
		{
			return new Seen(State.HARVESTABLE, "Kwuarm", 74 - value);
		}
		if (value >= 75 && value <= 78)
		{
			return new Seen(State.GROWING, "Snapdragon", value - 75);
		}
		if (value >= 79 && value <= 81)
		{
			return new Seen(State.HARVESTABLE, "Snapdragon", 81 - value);
		}
		if (value >= 82 && value <= 85)
		{
			return new Seen(State.GROWING, "Cadantine", value - 82);
		}
		if (value >= 86 && value <= 88)
		{
			return new Seen(State.HARVESTABLE, "Cadantine", 88 - value);
		}
		if (value >= 89 && value <= 92)
		{
			return new Seen(State.GROWING, "Lantadyme", value - 89);
		}
		if (value >= 93 && value <= 95)
		{
			return new Seen(State.HARVESTABLE, "Lantadyme", 95 - value);
		}
		if (value >= 96 && value <= 99)
		{
			return new Seen(State.GROWING, "Dwarf Weed", value - 96);
		}
		if (value >= 100 && value <= 102)
		{
			return new Seen(State.HARVESTABLE, "Dwarf Weed", 102 - value);
		}
		if (value >= 103 && value <= 106)
		{
			return new Seen(State.GROWING, "Torstol", value - 103);
		}
		if (value >= 107 && value <= 109)
		{
			return new Seen(State.HARVESTABLE, "Torstol", 109 - value);
		}
		if (value >= 128 && value <= 130)
		{
			return new Seen(State.DISEASED, "Guam", value - 127);
		}
		if (value >= 131 && value <= 133)
		{
			return new Seen(State.DISEASED, "Marrentill", value - 130);
		}
		if (value >= 134 && value <= 136)
		{
			return new Seen(State.DISEASED, "Tarromin", value - 133);
		}
		if (value >= 137 && value <= 139)
		{
			return new Seen(State.DISEASED, "Harralander", value - 136);
		}
		if (value >= 140 && value <= 142)
		{
			return new Seen(State.DISEASED, "Ranarr", value - 139);
		}
		if (value >= 143 && value <= 145)
		{
			return new Seen(State.DISEASED, "Toadflax", value - 142);
		}
		if (value >= 146 && value <= 148)
		{
			return new Seen(State.DISEASED, "Irit", value - 145);
		}
		if (value >= 149 && value <= 151)
		{
			return new Seen(State.DISEASED, "Avantoe", value - 148);
		}
		if (value >= 152 && value <= 154)
		{
			return new Seen(State.DISEASED, "Kwuarm", value - 151);
		}
		if (value >= 155 && value <= 157)
		{
			return new Seen(State.DISEASED, "Snapdragon", value - 154);
		}
		if (value >= 158 && value <= 160)
		{
			return new Seen(State.DISEASED, "Cadantine", value - 157);
		}
		if (value >= 161 && value <= 163)
		{
			return new Seen(State.DISEASED, "Lantadyme", value - 160);
		}
		if (value >= 164 && value <= 166)
		{
			return new Seen(State.DISEASED, "Dwarf Weed", value - 163);
		}
		if (value >= 167 && value <= 169)
		{
			return new Seen(State.DISEASED, "Torstol", value - 166);
		}
		if (value >= 170 && value <= 172)
		{
			return new Seen(State.DEAD, "Anyherb", value - 169);
		}
		if (value >= 173 && value <= 175)
		{
			return new Seen(State.DISEASED, "Huasca", value - 172);
		}
		if (value >= 176 && value <= 191)
		{
			return new Seen(State.EMPTY, "Weeds", 3);
		}
		if (value >= 192 && value <= 195)
		{
			return new Seen(State.GROWING, "Goutweed", value - 192);
		}
		if (value >= 196 && value <= 197)
		{
			return new Seen(State.HARVESTABLE, "Goutweed", 197 - value);
		}
		if (value >= 198 && value <= 200)
		{
			return new Seen(State.DISEASED, "Goutweed", value - 197);
		}
		if (value >= 201 && value <= 203)
		{
			return new Seen(State.DEAD, "Goutweed", value - 200);
		}
		if (value >= 204 && value <= 219)
		{
			return new Seen(State.EMPTY, "Weeds", 3);
		}
		if (value >= 221 && value <= 255)
		{
			return new Seen(State.EMPTY, "Weeds", 3);
		}
		return new Seen(State.UNKNOWN, "", 0);
	}
}
