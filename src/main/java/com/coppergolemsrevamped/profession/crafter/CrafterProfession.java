package com.coppergolemsrevamped.profession.crafter;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Fetches ingredients from an input chest, crafts at a crafting table, delivers to an output chest. See superdoc/features/crafter.md. */
public final class CrafterProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("crafter");
	public static final String INPUT = "input";
	public static final String TABLE = "table";
	public static final String OUTPUT = "output";
	public static final int TEMPLATE_SLOTS = 9;
	public static final int BUFFER_SLOTS = 9;

	private CrafterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Crafter"),
			List.of(
				new SlotGroup("template", TEMPLATE_SLOTS, 1, s -> true, 3),
				new SlotGroup("buffer", BUFFER_SLOTS, 64, s -> true)),
			List.of(
				new AssignSpec(INPUT, Component.literal("Assign Input Chest"), Kind.CHEST, 0),
				new AssignSpec(TABLE, Component.literal("Assign Crafting Table"), Kind.CRAFTING_TABLE, 0),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0)),
			false,
			CrafterLogic::tick));
	}
}
