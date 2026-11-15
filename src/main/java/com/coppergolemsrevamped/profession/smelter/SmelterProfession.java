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
	/** Slot 8: shared overflow for results (a full row of 9 looks nicer than 8). */
	public static final int OVERFLOW = MAX_FURNACES;
	public static final int SLOTS = MAX_FURNACES + 1;

	private SmelterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Smelter"),
			List.of(new SlotGroup("furnace", SLOTS, 64, s -> true).at(3, 0)
				.labeled(2, 4, "minecraft:furnace", "Furnace buffers",
					"Slot 1 to 8 = furnace 1 to 8: what the golem carries to it or from it. Slot 9 is a shared overflow for results")),
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
				"It keeps their fuel topped from the fuel chest, collects the results (the experience pops at the furnace) and delivers them to the output chest.",
				"The first 8 slots are one buffer per furnace, the 9th is shared overflow for results. Items nothing can use go back to the input chest, a furnace holding something else is left alone.")));
	}
}
