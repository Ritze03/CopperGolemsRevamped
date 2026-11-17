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
 * Mending and the enchanted-tool guard for the tool professions (Lumber, Farmer, Butcher): XP bottles in the tool chest
 * repair Mending tools, and any enchanted tool (not only Mending) is never used down to its last points (it goes back
 * into the chest instead); plain tools are used until they break. See superdoc/features/mending.md.
 */
public final class GolemMending {
	private GolemMending() {}

	/**
	 * An enchanted tool with this many durability points left (or fewer) is not used any more. Why 10: one use costs 1 point
	 * (hit, break, till), so anything above 1 would already be safe; 10 leaves margin for tools that cost more per use,
	 * and is far below the weakest tool's durability (golden: 32), so little durability is parked.
	 */
	public static final int SAFE_DURABILITY = 10;

	public static boolean hasMending(ItemStack s) {
		return !s.isEmpty() && EnchantmentHelper.has(s, EnchantmentEffectComponents.REPAIR_WITH_XP);
	}

	/** Durability points left; 0 for an item without durability. */
	public static int remaining(ItemStack s) {
		return s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : 0;
	}

	/**
	 * Is this tool protected from wear right now: it is enchanted
	 * (any enchantment, not only Mending) and has {@link #SAFE_DURABILITY} points or fewer left. Plain tools never are.
	 */
	public static boolean protects(ItemStack tool) {
		return !tool.isEmpty() && tool.isEnchanted() && tool.isDamageableItem() && remaining(tool) <= SAFE_DURABILITY;
	}

	/** THE use guard: false for a protected tool ({@link #protects}); every other tool is usable. */
	public static boolean canUse(ItemStack tool) {
		return !protects(tool);
	}

	/** A held protected tool that must go back into the chest. */
	public static boolean mustPark(ItemStack tool) {
		return protects(tool);
	}

	/**
	 * Damages {@code tool} by {@code amount} like a use, except an enchanted tool that would drop to 0 or below: that one is left
	 * untouched and false is returned (the last line of defence behind {@link #canUse}). Plain tools just break.
	 */
	public static boolean damage(ServerLevel level, ItemStack tool, int amount) {
		if (tool.isEmpty()) return true;
		if (tool.isEnchanted() && remaining(tool) <= amount) return false;
		tool.hurtAndBreak(amount, level, null, broken -> {});
		return true;
	}

	/** Durability points one XP bottle restores on {@code tool}: the real vanilla conversion of 7 XP ({@code repair_with_xp}). */
	public static int perBottle(ServerLevel level, ItemStack tool) {
		return EnchantmentHelper.modifyDurabilityToRepairFromXp(level, tool, GolemXp.XP_PER_BOTTLE);
	}

	/** XP that restores {@code durability} points on {@code tool}, rounded up (vanilla ratio: {@code perBottle} per 7 XP). */
	private static int xpFor(ServerLevel level, ItemStack tool, int durability) {
		int per = perBottle(level, tool);
		return per <= 0 ? Integer.MAX_VALUE : (int) (((long) durability * GolemXp.XP_PER_BOTTLE + per - 1) / per);
	}

	/**
	 * Worth a repair: a damaged Mending tool that is resting (not usable) or lacks at least one bottle's worth of durability.
	 * Why: without this the golem would walk back to the chest after every single hit to burn a bottle on 1 point.
	 */
	private static boolean wantsRepair(ServerLevel level, ItemStack s, Predicate<ItemStack> isTool, boolean topUp) {
		if (s.getCount() != 1 || !isTool.test(s) || !hasMending(s) || !s.isDamaged()) return false;
		int per = perBottle(level, s);
		return per > 0 && (topUp || s.getDamageValue() >= per || !canUse(s));
	}

	public static int bottlesIn(Container chest) {
		return bottlesIn(chest, false);
	}

	/** Bottles the golem may take: under keep layout one per slot stays behind (see superdoc/features/keep-layout.md). */
	public static int bottlesIn(Container chest, boolean keepLayout) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (st.is(Items.EXPERIENCE_BOTTLE)) n += TransporterLogic.takeable(chest, st, keepLayout);
		}
		return n;
	}

	/** Is there XP (stored or a bottle in {@code chest}) and a damaged Mending tool (held or in the chest) worth using it on. */
	public static boolean canRepair(ServerLevel level, GolemData data, ItemStack held, Container chest, Predicate<ItemStack> isTool) {
		return canRepair(level, data, held, chest, isTool, false);
	}

	/**
	 * {@code topUp}: idle-time variant, any damaged Mending tool counts however little it misses (no one-bottle rule).
	 * Why: user request, repair while waiting instead of idling; an idle golem has nothing better to do.
	 */
	public static boolean canRepair(ServerLevel level, GolemData data, ItemStack held, Container chest, Predicate<ItemStack> isTool, boolean topUp) {
		if (data.xp <= 0 && bottlesIn(chest, data.keepLayout) <= 0) return false;
		if (wantsRepair(level, held, isTool, topUp)) return true;
		for (int i = 0; i < chest.getContainerSize(); i++) if (wantsRepair(level, chest.getItem(i), isTool, topUp)) return true;
		return false;
	}

	/**
	 * Repairs the held tool first, then the Mending tools lying in the chest, paying in XP so none is wasted: the golem's
	 * stored XP ({@code data.xp}) is spent first, and only when it is not enough a bottle from {@code chest} is opened (+7 to
	 * the store); what is left stays stored. Stops when all are whole, the XP and bottles are gone or {@code limit} bottles
	 * were used. Returns the bottles consumed (the caller spends them from the transfer budget; stored XP is free). The
	 * caller saves {@code data}.
	 */
	public static int repair(ServerLevel level, GolemData data, ItemStack held, Container chest, Predicate<ItemStack> isTool, int limit) {
		return repair(level, data, held, chest, isTool, limit, false);
	}

	/** As above; with {@code topUp} every damaged Mending tool is repaired to full, ignoring the one-bottle rule (idle time). */
	public static int repair(ServerLevel level, GolemData data, ItemStack held, Container chest, Predicate<ItemStack> isTool, int limit, boolean topUp) {
		int used = 0;
		for (int i = -1; i < chest.getContainerSize(); i++) {
			ItemStack t = i < 0 ? held : chest.getItem(i);
			// once a tool is worth repairing it is repaired to full, as far as the XP reaches
			if (!wantsRepair(level, t, isTool, topUp)) continue;
			while (t.isDamaged()) {
				int need = xpFor(level, t, t.getDamageValue());
				if (data.xp < need && data.xp < GolemXp.XP_PER_BOTTLE) {
					// store alone is not enough: open one more bottle (a partial repair from the store is fine when none is left)
					if (used < limit && takeBottle(chest, data.keepLayout)) { GolemXp.add(data, GolemXp.XP_PER_BOTTLE); used++; continue; }
					if (data.xp <= 0) break;
				}
				int spend = Math.min(need, data.xp);
				if (spend <= 0) break;
				t.setDamageValue(Math.max(0, t.getDamageValue() - EnchantmentHelper.modifyDurabilityToRepairFromXp(level, t, spend)));
				data.xp -= spend;
			}
			if (used >= limit && data.xp <= 0) break;
		}
		if (used > 0) chest.setChanged();
		return used;
	}

	private static boolean takeBottle(Container chest, boolean keepLayout) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack st = chest.getItem(i);
			if (!st.is(Items.EXPERIENCE_BOTTLE) || TransporterLogic.takeable(chest, st, keepLayout) <= 0) continue;
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
