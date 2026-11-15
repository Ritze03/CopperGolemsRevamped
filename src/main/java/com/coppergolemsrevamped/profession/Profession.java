package com.coppergolemsrevamped.profession;

import com.coppergolemsrevamped.core.GolemData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

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
	Tick tick,
	List<String> help
) {
	/** Declaration without help text; {@link Professions#register} fills it in from {@link ProfessionHelp}. */
	public Profession(Identifier id, Component name, List<SlotGroup> slots, List<AssignSpec> assigns,
		boolean runsVanillaBrain, Tick tick) {
		this(id, name, slots, assigns, runsVanillaBrain, tick, List.of());
	}

	/** Extra per-slot placement check on top of {@link SlotGroup#filter}: sees the golem's whole stored list. */
	@FunctionalInterface
	public interface SlotRule {
		/** May {@code stack} be placed into slot {@code slotInGroup} of its group; {@code items} = {@link GolemData#items}. */
		boolean mayPlace(List<ItemStack> items, int slotInGroup, ItemStack stack);
	}

	/**
	 * A run of {@code count} slots. In the GUI a group normally starts on a new row at column 0 and wraps after
	 * {@code width} cells (3 = a 3x3 square for count 9); the 4-arg constructor means width 9.
	 * {@code besideCol > 0} (see {@link #beside}) instead places the group at that column, starting {@code besideRow}
	 * rows below the first row of the previous stacked group, so it sits next to it. {@code rule} (nullable) is a
	 * per-slot placement check, {@code quickMove} false keeps shift-click from filling the group.
	 */
	public record SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter, int width,
		int besideCol, int besideRow, @Nullable SlotRule rule, boolean quickMove) {
		public static final int DEFAULT_WIDTH = 9;

		public SlotGroup {
			if (width < 1 || width > DEFAULT_WIDTH) throw new IllegalArgumentException("width must be 1..9: " + width);
			if (besideCol < 0 || besideRow < 0 || (besideCol > 0 && besideCol + Math.min(width, count) > DEFAULT_WIDTH)) {
				throw new IllegalArgumentException("bad beside placement: " + besideCol + "," + besideRow);
			}
		}

		public SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter, int width) {
			this(key, count, maxStack, filter, width, 0, 0, null, true);
		}

		public SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter) {
			this(key, count, maxStack, filter, DEFAULT_WIDTH);
		}

		/** Same group placed beside the previous one at column {@code col} (1..8), {@code row} rows down. */
		public SlotGroup beside(int col, int row) {
			return new SlotGroup(key, count, maxStack, filter, width, col, row, rule, quickMove);
		}

		public SlotGroup withRule(SlotRule r) {
			return new SlotGroup(key, count, maxStack, filter, width, besideCol, besideRow, r, quickMove);
		}

		/** Shift-click never fills this group. */
		public SlotGroup noQuickMove() {
			return new SlotGroup(key, count, maxStack, filter, width, besideCol, besideRow, rule, false);
		}

		/** GUI rows this group occupies. */
		public int rows() {
			return (count + width - 1) / width;
		}
	}

	public enum Kind { CHEST, CRAFTING_TABLE, BLOCK, AREA }

	/** {@code maxSize} = max footprint edge for AREA, else 0. {@code help} = what the target is for, shown when armed. */
	public record AssignSpec(String key, Component label, Kind kind, int maxSize, String help) {
		public AssignSpec(String key, Component label, Kind kind, int maxSize) {
			this(key, label, kind, maxSize, "");
		}
	}

	@FunctionalInterface
	public interface Tick {
		void tick(ServerLevel level, CopperGolem golem, GolemData data);
	}

	public int inventorySize() {
		int n = 0;
		for (SlotGroup g : slots) n += g.count();
		return n;
	}

	/**
	 * Origin {row, col} of every slot group, row relative to the first profession row. A stacked group starts one
	 * filler row below everything so far at column 0; a {@linkplain SlotGroup#besideCol beside} group shares the
	 * rows of the last stacked group. Slot {@code i} of a group sits at row {@code row + i / width},
	 * column {@code col + i % width}.
	 */
	public List<int[]> layout() {
		List<int[]> origins = new ArrayList<>();
		int end = 0;
		int anchor = 0;
		for (SlotGroup g : slots) {
			int row;
			int col;
			if (g.besideCol() > 0 && !origins.isEmpty()) {
				row = anchor + g.besideRow();
				col = g.besideCol();
			} else {
				row = origins.isEmpty() ? 0 : end + 1;
				col = 0;
				anchor = row;
			}
			end = Math.max(end, row + g.rows());
			origins.add(new int[] {row, col});
		}
		return origins;
	}

	/** GUI rows taken by all slot groups (see {@link #layout}): stacked groups get one filler row between them. */
	public int slotRows() {
		int n = 0;
		List<int[]> origins = layout();
		for (int i = 0; i < origins.size(); i++) n = Math.max(n, origins.get(i)[0] + slots.get(i).rows());
		return n;
	}

	public void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		tick.tick(level, golem, data);
	}
}
