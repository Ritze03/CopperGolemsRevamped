package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.ProfessionHelp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * Server-side-only golem menu: always a vanilla 6-row chest menu, so vanilla clients need no mod. Row 0 is the tab
 * bar; rows 1..5 show the current tab (Status, Targets, Recipe for a recipe-group profession, Inventory, Settings,
 * Profession) or the profession confirm
 * screen. The 54 top slots are fixed {@link ViewSlot}s that route to the cells of the current view
 * ({@link GolemViews}), so switching tabs never reopens the menu (a reopen re-centres the mouse on a vanilla
 * client). Cell index = row * 9 + column; the player inventory follows the 54 top cells.
 * See superdoc/features/gui.md.
 */
public class GolemMenu extends AbstractContainerMenu {
	private static final double MAX_DIST_SQ = 64.0;
	static final int COLS = 9;
	static final int ROWS = 6;
	static final int TOP = COLS * ROWS;
	/** Ticks between live refreshes of the Status tab. */
	private static final int STATUS_REFRESH = 10;

	private final CopperGolem golem;
	private final @Nullable Profession profession;
	private final @Nullable Identifier openedProfession;
	/** The profession's declared inventory size: the stored list must not be shorter (see {@link #stillValid}). */
	private final int itemCount;
	private final Container items;
	/** Menu slot indices of the Inventory tab's quick-move targets, last declared group first. */
	private final List<Integer> quickMoveCells = new ArrayList<>();

	private Cell[] cells = new Cell[TOP];
	private Tab tab;
	private boolean confirming;
	private @Nullable Identifier pending;
	private int age;

	public GolemMenu(int id, Inventory inv, CopperGolem golem) {
		this(id, inv, golem, Tab.STATUS);
	}

	public GolemMenu(int id, Inventory inv, CopperGolem golem, Tab tab) {
		super(MenuType.GENERIC_9x6, id);
		this.golem = golem;
		this.profession = GolemService.profession(golem);
		// raw stored id (not the resolved Profession): an unregistered id resolves to null but must still match in stillValid
		this.openedProfession = GolemData.get(golem).profession;
		this.itemCount = profession == null ? 0 : profession.inventorySize();
		this.items = new GolemItemsContainer(golem, itemCount);
		this.tab = tab;
		if (profession != null) {
			List<SlotGroup> groups = profession.slots();
			for (int gi = groups.size() - 1; gi >= 0; gi--) {
				SlotGroup g = groups.get(gi);
				if (!g.visible() || g.recipe() || !g.quickMove()) continue;
				for (int i = 0; i < g.count(); i++) quickMoveCells.add((g.row() + i / g.width()) * COLS + g.col() + i % g.width());
			}
		}
		SimpleContainer dummy = new SimpleContainer(TOP); // never read: every ViewSlot overrides what a Slot does with it
		for (int cell = 0; cell < TOP; cell++) addSlot(new ViewSlot(dummy, cell, 8 + (cell % COLS) * 18, 18 + (cell / COLS) * 18));
		addStandardInventorySlots(inv, 8, 18 + ROWS * 18 + 13);
		rebuild();
	}

	// ---- state the views read and the controls change (package-private: GolemViews) ----

	CopperGolem golem() {
		return golem;
	}

	@Nullable Profession profession() {
		return profession;
	}

	Container items() {
		return items;
	}

	public Tab tab() {
		return tab;
	}

	public boolean confirming() {
		return confirming;
	}

	public @Nullable Identifier pending() {
		return pending;
	}

	/** The id of the profession this menu was opened for (null = None or unknown). */
	@Nullable Identifier currentId() {
		return profession == null ? null : profession.id();
	}

	/** Rebuilds the current view's cells; the next {@code broadcastChanges} syncs whatever changed. */
	void rebuild() {
		cells = GolemViews.build(this);
	}

	/** In-place tab switch: same menu instance, so a vanilla client keeps the mouse where it is. Public for tests. */
	public void showTab(Tab t) {
		tab = t;
		confirming = false;
		pending = null;
		rebuild();
	}

	/** The profession buttons: None switches at once, a working golem asks for confirmation first. */
	void choose(@Nullable Identifier id, ServerPlayer player) {
		if (Objects.equals(id, currentId())) return;
		if (currentId() == null) {
			switchTo(id, player);
		} else {
			pending = id;
			confirming = true;
			rebuild();
		}
	}

	void cancelConfirm() {
		confirming = false;
		pending = null;
		rebuild();
	}

	void confirm(ServerPlayer player) {
		if (confirming) switchTo(pending, player);
	}

	/** Changes the profession (items go to the player) and reopens: the title carries the profession name. */
	private void switchTo(@Nullable Identifier id, ServerPlayer player) {
		if (!(golem.level() instanceof ServerLevel level)) return;
		GolemService.changeProfession(level, golem, id, player);
		GolemMenuOpener.open(player, golem, Tab.PROFESSION);
	}

	void toggleFollow(ServerPlayer player) {
		GolemData data = GolemData.get(golem);
		if (data.frozen) {
			player.sendSystemMessage(Component.literal("Unfreeze the golem first."));
			return;
		}
		data.following = !data.following;
		data.followPlayer = data.following ? player.getUUID() : null;
		GolemData.save(golem, data);
		rebuild();
	}

	/** Settings tab Freeze: the golem stops all work and stands still; freezing also turns Follow off (see freeze.md). */
	void toggleFreeze() {
		GolemData data = GolemData.get(golem);
		data.frozen = !data.frozen;
		if (data.frozen) {
			data.following = false;
			data.followPlayer = null;
		}
		GolemData.save(golem, data);
		rebuild();
	}

	/** Targets tab "Show": the golem walks to the target (block: next to it, area: along its outline). */
	void show(ServerPlayer player, AssignSpec spec) {
		if (!(golem.level() instanceof ServerLevel level)) return;
		GolemData data = GolemData.get(golem);
		String name = GolemViews.targetName(spec);
		if (spec.kind() == Profession.Kind.AREA) {
			BlockPos a = data.assignments.get(spec.key() + "_a");
			BlockPos b = data.assignments.get(spec.key() + "_b");
			if (a == null || b == null) {
				player.sendSystemMessage(Component.literal(name + " is not set."));
				return;
			}
			WalkTo.outline(level, golem, a, b);
		} else {
			BlockPos p = data.assignments.get(spec.key());
			if (p == null) {
				player.sendSystemMessage(Component.literal(name + " is not set."));
				return;
			}
			WalkTo.toBlock(level, golem, p);
		}
		player.sendSystemMessage(Component.literal(WalkTo.active(golem)
			? "Golem walks to " + name + "." : "No place to stand next to " + name + "."));
	}

	/** Targets tab "Clear": removes the assignment (both corners of an area) and saves. */
	void clear(ServerPlayer player, AssignSpec spec) {
		GolemData data = GolemData.get(golem);
		boolean was;
		if (spec.kind() == Profession.Kind.AREA) {
			was = data.assignments.remove(spec.key() + "_a") != null | data.assignments.remove(spec.key() + "_b") != null;
		} else {
			was = data.assignments.remove(spec.key()) != null;
		}
		GolemData.save(golem, data);
		String name = GolemViews.targetName(spec);
		player.sendSystemMessage(Component.literal(was ? "Cleared " + name + "." : name + " was not set."));
		rebuild();
	}

	/** Chat explanation of the current tab (and, where it fits, the profession); the menu stays open. */
	void sendHelp(ServerPlayer player) {
		List<String> lines = new ArrayList<>(tab.help);
		if (profession != null) {
			if (tab == Tab.TARGETS) lines.addAll(ProfessionHelp.assignLines(profession));
			else if (tab == Tab.INVENTORY || tab == Tab.RECIPE) lines.addAll(profession.help());
		}
		player.sendSystemMessage(Component.literal("-- " + tab.label + (profession == null ? "" : ": " + profession.name().getString()) + " --"));
		for (String l : lines) player.sendSystemMessage(Component.literal(l));
	}

	public static boolean inRange(Player player, CopperGolem golem) {
		return golem.isAlive() && player.distanceToSqr(golem) <= MAX_DIST_SQ;
	}

	/** The cell currently at a top slot (tests and views). */
	Cell cell(int index) {
		return cells[index];
	}

	// ---- clicks ----

	/**
	 * Icon cells (controls, labels, filler) never move items: PICKUP / QUICK_MOVE run the control, every other input
	 * on them (CLONE would copy the icon for creative players, SWAP / THROW / PICKUP_ALL) is dropped. A cell that only shows a display copy
	 * (faded input, ghost output) handles PICKUP itself (place a fitting item) and drops every other input, so the
	 * copy can never reach a cursor, however the click is made. QUICK_CRAFT is always passed on, swallowing it would corrupt the drag state (the slots refuse
	 * placement anyway).
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		if (slotId >= 0 && slotId < TOP && input != ContainerInput.QUICK_CRAFT) {
			Cell c = cells[slotId];
			if (c instanceof Cell.Icon icon) {
				if (icon.control() != null && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)
					&& player instanceof ServerPlayer sp && stillValid(sp)) {
					icon.control().click(sp, button);
				}
				return;
			}
			if (c instanceof Cell.Store s && s.placeholder()) {
				placeholderClick(s, button, input, player);
				return;
			}
		}
		super.clicked(slotId, button, input, player);
		// the Recipe tab's arrow lore and ghost preview name the outcome of the template: refresh after any real slot change
		if (tab == Tab.RECIPE && !confirming) rebuild();
	}

	/** A faded cell takes a fitting item on PICKUP; an empty hand does nothing (the recipe is set on the Recipe tab). */
	private void placeholderClick(Cell.Store s, int button, ContainerInput input, Player player) {
		if (input != ContainerInput.PICKUP || !(player instanceof ServerPlayer sp) || !stillValid(sp)) return;
		ItemStack carried = getCarried();
		if (carried.isEmpty() || !s.mayPlace(carried)) return;
		int n = Math.min(button == 0 ? carried.getCount() : 1, Math.min(s.maxStack(), carried.getMaxStackSize()));
		if (n > 0) s.set(carried.split(n));
	}

	/** Double-click collect must not strip a 1-stack slot (tool) or touch icon cells and display copies. */
	@Override
	public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		if (target instanceof ViewSlot v) {
			if (!(cells[v.cell] instanceof Cell.Store s) || s.placeholder() || s.maxStack() == 1) return false;
		}
		return super.canTakeItemForPickAll(carried, target);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
		if (index < TOP && !(cells[index] instanceof Cell.Store s && !s.real().isEmpty())) return ItemStack.EMPTY;
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index < TOP) {
			// golem slot -> player inventory, hotbar end first like vanilla
			if (!moveItemStackTo(stack, TOP, slots.size(), true)) return ItemStack.EMPTY;
		} else {
			// player -> the golem's working slots only, never icon cells, memory or the output
			if (!moveIntoProfession(stack)) return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
		slot.onTake(player, stack);
		return copy;
	}

	/**
	 * Like {@code moveItemStackTo} over the Inventory tab's working slots only (they are not contiguous in cell
	 * space): first merge into matching non-empty slots, then fill empty ones, both respecting {@code mayPlace}
	 * and the slot max. Groups are tried last-declared first, so shift-click fills working storage before a tool slot;
	 * a group declared {@code noQuickMove} is skipped. Does nothing on other tabs.
	 */
	private boolean moveIntoProfession(ItemStack stack) {
		if (tab != Tab.INVENTORY || confirming) return false;
		boolean moved = false;
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			for (int cell : quickMoveCells) {
				if (stack.isEmpty()) break;
				if (!(cells[cell] instanceof Cell.Store s)) continue;
				ItemStack cur = s.real();
				if (pass == 0 ? cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, stack) : !cur.isEmpty()) continue;
				if (!s.mayPlace(stack)) continue;
				int room = Math.min(s.maxStack(), stack.getMaxStackSize()) - cur.getCount();
				int n = Math.min(room, stack.getCount());
				if (n <= 0) continue;
				if (cur.isEmpty()) {
					s.set(stack.split(n));
				} else {
					cur.grow(n);
					stack.shrink(n);
					s.changed();
				}
				moved = true;
			}
		}
		return moved;
	}

	@Override
	public void broadcastChanges() {
		if (tab == Tab.STATUS && !confirming && ++age % STATUS_REFRESH == 0) rebuild();
		super.broadcastChanges();
	}

	@Override
	public boolean stillValid(Player player) {
		if (!inRange(player, golem)) return false;
		// The profession is fixed per menu: close if another player changed it or the stored list shrank.
		GolemData d = GolemData.get(golem);
		return Objects.equals(d.profession, openedProfession) && d.items.size() >= itemCount;
	}

	/**
	 * One of the 54 top slots. Every Slot behaviour routes to the cell of the current view; the backing container is
	 * a dummy that is never read. A display copy is only ever returned from {@code getItem}, never stored.
	 */
	private final class ViewSlot extends Slot {
		final int cell;

		ViewSlot(Container dummy, int cell, int x, int y) {
			super(dummy, cell, x, y);
			this.cell = cell;
		}

		@Override
		public ItemStack getItem() {
			Cell c = cells[cell];
			return c instanceof Cell.Icon i ? i.stack() : ((Cell.Store) c).display();
		}

		@Override
		public void set(ItemStack stack) {
			if (cells[cell] instanceof Cell.Store s) s.set(stack);
		}

		@Override
		public ItemStack remove(int amount) {
			return cells[cell] instanceof Cell.Store s ? s.remove(amount) : ItemStack.EMPTY;
		}

		@Override
		public void setChanged() {
			if (cells[cell] instanceof Cell.Store s) s.changed();
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return cells[cell] instanceof Cell.Store s && s.mayPlace(stack);
		}

		@Override
		public boolean mayPickup(Player player) {
			return cells[cell] instanceof Cell.Store s && s.mayPickup();
		}

		@Override
		public int getMaxStackSize() {
			return cells[cell] instanceof Cell.Store s ? s.maxStack() : 64;
		}
	}
}
