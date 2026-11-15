package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.chunk.GolemChunkLoader;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Professions;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;

public final class GolemService {
	private GolemService() {}

	/** Switch profession; stored items drop at the golem (no player to hand them to). */
	public static void changeProfession(ServerLevel level, CopperGolem golem, @Nullable Identifier id) {
		changeProfession(level, golem, id, null);
	}

	/**
	 * Switch profession. Stored items go into {@code player}'s inventory, what does not fit drops at the player's
	 * feet; with a null player they drop at the golem.
	 * Why the player: a drop at the golem was easy to miss (the menu reopens and the player never sees it).
	 */
	public static void changeProfession(ServerLevel level, CopperGolem golem, @Nullable Identifier id, @Nullable ServerPlayer player) {
		GolemData data = GolemData.get(golem);
		releaseHand(level, golem, player);
		drop(level, golem, data, player);
		data.assignments.clear();
		data.frozen = false; // a new profession starts working (freeze.md)
		data.profession = id;
		Profession p = Professions.get(id);
		data.items = NonNullList.withSize(p == null ? 0 : p.inventorySize(), ItemStack.EMPTY);
		prefillSpawnChest(level, data, p);
		updateName(golem, data, p);
		WalkTo.cancel(golem);
		if (p != null) GolemChunkLoader.keep(level, golem);
		else GolemChunkLoader.release(level, golem);
		GolemData.save(golem, data);
	}

	/**
	 * The main hand on a profession change: a display copy ({@link GolemHandDisplay}) is just cleared, a real carried
	 * item (vanilla Chest Sorter pickup) goes to the player like the stored items (drops at the golem without one).
	 * Why: the menu no longer has a held-item cell, so this is the player's only way to get a carried item back (D9).
	 */
	private static void releaseHand(ServerLevel level, CopperGolem golem, @Nullable ServerPlayer player) {
		ItemStack hand = golem.getMainHandItem();
		if (hand.isEmpty()) return;
		if (DisplayItems.is(hand)) {
			GolemHandDisplay.clear(golem);
			return;
		}
		ItemStack real = hand.copy();
		golem.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		if (player != null) {
			give(player, real);
			if (!real.isEmpty()) player.spawnAtLocation(level, real);
		} else {
			golem.spawnAtLocation(level, real);
		}
	}

	/** The first CHEST assign (declaration order) starts as the chest the golem was built next to, if it still is a chest. */
	private static void prefillSpawnChest(ServerLevel level, GolemData data, @Nullable Profession p) {
		if (p == null || data.spawnChest == null) return;
		if (!(level.getBlockState(data.spawnChest).getBlock() instanceof ChestBlock)) return;
		for (AssignSpec a : p.assigns()) {
			if (a.kind() != Kind.CHEST) continue;
			data.assignments.putIfAbsent(a.key(), data.spawnChest);
			return;
		}
	}

	/**
	 * Show the profession as the golem's custom name: vanilla renders it above the head while the player looks at the
	 * golem (the name is not "visible" permanently). A name the player gave (name tag) is never touched: ours is
	 * tracked in {@link GolemData#autoName} and only replaced or cleared while the current name still equals it.
	 */
	private static void updateName(CopperGolem golem, GolemData data, @Nullable Profession p) {
		Component current = golem.getCustomName();
		boolean ours = current == null || current.getString().equals(data.autoName);
		if (!ours) {
			data.autoName = null;
			return;
		}
		if (p == null) {
			if (current != null) golem.setCustomName(null);
			data.autoName = null;
		} else {
			golem.setCustomName(p.name());
			golem.setCustomNameVisible(false);
			data.autoName = p.name().getString();
		}
	}

	/** Drops every stored item at the golem (death / statue). */
	public static void dropAll(ServerLevel level, CopperGolem golem) {
		GolemData data = GolemData.get(golem);
		drop(level, golem, data, null);
		GolemData.save(golem, data);
	}

	public static @Nullable Profession profession(CopperGolem golem) {
		return Professions.get(GolemData.get(golem).profession);
	}

	private static void drop(ServerLevel level, CopperGolem golem, GolemData data, @Nullable ServerPlayer player) {
		// a ghost recipe slot is memory, not an item: its one-item copy is never given out (it would duplicate an item, D9)
		boolean[] memory = new boolean[data.items.size()];
		Profession p = profession(golem);
		if (p != null) {
			int idx = 0;
			for (Profession.SlotGroup g : p.slots()) {
				for (int k = 0; k < g.count(); k++, idx++) if (g.recipe() && idx < memory.length) memory[idx] = true;
			}
		}
		for (int i = 0; i < data.items.size(); i++) {
			ItemStack s = data.items.get(i);
			data.items.set(i, ItemStack.EMPTY);
			if (memory[i]) s = s.getCount() > 1 ? s.copyWithCount(s.getCount() - 1) : ItemStack.EMPTY; // surplus of an odd stack is real
			if (s.isEmpty()) continue;
			if (player != null) {
				give(player, s);
				if (!s.isEmpty()) player.spawnAtLocation(level, s); // the rest that did not fit
			} else {
				golem.spawnAtLocation(level, s);
			}
		}
	}

	/**
	 * Moves as much of {@code stack} as fits into the player's main inventory (merge first, then empty slots); the rest
	 * stays in {@code stack}. Not {@code Inventory#add}: for a creative player it deletes the stack.
	 */
	public static void give(ServerPlayer player, ItemStack stack) {
		NonNullList<ItemStack> inv = player.getInventory().getNonEquipmentItems();
		for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
			ItemStack cur = inv.get(i);
			if (cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, stack)) continue;
			int move = Math.min(stack.getCount(), cur.getMaxStackSize() - cur.getCount());
			if (move <= 0) continue;
			cur.grow(move);
			stack.shrink(move);
		}
		for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
			if (!inv.get(i).isEmpty()) continue;
			int move = Math.min(stack.getCount(), stack.getMaxStackSize());
			inv.set(i, stack.split(move));
		}
	}
}
