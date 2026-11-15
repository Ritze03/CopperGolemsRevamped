package com.coppergolemsrevamped.profession.butcher;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Keeps an animal pen at a fixed size: culls the surplus adults with a sword or axe (Looting applies), breeds new ones
 * with food from a chest and delivers the drops, in the cycle breed, kill, deliver. See superdoc/features/butcher.md.
 */
public final class ButcherProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("butcher");
	public static final int MAX_AREA = 16;
	/** Same key as the Cleaner's area, so the box rule ({@code CleanerLogic:box}) is shared. */
	public static final String AREA = "area";
	public static final String TOOLS = "tools";
	public static final String FOOD = "food";
	public static final String OUTPUT = "output";
	/** Slot indices in {@code GolemData.items}: the weapon, two breeding food slots, then carry. */
	public static final int TOOL_SLOT = 0;
	public static final int FOOD_FROM = 1;
	public static final int FOOD_SLOTS = 2;
	public static final int CARRY_FROM = 3;
	public static final int CARRY_SLOTS = 9;

	private ButcherProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Butcher"),
			List.of(
				new SlotGroup("tool", 1, 1, ButcherLogic::isWeapon).at(2, 3).shownInHand()
					.labeled(1, 3, "assign_tools", "Weapon", "The slot below holds the golem's sword or axe"),
				new SlotGroup("food", FOOD_SLOTS, 64, ButcherLogic::isFood, FOOD_SLOTS).at(2, 5)
					.labeled(1, 5, "minecraft:wheat", "Breeding food", "The slots below hold the food used to breed"),
				new SlotGroup("carry", CARRY_SLOTS, 64, s -> true).at(5, 0)
					.labeled(4, 4, "assign_output", "Carry", "Nine carry slots in the row below")),
			List.of(
				new AssignSpec(AREA, Component.literal("Assign Area"), Kind.AREA, MAX_AREA, "the pen the animals live in"),
				new AssignSpec(TOOLS, Component.literal("Assign Tool Chest"), Kind.CHEST, 0, "the chest the golem takes its sword or axe from"),
				new AssignSpec(FOOD, Component.literal("Assign Food Chest"), Kind.CHEST, 0, "the chest the breeding food is taken from"),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0, "the chest the meat and other drops go to")),
			false,
			ButcherLogic::tick,
			List.of(
				"Keeps the animals in the area (max 16x16) at 8 adults per kind: the surplus adults are killed with the golem's sword or axe, Looting on it works.",
				"It also feeds pairs of adults with food from the food chest so they breed, until the pen holds 8 babies or 24 animals of a kind. Babies are never touched.",
				"Named, tamed and leashed animals are left alone. Drops go to the output chest.")));
	}
}
