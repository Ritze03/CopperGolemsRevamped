package com.coppergolemsrevamped.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server-side one-slot view of the golem's vanilla carried item (main hand). */
public class GolemHeldContainer implements Container {
	private final CopperGolem golem;

	public GolemHeldContainer(CopperGolem golem) {
		this.golem = golem;
	}

	@Override
	public int getContainerSize() {
		return 1;
	}

	@Override
	public boolean isEmpty() {
		return golem.getMainHandItem().isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return slot == 0 ? golem.getMainHandItem() : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		if (slot != 0 || count <= 0) return ItemStack.EMPTY;
		ItemStack rest = golem.getMainHandItem().copy();
		ItemStack out = rest.split(count);
		setItem(0, rest);
		return out;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		if (slot != 0) return ItemStack.EMPTY;
		ItemStack s = golem.getMainHandItem();
		golem.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		return s;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (slot != 0) return;
		golem.setItemSlot(EquipmentSlot.MAINHAND, stack);
		if (!stack.isEmpty()) golem.setGuaranteedDrop(EquipmentSlot.MAINHAND);
	}

	@Override
	public void setChanged() {}

	@Override
	public boolean stillValid(Player player) {
		return GolemMenu.inRange(player, golem);
	}

	@Override
	public void clearContent() {
		golem.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
	}
}
