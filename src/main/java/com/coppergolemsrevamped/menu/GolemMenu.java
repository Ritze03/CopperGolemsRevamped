package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Professions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side-only golem menu: a vanilla chest menu type (9 x 1..6) with a custom slot layout, so vanilla clients
 * need no mod. Row 0 holds controls drawn as items; the rows below hold the profession's slots (from its declaration)
 * and filler. Cell index = row * 9 + column; the player inventory follows the {@code rows * 9} top cells.
 * See superdoc/features/gui.md.
 */
public class GolemMenu extends AbstractContainerMenu {
	private static final double MAX_DIST_SQ = 64.0;
	private static final int COLS = 9;
	private static final int MAX_ROWS = 6;
	private static final int PROFESSION_CELL = 0;
	private static final int FOLLOW_CELL = 1;
	private static final int FIRST_ASSIGN_CELL = 2;
	private static final int HELD_CELL = 8;
	/** Cells reserved for assign buttons (2..7). */
	private static final int MAX_ASSIGNS = HELD_CELL - FIRST_ASSIGN_CELL;
	/** First profession slot cell (row 1). */
	private static final int ITEMS_START = COLS;

	private static final List<MenuType<?>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
		MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

	/** A control cell's click action; {@code button} is 0 for left, 1 for right. */
	@FunctionalInterface
	private interface Control {
		void click(ServerPlayer player, int button);
	}

	private final CopperGolem golem;
	private final @Nullable Profession profession;
	private final @Nullable Identifier openedProfession;
	private final int itemCount;
	private final int rows;
	/** Icon stacks of the control and filler cells (indexed by cell; the held and profession cells stay empty). */
	private final SimpleContainer icons;
	/** Per cell: the click action, null for filler / held / profession cells. */
	private final Control[] controls;
	/** Per cell: true for control and filler slots (no item can enter or leave them). */
	private final boolean[] iconCell;

	public GolemMenu(int id, Inventory inv, CopperGolem golem) {
		this(id, inv, golem, GolemService.profession(golem));
	}

	private GolemMenu(int id, Inventory inv, CopperGolem golem, @Nullable Profession profession) {
		super(TYPES.get(rowsFor(profession) - 1), id);
		this.golem = golem;
		this.profession = profession;
		this.openedProfession = profession == null ? null : profession.id();
		this.rows = rowsFor(profession);
		this.itemCount = profession == null ? 0 : Math.min(profession.inventorySize(), (rows - 1) * COLS);
		this.icons = new SimpleContainer(rows * COLS);
		this.controls = new Control[rows * COLS];
		this.iconCell = new boolean[rows * COLS];

		Container items = new GolemItemsContainer(golem, itemCount);
		Container held = new GolemHeldContainer(golem);
		List<Profession.SlotGroup> groups = profession == null ? List.of() : profession.slots();

		buildControls();
		// per-slot filter / max stack from the declaration, in slot order
		List<Predicate<ItemStack>> filters = new ArrayList<>();
		List<Integer> maxStacks = new ArrayList<>();
		for (Profession.SlotGroup g : groups) {
			for (int i = 0; i < g.count(); i++) {
				filters.add(g.filter());
				maxStacks.add(g.maxStack());
			}
		}

		for (int cell = 0; cell < rows * COLS; cell++) {
			int x = 8 + (cell % COLS) * 18;
			int y = 18 + (cell / COLS) * 18;
			if (cell == HELD_CELL) {
				addSlot(new Slot(held, 0, x, y) {
					@Override
					public boolean mayPlace(ItemStack stack) {
						return false;
					}
				});
			} else if (cell >= ITEMS_START && cell < ITEMS_START + itemCount) {
				int k = cell - ITEMS_START;
				addSlot(new GroupSlot(items, k, x, y, maxStacks.get(k), filters.get(k)));
			} else {
				iconCell[cell] = true;
				if (controls[cell] == null) icons.setItem(cell, GolemIcon.filler());
				addSlot(new IconSlot(icons, cell, x, y));
			}
		}
		addStandardInventorySlots(inv, 8, 18 + rows * 18 + 13);
	}

	/** Rows = 1 control row + ceil(profession slots / 9), at most 6. */
	private static int rowsFor(@Nullable Profession p) {
		int n = p == null ? 0 : p.inventorySize();
		return Math.min(MAX_ROWS, 1 + (n + COLS - 1) / COLS);
	}

	private void buildControls() {
		GolemData data = GolemData.get(golem);

		String path = profession == null ? "none" : profession.id().getPath();
		Component profName = profession == null ? Component.literal("None") : profession.name();
		icons.setItem(PROFESSION_CELL, GolemIcon.profession(path).stack(
			Component.literal("Profession: ").append(profName),
			List.of(GolemIcon.line("Left-click: next profession"), GolemIcon.line("Right-click: previous profession")),
			false));
		controls[PROFESSION_CELL] = this::cycleProfession;

		icons.setItem(FOLLOW_CELL, followIcon(data.following));
		controls[FOLLOW_CELL] = (player, button) -> toggleFollow(player);

		if (profession == null) return;
		List<AssignSpec> assigns = profession.assigns();
		for (int i = 0; i < Math.min(assigns.size(), MAX_ASSIGNS); i++) {
			AssignSpec spec = assigns.get(i);
			int cell = FIRST_ASSIGN_CELL + i;
			icons.setItem(cell, assignIcon(spec, data));
			controls[cell] = (player, button) -> {
				player.closeContainer();
				AssignMode.arm(player, golem, spec.key());
			};
		}
	}

	private static ItemStack followIcon(boolean on) {
		return (on ? GolemIcon.FOLLOW_ON : GolemIcon.FOLLOW_OFF).stack(
			Component.literal("Follow: " + (on ? "on" : "off")),
			List.of(GolemIcon.line("Click to toggle"), GolemIcon.line("The golem follows you while on")),
			on);
	}

	private static ItemStack assignIcon(AssignSpec spec, GolemData data) {
		GolemIcon icon = switch (spec.kind()) {
			case CHEST -> GolemIcon.ASSIGN_CHEST;
			case CRAFTING_TABLE -> GolemIcon.ASSIGN_CRAFTING_TABLE;
			case BLOCK -> GolemIcon.ASSIGN_BLOCK;
			case AREA -> GolemIcon.ASSIGN_AREA;
		};
		String how = switch (spec.kind()) {
			case CHEST -> "Click, then right-click a chest";
			case CRAFTING_TABLE -> "Click, then right-click a crafting table";
			case BLOCK -> "Click, then right-click a block";
			case AREA -> "Click, then right-click two opposite corners";
		};
		String current;
		if (spec.kind() == Profession.Kind.AREA) {
			BlockPos a = data.assignments.get(spec.key() + "_a");
			BlockPos b = data.assignments.get(spec.key() + "_b");
			current = a == null || b == null ? "unset" : fmt(a) + " to " + fmt(b);
		} else {
			BlockPos p = data.assignments.get(spec.key());
			current = p == null ? "unset" : fmt(p);
		}
		return icon.stack(spec.label(), List.of(GolemIcon.line("Current: " + current), GolemIcon.line(how)), false);
	}

	private static String fmt(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	// ---- control actions (server thread, trust already checked in clicked) ----

	private void cycleProfession(ServerPlayer player, int button) {
		if (!(golem.level() instanceof ServerLevel level)) return;
		// order: no profession, then registration order
		List<Identifier> order = new ArrayList<>();
		order.add(null);
		for (Profession p : Professions.all()) order.add(p.id());
		int cur = order.indexOf(GolemData.get(golem).profession);
		int next = Math.floorMod(cur + (button == 1 ? -1 : 1), order.size());
		GolemService.changeProfession(level, golem, order.get(next));
		// the slot layout depends on the profession, so rebuild the menu
		GolemMenuOpener.open(player, golem);
	}

	private void toggleFollow(ServerPlayer player) {
		GolemData data = GolemData.get(golem);
		data.following = !data.following;
		data.followPlayer = data.following ? player.getUUID() : null;
		GolemData.save(golem, data);
		// broadcastChanges on the next tick syncs the new icon
		icons.setItem(FOLLOW_CELL, followIcon(data.following));
	}

	public static boolean inRange(Player player, CopperGolem golem) {
		return golem.isAlive() && player.distanceToSqr(golem) <= MAX_DIST_SQ;
	}

	/**
	 * Control and filler cells never move items: PICKUP / QUICK_MOVE run the control, every other input on them
	 * (CLONE would copy the icon for creative players, SWAP / THROW / PICKUP_ALL) is dropped. QUICK_CRAFT is always
	 * passed on, swallowing it would corrupt the drag state (the slots refuse placement anyway).
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		if (slotId >= 0 && slotId < iconCell.length && iconCell[slotId] && input != ContainerInput.QUICK_CRAFT) {
			Control c = controls[slotId];
			if (c != null && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)
				&& player instanceof ServerPlayer sp && stillValid(sp)) {
				c.click(sp, button);
			}
			return;
		}
		super.clicked(slotId, button, input, player);
	}

	/** First player-inventory slot index. */
	private int playerStart() { return rows * COLS; }

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
		if (index < playerStart() && iconCell[index]) return ItemStack.EMPTY;
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index < playerStart()) {
			// golem (held or profession slot) -> player inventory, hotbar end first like vanilla
			if (!moveItemStackTo(stack, playerStart(), slots.size(), true)) return ItemStack.EMPTY;
		} else {
			// player -> profession slots only; never the held / control / filler cells (the merge pass skips mayPlace)
			if (itemCount == 0 || !moveItemStackTo(stack, ITEMS_START, ITEMS_START + itemCount, false)) return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
		slot.onTake(player, stack);
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		if (!inRange(player, golem)) return false;
		// Layout is fixed at construction: close if another player changed the profession or the stored list shrank.
		GolemData d = GolemData.get(golem);
		return Objects.equals(d.profession, openedProfession) && d.items.size() >= itemCount;
	}

	/** Control or filler cell: nothing can be placed in or taken out of it. */
	private static class IconSlot extends Slot {
		IconSlot(Container c, int index, int x, int y) {
			super(c, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}
	}

	private static class GroupSlot extends Slot {
		private final int maxStack;
		private final Predicate<ItemStack> filter;

		GroupSlot(Container c, int index, int x, int y, int maxStack, Predicate<ItemStack> filter) {
			super(c, index, x, y);
			this.maxStack = maxStack;
			this.filter = filter;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return container.canPlaceItem(getContainerSlot(), stack) && filter.test(stack);
		}

		@Override
		public int getMaxStackSize() {
			return maxStack;
		}
	}
}
