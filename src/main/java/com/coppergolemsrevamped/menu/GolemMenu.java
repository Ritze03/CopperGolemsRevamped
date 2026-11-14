package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Golem menu, built entirely from the profession declaration (one slot section per SlotGroup) plus the
 * vanilla held-item slot. Slot order: held slot (0), profession slots, player inventory (27), hotbar (9).
 * The same class runs on both sides; the server passes live containers and the golem, the client plain ones.
 */
public class GolemMenu extends AbstractContainerMenu {
	public static final int WIDTH = 176;
	public static final int TOP_ROW_Y = 18;
	public static final int ASSIGN_ROW_H = 22;
	private static final double MAX_DIST_SQ = 64.0;

	public record Heading(String text, int y) {}

	private final GolemMenuData data;
	private final @Nullable CopperGolem golem;
	private final @Nullable Profession profession;
	private final int itemCount;
	private final List<Heading> headings = new ArrayList<>();
	private int assignY;
	private int heldY;
	private int invLabelY;
	private int imageHeight;
	private int clientFollowing;

	/** Client factory (ExtendedMenuType). */
	public GolemMenu(int id, Inventory inv, GolemMenuData data) {
		this(id, inv, data, null, null, null);
	}

	public static GolemMenu server(int id, Inventory inv, CopperGolem golem, GolemMenuData data) {
		Profession p = Professions.get(data.profession().orElse(null));
		return new GolemMenu(id, inv, data, golem, new GolemItemsContainer(golem, p == null ? 0 : p.inventorySize()), new GolemHeldContainer(golem));
	}

	private GolemMenu(int id, Inventory inv, GolemMenuData data, @Nullable CopperGolem golem, @Nullable Container items, @Nullable Container held) {
		super(ModMenus.GOLEM, id);
		this.data = data;
		this.golem = golem;
		this.profession = Professions.get(data.profession().orElse(null));
		this.itemCount = profession == null ? 0 : profession.inventorySize();
		if (items == null) items = new SimpleContainer(itemCount);
		if (held == null) held = new SimpleContainer(1);
		this.clientFollowing = data.following() ? 1 : 0;

		int y = TOP_ROW_Y + 24;
		assignY = y;
		if (profession != null) y += ASSIGN_ROW_H * profession.assigns().size();

		heldY = y;
		addSlot(new Slot(held, 0, 8, y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		});
		y += 20;

		int index = 0;
		if (profession != null) {
			for (Profession.SlotGroup g : profession.slots()) {
				headings.add(new Heading(label(g.key()), y));
				y += 10;
				for (int i = 0; i < g.count(); i++, index++) {
					addSlot(new GroupSlot(items, index, 8 + (i % 9) * 18, y + (i / 9) * 18, g.maxStack(), g.filter()));
				}
				y += ((g.count() + 8) / 9) * 18 + 2;
			}
		}

		invLabelY = y;
		y += 10;
		for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
		y += 58;
		for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c, 8 + c * 18, y));
		imageHeight = y + 25;

		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return GolemMenu.this.golem != null ? (GolemData.get(GolemMenu.this.golem).following ? 1 : 0) : clientFollowing;
			}

			@Override
			public void set(int value) {
				clientFollowing = value;
			}
		});
	}

	private static String label(String key) {
		return key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
	}

	public static boolean inRange(Player player, CopperGolem golem) {
		return golem.isAlive() && player.distanceToSqr(golem) <= MAX_DIST_SQ;
	}

	public int golemId() { return data.golemId(); }
	public GolemMenuData data() { return data; }
	public @Nullable Profession profession() { return profession; }
	public boolean isFollowing() { return clientFollowing != 0; }
	public List<Heading> headings() { return headings; }
	public int assignY() { return assignY; }
	public int heldY() { return heldY; }
	public int invLabelY() { return invLabelY; }
	public int imageHeight() { return imageHeight; }

	/** First player-inventory slot index (slot 0 is the held slot, then the profession slots). */
	private int playerStart() { return 1 + itemCount; }

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index < playerStart()) {
			// golem (held or profession slot) -> player inventory, hotbar end first like vanilla
			if (!moveItemStackTo(stack, playerStart(), slots.size(), true)) return ItemStack.EMPTY;
		} else {
			// player -> profession slots only; moveItemStackTo honours mayPlace and slot max stack, the held slot refuses placement
			if (itemCount == 0 || !moveItemStackTo(stack, 1, playerStart(), false)) return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
		slot.onTake(player, stack);
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		return golem == null || inRange(player, golem);
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
			return filter.test(stack);
		}

		@Override
		public int getMaxStackSize() {
			return maxStack;
		}
	}
}
