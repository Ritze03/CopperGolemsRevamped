package com.coppergolemsrevamped.profession.transporter;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class TransporterProfession {
	private TransporterProfession() {}

	/** Slot indices in {@code GolemData.items}: {@link #CARRY_SLOTS} carry slots, then {@link #FILTER_SLOTS} filter slots. */
	public static final int CARRY_SLOTS = 9;
	public static final int FILTER_FROM = CARRY_SLOTS;
	public static final int FILTER_SLOTS = 9;

	/**
	 * Old saves store only the 9 carry slots: pad the list with empty filter slots (called every tick from
	 * {@code GolemTicker}, like the other per-profession migrations). Nothing is lost, the carry keeps its indices.
	 */
	public static void migrate(GolemData data) {
		if (data.items.size() >= CARRY_SLOTS + FILTER_SLOTS) return;
		NonNullList<ItemStack> padded = NonNullList.withSize(CARRY_SLOTS + FILTER_SLOTS, ItemStack.EMPTY);
		for (int k = 0; k < data.items.size(); k++) padded.set(k, data.items.get(k));
		data.items = padded;
	}

	public static final Identifier ID = CopperGolemsRevamped.id("transporter");

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Transporter"),
			List.of(new SlotGroup("carry", CARRY_SLOTS, 64, s -> true).at(3, 0).shownInHand()
					.labeled(2, 4, "assign_output", "Carry", "Nine carry slots in the row below"),
				// same cells as carry, but on the Filter tab; the label cell there is the mode button
				new SlotGroup("filter", FILTER_SLOTS, 1, s -> true).noQuickMove().filterAt(3, 0)
					.labeled(2, 4, "minecraft:hopper", "Filter mode", "")),
			List.of(
				new AssignSpec("input", Component.literal("Assign Chest A"), Kind.CHEST, 0),
				new AssignSpec("output", Component.literal("Assign Chest B"), Kind.CHEST, 0)),
			false,
			TransporterLogic::tick,
			TransporterLogic::forget));
	}
}
