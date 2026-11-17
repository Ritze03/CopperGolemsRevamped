package com.coppergolemsrevamped.profession.farmer;

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

/** Farms an assigned field (at most 9x9) with one tool (hoe: crops, axe: melons/pumpkins), delivers seeds to the seed chest. See superdoc/features/farmer.md. */
public final class FarmerProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("farmer");
	public static final int MAX_FIELD = 9;
	public static final String FIELD = "field";
	public static final String TOOLS = "tools";
	public static final String SEEDS = "seeds";
	public static final String OUTPUT = "output";
	/** Slot indices in {@code GolemData.items}: tool, seeds, then {@link #CARRY_SLOTS} carry slots. */
	public static final int TOOL_SLOT = 0;
	public static final int SEED_SLOT = 1;
	public static final int CARRY_FROM = 2;
	public static final int CARRY_SLOTS = 9;

	private FarmerProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Farmer"),
			List.of(
				new SlotGroup("tool", 1, 1, s -> s.is(ItemTags.HOES) || s.is(ItemTags.AXES)).at(2, 3).shownInHand()
					.labeled(1, 3, "assign_tools", "Tool", "The slot below holds the golem's tool"),
				new SlotGroup("seeds", 1, 64, FarmerLogic::isSeed).at(2, 5)
					.labeled(1, 5, "assign_seeds", "Seeds", "The slot below holds seeds"),
				new SlotGroup("carry", CARRY_SLOTS, 64, s -> true).at(5, 0)
					.labeled(4, 4, "assign_output", "Carry", "Nine carry slots in the row below")),
			List.of(
				new AssignSpec(FIELD, Component.literal("Assign Field"), Kind.AREA, MAX_FIELD),
				new AssignSpec(TOOLS, Component.literal("Assign Tool Chest"), Kind.CHEST, 0),
				new AssignSpec(SEEDS, Component.literal("Assign Seed Chest"), Kind.CHEST, 0),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0)),
			false,
			FarmerLogic::tick,
			FarmerLogic::forget));
	}
}
