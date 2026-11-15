package com.coppergolemsrevamped.profession;

import com.coppergolemsrevamped.core.GolemData;

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

	/** Computes what a profession would produce right now ({@link ItemStack#EMPTY} for nothing); drives {@link SlotGroup#previewing}. */
	@FunctionalInterface
	public interface Preview {
		ItemStack outcome(ServerLevel level, GolemData data);
	}

	/**
	 * A non-interactive icon cell next to a group in the Inventory tab. {@code icon} is a {@code GolemIcon} name
	 * ({@code assign_tools}) or a vanilla item id ({@code minecraft:bone_meal}); the menu resolves it.
	 */
	public record Label(int row, int col, String icon, String name, String lore) {}

	/**
	 * A run of {@code count} slots in {@code GolemData.items}. A group is shown in the Inventory tab only after
	 * {@link #at}: slot {@code i} sits at row {@code row + i / width}, column {@code col + i % width} of the 6-row menu
	 * (row 0 is the tab bar, so rows 1..5; the 4-arg constructor means width 9). Without {@code at} the group is
	 * hidden: it still reserves its data slots. {@code recipe} (see {@link #recipeAt}): a ghost recipe, shown on the Recipe tab.
	 * {@code memoryOffset > 0}: slot {@code k} remembers its item in data slot {@code k - memoryOffset}; an empty slot
	 * shows it faded and accepts only that item, a slot whose memory is empty accepts nothing.
	 * {@code inHand}: the first non-empty stack is shown in the golem's main hand. {@code preview}: while the (single)
	 * slot is empty it shows the outcome as a take-less ghost, and the label's lore names it.
	 */
	public record SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter, int width,
		int row, int col, boolean quickMove, int memoryOffset, boolean inHand, boolean recipe, @Nullable Label label,
		@Nullable Preview preview) {
		public static final int DEFAULT_WIDTH = 9;
		public static final int HIDDEN = -1;

		public SlotGroup {
			if (width < 1 || width > DEFAULT_WIDTH) throw new IllegalArgumentException("width must be 1..9: " + width);
		}

		public SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter, int width) {
			this(key, count, maxStack, filter, width, HIDDEN, 0, true, 0, false, false, null, null);
		}

		public SlotGroup(String key, int count, int maxStack, Predicate<ItemStack> filter) {
			this(key, count, maxStack, filter, DEFAULT_WIDTH);
		}

		public boolean visible() {
			return row != HIDDEN;
		}

		/** Shown in the Inventory tab starting at {@code r} (1..5), {@code c} (0..8). */
		public SlotGroup at(int r, int c) {
			return new SlotGroup(key, count, maxStack, filter, width, r, c, quickMove, memoryOffset, inHand, recipe, label, preview);
		}

		/** A label icon cell at its own position. */
		public SlotGroup labeled(int r, int c, String icon, String name, String lore) {
			return new SlotGroup(key, count, maxStack, filter, width, row, col, quickMove, memoryOffset, inHand, recipe,
				new Label(r, c, icon, name, lore), preview);
		}

		/** Slot {@code k} remembers its item in data slot {@code k - offset}. */
		public SlotGroup remembers(int offset) {
			return new SlotGroup(key, count, maxStack, filter, width, row, col, quickMove, offset, inHand, recipe, label, preview);
		}

		/** Shift-click never fills this group. */
		public SlotGroup noQuickMove() {
			return new SlotGroup(key, count, maxStack, filter, width, row, col, false, memoryOffset, inHand, recipe, label, preview);
		}

		/** The golem shows the first non-empty stack of this group in its main hand (display only). */
		public SlotGroup shownInHand() {
			return new SlotGroup(key, count, maxStack, filter, width, row, col, quickMove, memoryOffset, true, recipe, label, preview);
		}

		public SlotGroup previewing(Preview p) {
			return new SlotGroup(key, count, maxStack, filter, width, row, col, quickMove, memoryOffset, inHand, recipe, label, p);
		}

		/**
		 * A ghost recipe: the group is the profession's memory (not items), shown only on the Recipe tab starting at
		 * {@code r}, {@code c}, where a click with an item sets a cell to a one-item copy and an empty hand clears it.
		 * Never shown on the Inventory tab. A profession with such a group gets the Recipe tab (see {@link #hasRecipeTab}).
		 */
		public SlotGroup recipeAt(int r, int c) {
			return new SlotGroup(key, count, maxStack, filter, width, r, c, false, memoryOffset, inHand, true, label, preview);
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

	/** The data index of the first slot of group {@code g} (declaration order). */
	public int firstIndex(SlotGroup g) {
		int n = 0;
		for (SlotGroup o : slots) {
			if (o == g) return n;
			n += o.count();
		}
		throw new IllegalArgumentException("not a group of " + id);
	}

	/** The group with this key, or null. */
	public @Nullable SlotGroup group(String key) {
		for (SlotGroup g : slots) if (g.key().equals(key)) return g;
		return null;
	}

	/** True when a group is a ghost recipe ({@link SlotGroup#recipeAt}): the menu then shows a Recipe tab before Inventory. */
	public boolean hasRecipeTab() {
		for (SlotGroup g : slots) if (g.recipe()) return true;
		return false;
	}

	/**
	 * Throws when a shown group or label leaves rows 1..5 / columns 0..8 or two cells overlap (checked on registration).
	 * The Inventory tab and the Recipe tab are separate boards: recipe groups only on the Recipe tab, previewing groups
	 * (their ghost and label) on both.
	 */
	public void validateLayout() {
		boolean[] inventory = new boolean[6 * 9];
		boolean[] recipe = new boolean[6 * 9];
		for (SlotGroup g : slots) {
			if (!g.visible()) continue;
			if (!g.recipe()) claimGroup(inventory, g);
			if (g.recipe() || g.preview() != null) claimGroup(recipe, g);
		}
	}

	private void claimGroup(boolean[] used, SlotGroup g) {
		for (int i = 0; i < g.count(); i++) claim(used, g.row() + i / g.width(), g.col() + i % g.width(), g.key());
		if (g.label() != null) claim(used, g.label().row(), g.label().col(), g.key() + " label");
	}

	private void claim(boolean[] used, int r, int c, String what) {
		if (r < 1 || r > 5 || c < 0 || c > 8 || used[r * 9 + c]) {
			throw new IllegalStateException(id + ": " + what + " has no free cell at row " + r + ", col " + c);
		}
		used[r * 9 + c] = true;
	}

	public void tick(ServerLevel level, CopperGolem golem, GolemData data) {
		tick.tick(level, golem, data);
	}
}
