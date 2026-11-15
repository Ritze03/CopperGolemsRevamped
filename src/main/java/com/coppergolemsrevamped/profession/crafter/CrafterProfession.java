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
import net.minecraft.world.item.ItemStack;

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
				new SlotGroup("template", TEMPLATE_SLOTS, 1, s -> true, 3).noQuickMove(),
				// input slot i takes only the item of template cell i; mirrors the template, beside it at columns 4..6
				new SlotGroup("input", INPUT_SLOTS, 64, s -> true, 3).beside(4, 0)
					.withRule((items, i, stack) -> i < items.size() && !items.get(i).isEmpty()
						&& ItemStack.isSameItemSameComponents(items.get(i), stack)),
				// output is take-out only for players: the golem fills it
				new SlotGroup("output", 1, 64, s -> false).beside(8, 1)),
			List.of(
				new AssignSpec(INPUT, Component.literal("Assign Input Chest"), Kind.CHEST, 0),
				new AssignSpec(TABLE, Component.literal("Assign Crafting Table"), Kind.CRAFTING_TABLE, 0),
				new AssignSpec(OUTPUT, Component.literal("Assign Output Chest"), Kind.CHEST, 0)),
			false,
			CrafterLogic::tick));
	}
}
