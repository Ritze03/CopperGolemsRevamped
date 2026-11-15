package com.coppergolemsrevamped.core;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Marker for display-only copies of items: the faded cells of the Crafter grid, the ghost output and the tool in the
 * golem's hand. A marked stack is never a real item: nothing may hand it to a player or drop it.
 * Why a persisted marker and not a transient set: the hand copy is saved with the entity, and after a reload the mod
 * must still recognise it to clear or refresh it instead of mistaking it for something the golem really carries.
 */
public final class DisplayItems {
	private static final String KEY = "cgr_display";

	private DisplayItems() {}

	/** {@code stack} (modified in place) with the marker; returns it for chaining. */
	public static ItemStack mark(ItemStack stack) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(KEY, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	public static boolean is(ItemStack stack) {
		CustomData d = stack.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().contains(KEY);
	}
}
