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
	/** {@code GolemData.items}: 0..8 template, 9..17 input slots (one per grid cell), 18 output. */
	public static final int TEMPLATE_SLOTS = 9;
	public static final int INPUT_SLOTS = 9;
	public static final int FIRST_INPUT = TEMPLATE_SLOTS;
	public static final int OUTPUT_SLOT = TEMPLATE_SLOTS + INPUT_SLOTS;
	public static final int TOTAL_SLOTS = OUTPUT_SLOT + 1;

	private CrafterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Crafter"),
			List.of(
				// the recipe: a grid of real one-item slots on the Recipe tab
				new SlotGroup("template", TEMPLATE_SLOTS, 1, s -> true, 3).noQuickMove().recipeAt(2, 1),
				// the Inventory tab's 3x3 grid; input cell i accepts only template slot i's item (offset = TEMPLATE_SLOTS)
				new SlotGroup("input", INPUT_SLOTS, 64, s -> true, 3).at(2, 1).remembers(TEMPLATE_SLOTS)
					.labeled(1, 2, "icon_inventory", "Ingredients", "Filled by the golem from its input chest; you can add items too"),
				// output is take-out only for players: the golem fills it; the arrow names the outcome on the Recipe tab
				new SlotGroup("output", 1, 64, s -> false).at(3, 7).labeled(3, 5, "craft_arrow", "Crafts", "")
					.previewing(CrafterLogic::preview)),
			List.of(
				new AssignSpec(INPUT, Component.literal("Assign Input Chest"), Kind.CHEST, 0),
				new AssignSpec(TABLE, Component.literal("Assign Crafting Table"), Kind.CRAFTING_TABLE, 0),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0)),
			false,
			CrafterLogic::tick));
	}
}
