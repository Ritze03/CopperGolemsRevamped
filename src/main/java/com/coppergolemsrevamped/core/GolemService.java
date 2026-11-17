package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.ai.WalkTo;
import com.coppergolemsrevamped.chunk.GolemChunkLoader;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.AssignSpec;
import com.coppergolemsrevamped.profession.Profession.Kind;
import com.coppergolemsrevamped.profession.Professions;
import com.coppergolemsrevamped.profession.chest_sorter.ChestSorterProfession;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.CopperGolemStatueBlockEntity;
import net.minecraft.world.phys.Vec3;

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
		Profession old = Professions.get(data.profession);
		releaseHand(level, golem, player);
		drop(level, golem, data, player);
		data.assignments.clear();
		data.frozen = false; // a new profession starts working (freeze.md)
		data.filterMode = FilterMode.OFF; // the filter items are dropped with the other items
		data.profession = id;
		data.home = ChestSorterProfession.ID.equals(id) ? GlobalPos.of(level.dimension(), golem.blockPosition()) : null;
		Profession p = Professions.get(id);
		// transient phase/skip/plan state starts fresh: switching away and back must not resume it (Profession.Forget)
		if (old != null) old.forget().forget(golem);
		if (p != null) p.forget().forget(golem);
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
		if (!level.hasChunkAt(data.spawnChest) || !(level.getBlockState(data.spawnChest).getBlock() instanceof ChestBlock)) return;
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

	/** Drops every stored item at the golem (death). */
	public static void dropAll(ServerLevel level, CopperGolem golem) {
		GolemData data = GolemData.get(golem);
		drop(level, golem, data, null);
		GolemData.save(golem, data);
	}

	public static @Nullable Profession profession(CopperGolem golem) {
		return Professions.get(GolemData.get(golem).profession);
	}

	/**
	 * The golem is turning into a statue ({@code CopperGolem#turnToStatue}, after {@code createStatue}, before vanilla
	 * drops the preserved equipment and discards the golem): its whole {@link GolemData} moves onto the statue block
	 * entity, a real held item (vanilla Chest Sorter carry) into {@link ModAttachments#STATUE_HELD}, so nothing drops.
	 * Our auto name is taken off the statue (vanilla copied the custom name onto it): a mined statue item must not carry
	 * the profession name, and {@link #fromStatue} sets it again. A golem that never had mod data stores nothing.
	 * See superdoc/features/oxidation.md.
	 */
	public static void toStatue(CopperGolem golem, CopperGolemStatueBlockEntity statue) {
		ItemStack hand = golem.getMainHandItem();
		if (DisplayItems.is(hand)) {
			GolemHandDisplay.clear(golem);
		} else if (!hand.isEmpty()) {
			statue.setAttached(ModAttachments.STATUE_HELD, hand.copy());
			golem.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		GolemData data = golem.getAttached(ModAttachments.GOLEM_DATA);
		if (data != null) {
			statue.setAttached(ModAttachments.GOLEM_DATA, data);
			Component name = statue.components().get(DataComponents.CUSTOM_NAME);
			if (name != null && data.autoName != null && name.getString().equals(data.autoName)) {
				statue.setComponents(DataComponentMap.builder().addAll(statue.components()).set(DataComponents.CUSTOM_NAME, null).build());
			}
		}
		statue.setChanged();
	}

	/**
	 * The statue is revived ({@code CopperGolemStatueBlockEntity#removeStatue}, axe on an unaffected statue): the new
	 * golem gets the stored {@link GolemData} and held item back, and the statue keeps nothing (vanilla removes the
	 * block right after, {@link #statueRemoved} then finds nothing to drop).
	 */
	public static void fromStatue(CopperGolemStatueBlockEntity statue, CopperGolem golem) {
		GolemData data = statue.removeAttached(ModAttachments.GOLEM_DATA);
		if (data != null) {
			data.fitItems();
			golem.setAttached(ModAttachments.GOLEM_DATA, data);
			updateName(golem, data, Professions.get(data.profession));
		}
		ItemStack held = statue.removeAttached(ModAttachments.STATUE_HELD);
		if (held != null && !held.isEmpty()) {
			golem.setItemSlot(EquipmentSlot.MAINHAND, held);
			golem.setGuaranteedDrop(EquipmentSlot.MAINHAND); // as vanilla sets it when the golem picks an item up
		}
		statue.setChanged();
	}

	/**
	 * A statue block entity is removed without a revival (mined, blown up, replaced): vanilla keeps only the custom
	 * name in the statue item, so the stored items, XP and the held item drop at the block like a container's contents.
	 * Profession, assignments and settings are lost then, as on death.
	 */
	public static void statueRemoved(ServerLevel level, BlockPos pos, CopperGolemStatueBlockEntity statue) {
		GolemData data = statue.removeAttached(ModAttachments.GOLEM_DATA);
		ItemStack held = statue.removeAttached(ModAttachments.STATUE_HELD);
		Vec3 at = Vec3.atCenterOf(pos);
		if (held != null && !held.isEmpty()) Containers.dropItemStack(level, at.x, at.y, at.z, held);
		if (data != null) drop(level, at, data, null, s -> Containers.dropItemStack(level, at.x, at.y, at.z, s));
	}

	private static void drop(ServerLevel level, CopperGolem golem, GolemData data, @Nullable ServerPlayer player) {
		drop(level, golem.position(), data, player, s -> golem.spawnAtLocation(level, s));
	}

	/**
	 * Hands every stored item and the stored XP to {@code player}, or with a null player drops them through
	 * {@code dropper} (XP orbs at {@code at}). Empties the list in place.
	 */
	private static void drop(ServerLevel level, Vec3 at, GolemData data, @Nullable ServerPlayer player, Consumer<ItemStack> dropper) {
		// stored XP: whole bottles like the items, the remainder below one bottle as orbs (xp-bottles.md)
		for (ItemStack s : GolemXp.takeBottles(data)) {
			if (player != null) {
				give(player, s);
				if (!s.isEmpty()) player.spawnAtLocation(level, s);
			} else {
				dropper.accept(s);
			}
		}
		if (data.xp > 0) {
			ExperienceOrb.award(level, player != null ? player.position() : at, data.xp);
			data.xp = 0;
		}
		for (int i = 0; i < data.items.size(); i++) {
			ItemStack s = data.items.get(i);
			data.items.set(i, ItemStack.EMPTY);
			if (s.isEmpty()) continue;
			if (player != null) {
				give(player, s);
				if (!s.isEmpty()) player.spawnAtLocation(level, s); // the rest that did not fit
			} else {
				dropper.accept(s);
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
