package com.coppergolemsrevamped.core;

import java.util.function.Predicate;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import com.coppergolemsrevamped.profession.transporter.TransporterLogic;

/**
 * Mending for the tool professions (Lumber, Farmer, Butcher): XP bottles in the tool chest repair the tools, and a Mending
 * tool is never used down to its last points (it goes back into the chest instead). See superdoc/features/mending.md.
 */
public final class GolemMending {
	private GolemMending() {}

	/**
	 * A Mending tool with this many durability points left (or fewer) is not used any more. Why 10: one use costs 1 point
	 * (hit, break, till), so anything above 1 would already be safe; 10 leaves margin for tools that cost more per use,
	 * and is far below the weakest tool's durability (golden: 32), so little durability is parked.
	 */
	public static final int MENDING_SAFE_DURABILITY = 10;

	public static boolean hasMending(ItemStack s) {
		return !s.isEmpty() && EnchantmentHelper.has(s, EnchantmentEffectComponents.REPAIR_WITH_XP);
	}

	/** Durability points left; 0 for an item without durability. */
	public static int remaining(ItemStack s) {
		return s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : 0;
	}

	/** THE use guard: false for a Mending tool at or below {@link #MENDING_SAFE_DURABILITY}; every other tool is usable. */
	public static boolean canUse(ItemStack tool) {
		return !hasMending(tool) || remaining(tool) > MENDING_SAFE_DURABILITY;
	}

	/** A held Mending tool that must go back into the chest. */
	public static boolean mustPark(ItemStack tool) {
		return !tool.isEmpty() && !canUse(tool);
	}

	/**
	 * Damages {@code tool} by {@code amount} like a use, except a Mending tool that would drop to 0 or below: that one is
	 * left untouched and false is returned (the last line of defence behind {@link #canUse}).
	 */
	public static boolean damage(ServerLevel level, ItemStack tool, int amount) {
		if (tool.isEmpty()) return true;
		if (hasMending(tool) && remaining(tool) <= amount) return false;
		tool.hurtAndBreak(amount, level, null, broken -> {});
		return true;
	}

	/** Durability points one XP bottle restores on {@code tool}: the real vanilla conversion of 7 XP ({@code repair_with_xp}). */
	public static int perBottle(ServerLevel level, ItemStack tool) {
		return EnchantmentHelper.modifyDurabilityToRepairFromXp(level, tool, GolemXp.XP_PER_BOTTLE);
	}

	/**
	 * Worth a bottle: a damaged Mending tool that is resting (not usable) or lacks at least one bottle's worth of durability.
	 * Why: without this the golem would walk back to the chest after every single hit to burn a bottle on 1 point.
	 */
	private static boolean wantsRepair(ServerLevel level, ItemStack s, Predicate<ItemStack> isTool, boolean topUp) {
		if (s.getCount() != 1 || !isTool.test(s) || !hasMending(s) || !s.isDamaged()) return false;
		int per = perBottle(level, s);
		return per > 0 && (topUp || s.getDamageValue() >= per || !canUse(s));
	}

	public static int bottlesIn(Container chest) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(Items.EXPERIENCE_BOTTLE)) n += chest.getItem(i).getCount();
		return n;
	}

	/** Is there a bottle in {@code chest} and a damaged Mending tool (held or in the chest) worth using it on. */
	public static boolean canRepair(ServerLevel level, ItemStack held, Container chest, Predicate<ItemStack> isTool) {
		return canRepair(level, held, chest, isTool, false);
	}

	/**
	 * {@code topUp}: idle-time variant, any damaged Mending tool counts however little it misses (no one-bottle rule).
	 * Why: user request, repair while waiting instead of idling; an idle golem has nothing better to do.
	 */
	public static boolean canRepair(ServerLevel level, ItemStack held, Container chest, Predicate<ItemStack> isTool, boolean topUp) {
		if (bottlesIn(chest) <= 0) return false;
		if (wantsRepair(level, held, isTool, topUp)) return true;
		for (int i = 0; i < chest.getContainerSize(); i++) if (wantsRepair(level, chest.getItem(i), isTool, topUp)) return true;
		return false;
	}

	/**
	 * Repairs the held tool first, then the Mending tools lying in the chest, one bottle (7 XP) at a time until all are
	 * whole, the bottles are gone or {@code limit} bottles were used. Bottles are only taken from {@code chest}. Returns the
	 * bottles consumed (the caller spends them from the transfer budget).
	 */
	public static int repair(ServerLevel level, ItemStack held, Container chest, Predicate<ItemStack> isTool, int limit) {
		return repair(level, held, chest, isTool, limit, false);
	}

	/** As above; with {@code topUp} every damaged Mending tool is repaired to full, ignoring the one-bottle rule (idle time). */
	public static int repair(ServerLevel level, ItemStack held, Container chest, Predicate<ItemStack> isTool, int limit, boolean topUp) {
		int used = 0;
		for (int i = -1; i < chest.getContainerSize() && used < limit; i++) {
			ItemStack t = i < 0 ? held : chest.getItem(i);
			// once a tool is worth repairing it is repaired to full (the last bottle may overshoot)
			boolean go = wantsRepair(level, t, isTool, topUp);
			while (go && used < limit && t.isDamaged() && takeBottle(chest)) {
				t.setDamageValue(Math.max(0, t.getDamageValue() - perBottle(level, t)));
				used++;
			}
		}
		if (used > 0) chest.setChanged();
		return used;
	}

	private static boolean takeBottle(Container chest) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (!st.is(Items.EXPERIENCE_BOTTLE)) continue;
			st.shrink(1);
			if (st.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
			return true;
		}
		return false;
	}

	/** Would the chest take {@code tool} (dry run on a copy, the chest is not touched). */
	public static boolean canPark(Container chest, ItemStack tool) {
		SimpleContainer probe = new SimpleContainer(chest.getContainerSize()) {
			@Override
			public boolean canPlaceItem(int slot, ItemStack stack) {
				return chest.canPlaceItem(slot, stack);
			}
		};
		for (int i = 0; i < chest.getContainerSize(); i++) probe.setItem(i, chest.getItem(i).copy());
		return TransporterLogic.insertAll(NonNullList.withSize(1, tool.copy()), probe, 1) > 0;
	}

	/** Puts the tool into the chest; true if it went in (the caller then empties its slot). Never drops it anywhere. */
	public static boolean park(Container chest, ItemStack tool) {
		NonNullList<ItemStack> one = NonNullList.withSize(1, tool.copy());
		return TransporterLogic.insertAll(one, chest, 1) > 0 && one.get(0).isEmpty();
	}
}
