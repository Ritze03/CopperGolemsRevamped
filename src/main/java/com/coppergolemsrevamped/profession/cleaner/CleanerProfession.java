package com.coppergolemsrevamped.profession.cleaner;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Picks up item entities inside an assigned area and delivers them to an output chest. See superdoc/features/cleaner.md. */
public final class CleanerProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("cleaner");
	public static final int MAX_AREA = 16;
	public static final String AREA = "area";
	public static final String OUTPUT = "output";

	private CleanerProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Cleaner"),
			List.of(new SlotGroup("carry", 9, 64, s -> true).at(3, 0).shownInHand()
				.labeled(2, 4, "assign_output", "Carry", "Nine carry slots in the row below")),
			List.of(
				new AssignSpec(AREA, Component.literal("Assign Area"), Kind.AREA, MAX_AREA),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0)),
			false,
			CleanerLogic::tick));
	}
}
