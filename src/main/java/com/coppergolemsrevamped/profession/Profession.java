package com.coppergolemsrevamped.profession;

import com.coppergolemsrevamped.core.GolemData;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

/**
 * Declaration of a profession: inventory layout, assign buttons and a server tick.
 * Inventory size = sum of {@link SlotGroup#count()}; slot index order = declaration order.
 * An {@link Kind#AREA} assign uses two keys in {@link GolemData#assignments}: {@code key + "_a"} and {@code key + "_b"}.
 */
public record Profession(
	Identifier id,
	Component name,
	List<SlotGroup> slots,
	List<AssignSpec> assigns,
	boolean runsVanillaBrain,
	Tick tick
) {
	/**
	 * A run of {@code count} slots. In the GUI each group starts on a new row and wraps after {@code width} cells
	 * (3 = a 3x3 square for count 9); the 4-arg constructor means width 9.
	 */
	public record SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter, int width) {
		public static final int DEFAULT_WIDTH = 9;

		public SlotGroup {
			if (width < 1 || width > DEFAULT_WIDTH) throw new IllegalArgumentException("width must be 1..9: " + width);
		}

		public SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter) {
			this(key, count, maxStack, filter, DEFAULT_WIDTH);
		}

		/** GUI rows this group occupies. */
		public int rows() {
			return (count + width - 1) / width;
		}
	}

	public enum Kind { CHEST, CRAFTING_TABLE, BLOCK, AREA }

	/** {@code maxSize} = max footprint edge for AREA, else 0. */
	public record AssignSpec(String key, Component label, Kind kind, int maxSize) {}

	@FunctionalInterface
	public interface Tick {
		void tick(ServerLevel level, CopperGolem golem, GolemData data);
	}

	public int inventorySize() {
		int n = 0;
		for (SlotGroup g : slots) n += g.count();
		return n;
	}

	/** GUI rows taken by all slot groups (each group starts on a new row). */
	public int slotRows() {
		int n = 0;
		for (SlotGroup g : slots) n += g.rows();
		return n;
	}

	public void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		tick.tick(level, golem, data);
	}
}
