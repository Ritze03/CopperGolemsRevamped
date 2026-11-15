package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.assign.AssignMode;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.ProfessionHelp;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Professions;

import java.util.ArrayList;
import java.util.Arrays;
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
	private static final int HELP_CELL = 7;
	private static final int HELD_CELL = 8;
	/** Cells reserved for assign buttons (2..6); the Help button owns cell 7, so a profession has at most 5 assigns. */
	private static final int MAX_ASSIGNS = HELP_CELL - FIRST_ASSIGN_CELL;

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
	/** Shown profession slots: a prefix of the declared slot order (rows beyond the 6-row limit are cut). */
	private final int itemCount;
	private final int rows;
	/** Per cell: index into {@link GolemData#items}, -1 for control / filler / held cells. */
	private final int[] itemOfCell;
	/** Menu slot indices of the profession slots in quick-move order (last declared group first). */
	private final List<Integer> quickMoveCells = new ArrayList<>();
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
		// raw stored id (not the resolved Profession): an unregistered id resolves to null but must still match in stillValid
		this.openedProfession = GolemData.get(golem).profession;
		this.rows = rowsFor(profession);
		this.itemOfCell = new int[rows * COLS];
		Arrays.fill(itemOfCell, -1);
		this.icons = new SimpleContainer(rows * COLS);
		this.controls = new Control[rows * COLS];
		this.iconCell = new boolean[rows * COLS];

		List<Profession.SlotGroup> groups = profession == null ? List.of() : profession.slots();

		buildControls();
		// per-slot filter / max stack from the declaration, in slot order; group origins come from Profession#layout
		List<Predicate<ItemStack>> filters = new ArrayList<>();
		List<Integer> maxStacks = new ArrayList<>();
		List<Profession.SlotRule> rules = new ArrayList<>();
		List<Integer> slotInGroup = new ArrayList<>();
		List<Boolean> quick = new ArrayList<>();
		List<int[]> origins = profession == null ? List.of() : profession.layout();
		List<List<Integer>> groupCells = new ArrayList<>();
		int shown = 0;
		boolean cut = false;
		for (int gi = 0; gi < groups.size(); gi++) {
			Profession.SlotGroup g = groups.get(gi);
			int[] origin = origins.get(gi);
			List<Integer> cells = new ArrayList<>();
			for (int i = 0; i < g.count(); i++) {
				int cell = (1 + origin[0] + i / g.width()) * COLS + origin[1] + i % g.width();
				if (cut || cell >= rows * COLS) {
					cut = true; // keep the shown slots a prefix of the declared order
					continue;
				}
				itemOfCell[cell] = filters.size();
				filters.add(g.filter());
				maxStacks.add(g.maxStack());
				rules.add(g.rule());
				slotInGroup.add(i);
				cells.add(cell);
				shown++;
			}
			groupCells.add(g.quickMove() ? cells : List.of());
		}
		this.itemCount = shown;
		for (int gi = groupCells.size() - 1; gi >= 0; gi--) quickMoveCells.addAll(groupCells.get(gi));

		Container items = new GolemItemsContainer(golem, itemCount);
		Container held = new GolemHeldContainer(golem);
		for (int cell = 0; cell < rows * COLS; cell++) {
			int x = 8 + (cell % COLS) * 18;
			int y = 18 + (cell / COLS) * 18;
			if (cell == HELD_CELL) {
				addSlot(new HeldSlot(held, x, y));
			} else if (itemOfCell[cell] >= 0) {
				int k = itemOfCell[cell];
				addSlot(new GroupSlot(items, k, x, y, maxStacks.get(k), filters.get(k), rules.get(k), slotInGroup.get(k), golem));
			} else {
				iconCell[cell] = true;
				if (controls[cell] == null) icons.setItem(cell, GolemIcon.filler());
				addSlot(new IconSlot(icons, cell, x, y));
			}
		}
		addStandardInventorySlots(inv, 8, 18 + rows * 18 + 13);
	}

	/** Rows = 1 control row + the rows of every slot group (each group on new rows), at most 6. */
	private static int rowsFor(@Nullable Profession p) {
		return Math.min(MAX_ROWS, 1 + (p == null ? 0 : p.slotRows())); // slotRows includes the separator rows
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

		icons.setItem(HELP_CELL, GolemIcon.HELP.stack(Component.literal("Help"),
			List.of(GolemIcon.line("Click for help in chat")), false));
		controls[HELP_CELL] = (player, button) -> sendHelp(player);

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
		GolemIcon keyed = GolemIcon.byName("assign_" + spec.key());
		GolemIcon icon = keyed != null ? keyed : switch (spec.kind()) {
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
		int cur = Math.max(0, order.indexOf(GolemData.get(golem).profession)); // unknown id counts as None
		int next = Math.floorMod(cur + (button == 1 ? -1 : 1), order.size());
		GolemService.changeProfession(level, golem, order.get(next), player);
		// the slot layout depends on the profession, so rebuild the menu
		GolemMenuOpener.open(player, golem);
	}

	/** Chat explanation of the current profession; the menu stays open. */
	private void sendHelp(ServerPlayer player) {
		Profession p = GolemService.profession(golem);
		List<String> lines = p == null
			? List.of("No profession: the golem stands still.",
				"Click the Profession button (left = next, right = previous) to pick one.",
				"Then use its Assign buttons to set chests, blocks and areas.")
			: ProfessionHelp.lines(p);
		player.sendSystemMessage(Component.literal("-- " + (p == null ? "Copper Golem" : p.name().getString()) + " --"));
		for (String l : lines) player.sendSystemMessage(Component.literal(l));
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
		// empty hand: the cell shows a placeholder that no input may touch (QUICK_CRAFT still goes on, mayPlace refuses it)
		if (slotId == HELD_CELL && input != ContainerInput.QUICK_CRAFT && heldIsEmpty()) return;
		super.clicked(slotId, button, input, player);
	}

	/** Double-click collect must not strip a 1-stack template slot (and never touches control / filler / held cells). */
	@Override
	public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		if (target instanceof GroupSlot g && g.maxStack == 1) return false;
		return super.canTakeItemForPickAll(carried, target);
	}

	private boolean heldIsEmpty() {
		return golem.getMainHandItem().isEmpty();
	}

	/** First player-inventory slot index. */
	private int playerStart() { return rows * COLS; }

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
		if (index < playerStart() && iconCell[index]) return ItemStack.EMPTY;
		if (index == HELD_CELL && heldIsEmpty()) return ItemStack.EMPTY;
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index < playerStart()) {
			// golem (held or profession slot) -> player inventory, hotbar end first like vanilla
			if (!moveItemStackTo(stack, playerStart(), slots.size(), true)) return ItemStack.EMPTY;
		} else {
			// player -> profession slots only; never the held / control / filler cells
			if (!moveIntoProfession(stack)) return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
		slot.onTake(player, stack);
		return copy;
	}

	/**
	 * Like {@code moveItemStackTo} over the profession slots only (they are not contiguous in cell space): first merge
	 * into matching non-empty slots, then fill empty ones, both respecting {@code mayPlace} and the slot max.
	 * Groups are tried last-declared first, so shift-click fills working storage before a recipe template; a group
	 * declared {@code noQuickMove} is skipped.
	 */
	private boolean moveIntoProfession(ItemStack stack) {
		boolean moved = false;
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			for (int cell : quickMoveCells) {
				if (stack.isEmpty()) break;
				Slot slot = slots.get(cell);
				ItemStack cur = slot.getItem();
				if (pass == 0 ? cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, stack) : !cur.isEmpty()) continue;
				if (!slot.mayPlace(stack)) continue;
				int room = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize()) - cur.getCount();
				int n = Math.min(room, stack.getCount());
				if (n <= 0) continue;
				if (cur.isEmpty()) slot.setByPlayer(stack.split(n));
				else {
					cur.grow(n);
					stack.shrink(n);
					slot.setChanged();
				}
				moved = true;
			}
		}
		return moved;
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

	/**
	 * The golem's carried item, take-out only. While the hand is empty {@code getItem} returns a cached placeholder
	 * icon so the cell is distinguishable from an empty slot; the placeholder is not pickable and every click on the
	 * cell is dropped in {@link #clicked}. {@code remove} / {@code set} only ever touch the real container.
	 */
	private static class HeldSlot extends Slot {
		private final ItemStack placeholder = GolemIcon.HELD_EMPTY.stack(Component.literal("Held item"),
			List.of(GolemIcon.line("The golem is holding nothing")), false);

		HeldSlot(Container c, int x, int y) {
			super(c, 0, x, y);
		}

		@Override
		public ItemStack getItem() {
			ItemStack real = container.getItem(0);
			return real.isEmpty() ? placeholder : real;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return !container.getItem(0).isEmpty();
		}
	}

	private static class GroupSlot extends Slot {
		private final int maxStack;
		private final Predicate<ItemStack> filter;
		private final Profession.@Nullable SlotRule rule;
		private final int slotInGroup;
		private final CopperGolem golem;

		GroupSlot(Container c, int index, int x, int y, int maxStack, Predicate<ItemStack> filter,
			Profession.@Nullable SlotRule rule, int slotInGroup, CopperGolem golem) {
			super(c, index, x, y);
			this.maxStack = maxStack;
			this.filter = filter;
			this.rule = rule;
			this.slotInGroup = slotInGroup;
			this.golem = golem;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return container.canPlaceItem(getContainerSlot(), stack) && filter.test(stack)
				&& (rule == null || rule.mayPlace(GolemData.get(golem).items, slotInGroup, stack));
		}

		@Override
		public int getMaxStackSize() {
			return maxStack;
		}

	}
}
