package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

/**
 * What one of the menu's 54 top cells currently shows and does. The menu's slots are fixed objects; they route
 * {@code getItem} / {@code set} / {@code mayPlace} / {@code mayPickup} to the cell of the current view, so a tab
 * switch is a swap of the cell array and vanilla's {@code broadcastChanges} syncs the new contents.
 */
sealed interface Cell {
	/** A control's click action; {@code button} is 0 for left, 1 for right. */
	@FunctionalInterface
	interface Control {
		void click(ServerPlayer player, int button);
	}

	/** A control, label or filler: nothing can be placed in or taken out of it. */
	record Icon(ItemStack stack, @Nullable Control control) implements Cell {}

	/**
	 * A cell backed by one slot of {@code GolemData.items}. Besides the real stack it can show a display copy (never
	 * stored, see {@link com.coppergolemsrevamped.core.DisplayItems}) while the slot is empty: the remembered item of
	 * a memory slot, or the preview of a previewing group. Such a cell is a {@linkplain #placeholder placeholder}.
	 */
	final class Store implements Cell {
		private final CopperGolem golem;
		private final Container items;
		final int idx;
		final SlotGroup group;

		Store(CopperGolem golem, Container items, int idx, SlotGroup group) {
			this.golem = golem;
			this.items = items;
			this.idx = idx;
			this.group = group;
		}

		/** The live stored stack (EMPTY when none). */
		ItemStack real() {
			return items.getItem(idx);
		}

		/** The remembered item of a memory slot, else EMPTY. */
		ItemStack memory() {
			return group.memoryOffset() > 0 ? items.getItem(idx - group.memoryOffset()) : ItemStack.EMPTY;
		}

		/** Real stack, else the display copy for an empty cell, else EMPTY. */
		ItemStack display() {
			ItemStack r = real();
			return r.isEmpty() ? ghost() : r;
		}

		private ItemStack ghost() {
			if (group.preview() != null) {
				ItemStack o = golem.level() instanceof ServerLevel level
					? group.preview().outcome(level, GolemData.get(golem)) : ItemStack.EMPTY;
				return o.isEmpty() ? ItemStack.EMPTY : GolemIcon.ghost(o, "preview",
					List.of(GolemIcon.line("Crafted by the golem"), GolemIcon.line("Cannot be taken")));
			}
			ItemStack m = memory();
			return m.isEmpty() ? ItemStack.EMPTY : GolemIcon.ghost(m.copyWithCount(1), "remembered, empty",
				List.of(GolemIcon.line("Only this item fits here"), GolemIcon.line("Set the recipe on the Recipe tab")));
		}

		/** True while the cell shows a display copy: no input may take it, and only placing a fitting item is handled. */
		boolean placeholder() {
			return real().isEmpty() && !ghost().isEmpty();
		}

		/** A memory slot accepts only its remembered item and refuses everything while it remembers nothing. */
		boolean mayPlace(ItemStack stack) {
			if (!items.canPlaceItem(idx, stack) || !group.filter().test(stack)) return false;
			if (group.memoryOffset() == 0) return true;
			ItemStack m = memory();
			return !m.isEmpty() && ItemStack.isSameItemSameComponents(m, stack);
		}

		boolean mayPickup() {
			return !real().isEmpty();
		}

		int maxStack() {
			return group.maxStack();
		}

		void set(ItemStack stack) {
			items.setItem(idx, stack);
		}

		ItemStack remove(int n) {
			return items.removeItem(idx, n);
		}

		void changed() {
			items.setChanged();
		}
	}
}
