package com.coppergolemsrevamped.profession.smelter;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Feeds up to 8 furnaces of an area from an input and a fuel chest and delivers the results. See superdoc/features/smelter.md. */
public final class SmelterProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("smelter");
	public static final String FURNACES = "furnaces";
	public static final String INPUT = "input";
	public static final String FUEL = "fuel";
	public static final String OUTPUT = "output";
	public static final int MAX_AREA = 16;
	/** Furnace cap; slot i (0..7) is the buffer of furnace i. */
	public static final int MAX_FURNACES = 8;
	/** Slot 8: the golem's XP bottles (take-only for players), see superdoc/features/xp-bottles.md. */
	public static final int XP_SLOT = MAX_FURNACES;
	/** Slots 9..11: the 3 shared result slots (take-only for players), see superdoc/features/smelter.md. */
	public static final int RESULT_FIRST = XP_SLOT + 1;
	public static final int RESULT_SLOTS = 3;
	public static final int SLOTS = RESULT_FIRST + RESULT_SLOTS;

	private SmelterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Smelter"),
			List.of(new SlotGroup("furnace", MAX_FURNACES, 64, s -> true).at(3, 0)
					.labeled(2, 4, "minecraft:furnace", "Furnace buffers",
						"Slot 1 to 8 = furnace 1 to 8: the items the golem carries to it (results go to the row below)"),
				// take-out only for players: the golem fills it from its stored XP
				new SlotGroup("xp", 1, 64, s -> false).at(3, 8)
					.labeled(2, 8, "minecraft:experience_bottle", "XP bottles", "Filled from the golem's stored XP"),
				// the shared results of all furnaces, take-out only for players like the Crafter output; no label (it would
				// have to sit below the slots, unlike every other label)
				new SlotGroup("output", RESULT_SLOTS, 64, s -> false, RESULT_SLOTS).at(4, 3)),
			List.of(
				new AssignSpec(FURNACES, Component.literal("Assign Furnaces"), Kind.AREA, MAX_AREA,
					"two opposite corners of an area with furnaces, blast furnaces or smokers"),
				new AssignSpec(INPUT, Component.literal("Assign Input Chest"), Kind.CHEST, 0, "the chest with the items to smelt"),
				new AssignSpec(FUEL, Component.literal("Assign Fuel Chest"), Kind.CHEST, 0, "the chest with the fuel"),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0, "the chest the results go to")),
			false,
			SmelterLogic::tick,
			List.of(
				"Feeds up to 8 furnaces (furnace, blast furnace, smoker) in the area, the 8 nearest to the input chest, and splits the items evenly between them.",
				"It keeps their fuel topped from the fuel chest, collects the results and delivers them to the output chest. The furnaces' experience is kept and made into XP bottles.",
				"The 8 slots in the furnace row are one input buffer per furnace; the 3 slots below are the shared results and the slot at the right holds the XP bottles (you can take both out). Items nothing can use go back to the input chest, a furnace holding something else is left alone.")));
	}
}
