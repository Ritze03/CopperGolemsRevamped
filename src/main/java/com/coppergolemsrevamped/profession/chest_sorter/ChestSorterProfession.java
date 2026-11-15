package com.coppergolemsrevamped.profession.chest_sorter;

import com.coppergolemsrevamped.CopperGolemsRevamped;
import com.coppergolemsrevamped.core.GolemData;
import com.coppergolemsrevamped.core.GolemService;
import com.coppergolemsrevamped.profession.Profession;
import com.coppergolemsrevamped.profession.Professions;

import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/** Vanilla sorting behaviour; a Chest Sorter visits up to 64 chests before it gives up (vanilla: 10). */
public final class ChestSorterProfession {
	public static final Identifier ID = CopperGolemsRevamped.id("chest_sorter");
	/** Vanilla's literal in {@code TransportItemsBetweenContainers#setVisitedBlockPos}. */
	public static final int VANILLA_VISITED_LIMIT = 10;
	public static final int SORTER_VISITED_LIMIT = 64;

	private ChestSorterProfession() {}

	public static void init() {
		Professions.register(new Profession(
			ID,
			Component.literal("Chest Sorter"),
			List.of(),
			List.of(),
			true,
			(level, golem, data) -> {}));
	}

	/** Chests a body may visit before it gives up: 64 for a Chest Sorter golem, vanilla's 10 otherwise. */
	public static int visitedLimit(PathfinderMob body) {
		if (body instanceof CopperGolem golem && ID.equals(GolemData.get(golem).profession)) {
			if (golem.level() instanceof ServerLevel level) migrateLegacyItems(level, golem);
			return SORTER_VISITED_LIMIT;
		}
		return VANILLA_VISITED_LIMIT;
	}

	/**
	 * Old saves kept waxed copper blocks in a Chest Sorter's slot; the slot is gone, so drop them at the golem and
	 * clear the list. No-op when the list is empty.
	 * Why here: a vanilla-brain profession's {@code tick} is never called (see {@code GolemTicker}), and this runs
	 * whenever the sorter works. Until then the items stay saved, and death or a profession change drops them too.
	 */
	public static void migrateLegacyItems(ServerLevel level, CopperGolem golem) {
		GolemData data = GolemData.get(golem);
		if (!ID.equals(data.profession) || data.items.isEmpty()) return;
		GolemService.dropAll(level, golem);
		data = GolemData.get(golem);
		data.items = NonNullList.create();
		GolemData.save(golem, data);
	}
}
