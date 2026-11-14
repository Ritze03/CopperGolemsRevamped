package com.coppergolemsrevamped.menu;

import com.coppergolemsrevamped.core.GolemData;

import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server-side view over {@link GolemData#items}; saves the attachment on every change. Reads the live list each call. */
public class GolemItemsContainer implements Container {
	private final CopperGolem golem;
	private final int size;

	/** {@code size} is the declared inventory size; slots missing from the stored list read as empty and ignore writes. */
	public GolemItemsContainer(CopperGolem golem, int size) {
		this.golem = golem;
		this.size = size;
	}

	@Override
	public int getContainerSize() {
		return size;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < size; i++) if (!getItem(i).isEmpty()) return false;
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		var items = GolemData.get(golem).items;
		return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		ItemStack s = getItem(slot);
		if (s.isEmpty() || count <= 0) return ItemStack.EMPTY;
		ItemStack out = s.split(count);
		setChanged();
		return out;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		ItemStack s = getItem(slot);
		if (s.isEmpty()) return ItemStack.EMPTY;
		GolemData.get(golem).items.set(slot, ItemStack.EMPTY);
		return s;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		var items = GolemData.get(golem).items;
		if (slot < 0 || slot >= items.size()) return;
		items.set(slot, stack);
		setChanged();
	}

	@Override
	public void setChanged() {
		GolemData.save(golem, GolemData.get(golem));
	}

	@Override
	public boolean stillValid(Player player) {
		return GolemMenu.inRange(player, golem);
	}

	@Override
	public void clearContent() {
		GolemData.get(golem).items.replaceAll(s -> ItemStack.EMPTY);
		setChanged();
	}
}
