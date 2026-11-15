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

/** Fells one assigned tree and replants it. See superdoc/features/lumber.md. */
public final class LumberProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("lumber");
	public static final String TREE = "tree";
	public static final String INPUT = "input";
	public static final String OUTPUT = "output";
	/** Slot indices in {@code GolemData.items}: two tool slots (axe / shears, either order), sapling, then carry. */
	public static final int TOOL_FROM = 0;
	public static final int TOOL_SLOTS = 2;
	public static final int SAPLING_SLOT = 2;
	public static final int CARRY_FROM = 3;
	public static final int CARRY_SLOTS = 9;

	private LumberProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Lumber"),
			List.of(
				new SlotGroup("tools", TOOL_SLOTS, 1, s -> s.is(ItemTags.AXES) || s.is(Items.SHEARS)),
				new SlotGroup("sapling", 1, 64, s -> s.is(ItemTags.SAPLINGS)),
				new SlotGroup("carry", CARRY_SLOTS, 64, s -> true)),
			List.of(
				new AssignSpec(TREE, Component.literal("Assign Tree"), Kind.BLOCK, 0),
				new AssignSpec(INPUT, Component.literal("Assign Input Chest"), Kind.CHEST, 0),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0)),
			false,
			LumberLogic::tick));
	}
}
