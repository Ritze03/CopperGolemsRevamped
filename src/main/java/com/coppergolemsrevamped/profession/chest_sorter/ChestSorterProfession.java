package com.coppergolemsrevamped.profession.chest_sorter;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Profession.SlotGroup;
import com.coppergolemsrevamped.profession.Professions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Vanilla sorting behaviour; each waxed copper block in the GUI slot raises the visited-chest limit by one. */
public final class ChestSorterProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("chest_sorter");
	/** Vanilla's literal in {@code TransportItemsBetweenContainers#setVisitedBlockPos}. */
	public static final int VANILLA_VISITED_LIMIT = 10;

	private static Set<Item> waxedBlocks;

	private ChestSorterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Chest Sorter"),
			List.of(new SlotGroup("waxed", 1, 64, ChestSorterProfession::isWaxedCopperBlock)),
			List.of(),
			true,
			(level, golem, data) -> {}));
	}

	/** The four waxed full copper blocks (any oxidation stage); cut/stairs/slabs do not count. */
	public static boolean isWaxedCopperBlock(ItemStack stack) {
		if (waxedBlocks == null) {
			Set<Item> set = new HashSet<>();
			Items.COPPER_BLOCK.waxed().forEach(set::add);
			waxedBlocks = set;
		}
		return !stack.isEmpty() && waxedBlocks.contains(stack.getItem());
	}

	/** Chests a sorter may visit before it gives up: 10 plus the waxed blocks in its slot. */
	public static int visitedLimit(PathfinderMob body) {
		if (body instanceof CopperGolem golem) {
			GolemData data = GolemData.get(golem);
			if (ID.equals(data.profession) && !data.items.isEmpty()) {
				ItemStack s = data.items.get(0);
				if (isWaxedCopperBlock(s)) return VANILLA_VISITED_LIMIT + s.getCount();
			}
		}
		return VANILLA_VISITED_LIMIT;
	}
}
