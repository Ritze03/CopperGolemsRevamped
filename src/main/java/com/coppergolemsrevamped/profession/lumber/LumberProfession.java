package com.coppergolemsrevamped.profession.lumber;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

/** Fells one assigned tree from the ground and replants it. See superdoc/features/lumber.md. */
public final class LumberProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("lumber");
	public static final String TREE = "tree";
	public static final String INPUT = "input";
	public static final String OUTPUT = "output";
	/**
	 * Marker in {@code GolemData.assignments}: the value is the tree spot that is a 2x2 site. Why here: the assignments map is
	 * persisted and cleared on a profession change, and a marker whose value differs from the current tree spot is void, so
	 * reassigning the tree resets the size without a new GolemData field.
	 */
	public static final String SITE_2X2 = "tree_2x2";
	/** Slot indices in {@code GolemData.items}: the axe, two supply slots (saplings / bone meal), then carry. */
	public static final int AXE_SLOT = 0;
	public static final int SUPPLY_FROM = 1;
	public static final int SUPPLY_SLOTS = 2;
	public static final int CARRY_FROM = 3;
	public static final int CARRY_SLOTS = 9;

	private LumberProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Lumber"),
			List.of(
				new SlotGroup("tool", 1, 1, s -> s.is(ItemTags.AXES)).at(2, 3).shownInHand()
					.labeled(1, 3, "assign_tools", "Axe", "The slot below holds the golem's axe"),
				new SlotGroup("supplies", SUPPLY_SLOTS, 64, s -> s.is(ItemTags.SAPLINGS) || s.is(Items.BONE_MEAL), SUPPLY_SLOTS)
					.at(2, 5).labeled(1, 5, "minecraft:bone_meal", "Supplies", "The slots below hold saplings and bone meal"),
				new SlotGroup("carry", CARRY_SLOTS, 64, s -> true).at(5, 0)
					.labeled(4, 4, "assign_output", "Carry", "Nine carry slots in the row below")),
			List.of(
				new AssignSpec(TREE, Component.literal("Assign Tree"), Kind.BLOCK, 0),
				new AssignSpec(INPUT, Component.literal("Assign Input Chest"), Kind.CHEST, 0),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0)),
			false,
			LumberLogic::tick));
	}
}
