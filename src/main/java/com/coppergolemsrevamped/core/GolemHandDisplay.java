package com.coppergolemsrevamped.core;

import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;

import net.minecraft.world.entity.DropChances;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

/**
 * Shows the tool (Lumber, Farmer) or the first carried stack (Transporter, Cleaner) in the golem's main hand, as a
 * display copy that can never become a real item: it is a one-item copy marked with {@link DisplayItems}, its drop
 * chance is 0 (death and statue conversion drop nothing from it), and it is cleared on a profession change, on every
 * sync that finds nothing to show, and recognised after a reload by the marker.
 * Rules: a real held item (vanilla Chest Sorter carry) always wins and is never overwritten; a vanilla-brain
 * profession shows nothing. Which group is shown comes from {@link SlotGroup#shownInHand()}, not from profession code.
 * See superdoc/features/gui.md.
 */
public final class GolemHandDisplay {
	/** Ticks between syncs: the hand lags the slot by at most half a second, and the check stays cheap. */
	public static final int PERIOD = 10;

	private GolemHandDisplay() {}

	/** Brings the hand in line with the golem's slots. */
	public static void sync(CopperGolem golem) {
		ItemStack hand = golem.getMainHandItem();
		boolean ours = DisplayItems.is(hand);
		if (!hand.isEmpty() && !ours) return; // a real held item wins
		ItemStack want = wanted(golem);
		if (want.isEmpty()) {
			if (ours) clear(golem);
			return;
		}
		if (!ours || !ItemStack.matches(hand, want)) {
			golem.setItemSlot(EquipmentSlot.MAINHAND, want);
			golem.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		}
	}

	/** Empties the hand if (and only if) it holds a display copy. */
	public static void clear(CopperGolem golem) {
		if (!DisplayItems.is(golem.getMainHandItem())) return;
		golem.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		golem.setDropChance(EquipmentSlot.MAINHAND, DropChances.DEFAULT_EQUIPMENT_DROP_CHANCE);
	}

	private static ItemStack wanted(CopperGolem golem) {
		Profession p = GolemService.profession(golem);
		if (p == null || p.runsVanillaBrain()) return ItemStack.EMPTY;
		GolemData data = GolemData.get(golem);
		for (SlotGroup g : p.slots()) {
			if (!g.inHand()) continue;
			int first = p.firstIndex(g);
			for (int i = 0; i < g.count() && first + i < data.items.size(); i++) {
				ItemStack s = data.items.get(first + i);
				if (!s.isEmpty()) return DisplayItems.mark(s.copyWithCount(1));
			}
		}
		return ItemStack.EMPTY;
	}
}
