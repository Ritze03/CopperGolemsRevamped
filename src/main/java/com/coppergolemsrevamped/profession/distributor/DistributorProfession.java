package com.coppergolemsrevamped.profession.distributor;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Splits the input chest's items evenly over all inventories of an area. See superdoc/features/distributor.md. */
public final class DistributorProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("distributor");
	public static final String INPUT = "input";
	public static final String TARGETS = "targets";
	public static final int MAX_AREA = 16;
	/** Carry slots, data indices 0..8 (the same layout as the Transporter's carry). */
	public static final int CARRY_SLOTS = 9;

	private DistributorProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Distributor"),
			List.of(new SlotGroup("carry", CARRY_SLOTS, 64, s -> true).at(3, 0).shownInHand()
				.labeled(2, 4, "assign_output", "Carry", "Nine carry slots in the row below")),
			List.of(
				new AssignSpec(INPUT, Component.literal("Assign Input Chest"), Kind.CHEST, 0, "the chest the items are taken from"),
				new AssignSpec(TARGETS, Component.literal("Assign Inventories"), Kind.AREA, MAX_AREA,
					"two opposite corners of an area with chests, barrels, hoppers, droppers and so on")),
			false,
			DistributorLogic::tick,
			List.of(
				"Takes items from the input chest and splits them evenly between all inventories in the area (chests, barrels, hoppers, droppers, shulker boxes and so on).",
				"With 8 inventories it takes a multiple of 8 of an item and gives each one the same amount. Items with fewer than that stay in the input chest. A double chest counts as one inventory; furnaces, brewing stands and other machines are skipped.",
				"What an inventory has no room for goes back to the input chest. The 9 slots are what it carries.")));
	}
}
