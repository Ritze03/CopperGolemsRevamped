package com.coppergolemsrevamped.core;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

/**
 * A golem's stored experience and its conversion into Bottles o' Enchanting (the golem makes them itself, no glass
 * bottles needed). See superdoc/features/xp-bottles.md.
 */
public final class GolemXp {
	private GolemXp() {}

	/**
	 * XP per bottle. Why 7: a thrown Bottle o' Enchanting gives 3 to 11 XP, 7 on average, so converting at 7 keeps the XP
	 * roughly neutral.
	 */
	public static final int XP_PER_BOTTLE = 7;

	/** Adds XP to the store (saturating, never negative). The caller saves the data. */
	public static void add(GolemData data, int amount) {
		if (amount <= 0) return;
		data.xp = (int) Math.min(Integer.MAX_VALUE, (long) data.xp + amount);
	}

	public static int bottlesReady(GolemData data) {
		return data.xp / XP_PER_BOTTLE;
	}

	/** The bottles for all whole 7s of the store, split into full stacks (nothing is deducted). */
	private static List<ItemStack> makeBottles(int count) {
		List<ItemStack> out = new ArrayList<>();
		while (count > 0) {
			int n = Math.min(count, new ItemStack(Items.EXPERIENCE_BOTTLE).getMaxStackSize());
			out.add(new ItemStack(Items.EXPERIENCE_BOTTLE, n));
			count -= n;
		}
		return out;
	}

	/**
	 * Inserts bottles for the stored XP into {@code dst}; the store shrinks only by what was actually inserted, the rest
	 * stays stored. True if at least one bottle went in.
	 */
	public static boolean deliver(GolemData data, Container dst) {
		return deliver(data, dst, Integer.MAX_VALUE) > 0;
	}

	/** Like {@link #deliver(GolemData, Container)} but inserts at most {@code limit} bottles; returns how many went in. */
	public static int deliver(GolemData data, Container dst, int limit) {
		int ready = bottlesReady(data);
		if (ready <= 0 || limit <= 0) return 0;
		NonNullList<ItemStack> carry = NonNullList.create();
		carry.addAll(makeBottles(ready));
		TransporterLogic.insertAll(carry, dst, limit, data.keepLayoutStore);
		int left = 0;
		for (ItemStack s : carry) left += s.getCount();
		int inserted = ready - left;
		data.xp -= inserted * XP_PER_BOTTLE;
		return inserted;
	}

	/**
	 * Smelter: converts whole bottles into real items in data slot {@code slot} (empty or already bottles), up to a full
	 * stack; the remainder and what does not fit stays stored. True if anything moved.
	 */
	public static boolean fillSlot(GolemData data, int slot) {
		ItemStack cur = data.items.get(slot);
		if (!cur.isEmpty() && !cur.is(Items.EXPERIENCE_BOTTLE)) return false;
		int n = Math.min(bottlesReady(data), cur.getMaxStackSize() - cur.getCount());
		if (cur.isEmpty()) n = Math.min(bottlesReady(data), new ItemStack(Items.EXPERIENCE_BOTTLE).getMaxStackSize());
		if (n <= 0) return false;
		if (cur.isEmpty()) data.items.set(slot, new ItemStack(Items.EXPERIENCE_BOTTLE, n)); else cur.grow(n);
		data.xp -= n * XP_PER_BOTTLE;
		return true;
	}

	/** All whole bottles of the store, removed from it (profession change); the remainder stays in {@code data.xp}. */
	public static List<ItemStack> takeBottles(GolemData data) {
		int ready = bottlesReady(data);
		data.xp -= ready * XP_PER_BOTTLE;
		return makeBottles(ready);
	}
}
